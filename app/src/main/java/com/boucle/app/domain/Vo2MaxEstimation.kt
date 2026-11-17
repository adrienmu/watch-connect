package com.boucle.app.domain

import com.boucle.app.data.db.TrainingSessionEntity
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Estimation de la VO2max (ml/kg/min) par deux méthodes publiées. Le Loop ne mesure pas la VO2max
 * lui-même : c'est une valeur qu'on lui fournit.
 *
 * 1. **Course à pied** (prioritaire) : le coût en oxygène d'une course à plat suit l'équation de l'ACSM,
 *    VO2 = 0,2 × vitesse (m/min) + 3,5. On rapporte cette VO2 à l'intensité cardiaque de la séance, en
 *    supposant que la part de réserve de FC utilisée vaut la part de réserve de VO2 (relation %FCR ≈ %VO2R,
 *    Swain) : VO2max = (VO2 − 3,5) / %FCR + 3,5. Principe proche de ce que font les montres GPS.
 * 2. **FC de repos** (repli) : VO2max ≈ k × FCmax / FCrepos, avec k = 15,3 chez l'homme et 14,5 chez la
 *    femme (Uth et al., 2004 et 2005). Environ ±4,5 % d'erreur avec une FC max mesurée, ±8 % avec une
 *    FC max estimée par l'âge.
 *
 * Limites : la distance du Loop vient de l'accéléromètre (pas de GPS), et la FC moyenne d'une séance
 * inclut l'échauffement ; l'estimation par la course est donc approximative, d'où la médiane sur plusieurs
 * séances.
 */
enum class Vo2Method { RUNNING, RESTING_HR }

enum class HrMaxSource { PROFILE, OBSERVED, AGE_FORMULA }

/** Niveau de la VO2max par rapport aux personnes du même âge et du même sexe (voir [Vo2MaxEstimation.category]). */
enum class Vo2MaxCategory { POOR, FAIR, GOOD, EXCELLENT }

data class Vo2MaxEstimate(
    val value: Int,
    val method: Vo2Method,
    /** Nombre de courses retenues pour la méthode course. */
    val runCount: Int,
    val runningEstimate: Int?,
    val restingEstimate: Int?,
    val hrMax: Int,
    val hrMaxSource: HrMaxSource,
    val restingHr: Int,
    /** Niveau de [value] pour l'âge et le sexe du profil. */
    val category: Vo2MaxCategory,
)

object Vo2MaxEstimation {
    const val RUNNING_SPORT_ID = 1L
    /** Fenêtre glissante des courses prises en compte. */
    const val RUN_WINDOW_DAYS = 42L
    const val MAX_RUNS = 5

    private const val UTH_FACTOR_MALE = 15.3
    private const val UTH_FACTOR_FEMALE = 14.5

    private const val MIN_RUN_SEC = 15 * 60
    // Allures plausibles : de 4,8 à 24 km/h.
    private const val MIN_SPEED_M_PER_MIN = 80.0
    private const val MAX_SPEED_M_PER_MIN = 400.0
    // En dessous, la séance est trop facile pour extrapoler ; au-dessus, la relation n'est plus linéaire.
    private const val MIN_HRR = 0.50
    private const val MAX_HRR = 0.95

    private const val MIN_VO2MAX = 15.0
    private const val MAX_VO2MAX = 90.0

    /**
     * Seuils (ml/kg/min) de « Moyen », « Bon » et « Excellent » par tranche d'âge (moins de 30, 30–39,
     * 40–49, 50–59, 60 ans et plus). Repères indicatifs, arrondis, tirés des normes en percentiles du
     * Cooper Institute reprises par l'ACSM (Guidelines for Exercise Testing and Prescription) : « Moyen »
     * à partir du 20ᵉ percentile, « Bon » du 50ᵉ, « Excellent » du 80ᵉ.
     */
    private val MALE_THRESHOLDS = listOf(
        intArrayOf(40, 45, 52),
        intArrayOf(38, 43, 50),
        intArrayOf(36, 41, 47),
        intArrayOf(33, 37, 43),
        intArrayOf(29, 33, 39),
    )
    private val FEMALE_THRESHOLDS = listOf(
        intArrayOf(33, 38, 44),
        intArrayOf(31, 36, 42),
        intArrayOf(29, 34, 39),
        intArrayOf(26, 30, 35),
        intArrayOf(23, 27, 31),
    )

