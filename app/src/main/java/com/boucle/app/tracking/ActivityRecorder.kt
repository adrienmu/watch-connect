package com.boucle.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.boucle.app.domain.LiveStats
import com.boucle.app.domain.LiveTracker
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.Sport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class RecordingPhase { IDLE, RUNNING, PAUSED, FINISHED }

/** État de l'activité en cours, tel que l'affichent l'écran « en cours » et la notification. */
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val sport: Sport = Sport.RUN,
    /** Temps d'activité hors pauses. */
    val activeSec: Int = 0,
    val stats: LiveStats = LiveStats(),
    /** FC du moment, lue sur le bracelet ; `null` s'il n'est pas connecté. */
    val heartRate: Int? = null,
    /** Pas comptés par le téléphone ; `null` sans capteur ou sans autorisation. */
    val steps: Int? = null,
    /** FC max qui sert aux zones pendant cette activité. */
    val maxHr: Int = 0,
    /** Change à chaque point ajouté au tracé : la carte se redessine. */
    val trackVersion: Int = 0,
) {
    val active: Boolean get() = phase == RecordingPhase.RUNNING || phase == RecordingPhase.PAUSED
}

/** Signal du GPS avant le départ. */
data class GpsPreview(val listening: Boolean = false, val accuracyMeters: Float? = null, val altitude: Double? = null)

/** Activité terminée, prête à être enregistrée dans la base. */
data class RecordedActivity(
    val sport: Sport,
    val startMs: Long,
    val durationSec: Int,
    val distanceMeters: Double,
    val points: List<RoutePoint>,
    val averageHr: Int?,
    val maxHr: Int?,
    val steps: Int?,
    val gainMeters: Double,
    val lossMeters: Double,
    val minAltitude: Double?,
    val maxAltitude: Double?,
    /** Secondes passées dans chacune des 5 zones cardiaques. */
    val hrZoneSec: List<Int>,
)

/**
 * Enregistre une activité avec le GPS du téléphone : position une fois par seconde, altitude du baromètre calée sur
 * le GPS quand le téléphone en a un, FC en direct du bracelet, pas du téléphone. Tout se passe sur le fil principal
 * (rappels de position, capteurs, minuteur) ; [RecordingService] garde l'app en vie écran éteint.
 */
