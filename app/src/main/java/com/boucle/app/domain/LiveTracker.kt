package com.boucle.app.domain

import kotlin.math.abs

/*
 * Suivi d'une activité en cours : accumule les positions du GPS du téléphone et tient à jour la distance, l'allure
 * du moment, le dénivelé et le temps du dernier kilomètre. Pur Kotlin, testable sur la JVM.
 */

/** Chiffres en direct d'une activité. */
data class LiveStats(
    val distanceMeters: Double = 0.0,
    /** Vitesse du moment (m/s), lissée sur les dernières secondes ; `null` à l'arrêt ou sans assez de points. */
    val speedMps: Double? = null,
    val altitude: Double? = null,
    val gainMeters: Double = 0.0,
    val lossMeters: Double = 0.0,
    /** Durée (s) du dernier kilomètre complet. */
    val lastKmSec: Double? = null,
    /** Précision horizontale (m) de la dernière position. */
    val accuracyMeters: Float? = null,
    val pointCount: Int = 0,
)

class LiveTracker {
    private val recorded = ArrayList<RoutePoint>()
    private var distance = 0.0
    private var gain = 0.0
    private var loss = 0.0
    private var altitudeReference: Double? = null
    private var lastAltitude: Double? = null
    private var lastAccuracy: Float? = null
    /** Temps actif (s) à chaque point gardé, et distance cumulée : pour la vitesse lissée et les kilomètres. */
    private val activeSec = ArrayList<Double>()
    private val cumulative = ArrayList<Double>()
    private var kmStartSec = 0.0
    private var lastKmSec: Double? = null
    /** Temps actif (s) de la dernière position reçue, gardée ou non. */
    private var lastSeenSec = 0.0
    /** Vrai juste après une pause : le prochain point repart de sa position, sans compter le trajet de la pause. */
    private var resumed = true

    val points: List<RoutePoint> get() = recorded

    /** À appeler à la reprise après une pause. */
    fun resume() {
        resumed = true
    }

    /**
     * Ajoute une position. [activeElapsedSec] est le temps d'activité hors pauses. Les positions trop imprécises
     * sont ignorées, comme les sauts de moins de [MIN_STEP_M] (bruit à l'arrêt).
     */
    fun add(point: RoutePoint, accuracyMeters: Float?, activeElapsedSec: Double): LiveStats {
        lastAccuracy = accuracyMeters
        lastSeenSec = activeElapsedSec
        if (accuracyMeters != null && accuracyMeters > MAX_ACCURACY_M) return stats()
        val previous = recorded.lastOrNull()
        if (previous != null && !resumed) {
            val step = RouteAnalysis.haversine(previous.latitude, previous.longitude, point.latitude, point.longitude)
            if (step < MIN_STEP_M) {
                // À l'arrêt : on garde la FC et l'altitude du moment, pas la position.
                trackAltitude(point.altitude)
                return stats()
            }
            distance += step
        }
        resumed = false
        recorded += point
        activeSec += activeElapsedSec
        cumulative += distance
        trackAltitude(point.altitude)
        val km = (distance / 1000).toInt()
        val previousKm = (cumulative.getOrNull(cumulative.size - 2) ?: 0.0).let { (it / 1000).toInt() }
        if (km > previousKm) {
            lastKmSec = activeElapsedSec - kmStartSec
            kmStartSec = activeElapsedSec
        }
        return stats()
    }

    fun stats(): LiveStats = LiveStats(
        distanceMeters = distance,
        speedMps = smoothedSpeed(),
        altitude = lastAltitude,
        gainMeters = gain,
        lossMeters = loss,
        lastKmSec = lastKmSec,
        accuracyMeters = lastAccuracy,
        pointCount = recorded.size,
    )

    /** Dénivelé avec hystérésis, comme [RouteAnalysis] : la référence ne bouge qu'au-delà de 2 m d'écart. */
    private fun trackAltitude(altitude: Double?) {
        altitude ?: return
        lastAltitude = altitude
        val reference = altitudeReference
        if (reference == null) {
            altitudeReference = altitude
        } else if (abs(altitude - reference) >= ELEVATION_THRESHOLD_M) {
            val delta = altitude - reference
            if (delta > 0) gain += delta else loss -= delta
            altitudeReference = altitude
        }
    }

    /** Vitesse sur les [SPEED_WINDOW_SEC] dernières secondes d'activité. */
    private fun smoothedSpeed(): Double? {
        if (activeSec.size < 2) return null
        val end = activeSec.lastIndex
        // À l'arrêt, plus aucun point n'est gardé : la dernière vitesse ne reste pas affichée.
        if (lastSeenSec - activeSec[end] > STALE_SEC) return null
        var from = end
        while (from > 0 && activeSec[end] - activeSec[from] < SPEED_WINDOW_SEC) from--
        val dt = activeSec[end] - activeSec[from]
        if (dt <= 0) return null
        val speed = (cumulative[end] - cumulative[from]) / dt
        return speed.takeIf { it >= MIN_SPEED_MPS }
    }

    companion object {
        const val MAX_ACCURACY_M = 35f
        const val MIN_STEP_M = 2.0
        const val SPEED_WINDOW_SEC = 15.0
        const val MIN_SPEED_MPS = 0.3
        const val STALE_SEC = 5.0
        private const val ELEVATION_THRESHOLD_M = 2.0
    }
}
