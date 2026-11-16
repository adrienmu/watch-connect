package com.boucle.app.ui

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RecoveryScore
import com.boucle.app.domain.SleepScore
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

data class HeartRateStats(val min: Int, val average: Int, val max: Int, val sampleCount: Int)

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
)

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
) {
    val isEmpty: Boolean
        get() = activity == null && minuteStepBlocks == 0 && heartRate == null && ppiIntervalCount == 0 &&
            sleep == null && recharge == null && skinTemperature == null && sessions.isEmpty()
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
    phases.forEachIndexed { index, (startSec, state) ->
        val endSec = phases.getOrNull(index + 1)?.first ?: totalSec ?: startSec
        secondsByState.merge(state, (endSec - startSec).coerceAtLeast(0), Long::plus)
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
    )
}

fun temperatureStats(entity: SkinTemperatureEntity): TemperatureStats? {
    val values = entity.samples.csv().mapNotNull { it.substringAfter(':').toFloatOrNull() }
    if (values.isEmpty()) return null
    return TemperatureStats(values.min(), values.average().toFloat(), values.max(), values.size)
}
