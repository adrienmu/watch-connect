package com.boucle.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Zones du stress VFC, comme celles de [HrvStress] : 0–25 repos, 26–50 faible, 51–75 moyen, 76–100 élevé. */
enum class StressZone {
    REST, LOW, MEDIUM, HIGH;

    companion object {
        fun of(stress: Double): StressZone = when {
            stress <= 25.0 -> REST
            stress <= 50.0 -> LOW
            stress <= 75.0 -> MEDIUM
            else -> HIGH
        }
    }
}

/** Les quatre paliers du score de bien-être, du plus bas au plus haut. */
enum class WellbeingLevel {
    WATCH, FRAGILE, BALANCED, SERENE;

    companion object {
        fun of(total: Int): WellbeingLevel = when {
            total >= 80 -> SERENE
            total >= 60 -> BALANCED
            total >= 40 -> FRAGILE
            else -> WATCH
        }
    }
}

/** Lecture des 4 dernières semaines : le stress est-il passager ou installé ? */
enum class ChronicStress {
    /** Trop peu de jours mesurés pour conclure. */
    UNKNOWN,
    NONE,
    /** Au moins [Wellbeing.WATCH_ELEVATED_DAYS] jours de stress moyen ou élevé. */
    WATCH,
    /** Au moins [Wellbeing.SETTLED_ELEVATED_DAYS] jours de stress moyen ou élevé. */
    SETTLED,
}

/**
 * Stress d'une journée d'éveil.
 *
 * @param mean moyenne des créneaux mesurés, de 0 à 100.
 * @param windows nombre de créneaux de 5 minutes mesurés.
 * @param restShare part des créneaux mesurés en zone de repos, de 0 à 1.
 */
data class StressDay(val date: LocalDate, val mean: Double, val windows: Int, val restShare: Double) {
    val zone: StressZone get() = StressZone.of(mean)
    /** Journée de stress moyen ou élevé. */
    val elevated: Boolean get() = zone >= StressZone.MEDIUM
}

/** Ce qu'une nuit apporte au score : sous-score VFC de la récupération, RMSSD et sa référence, score de sommeil. */
data class WellbeingNight(
    val date: LocalDate,
    val hrvScore: Int?,
    val rmssdMs: Int?,
    val rmssdBaselineMs: Int?,
    val sleepScore: Int?,
)

/**
 * Score de bien-être sur 100 et ses composantes ; une composante sans mesure est `null` et son poids est
 * réparti sur les autres.
 *
 * @param stress sous-score du stress en journée (100 = très calme).
 * @param meanStress stress moyen des journées mesurées, de 0 à 100.
 * @param hrv sous-score de la VFC nocturne, moyenne de celui des nuits.
 * @param calm sous-score du retour au calme.
 * @param restShare part du temps d'éveil mesuré en zone de repos, de 0 à 1.
 * @param sleep score de sommeil moyen des nuits.
 */
data class WellbeingScore(
    val total: Int,
    val stress: Int?,
    val meanStress: Int?,
    val hrv: Int?,
    val rmssdMs: Int?,
    val rmssdBaselineMs: Int?,
    val calm: Int?,
    val restShare: Double?,
    val sleep: Int?,
) {
    val level: WellbeingLevel get() = WellbeingLevel.of(total)
}

/**
 * Bien-être vu depuis une journée : le score des 7 derniers jours, le stress du jour et la tendance
 * sur 4 semaines.
 *
 * @param timeline stress moyen par demi-heure de [Wellbeing.TIMELINE_START_HOUR] à
 *   [Wellbeing.TIMELINE_END_HOUR] ; `null` pour une demi-heure sans mesure.
 * @param week les [Wellbeing.SCORE_DAYS] jours qui finissent à la date affichée ; `null` pour un jour sans mesure.
 * @param month les [Wellbeing.TREND_DAYS] jours qui finissent à la date affichée.
 * @param usualBand zone habituelle du stress moyen quotidien : 8 jours mesurés sur 10 y tombent (du 1er au 9e décile).
 */
data class WellbeingDay(
    val score: WellbeingScore?,
    val today: StressDay?,
    val timeline: List<Double?>,
    val week: List<StressDay?>,
    val month: List<StressDay?>,
    val measuredDays: Int,
    val elevatedDays: Int,
    val chronic: ChronicStress,
    val usualBand: ClosedFloatingPointRange<Double>?,
) {
    val isEmpty: Boolean get() = score == null && measuredDays == 0
}

