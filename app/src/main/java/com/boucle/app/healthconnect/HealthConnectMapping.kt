package com.boucle.app.healthconnect

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.RouteSource
import com.boucle.app.domain.RouteStatus
import com.boucle.app.domain.Vo2MaxEstimation
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/*
 * Données Health Connect réduites à l'essentiel, sans dépendre de la bibliothèque : le passage aux tables de
 * l'app est ainsi testable sur la JVM. Le gateway lit les enregistrements et les convertit en ces types.
 */

data class HcHeartRate(val time: Instant, val bpm: Double)

data class HcSteps(val start: Instant, val end: Instant, val count: Long)

/** Phase de sommeil, déjà traduite dans le vocabulaire du bracelet : WAKE, NONREM12, NONREM3 ou REM. */
data class HcSleepStage(val start: Instant, val end: Instant, val state: String)

data class HcSleepSession(val start: Instant, val end: Instant, val zone: ZoneId?, val stages: List<HcSleepStage>)

/** Mesure ponctuelle : VFC (RMSSD en ms) ou fréquence respiratoire (respirations/min). */
data class HcValue(val time: Instant, val value: Double)

/** Point d'un tracé Health Connect. */
data class HcLocation(val time: Instant, val latitude: Double, val longitude: Double, val altitude: Double?)

/** Tracé d'une séance tel que Health Connect le rend. */
sealed interface HcRoute {
    data class Points(val locations: List<HcLocation>) : HcRoute
    /** Un tracé existe, mais l'accord de l'utilisateur est nécessaire pour le lire. */
    data object ConsentRequired : HcRoute
    data object None : HcRoute
}

data class HcExercise(
    val id: String,
    val start: Instant,
    val end: Instant,
    val zone: ZoneId?,
    val running: Boolean,
    val name: String,
    val distanceMeters: Double?,
    val calories: Double?,
    val route: HcRoute = HcRoute.None,
)

data class HcDayTotals(
    val date: LocalDate,
    val steps: Long?,
    val distanceMeters: Double?,
    val activeCalories: Double?,
    /** Calories du métabolisme de base, quand la source les donne (archive Withings). */
    val bmrCalories: Double? = null,
)

/**
 * Traduit les données de la montre (via Health Connect) dans les tables remplies d'habitude par le bracelet
 * Polar, pour que tous les calculs (sommeil, récupération, Body Battery, VO2max) fonctionnent sans bracelet.
 */
object HealthConnectMapping {
    /** Marque les blocs de FC venus de Health Connect : 1 valeur par 5 minutes, comme la FC 24/7 au repos. */
    const val HR_TRIGGER = "HEALTH_CONNECT"
    private const val HR_SLOT_MINUTES = 5
    private const val MINUTES_PER_DAY = 24 * 60
    /** Une nuit doit compter au moins ce nombre de mesures de FC pour donner une FC nocturne. */
    private const val MIN_NIGHT_HR_SAMPLES = 3

    /** FC moyenne par créneau de 5 minutes, en blocs d'une heure (0 = pas de mesure). */
    fun hrBlocks(samples: List<HcHeartRate>, zone: ZoneId): List<HrBlockEntity> {
        val bySlot = samples.filter { it.bpm > 0 }.groupBy { s ->
            val t = s.time.atZone(zone).toLocalDateTime()
            t.withMinute(t.minute - t.minute % HR_SLOT_MINUTES).withSecond(0).withNano(0)
        }
        return bySlot.keys.groupBy { it.withMinute(0) }.toSortedMap().map { (hour, _) ->
            val values = (0 until 60 / HR_SLOT_MINUTES).joinToString(",") { k ->
                bySlot[hour.plusMinutes(k * HR_SLOT_MINUTES.toLong())]
                    ?.let { slot -> slot.map { it.bpm }.average().roundToInt().toString() } ?: "0"
            }
            HrBlockEntity(hour.toLocalDate().toString(), "%02d:00".format(hour.hour), HR_TRIGGER, values)
        }
    }

    /**
     * Pas à la minute et MET estimés à partir de la cadence, un bloc par jour. Les jours sans aucun pas ne
     * produisent rien ; les minutes sans pas d'un jour actif valent 0 pas et un MET de repos.
     */
    fun activitySamples(steps: List<HcSteps>, zone: ZoneId): List<ActivitySamplesEntity> {
        val perDay = HashMap<LocalDate, DoubleArray>()
        steps.filter { it.count > 0 && it.end > it.start }.forEach { record ->
            // Les pas d'un enregistrement sont répartis uniformément sur les minutes qu'il couvre.
            val totalSec = Duration.between(record.start, record.end).seconds.coerceAtLeast(1)
            var t = record.start
            while (t < record.end) {
                val local = t.atZone(zone).toLocalDateTime()
                val minuteEnd = local.withSecond(0).withNano(0).plusMinutes(1).atZone(zone).toInstant()
                val stepEnd = minOf(minuteEnd, record.end)
                val share = Duration.between(t, stepEnd).seconds.toDouble() / totalSec
                val day = perDay.getOrPut(local.toLocalDate()) { DoubleArray(MINUTES_PER_DAY) }
                day[local.hour * 60 + local.minute] += record.count * share
                t = stepEnd
            }
        }
        return perDay.toSortedMap().map { (date, minutes) -> activitySamplesOf(date, minutes) }
    }

