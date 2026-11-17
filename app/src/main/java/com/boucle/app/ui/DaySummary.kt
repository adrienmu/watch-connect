package com.boucle.app.ui

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.BodyBatteryDay
import com.boucle.app.domain.BodyBatteryInputs
import com.boucle.app.domain.MeasurementType
import com.boucle.app.domain.BodySummary
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.domain.SleepScore
import com.boucle.app.domain.Spo2Stats
import com.boucle.app.domain.Vo2MaxEstimate
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

data class HeartRateStats(val min: Int, val average: Int, val max: Int, val sampleCount: Int)

/** Une phase de la nuit, en secondes depuis le coucher. [state] : WAKE, REM, NONREM12, NONREM3 ou UNKNOWN. */
data class SleepPhase(val startSec: Long, val endSec: Long, val state: String)

data class SleepStats(
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val totalSec: Long?,
    /** Secondes passées dans chaque état (WAKE, REM, NONREM12, NONREM3, UNKNOWN). */
    val secondsByState: Map<String, Long>,
    val cycleCount: Int,
    val goalMinutes: Int?,
    val skinTemperatureC: Float?,
    val skinTemperatureDeviationC: Float?,
    /** Phases dans l'ordre de la nuit (hypnogramme). */
    val phases: List<SleepPhase> = emptyList(),
)

/** Pas d'un jour ; `null` sans donnée d'activité ce jour-là. */
data class DaySteps(val date: LocalDate, val steps: Int?)

/** Fréquence cardiaque la plus basse et la plus haute d'une heure de la journée (0 à 23). */
data class HourHeartRate(val hour: Int, val min: Int, val max: Int)

/** Une pesée (la dernière de la journée). */
data class WeightPoint(val date: LocalDate, val kg: Double)

data class TemperatureStats(val min: Float, val average: Float, val max: Float, val sampleCount: Int)

/** Vue brute d'une journée, telle que lue sur le bracelet, sans aucun score calculé. */
data class DayUiState(
    val date: LocalDate,
    val activity: DailyActivityEntity? = null,
    val minuteStepBlocks: Int = 0,
    val heartRate: HeartRateStats? = null,
    val ppiIntervalCount: Int = 0,
    val sleep: SleepStats? = null,
    val recharge: NightlyRechargeEntity? = null,
    val skinTemperature: TemperatureStats? = null,
    val sessions: List<TrainingSessionEntity> = emptyList(),
    val sleepScore: SleepScore? = null,
    val recovery: RecoveryScore? = null,
    val battery: BodyBatteryDay? = null,
    val vo2Max: Vo2MaxEstimate? = null,
    val body: BodySummary? = null,
    val spo2: Spo2Stats? = null,
    /** Les 7 jours qui finissent à [date], du plus ancien au plus récent. */
    val weekSteps: List<DaySteps> = emptyList(),
    /** Plage de FC heure par heure ; les heures sans mesure sont absentes. */
    val hourlyHeartRate: List<HourHeartRate> = emptyList(),
    /** Pesées des 30 jours qui finissent à [date], dans l'ordre. */
    val weightHistory: List<WeightPoint> = emptyList(),
) {
    val isEmpty: Boolean
        get() = activity == null && minuteStepBlocks == 0 && heartRate == null && ppiIntervalCount == 0 &&
            sleep == null && recharge == null && skinTemperature == null && sessions.isEmpty() &&
            body?.weighedOn != date && spo2 == null
}

private fun String.csv(): List<String> = if (isEmpty()) emptyList() else split(',')

fun heartRateStats(blocks: List<HrBlockEntity>): HeartRateStats? {
    val samples = blocks.flatMap { block -> block.samples.csv().mapNotNull { it.toIntOrNull() } }.filter { it > 0 }
    if (samples.isEmpty()) return null
    return HeartRateStats(samples.min(), samples.average().toInt(), samples.max(), samples.size)
}

fun ppiIntervalCount(blocks: List<PpiBlockEntity>): Int = blocks.sumOf { it.ppiMs.csv().size }

fun minuteStepBlocks(blocks: List<ActivitySamplesEntity>): Int = blocks.count { it.stepSamples.isNotEmpty() }

fun sleepStats(entity: SleepEntity): SleepStats {
    val start = entity.startTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
    val end = entity.endTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() }
    val totalSec = if (start != null && end != null) Duration.between(start, end).seconds else null

    val phases = entity.phases.csv().mapNotNull { item ->
        val (seconds, state) = item.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (seconds.toLongOrNull() ?: return@mapNotNull null) to state
    }
    // Une phase dure jusqu'au début de la suivante ; la dernière jusqu'au réveil.
    val secondsByState = mutableMapOf<String, Long>()
    val timeline = mutableListOf<SleepPhase>()
    phases.forEachIndexed { index, (startSec, state) ->
        val endSec = phases.getOrNull(index + 1)?.first ?: totalSec ?: startSec
        secondsByState.merge(state, (endSec - startSec).coerceAtLeast(0), Long::plus)
        if (endSec > startSec) timeline += SleepPhase(startSec, endSec, state)
    }

    return SleepStats(
        start = start,
        end = end,
        totalSec = totalSec,
        secondsByState = secondsByState,
        cycleCount = entity.cycles.csv().size,
        goalMinutes = entity.goalMinutes,
        skinTemperatureC = entity.skinTemperatureC,
        skinTemperatureDeviationC = entity.skinTemperatureDeviationC,
        phases = timeline,
    )
}

/** Plage de FC par heure de la journée, à partir des blocs du bracelet (ou de la montre). */
fun hourlyHeartRate(blocks: List<HrBlockEntity>): List<HourHeartRate> {
    val byHour = mutableMapOf<Int, MutableList<Int>>()
    blocks.forEach { block ->
        val start = runCatching { LocalTime.parse(block.startTime) }.getOrNull() ?: return@forEach
        val interval = if ("HIGH" in block.trigger) {
            BodyBatteryInputs.HIGH_ACTIVITY_HR_INTERVAL_SEC
        } else {
            BodyBatteryInputs.LOW_ACTIVITY_HR_INTERVAL_SEC
        }
        block.samples.csv().forEachIndexed { k, raw ->
            val bpm = raw.toIntOrNull()?.takeIf { it > 0 } ?: return@forEachIndexed
            val second = start.toSecondOfDay() + k * interval
            if (second < 86_400) byHour.getOrPut((second / 3600).toInt()) { mutableListOf() } += bpm
        }
    }
    return byHour.toSortedMap().map { (hour, values) -> HourHeartRate(hour, values.min(), values.max()) }
}

/** Dernière pesée de chaque jour, de [from] à [to] inclus. */
fun weightHistory(measurements: List<ExternalMeasurementEntity>, from: LocalDate, to: LocalDate): List<WeightPoint> =
    measurements
        .filter { it.type == MeasurementType.WEIGHT.name && it.date >= from.toString() && it.date <= to.toString() }
        .sortedBy { it.time }
        .groupBy { it.date }
        .map { (date, values) -> WeightPoint(LocalDate.parse(date), values.last().value) }
        .sortedBy { it.date }

fun temperatureStats(entity: SkinTemperatureEntity): TemperatureStats? {
    val values = entity.samples.csv().mapNotNull { it.substringAfter(':').toFloatOrNull() }
    if (values.isEmpty()) return null
    return TemperatureStats(values.min(), values.average().toFloat(), values.max(), values.size)
}
