package com.boucle.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Objectifs quotidiens réglables : pas et calories actives (dépensées en bougeant, hors métabolisme de base). */
data class DailyGoals(
    val steps: Int = DEFAULT_STEPS,
    val activeCalories: Int = DEFAULT_ACTIVE_CALORIES,
) {
    /** Les mêmes objectifs ramenés dans les bornes du réglage. */
    fun coerced() = DailyGoals(
        steps = steps.coerceIn(MIN_STEPS, MAX_STEPS),
        activeCalories = activeCalories.coerceIn(MIN_ACTIVE_CALORIES, MAX_ACTIVE_CALORIES),
    )

    companion object {
        const val DEFAULT_STEPS = 10_000
        const val DEFAULT_ACTIVE_CALORIES = 600
        const val MIN_STEPS = 2_000
        const val MAX_STEPS = 30_000
        const val STEP_INCREMENT = 500
        const val MIN_ACTIVE_CALORIES = 100
        const val MAX_ACTIVE_CALORIES = 2_000
        const val ACTIVE_CALORIE_INCREMENT = 50
    }
}

/** Objectifs en vigueur à partir du jour [from] inclus. */
data class GoalChange(val from: LocalDate, val goals: DailyGoals)

/**
 * Suite des changements d'objectifs, du plus ancien au plus récent. Un changement ne vaut qu'à partir de son jour :
 * les jours d'avant gardent l'objectif qu'ils avaient, pour ne pas réécrire les objectifs déjà atteints ni les séries.
 * Avant le premier changement, ce sont les objectifs par défaut.
 */
data class GoalHistory(val changes: List<GoalChange> = emptyList()) {

    fun on(date: LocalDate): DailyGoals = changes.lastOrNull { it.from <= date }?.goals ?: DailyGoals()

    /** Historique où [goals] s'applique à partir de [from] ; un second réglage le même jour remplace le premier. */
    fun with(goals: DailyGoals, from: LocalDate): GoalHistory {
        val earlier = changes.filter { it.from < from }
        val previous = earlier.lastOrNull()?.goals ?: DailyGoals()
        return GoalHistory(if (goals == previous) earlier else earlier + GoalChange(from, goals))
    }

    /** « 2026-11-18:12000:500;2026-12-01:9000:450 ». */
    fun encode(): String = changes.joinToString(";") { "${it.from}:${it.goals.steps}:${it.goals.activeCalories}" }

    companion object {
        /** Lit [encode] ; les entrées illisibles sont ignorées. */
        fun decode(text: String?): GoalHistory {
            if (text.isNullOrBlank()) return GoalHistory()
            val changes = text.split(';').mapNotNull { entry ->
                val parts = entry.split(':')
                if (parts.size != 3) return@mapNotNull null
                val from = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
                val steps = parts[1].toIntOrNull() ?: return@mapNotNull null
                val calories = parts[2].toIntOrNull() ?: return@mapNotNull null
                GoalChange(from, DailyGoals(steps, calories).coerced())
            }
            return GoalHistory(changes.sortedBy { it.from })
        }
    }
}

/** Pas d'un jour face à l'objectif de ce jour-là ; [steps] est `null` sans donnée. */
data class StepDay(val date: LocalDate, val steps: Int?, val goal: Int) {
    val reached: Boolean get() = steps != null && steps >= goal

    /** Part de l'objectif atteinte (peut dépasser 1) ; 0 sans donnée. */
    val fraction: Float get() = if (steps == null || goal <= 0) 0f else steps.toFloat() / goal
}

/** Bilan d'une période (semaine ou mois). */
data class StepPeriodStats(
    val reachedDays: Int,
    /** Jours qui ont une mesure de pas. */
    val recordedDays: Int,
    val total: Int,
    /** Moyenne par jour mesuré ; `null` sans aucune mesure. */
    val average: Int?,
    val best: StepDay?,
    /** Plus longue suite de jours consécutifs à l'objectif dans la période. */
    val longestStreak: Int,
)

/** Pas quotidiens connus et objectifs en vigueur chaque jour. */
data class StepHistory(
    val steps: Map<LocalDate, Int> = emptyMap(),
    val goals: GoalHistory = GoalHistory(),
) {
    /** Premier jour qui a une mesure de pas. */
    val firstDate: LocalDate? get() = steps.keys.minOrNull()

    fun day(date: LocalDate) = StepDay(date, steps[date], goals.on(date).steps)

    /** Les 7 jours, du lundi au dimanche, de la semaine qui contient [date]. */
    fun week(date: LocalDate): List<StepDay> {
        val monday = weekStart(date)
        return (0L..6L).map { day(monday.plusDays(it)) }
    }

    /** Tous les jours de [month]. */
    fun month(month: YearMonth): List<StepDay> = (1..month.lengthOfMonth()).map { day(month.atDay(it)) }

    /**
     * Nombre de jours consécutifs à l'objectif qui finissent à [today]. Tant que l'objectif du jour n'est pas encore
     * atteint, la série compte jusqu'à la veille : elle ne retombe à zéro que si la veille est manquée.
     */
    fun currentStreak(today: LocalDate): Int {
        var date = if (day(today).reached) today else today.minusDays(1)
        var count = 0
        while (day(date).reached) {
            count++
            date = date.minusDays(1)
        }
        return count
    }

    companion object {
        fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun stats(days: List<StepDay>): StepPeriodStats {
            val recorded = days.filter { it.steps != null }
            val total = recorded.sumOf { it.steps ?: 0 }
            var run = 0
            var longest = 0
            days.forEach { day ->
                run = if (day.reached) run + 1 else 0
                if (run > longest) longest = run
            }
            return StepPeriodStats(
                reachedDays = recorded.count { it.reached },
                recordedDays = recorded.size,
                total = total,
                average = if (recorded.isEmpty()) null else Math.round(total.toFloat() / recorded.size),
                best = recorded.maxByOrNull { it.steps ?: 0 },
                longestStreak = longest,
            )
        }
    }
}
