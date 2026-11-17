package com.boucle.app.polar

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingExerciseEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.polar.sdk.api.model.PolarSkinTemperatureData
import com.polar.sdk.api.model.activity.Polar247HrSamplesData
import com.polar.sdk.api.model.activity.IntervalStatus
import com.polar.sdk.api.model.activity.Movement
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.SkinContact
import com.polar.sdk.api.model.activity.PolarActiveTime
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import com.polar.sdk.api.model.trainingsession.PolarTrainingSession
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.RouteSource
import com.boucle.app.domain.RouteStatus
import fi.polar.remote.representation.protobuf.Types
import java.time.LocalDateTime
import java.time.ZoneId

private fun PolarActiveTime.toSeconds(): Int = hours * 3600 + minutes * 60 + seconds

private fun Types.PbDuration.toSeconds(): Int = hours * 3600 + minutes * 60 + seconds

private fun Types.PbLocalDateTime.toLocalDateTime(): LocalDateTime =
    LocalDateTime.of(date.year, date.month, date.day, time.hour, time.minute, time.seconds)

fun PolarDailySummaryData.toEntity(
    fallbackSteps: Int?,
    fallbackDistance: Float?,
): DailyActivityEntity? {
    val day = date ?: return null
    val times = activityClassTimes
    return DailyActivityEntity(
        date = day.toString(),
        steps = steps ?: fallbackSteps,
        activityCalories = activityCalories,
        trainingCalories = trainingCalories,
        bmrCalories = bmrCalories,
        distanceMeters = activityDistance ?: fallbackDistance,
        activityGoal = activityGoalSummary?.activityGoal,
        achievedActivity = activityGoalSummary?.achievedActivity,
        sedentarySec = times?.timeSedentary?.toSeconds(),
        lightSec = times?.timeLightActivity?.toSeconds(),
        moderateSec = times?.let {
            it.timeContinuousModerateActivity.toSeconds() + it.timeIntermittentModerateActivity.toSeconds()
        },
        vigorousSec = times?.let {
            it.timeContinuousVigorousActivity.toSeconds() + it.timeIntermittentVigorousActivity.toSeconds()
        },
        sleepSec = times?.timeSleep?.toSeconds(),
        nonWearSec = times?.timeNonWear?.toSeconds(),
        dailyBalance = dailyBalanceFeedback?.name,
        readiness = readinessForSpeedAndStrengthTraining?.name,
    )
}

fun PolarActivitySamplesDayData.toEntities(): List<ActivitySamplesEntity> =
    polarActivitySamplesDataList.orEmpty().mapNotNull { block ->
        val start = block.startTime ?: return@mapNotNull null
        ActivitySamplesEntity(
            date = start.toLocalDate().toString(),
            startTime = start.toString(),
            stepIntervalSec = block.stepRecordingInterval,
            stepSamples = block.stepSamples.joinToString(","),
            metIntervalSec = block.metRecordingInterval,
            metSamples = block.metSamples.joinToString(","),
            activityInfo = block.activityInfoList.joinToString(";") {
                "${it.timeStamp}|${it.activityClass?.name.orEmpty()}|${it.factor}"
            },
        )
    }

fun Polar247HrSamplesData.toEntities(): List<HrBlockEntity> = samples.map {
    HrBlockEntity(
        date = date.toString(),
        startTime = it.startTime.toString(),
        trigger = it.triggerType.name,
        samples = it.hrSamples.joinToString(","),
    )
}

/** Estimation d'erreur donnée aux battements que Polar demande d'écarter : bien au-delà du seuil de [com.boucle.app.domain.HrvStress]. */
const val REJECTED_PPI_ERROR_MS = 999