    /** Bloc d'une journée à partir de ses pas à la minute ([minutes] : 1 440 valeurs depuis minuit). */
    fun activitySamplesOf(date: LocalDate, minutes: DoubleArray) = ActivitySamplesEntity(
        date = date.toString(),
        startTime = date.atStartOfDay().toString(),
        stepIntervalSec = 60,
        stepSamples = minutes.joinToString(",") { it.roundToInt().toString() },
        metIntervalSec = 60,
        metSamples = minutes.joinToString(",") { "%.1f".format(java.util.Locale.ROOT, metFromCadence(it)) },
        activityInfo = "",
    )

    /**
     * MET estimé d'après la cadence (pas/min), faute de mesure d'effort : repos 1,2, marche ≈ 1,2 + 0,025 × cadence
     * (100 pas/min ≈ 3,7 MET), course ≈ 0,07 × cadence − 1,5 (160 pas/min ≈ 9,7 MET), raccord linéaire entre
     * 130 et 140 pas/min.
     */
    fun metFromCadence(stepsPerMinute: Double): Double {
        val c = stepsPerMinute.coerceAtLeast(0.0)
        val walk = 1.2 + 0.025 * c
        val run = 0.07 * c - 1.5
        return when {
            c < 130 -> walk
            c >= 140 -> run
            else -> walk + (run - walk) * (c - 130) / 10
        }
    }

    /** Une nuit par date de réveil (la plus longue s'il y en a plusieurs, siestes comprises). */
    fun sleeps(sessions: List<HcSleepSession>, defaultZone: ZoneId): List<SleepEntity> =
        sessions.filter { it.end > it.start }
            .groupBy { it.end.atZone(it.zone ?: defaultZone).toLocalDate() }
            .map { (wakeDate, nights) ->
                val night = nights.maxBy { Duration.between(it.start, it.end) }
                val zone = night.zone ?: defaultZone
                val stages = night.stages.filter { it.end > it.start }.sortedBy { it.start }
                val phases = if (stages.isEmpty()) {
                    // Sans phases détaillées, toute la nuit compte comme du sommeil léger.
                    "0:NONREM12"
                } else {
                    buildList {
                        // Avant la première phase : éveillé au lit.
                        if (stages.first().start > night.start) add("0:WAKE")
                        stages.forEachIndexed { i, stage ->
                            add("${Duration.between(night.start, stage.start).seconds.coerceAtLeast(0)}:${stage.state}")
                            // Un trou entre deux phases est compté comme de l'éveil.
                            val next = stages.getOrNull(i + 1)
                            if (next != null && next.start > stage.end) {
                                add("${Duration.between(night.start, stage.end).seconds}:WAKE")
                            }
                        }
                    }.joinToString(",")
                }
                SleepEntity(
                    date = wakeDate.toString(),
                    startTime = ZonedDateTime.ofInstant(night.start, zone).toString(),
                    endTime = ZonedDateTime.ofInstant(night.end, zone).toString(),
                    goalMinutes = null,
                    phases = phases,
                    cycles = "",
                    userRating = null,
                    batteryRanOut = null,
                    skinTemperatureC = null,
                    skinTemperatureDeviationC = null,
                )
            }