/**
 * Score de bien-être : un résumé sur 7 jours de la façon dont ton système nerveux encaisse le stress
 * et en récupère. Barème maison, calculé à partir du stress VFC de [HrvStress] et des scores de nuit :
 *
 *  - Stress en journée : 35 % — stress moyen des heures d'éveil
 *  - VFC nocturne : 25 % — sous-score VFC de la récupération, comparé à tes 28 dernières nuits
 *  - Sommeil : 25 % — score de sommeil moyen
 *  - Retour au calme : 15 % — part du temps d'éveil passée en zone de repos
 *
 * La tendance sur 4 semaines distingue un stress passager d'un stress installé : c'est le stress qui
 * dure, pas un pic isolé, qui est associé à une inflammation de fond. Le score reste un indicateur de
 * tendance : le bracelet ne mesure ni le cortisol ni l'inflammation.
 */
object Wellbeing {
    const val SCORE_DAYS = 7
    const val TREND_DAYS = 28

    const val WEIGHT_STRESS = 0.35
    const val WEIGHT_HRV = 0.25
    const val WEIGHT_SLEEP = 0.25
    const val WEIGHT_CALM = 0.15

    /** Une journée compte à partir de ce nombre de créneaux mesurés (20 minutes). */
    const val MIN_DAY_WINDOWS = 4
    /** Jours (ou nuits) mesurés nécessaires dans la semaine pour afficher un score. */
    const val MIN_SCORE_DAYS = 3
    /** Jours mesurés nécessaires sur 4 semaines pour se prononcer sur le stress chronique. */
    const val MIN_TREND_DAYS = 14
    const val WATCH_ELEVATED_DAYS = 7
    const val SETTLED_ELEVATED_DAYS = 14
    /** Jours mesurés nécessaires pour dessiner la zone habituelle. */
    const val MIN_BAND_DAYS = 7

    /** Un stress moyen de 30 (éveil ordinaire) vaut 80 ; 50 vaut 50 ; au-delà de 83, 0. */
    private const val STRESS_SCORE_AT_ZERO = 125.0
    private const val STRESS_SCORE_SLOPE = 1.5
    /** Part du temps d'éveil en zone de repos qui vaut 100. */
    private const val CALM_FULL_SHARE = 0.5

    const val TIMELINE_START_HOUR = 6
    const val TIMELINE_END_HOUR = 22
    private const val TIMELINE_SLOT_MINUTES = 30

    /** Stress de chaque journée assez mesurée, à partir du stress par créneau de [HrvStress.score]. */
    fun days(stress: Map<LocalDateTime, Double>): Map<LocalDate, StressDay> = stress.entries
        .groupBy({ it.key.toLocalDate() }, { it.value })
        .filterValues { it.size >= MIN_DAY_WINDOWS }
        .mapValues { (date, values) ->
            StressDay(
                date = date,
                mean = values.average(),
                windows = values.size,
                restShare = values.count { StressZone.of(it) == StressZone.REST } / values.size.toDouble(),
            )
        }

    /** Sous-score du stress en journée pour un stress moyen donné. */
    fun stressScore(meanStress: Double): Int =
        (STRESS_SCORE_AT_ZERO - STRESS_SCORE_SLOPE * meanStress).roundToInt().coerceIn(0, 100)

    /** Sous-score du retour au calme pour une part de temps en zone de repos. */
    fun calmScore(restShare: Double): Int = (restShare / CALM_FULL_SHARE * 100).roundToInt().coerceIn(0, 100)