fun Polar247PPiSamplesData.toEntity(): PpiBlockEntity {
    // Polar : un intervalle sans contact avec la peau, pris pendant un mouvement ou marquant une période hors
    // ligne n'est pas valide. Le statut n'a pas de colonne : ces battements reçoivent une erreur estimée
    // énorme, que le calcul du stress écarte déjà.
    val statuses = samples.statusList.takeIf { it.size == samples.ppiValueList.size }
    val errors = samples.ppiErrorEstimateList.mapIndexed { i, error ->
        val status = statuses?.get(i)
        val rejected = status != null && (
            status.skinContact == SkinContact.NO_SKIN_CONTACT ||
                status.movement == Movement.MOVING_DETECTED ||
                status.intervalStatus == IntervalStatus.INTERVAL_DENOTES_OFFLINE_PERIOD
            )
        if (rejected) REJECTED_PPI_ERROR_MS else error
    }
    return PpiBlockEntity(
        date = date.toString(),
        startTime = samples.startTime.toString(),
        trigger = samples.triggerType.name,
        ppiMs = samples.ppiValueList.joinToString(","),
        errorEstimateMs = errors.joinToString(","),
    )
}

fun PolarSleepData.toEntity(): SleepEntity? {
    val analysis = result ?: return null
    val day = date ?: analysis.sleepResultDate ?: return null
    return SleepEntity(
        date = day.toString(),
        startTime = analysis.sleepStartTime?.toString(),
        endTime = analysis.sleepEndTime?.toString(),
        goalMinutes = analysis.sleepGoalMinutes,
        phases = analysis.sleepWakePhases.orEmpty().joinToString(",") {
            "${it.secondsFromSleepStart}:${it.state.name}"
        },
        cycles = analysis.sleepCycles.orEmpty().joinToString(",") {
            "${it.secondsFromSleepStart}:${it.sleepDepthStart}"
        },
        userRating = analysis.userSleepRating?.name,
        batteryRanOut = analysis.batteryRanOut,
        skinTemperatureC = analysis.sleepSkinTemperatureResult?.sleepSkinTemperatureCelsius,
        skinTemperatureDeviationC = analysis.sleepSkinTemperatureResult?.deviationFromBaseLine,
    )
}

fun PolarNightlyRechargeData.toEntity(): NightlyRechargeEntity = NightlyRechargeEntity(
    date = (sleepResultDate ?: createdTimestamp.toLocalDate()).toString(),
    ansStatus = ansStatus,
    recoveryIndicator = recoveryIndicator,
    recoveryIndicatorSubLevel = recoveryIndicatorSubLevel,
    ansRate = ansRate,
    meanRriMs = meanNightlyRecoveryRRI,
    meanRmssdMs = meanNightlyRecoveryRMSSD,
    meanRespirationIntervalMs = meanNightlyRecoveryRespirationInterval,
    baselineRriMs = meanBaselineRRI,
    baselineRriSd = sdBaselineRRI,
    baselineRmssdMs = meanBaselineRMSSD,
    baselineRmssdSd = sdBaselineRMSSD,
    baselineRespirationIntervalMs = meanBaselineRespirationInterval,
    baselineRespirationIntervalSd = sdBaselineRespirationInterval,
    sleepTip = sleepTip,
    vitalityTip = vitalityTip,
    exerciseTip = exerciseTip,
)

fun PolarSkinTemperatureData.toEntity(): SkinTemperatureEntity? {
    val day = date ?: return null
    val measurement = result ?: return null
    return SkinTemperatureEntity(
        date = day.toString(),
        sensorLocation = measurement.sensorLocation?.name,
        measurementType = measurement.measurementType?.name,
        samples = measurement.skinTemperatureList.orEmpty().joinToString(",") {
            "${it.recordingTimeDeltaMs}:${it.temperature}"
        },
    )
}

fun PolarTrainingSession.toEntity(): TrainingSessionEntity {
    val summary = sessionSummary
    return TrainingSessionEntity(
        path = reference.path,
        date = reference.date.toString(),
        startTime = summary?.takeIf { it.hasStart() }?.start?.toLocalDateTime()?.toString(),
        durationSec = summary?.takeIf { it.hasDuration() }?.duration?.toSeconds(),
        sportId = summary?.takeIf { it.hasSport() }?.sport?.value,
        name = summary?.takeIf { it.hasSessionName() }?.sessionName?.text,
        calories = summary?.takeIf { it.hasCalories() }?.calories,
        distanceMeters = summary?.takeIf { it.hasDistance() }?.distance,
        averageHr = summary?.takeIf { it.hasHeartRate() }?.heartRate?.average,
        maxHr = summary?.takeIf { it.hasHeartRate() }?.heartRate?.maximum,
        cardioLoad = summary?.takeIf { it.hasCardioLoad() }?.cardioLoad?.exerciseLoad,
        muscleLoad = summary?.takeIf { it.hasMuscleLoad() }?.muscleLoad,
        hrZoneSec = summary?.heartRateZoneDurationList.orEmpty().joinToString(",") { it.toSeconds().toString() },
        exerciseCount = exercises.size,
        summaryProto = summary?.toByteArray(),
    )
}