class ActivityRecorder(private val context: Context, private val liveHeartRate: () -> Int?) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _preview = MutableStateFlow(GpsPreview())
    val preview: StateFlow<GpsPreview> = _preview.asStateFlow()

    private val _finished = MutableStateFlow<RecordedActivity?>(null)
    /** Activité terminée en attente d'enregistrement ou de suppression. */
    val finished: StateFlow<RecordedActivity?> = _finished.asStateFlow()

    private var tracker = LiveTracker()
    private var listening = false
    private var ticker: Job? = null
    private var startMs = 0L
    /** Temps actif accumulé avant la reprise en cours, et instant (horloge monotone) de cette reprise. */
    private var activeBeforeMs = 0L
    private var resumedAtMs = 0L

    private val hrSeconds = IntArray(5)
    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0

    private var pressure: Float? = null
    private var altitudeOffset: Double? = null
    private var minAltitude: Double? = null
    private var maxAltitude: Double? = null

    private var stepBaseline: Float? = null
    private var stepCounter: Float? = null
    private var stepAtPause: Float? = null

    val hasBarometer: Boolean get() = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun gpsEnabled(): Boolean = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderDisabled(provider: String) = Unit
        override fun onProviderEnabled(provider: String) = Unit
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                // Pression lissée : le baromètre est bruité d'une mesure à l'autre.
                Sensor.TYPE_PRESSURE -> pressure = pressure?.let { it + PRESSURE_SMOOTHING * (event.values[0] - it) } ?: event.values[0]
                Sensor.TYPE_STEP_COUNTER -> {
                    val value = event.values[0]
                    if (stepBaseline == null) stepBaseline = value
                    stepCounter = value
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Écoute le GPS avant le départ, pour afficher la qualité du signal. Sans effet sans autorisation. */
    fun startPreview() {
        if (!hasLocationPermission() || listening) return
        listen()
        _preview.update { it.copy(listening = true) }
    }

    /** Fin de l'écoute d'avant départ, sauf si une activité est en cours. */
    fun stopPreview() {
        if (_state.value.active) return
        stopListening()
        _preview.value = GpsPreview()
    }

    /** Démarre l'activité. [maxHr] sert aux zones cardiaques. */
    fun start(sport: Sport, maxHr: Int) {
        if (_state.value.active || !hasLocationPermission()) return
        tracker = LiveTracker()
        _finished.value = null
        startMs = System.currentTimeMillis()
        activeBeforeMs = 0L
        resumedAtMs = SystemClock.elapsedRealtime()
        hrSeconds.fill(0)
        hrSum = 0
        hrCount = 0
        hrMax = 0
        altitudeOffset = null
        minAltitude = null
        maxAltitude = null
        stepBaseline = null
        stepCounter = null
        stepAtPause = null
        _state.value = RecordingState(phase = RecordingPhase.RUNNING, sport = sport, maxHr = maxHr, heartRate = liveHeartRate())
        if (!listening) listen()
        registerSensors()
        startTicker()
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
    }

    fun pause() {
        if (_state.value.phase != RecordingPhase.RUNNING) return
        activeBeforeMs += SystemClock.elapsedRealtime() - resumedAtMs
        stepAtPause = stepCounter
        _state.update { it.copy(phase = RecordingPhase.PAUSED) }
    }

    fun resume() {
        if (_state.value.phase != RecordingPhase.PAUSED) return
        resumedAtMs = SystemClock.elapsedRealtime()
        tracker.resume()
        // Les pas faits pendant la pause ne comptent pas.
        val atPause = stepAtPause
        val now = stepCounter
        val baseline = stepBaseline
        if (atPause != null && now != null && baseline != null) stepBaseline = baseline + (now - atPause)
        stepAtPause = null
        _state.update { it.copy(phase = RecordingPhase.RUNNING) }
    }

    /** Termine l'activité : elle attend dans [finished] d'être enregistrée ou supprimée. */
    fun finish() {
        val current = _state.value
        if (!current.active) return
        if (current.phase == RecordingPhase.RUNNING) activeBeforeMs += SystemClock.elapsedRealtime() - resumedAtMs
        ticker?.cancel()
        stopListening()
        sensorManager.unregisterListener(sensorListener)
        val stats = tracker.stats()
        _finished.value = RecordedActivity(
            sport = current.sport,
            startMs = startMs,
            durationSec = (activeBeforeMs / 1000).toInt(),
            distanceMeters = stats.distanceMeters,
            points = tracker.points.toList(),
            averageHr = if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt() else null,
            maxHr = hrMax.takeIf { it > 0 },
            steps = steps(),
            gainMeters = stats.gainMeters,
            lossMeters = stats.lossMeters,
            minAltitude = minAltitude,
            maxAltitude = maxAltitude,
            hrZoneSec = hrSeconds.toList(),
        )
        _state.update { it.copy(phase = RecordingPhase.FINISHED, activeSec = (activeBeforeMs / 1000).toInt()) }
    }

    /** Oublie l'activité terminée (enregistrée ou supprimée). */
    fun clear() {
        if (_state.value.active) return
        _finished.value = null
        _state.value = RecordingState()
    }

    /** Points enregistrés jusqu'ici, pour la carte. */
    fun trackPoints(): List<RoutePoint> = tracker.points.toList()

    @SuppressLint("MissingPermission")
    private fun listen() {
        runCatching {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper())
            listening = true
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopListening() {
        if (listening) runCatching { locationManager.removeUpdates(locationListener) }
        listening = false
        if (!_state.value.active) sensorManager.unregisterListener(sensorListener)
        pressure = null
    }

    private fun registerSensors() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun steps(): Int? {
        val baseline = stepBaseline ?: return null
        val counter = (if (_state.value.phase == RecordingPhase.PAUSED) stepAtPause else null) ?: stepCounter ?: return null
        return (counter - baseline).roundToInt().coerceAtLeast(0)
    }

    private fun activeMs(): Long = when (_state.value.phase) {
        RecordingPhase.RUNNING -> activeBeforeMs + SystemClock.elapsedRealtime() - resumedAtMs
        else -> activeBeforeMs
    }

    /** Une fois par seconde : chrono, FC du bracelet (moyenne, maximum, temps par zone), pas. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val current = _state.value
                if (!current.active) break
                val hr = liveHeartRate()?.takeIf { it > 0 }
                if (current.phase == RecordingPhase.RUNNING && hr != null) {
                    hrSum += hr
                    hrCount++
                    if (hr > hrMax) hrMax = hr
                    if (current.maxHr > 0) hrSeconds[RouteAnalysis.zoneOf(hr, RouteAnalysis.zoneBounds(current.maxHr))]++
                }
                _state.update { it.copy(activeSec = (activeMs() / 1000).toInt(), heartRate = hr, steps = steps()) }
            }
        }
    }

    private fun onLocation(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val altitude = altitudeOf(location)
        val current = _state.value
        if (current.phase != RecordingPhase.RUNNING) {
            if (!current.active) _preview.value = GpsPreview(listening = true, accuracyMeters = accuracy, altitude = altitude)
            return
        }
        altitude?.let {
            minAltitude = minOf(minAltitude ?: it, it)
            maxAltitude = maxOf(maxAltitude ?: it, it)
        }
        val before = tracker.points.size
        val stats = tracker.add(
            RoutePoint(
                timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = altitude,
                heartRate = liveHeartRate()?.takeIf { it > 0 },
            ),
            accuracy,
            activeMs() / 1000.0,
        )
        _state.update {
            it.copy(stats = stats, trackVersion = if (tracker.points.size != before) it.trackVersion + 1 else it.trackVersion)
        }
    }

    /**
     * Altitude : celle du baromètre quand il y en a un, recalée une fois sur le GPS (le baromètre suit bien les
     * variations, le GPS donne le niveau de départ) ; sinon celle du GPS.
     */
    private fun altitudeOf(location: Location): Double? {
        val gps = if (location.hasAltitude()) location.altitude else null
        val p = pressure ?: return gps
        val barometric = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p).toDouble()
        val offset = altitudeOffset ?: gps?.let { it - barometric }?.also { altitudeOffset = it }
        return if (offset != null) barometric + offset else gps
    }

    private companion object {
        const val LOCATION_INTERVAL_MS = 1000L
        const val PRESSURE_SMOOTHING = 0.2f
    }
}
