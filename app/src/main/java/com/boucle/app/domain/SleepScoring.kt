package com.boucle.app.domain

import com.boucle.app.data.db.SleepEntity
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Score de sommeil sur 100, calculé localement à partir des phases fournies par le bracelet.
 *
 * Structure en quatre piliers inspirée de la documentation publique du « Sleep Score » d'Open Wearables
 * (licence MIT, https://openwearables.io/docs/scores/sleep-score) : durée, stades, régularité,
 * interruptions. Les formules sont réécrites ici ; deux choix diffèrent volontairement :
 *  - les stades sont jugés en proportion du temps dormi (et non en minutes absolues), pour ne pas
 *    pénaliser deux fois une nuit courte, déjà sanctionnée par le pilier durée ;
 *  - sans historique, la régularité est omise et les autres piliers sont renormalisés, au lieu de
 *    compter 0.
 *
 * Les seuils sont des constantes réglables : ce sont des valeurs de départ raisonnables, à ajuster
 * en regardant tes propres nuits.
 */
data class SleepNight(
    /** Heure de coucher en minutes écoulées depuis midi, pour comparer sans saut à minuit. */
    val bedtimeMinutesAfterNoon: Int?,
    val sleepSec: Long,
    val deepSec: Long,
    val remSec: Long,
    /** Temps éveillé entre l'endormissement et le réveil final. */
    val wasoSec: Long,
    /** Nombre d'éveils de plus de 5 minutes. */
    val awakenings: Int,
)

data class SleepScore(
    val total: Int,
    val duration: Int,
    val stages: Int,
    /** `null` tant qu'il n'y a pas assez de nuits précédentes. */
    val consistency: Int?,
    val interruptions: Int,
    val night: SleepNight,
)

enum class SleepBand { EXCELLENT, GOOD, FAIR, POOR }

val SleepScore.band: SleepBand
    get() = when {
        total >= 85 -> SleepBand.EXCELLENT
        total >= 70 -> SleepBand.GOOD
        total >= 50 -> SleepBand.FAIR
        else -> SleepBand.POOR
    }

private val SLEEP_STATES = setOf("REM", "NONREM12", "NONREM3")

private data class Segment(val state: String, val seconds: Long)

/** Réduit une nuit stockée à ce dont le score a besoin ; `null` si les phases sont inexploitables. */
fun SleepEntity.toNight(): SleepNight? {
    val start = startTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return null
    val end = endTime?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return null
    val inBedSec = Duration.between(start, end).seconds
    if (inBedSec <= 0) return null

    val phases = if (phases.isEmpty()) emptyList() else phases.split(',').mapNotNull { item ->
        val parts = item.split(':')
        if (parts.size != 2) return@mapNotNull null
        (parts[0].toLongOrNull() ?: return@mapNotNull null) to parts[1]
    }.sortedBy { it.first }
    if (phases.isEmpty()) return null

    // Une phase dure jusqu'au début de la suivante ; la dernière jusqu'au réveil. Les phases
    // consécutives du même état sont fusionnées.
    val segments = mutableListOf<Segment>()
    phases.forEachIndexed { index, (startSec, state) ->
        val endSec = phases.getOrNull(index + 1)?.first ?: inBedSec
        val seconds = (endSec - startSec).coerceAtLeast(0)
        if (seconds == 0L) return@forEachIndexed
        val last = segments.lastOrNull()
        if (last != null && last.state == state) {
            segments[segments.lastIndex] = last.copy(seconds = last.seconds + seconds)
        } else {
            segments += Segment(state, seconds)
        }
    }

    val sleepSec = segments.filter { it.state in SLEEP_STATES }.sumOf { it.seconds }
    if (sleepSec == 0L) return null

    // Latence (éveil initial) et éveil final exclus : seuls les éveils au milieu de la nuit comptent.
    val firstSleep = segments.indexOfFirst { it.state in SLEEP_STATES }
    val lastSleep = segments.indexOfLast { it.state in SLEEP_STATES }
    val night = segments.subList(firstSleep, lastSleep + 1)
    val wakes = night.filter { it.state == "WAKE" }

    return SleepNight(
        bedtimeMinutesAfterNoon = (start.hour * 60 + start.minute - 12 * 60 + 24 * 60) % (24 * 60),
        sleepSec = sleepSec,
        deepSec = segments.filter { it.state == "NONREM3" }.sumOf { it.seconds },
        remSec = segments.filter { it.state == "REM" }.sumOf { it.seconds },
        wasoSec = wakes.sumOf { it.seconds },
        awakenings = wakes.count { it.seconds >= SIGNIFICANT_AWAKENING_SEC },
    )
}

private const val SIGNIFICANT_AWAKENING_SEC = 5 * 60L

object SleepScoring {
    const val WEIGHT_DURATION = 0.40
    const val WEIGHT_STAGES = 0.20
    const val WEIGHT_CONSISTENCY = 0.20
    const val WEIGHT_INTERRUPTIONS = 0.20

    /** Part du temps dormi visée pour le sommeil profond et paradoxal (valeurs de référence adulte). */
    const val DEEP_TARGET_SHARE = 0.16
    const val REM_TARGET_SHARE = 0.21

    const val CONSISTENCY_GRACE_MIN = 15
    const val CONSISTENCY_LATE_WINDOW_MIN = 105
    const val CONSISTENCY_EARLY_MAX_PENALTY = 20.0
    const val CONSISTENCY_MIN_NIGHTS = 3
    const val CONSISTENCY_WINDOW_NIGHTS = 14

    const val WASO_GRACE_MIN = 20
    const val WASO_ZERO_AT_MIN = 90

    /** @param history nuits précédentes, de la plus ancienne à la plus récente. */
    fun score(night: SleepNight, history: List<SleepNight>): SleepScore {
        val duration = durationScore(night.sleepSec / 3600.0)
        val stages = stagesScore(night)
        val consistency = night.bedtimeMinutesAfterNoon?.let { bedtime ->
            consistencyScore(bedtime, history.takeLast(CONSISTENCY_WINDOW_NIGHTS).mapNotNull { it.bedtimeMinutesAfterNoon })
        }
        val interruptions = interruptionsScore(night.wasoSec, night.awakenings)

        val parts = listOfNotNull(
            WEIGHT_DURATION to duration,
            WEIGHT_STAGES to stages,
            consistency?.let { WEIGHT_CONSISTENCY to it },
            WEIGHT_INTERRUPTIONS to interruptions,
        )
        val total = parts.sumOf { it.first * it.second } / parts.sumOf { it.first }

        return SleepScore(
            total = total.roundToInt().coerceIn(0, 100),
            duration = duration.roundToInt(),
            stages = stages.roundToInt(),
            consistency = consistency?.roundToInt(),
            interruptions = interruptions.roundToInt(),
            night = night,
        )
    }

    /** 100 entre 7 et 9 h ; chute rapide en dessous (50 pour 5 h) ; décroissance douce au-delà, plancher 50. */
    fun durationScore(hours: Double): Double = when {
        hours in 7.0..9.0 -> 100.0
        hours < 7.0 -> min(100.0, 100.0 * shortSleepSigmoid(hours) / shortSleepSigmoid(7.0))
        else -> 100.0 - 50.0 * (1.0 - exp(-(hours - 9.0) / 1.5))
    }

    private fun shortSleepSigmoid(hours: Double): Double = 1.0 / (1.0 + exp(-2.2 * (hours - 5.0)))

    /** Moyenne des scores profond et paradoxal, chacun linéaire jusqu'à sa part cible. */
    fun stagesScore(night: SleepNight): Double {
        if (night.sleepSec <= 0) return 0.0
        val deepShare = night.deepSec.toDouble() / night.sleepSec
        val remShare = night.remSec.toDouble() / night.sleepSec
        val deep = min(100.0, 100.0 * deepShare / DEEP_TARGET_SHARE)
        val rem = min(100.0, 100.0 * remShare / REM_TARGET_SHARE)
        return (deep + rem) / 2.0
    }

    /**
     * Écart entre l'heure de coucher et la médiane des nuits précédentes. Tolérance de 15 minutes ;
     * se coucher tard coûte jusqu'à 100 points sur 105 minutes, se coucher tôt au plus 20.
     * `null` s'il y a moins de [CONSISTENCY_MIN_NIGHTS] nuits de référence.
     */
    fun consistencyScore(bedtimeMinutesAfterNoon: Int, previousBedtimes: List<Int>): Double? {
        if (previousBedtimes.size < CONSISTENCY_MIN_NIGHTS) return null
        val deviation = bedtimeMinutesAfterNoon - median(previousBedtimes.map { it.toDouble() })
        val beyondGrace = abs(deviation) - CONSISTENCY_GRACE_MIN
        if (beyondGrace <= 0) return 100.0
        val penalty = beyondGrace / CONSISTENCY_LATE_WINDOW_MIN * 100.0
        return 100.0 - if (deviation > 0) min(100.0, penalty) else min(CONSISTENCY_EARLY_MAX_PENALTY, penalty)
    }

    /** 80 points pour le temps éveillé (plein jusqu'à 20 min, nul à 90 min) + 20 points pour le nombre d'éveils. */
    fun interruptionsScore(wasoSec: Long, awakenings: Int): Double {
        val wasoMin = wasoSec / 60.0
        val duration = when {
            wasoMin <= WASO_GRACE_MIN -> 80.0
            wasoMin >= WASO_ZERO_AT_MIN -> 0.0
            else -> 80.0 * (1.0 - (wasoMin - WASO_GRACE_MIN) / (WASO_ZERO_AT_MIN - WASO_GRACE_MIN))
        }
        val frequency = when (awakenings) {
            0, 1 -> 20.0
            2 -> 15.0
            3 -> 10.0
            else -> 0.0
        }
        return duration + frequency
    }

    private fun abs(value: Double) = max(value, -value)
}

internal fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}