fun PolarTrainingSession.toExerciseEntities(): List<TrainingExerciseEntity> = exercises.map {
    TrainingExerciseEntity(
        sessionPath = reference.path,
        exerciseIndex = it.index,
        baseProto = it.exerciseSummary?.toByteArray(),
        samplesProto = it.samples?.toByteArray(),
    )
}

private fun Types.PbDuration.toMillis(): Long = toSeconds() * 1000L + millis

/**
 * Tracé GPS de la séance (GPS du bracelet ou GPS connecté du téléphone), s'il y en a un : premier exercice qui a
 * des points. Chaque point reçoit la FC enregistrée au même moment par le bracelet.
 */
fun PolarTrainingSession.toRouteEntity(zone: ZoneId = ZoneId.systemDefault()): TrainingRouteEntity? {
    val sessionStart = sessionSummary?.takeIf { it.hasStart() }?.start?.toLocalDateTime()
    exercises.forEach { exercise ->
        val route = exercise.route ?: return@forEach
        if (route.latitudeCount < RouteAnalysis.MIN_POINTS) return@forEach
        val start = exercise.exerciseSummary?.takeIf { it.hasStart() }?.start?.toLocalDateTime() ?: sessionStart ?: return@forEach
        val samples = exercise.samples
        val points = bandRoutePoints(
            startMs = start.atZone(zone).toInstant().toEpochMilli(),
            offsetsMs = route.durationList.map { it.toLong() },
            latitudes = route.latitudeList,
            longitudes = route.longitudeList,
            altitudes = route.gpsAltitudeList.map { it.toDouble() },
            hrIntervalMs = samples?.takeIf { it.hasRecordingInterval() }?.recordingInterval?.toMillis(),
            heartRates = samples?.heartRateSamplesList.orEmpty(),
        )
        if (points.size < RouteAnalysis.MIN_POINTS) return@forEach
        return TrainingRouteEntity(
            sessionPath = reference.path,
            status = RouteStatus.AVAILABLE.name,
            source = RouteSource.BAND.name,
            healthConnectId = null,
            points = RouteCodec.encode(points),
        )
    }
    return null
}

/**
 * Assemble les colonnes du tracé du bracelet : décalages (ms depuis le début de l'exercice), latitudes, longitudes,
 * altitudes GPS ; la FC est échantillonnée toutes les [hrIntervalMs] depuis le même début (0 = capteur absent).
 */
fun bandRoutePoints(
    startMs: Long,
    offsetsMs: List<Long>,
    latitudes: List<Double>,
    longitudes: List<Double>,
    altitudes: List<Double>,
    hrIntervalMs: Long?,
    heartRates: List<Int>,
): List<RoutePoint> {
    val count = minOf(offsetsMs.size, latitudes.size, longitudes.size)
    return (0 until count).mapNotNull { i ->
        val lat = latitudes[i]
        val lon = longitudes[i]
        // (0, 0) : pas de position (recherche des satellites).
        if (lat == 0.0 && lon == 0.0) return@mapNotNull null
        val hr = hrIntervalMs?.takeIf { it > 0 }?.let { interval -> heartRates.getOrNull((offsetsMs[i] / interval).toInt()) }
        RoutePoint(
            timeMs = startMs + offsetsMs[i],
            latitude = lat,
            longitude = lon,
            altitude = altitudes.getOrNull(i).takeIf { altitudes.size == count },
            heartRate = hr?.takeIf { it > 0 },
        )
    }
}
