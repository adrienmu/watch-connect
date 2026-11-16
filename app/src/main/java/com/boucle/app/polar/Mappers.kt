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
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.PolarActiveTime
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import com.polar.sdk.api.model.trainingsession.PolarTrainingSession
import fi.polar.remote.representation.protobuf.Types
import java.time.LocalDateTime

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

fun Polar247PPiSamplesData.toEntity(): PpiBlockEntity = PpiBlockEntity(
    date = date.toString(),
    startTime = samples.startTime.toString(),
    trigger = samples.triggerType.name,
    ppiMs = samples.ppiValueList.joinToString(","),
    errorEstimateMs = samples.ppiErrorEstimateList.joinToString(","),
)

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