    /** Score de la semaine ; `null` avec moins de [MIN_SCORE_DAYS] jours ou nuits mesurés. */
    fun score(days: List<StressDay>, nights: List<WellbeingNight>): WellbeingScore? {
        val hrvNights = nights.filter { it.hrvScore != null }
        if (maxOf(days.size, hrvNights.size) < MIN_SCORE_DAYS) return null

        val meanStress = days.takeIf { it.isNotEmpty() }?.map { it.mean }?.average()
        val restShare = days.takeIf { it.isNotEmpty() }?.let { list ->
            list.sumOf { it.restShare * it.windows } / list.sumOf { it.windows }
        }
        val stress = meanStress?.let(::stressScore)
        val calm = restShare?.let(::calmScore)
        val hrv = hrvNights.mapNotNull { it.hrvScore }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val sleep = nights.mapNotNull { it.sleepScore }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

        val parts = listOfNotNull(
            stress?.let { it to WEIGHT_STRESS },
            hrv?.let { it to WEIGHT_HRV },
            sleep?.let { it to WEIGHT_SLEEP },
            calm?.let { it to WEIGHT_CALM },
        )
        val total = parts.sumOf { (value, weight) -> value * weight } / parts.sumOf { it.second }
        return WellbeingScore(
            total = total.roundToInt().coerceIn(0, 100),
            stress = stress,
            meanStress = meanStress?.roundToInt(),
            hrv = hrv,
            rmssdMs = hrvNights.mapNotNull { it.rmssdMs }.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            rmssdBaselineMs = hrvNights.lastOrNull { it.rmssdBaselineMs != null }?.rmssdBaselineMs,
            calm = calm,
            restShare = restShare,
            sleep = sleep,
        )
    }

    /** Lecture de la tendance à partir des jours mesurés sur 4 semaines. */
    fun chronic(measuredDays: Int, elevatedDays: Int): ChronicStress = when {
        measuredDays < MIN_TREND_DAYS -> ChronicStress.UNKNOWN
        elevatedDays >= SETTLED_ELEVATED_DAYS -> ChronicStress.SETTLED
        elevatedDays >= WATCH_ELEVATED_DAYS -> ChronicStress.WATCH
        else -> ChronicStress.NONE
    }

    /** Stress moyen par demi-heure de la journée [date], entre 6 h et 22 h. */
    fun timeline(stress: Map<LocalDateTime, Double>, date: LocalDate): List<Double?> {
        val slots = (TIMELINE_END_HOUR - TIMELINE_START_HOUR) * 60 / TIMELINE_SLOT_MINUTES
        val sums = DoubleArray(slots)
        val counts = IntArray(slots)
        stress.forEach { (moment, value) ->
            if (moment.toLocalDate() != date) return@forEach
            val minutes = moment.hour * 60 + moment.minute - TIMELINE_START_HOUR * 60
            if (minutes < 0) return@forEach
            val slot = minutes / TIMELINE_SLOT_MINUTES
            if (slot >= slots) return@forEach
            sums[slot] += value
            counts[slot]++
        }
        return List(slots) { if (counts[it] > 0) sums[it] / counts[it] else null }
    }

    /**
     * @param stress stress par créneau d'éveil sur au moins [TREND_DAYS] jours finissant à [date].
     * @param nights nuits dont le réveil tombe dans les [SCORE_DAYS] jours finissant à [date] ; les autres sont ignorées.
     */
    fun build(date: LocalDate, stress: Map<LocalDateTime, Double>, nights: List<WellbeingNight>): WellbeingDay {
        val byDate = days(stress)
        val week = (SCORE_DAYS - 1 downTo 0).map { byDate[date.minusDays(it.toLong())] }
        val month = (TREND_DAYS - 1 downTo 0).map { byDate[date.minusDays(it.toLong())] }
        val measured = month.filterNotNull()
        val elevated = measured.count { it.elevated }
        val weekStart = date.minusDays(SCORE_DAYS - 1L)
        return WellbeingDay(
            score = score(week.filterNotNull(), nights.filter { it.date in weekStart..date }),
            today = byDate[date],
            timeline = timeline(stress, date),
            week = week,
            month = month,
            measuredDays = measured.size,
            elevatedDays = elevated,
            chronic = chronic(measured.size, elevated),
            usualBand = usualBand(measured),
        )
    }

    private fun usualBand(days: List<StressDay>): ClosedFloatingPointRange<Double>? {
        if (days.size < MIN_BAND_DAYS) return null
        val sorted = days.map { it.mean }.sorted()
        return quantile(sorted, 0.10)..quantile(sorted, 0.90)
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val position = q * (sorted.size - 1)
        val low = position.toInt()
        val high = minOf(low + 1, sorted.lastIndex)
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low)
    }
}