    /** Niveau d'une VO2max pour l'âge et le sexe, d'après les repères de [MALE_THRESHOLDS] et [FEMALE_THRESHOLDS]. */
    fun category(value: Int, age: Int, female: Boolean): Vo2MaxCategory {
        val band = ((age - 20) / 10).coerceIn(0, MALE_THRESHOLDS.lastIndex)
        val thresholds = (if (female) FEMALE_THRESHOLDS else MALE_THRESHOLDS)[band]
        return Vo2MaxCategory.entries[thresholds.count { value >= it }]
    }

    /** FC max d'après l'âge, formule de Tanaka (2001) : 208 − 0,7 × âge. */
    fun ageMaxHr(age: Int): Double = 208.0 - 0.7 * age

    /**
     * FC max retenue : la plus haute entre celle du profil (ou, à défaut, la formule d'âge) et la plus haute
     * FC réellement observée, puisqu'une FC observée ne peut pas dépasser la vraie FC max.
     */
    fun resolveHrMax(profileMaxHr: Int?, age: Int, observedMaxHr: Int?): Pair<Int, HrMaxSource> {
        val declared = profileMaxHr?.let { it to HrMaxSource.PROFILE }
            ?: (ageMaxHr(age).roundToInt() to HrMaxSource.AGE_FORMULA)
        return if (observedMaxHr != null && observedMaxHr > declared.first) {
            observedMaxHr to HrMaxSource.OBSERVED
        } else {
            declared
        }
    }

    fun restingEstimate(hrMax: Double, restingHr: Double, female: Boolean): Double =
        (if (female) UTH_FACTOR_FEMALE else UTH_FACTOR_MALE) * hrMax / restingHr

    /** VO2max d'après une course, ou `null` si la séance ne se prête pas au calcul. */
    fun runEstimate(session: TrainingSessionEntity, hrMax: Double, restingHr: Double): Double? {
        if (session.sportId != RUNNING_SPORT_ID) return null
        val duration = session.durationSec ?: return null
        val distance = session.distanceMeters ?: return null
        val avgHr = session.averageHr ?: return null
        if (duration < MIN_RUN_SEC || distance <= 0f || hrMax <= restingHr) return null

        val speed = distance / (duration / 60.0)
        if (speed !in MIN_SPEED_M_PER_MIN..MAX_SPEED_M_PER_MIN) return null
        val heartRateReserve = (avgHr - restingHr) / (hrMax - restingHr)
        if (heartRateReserve !in MIN_HRR..MAX_HRR) return null

        val vo2 = 0.2 * speed + 3.5
        return (vo2 - 3.5) / heartRateReserve + 3.5
    }

    /**
     * @param sessions séances de la fenêtre, de la plus ancienne à la plus récente.
     * @param restingHr FC de repos de référence (nocturne).
     * @return `null` sans FC de repos.
     */
    fun estimate(
        age: Int,
        female: Boolean,
        profileMaxHr: Int?,
        restingHr: Double?,
        sessions: List<TrainingSessionEntity>,
    ): Vo2MaxEstimate? {
        if (restingHr == null || restingHr <= 0) return null
        val observedMax = sessions.mapNotNull { it.maxHr }.maxOrNull()
        val (hrMax, source) = resolveHrMax(profileMaxHr, age, observedMax)

        val runs = sessions.mapNotNull { runEstimate(it, hrMax.toDouble(), restingHr) }.takeLast(MAX_RUNS)
        val running = if (runs.isEmpty()) null else median(runs).coerceIn(MIN_VO2MAX, MAX_VO2MAX)
        val resting = restingEstimate(hrMax.toDouble(), restingHr, female).coerceIn(MIN_VO2MAX, MAX_VO2MAX)

        val value = (running ?: resting).roundToInt()
        return Vo2MaxEstimate(
            value = value,
            method = if (running != null) Vo2Method.RUNNING else Vo2Method.RESTING_HR,
            runCount = runs.size,
            runningEstimate = running?.roundToInt(),
            restingEstimate = resting.roundToInt(),
            hrMax = hrMax,
            hrMaxSource = source,
            restingHr = max(1.0, restingHr).roundToInt(),
            category = category(value, age, female),
        )
    }
}