    /**
     * Mesures nocturnes au format du Nightly Recharge, sans statut Polar : FC, VFC et respiration moyennes
     * pendant le sommeil. Notre score de récupération s'en sert (ligne de base personnelle après 5 nuits).
     */
    fun nightlyMeasures(
        sleeps: List<SleepEntity>,
        heart: List<HcHeartRate>,
        hrv: List<HcValue>,
        respiration: List<HcValue>,
    ): List<NightlyRechargeEntity> = sleeps.mapNotNull { sleep ->
        val start = sleep.startTime?.let { runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
        val end = sleep.endTime?.let { runCatching { ZonedDateTime.parse(it).toInstant() }.getOrNull() } ?: return@mapNotNull null
        fun during(t: Instant) = t >= start && t <= end
        val hr = heart.filter { during(it.time) && it.bpm > 0 }.map { it.bpm }
        val rmssd = hrv.filter { during(it.time) && it.value > 0 }.map { it.value }
        val breath = respiration.filter { during(it.time) && it.value > 0 }.map { it.value }
        val meanRri = hr.takeIf { it.size >= MIN_NIGHT_HR_SAMPLES }?.let { (60_000.0 / it.average()).roundToInt() }
        val meanRmssd = rmssd.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        nightlyMeasure(
            date = sleep.date,
            meanRriMs = meanRri,
            meanRmssdMs = meanRmssd,
            meanRespirationIntervalMs = breath.takeIf { it.isNotEmpty() }?.let { (60_000.0 / it.average()).roundToInt() },
        )
    }

    /** Mesures d'une nuit sans statut Polar ; `null` sans FC ni VFC. */
    fun nightlyMeasure(date: String, meanRriMs: Int?, meanRmssdMs: Int?, meanRespirationIntervalMs: Int?): NightlyRechargeEntity? {
        if (meanRriMs == null && meanRmssdMs == null) return null
        return NightlyRechargeEntity(
            date = date,
            ansStatus = null,
            recoveryIndicator = null,
            recoveryIndicatorSubLevel = null,
            ansRate = null,
            meanRriMs = meanRriMs,
            meanRmssdMs = meanRmssdMs,
            meanRespirationIntervalMs = meanRespirationIntervalMs,
            baselineRriMs = null,
            baselineRriSd = null,
            baselineRmssdMs = null,
            baselineRmssdSd = null,
            baselineRespirationIntervalMs = null,
            baselineRespirationIntervalSd = null,
            sleepTip = null,
            vitalityTip = null,
            exerciseTip = null,
        )
    }

    fun dailyActivity(totals: List<HcDayTotals>): List<DailyActivityEntity> = totals
        .filter { it.steps != null || it.distanceMeters != null || it.activeCalories != null || it.bmrCalories != null }
        .map {
            DailyActivityEntity(
                date = it.date.toString(),
                steps = it.steps?.toInt(),
                activityCalories = it.activeCalories?.roundToInt(),
                trainingCalories = null,
                bmrCalories = it.bmrCalories?.roundToInt(),
                distanceMeters = it.distanceMeters?.toFloat(),
                activityGoal = null,
                achievedActivity = null,
                sedentarySec = null,
                lightSec = null,
                moderateSec = null,
                vigorousSec = null,
                sleepSec = null,
                nonWearSec = null,
                dailyBalance = null,
                readiness = null,
            )
        }

    /** Chemin d'une séance Health Connect dans la table des séances. */
    fun sessionPath(healthConnectId: String) = "healthconnect:$healthConnectId"

    /** Tracés des séances qui en ont un (lisible ou à autoriser). */
    fun routes(exercises: List<HcExercise>, heart: List<HcHeartRate>): List<TrainingRouteEntity> =
        exercises.mapNotNull { exercise ->
            when (val route = exercise.route) {
                is HcRoute.Points -> routeEntity(exercise.id, route.locations, heart)
                HcRoute.ConsentRequired -> TrainingRouteEntity(
                    sessionPath = sessionPath(exercise.id),
                    status = RouteStatus.CONSENT_REQUIRED.name,
                    source = RouteSource.HEALTH_CONNECT.name,
                    healthConnectId = exercise.id,
                    points = "",
                )
                HcRoute.None -> null
            }
        }

    /** Tracé lisible : chaque point reçoit la FC mesurée au même moment (interpolée). `null` sans assez de points. */
    fun routeEntity(healthConnectId: String, locations: List<HcLocation>, heart: List<HcHeartRate>): TrainingRouteEntity? {
        if (locations.size < RouteAnalysis.MIN_POINTS) return null
        val samples = heart.filter { it.bpm > 0 }.sortedBy { it.time }.map { it.time.toEpochMilli() to it.bpm.roundToInt() }
        val points = locations.sortedBy { it.time }.map { location ->
            val time = location.time.toEpochMilli()
            RoutePoint(time, location.latitude, location.longitude, location.altitude, RouteAnalysis.heartRateAt(time, samples))
        }
        return TrainingRouteEntity(
            sessionPath = sessionPath(healthConnectId),
            status = RouteStatus.AVAILABLE.name,
            source = RouteSource.HEALTH_CONNECT.name,
            healthConnectId = healthConnectId,
            points = RouteCodec.encode(points),
        )
    }

    /** Séances, avec FC moyenne et max tirées des mesures de FC pendant la séance ; les courses servent au VO2max. */
    fun trainingSessions(exercises: List<HcExercise>, heart: List<HcHeartRate>, defaultZone: ZoneId): List<TrainingSessionEntity> =
        exercises.filter { it.end > it.start }.map { exercise ->
            val start: LocalDateTime = exercise.start.atZone(exercise.zone ?: defaultZone).toLocalDateTime()
            val hr = heart.filter { it.time >= exercise.start && it.time <= exercise.end && it.bpm > 0 }.map { it.bpm }
            TrainingSessionEntity(
                path = sessionPath(exercise.id),
                date = start.toLocalDate().toString(),
                startTime = start.toString(),
                durationSec = Duration.between(exercise.start, exercise.end).seconds.toInt(),
                sportId = if (exercise.running) Vo2MaxEstimation.RUNNING_SPORT_ID else null,
                name = exercise.name,
                calories = exercise.calories?.roundToInt(),
                distanceMeters = exercise.distanceMeters?.toFloat(),
                averageHr = hr.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                maxHr = hr.maxOrNull()?.roundToInt(),
                cardioLoad = null,
                muscleLoad = null,
                hrZoneSec = "",
                exerciseCount = 0,
                summaryProto = null,
            )
        }
}
