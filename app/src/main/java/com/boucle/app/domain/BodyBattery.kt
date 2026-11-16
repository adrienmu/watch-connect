package com.boucle.app.domain

import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Niveau d'énergie de 0 à 100, dans l'esprit du « Body Battery » de Garmin.
 *
 * L'algorithme de Garmin (Firstbeat) est propriétaire et aucune implémentation ouverte n'existe : ce
 * modèle est le nôtre. Il suit un réservoir d'énergie sur une grille de 5 minutes :
 *
 *  - **Sommeil** : recharge, répartie sur la nuit selon la profondeur du sommeil. Le total regagné est une
 *    fraction du déficit (100 − niveau au coucher), d'après la qualité de la nuit (sommeil et ANS charge du
 *    Nightly Recharge) : 80 % pour une nuit habituelle, qui ne remplit donc pas la batterie depuis un niveau
 *    bas ; 100 % dès une bonne nuit ; jusqu'à 35 % seulement pour une mauvaise.
 *  - **Éveil, repos** : consommation de base, légèrement compensée quand le corps est vraiment au calme
 *    (immobile et FC proche de la FC de repos).
 *  - **Éveil, activité** : consommation proportionnelle à l'effort au-dessus de 1,5 MET.
 *  - **Éveil, stress** : quand le créneau a des intervalles PPI exploitables, le stress de 0 à 100 calculé
 *    sur la VFC ([HrvStress]), comme Garmin : au-dessus de 25 il vide la batterie, en dessous (vrai calme)
 *    il la recharge un peu, même assis. Sinon, un proxy : la FC élevée alors qu'on est immobile.
 *
 * Les taux ci-dessous sont des valeurs de départ réglables, à ajuster sur de vraies journées.
 */
data class BatterySlot(
    /** 0 = éveillé ; sinon contribution du créneau à la recharge (profond > paradoxal > léger). */
    val sleepWeight: Double = 0.0,
    /** Qualité de la nuit entre 0 et 1, renseignée sur les créneaux de sommeil. */
    val nightQuality: Double? = null,
    /** Dépense énergétique moyenne du créneau, en MET. */
    val met: Double? = null,
    /** FC moyenne du créneau, en bpm. */
    val hr: Double? = null,
    /** Stress de 0 à 100 calculé sur la VFC du créneau ([HrvStress]), s'il a des PPI exploitables. */
    val stress: Double? = null,
) {
    val hasData: Boolean get() = sleepWeight > 0.0 || met != null || hr != null || stress != null
}

data class BatteryPoint(val time: LocalDateTime, val level: Double)

data class BodyBatteryDay(
    val points: List<BatteryPoint>,
    val current: Int,
    /** Niveau au réveil, si une nuit se termine dans la matinée. */
    val wake: Int?,
    val high: Int,
    val low: Int,
    val drained: Int,
    val charged: Int,
)

enum class BatteryBand { FULL, GOOD, MEDIUM, LOW }

val BodyBatteryDay.band: BatteryBand
    get() = when {
        current >= 76 -> BatteryBand.FULL
        current >= 51 -> BatteryBand.GOOD
        current >= 26 -> BatteryBand.MEDIUM
        else -> BatteryBand.LOW
    }

class BatterySimulation(
    val levels: DoubleArray,
    val drained: DoubleArray,
    val charged: DoubleArray,
    /** Plages d'indices des nuits détectées. */
    val nights: List<IntRange>,
)

object BodyBatteryModel {
    const val SLOT_MINUTES = 5
    const val SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES
    private const val SLOT_HOURS = SLOT_MINUTES / 60.0

    const val DEFAULT_START_LEVEL = 75.0
    /** Qualité supposée d'une nuit sans score. */
    const val DEFAULT_QUALITY = 0.6
    /** Part du déficit regagnée après la pire nuit possible. */
    const val MIN_RESTORE = 0.35
    /** Part du déficit regagnée par une nuit de qualité [DEFAULT_QUALITY] (ANS charge habituelle). */
    const val USUAL_RESTORE = 0.80
    /** Part supplémentaire regagnée par point de qualité : +0,1 de qualité ≈ +13,5 % du déficit. */
    const val RESTORE_PER_QUALITY = 1.35
    /** Un éveil de plus d'une heure sépare deux nuits. */
    const val MAX_WAKE_GAP_SLOTS = 60 / SLOT_MINUTES

    // Points d'énergie par heure d'éveil.
    const val BASE_DRAIN = 1.8
    const val ACTIVITY_DRAIN_PER_MET = 4.0
    const val STRESS_DRAIN = 6.0
    /** Recharge du repos diurne réel (allongé, sieste, relaxation) ; supérieure à la consommation de base. */
    const val REST_RECHARGE = 2.5

    private const val ACTIVITY_MET_THRESHOLD = 1.5
    private const val STRESS_MAX_MET = 2.0
    /** Écart de FC toléré au-dessus de la FC de repos avant d'y voir du stress (position debout, café…). */
    private const val STRESS_HR_MARGIN = 12.0
    private const val STRESS_HR_RANGE = 25.0
    // Être assis (≈ 1,2 MET) ne recharge pas : il faut être quasi immobile avec une FC au niveau du repos.
    private const val REST_MAX_MET = 1.15
    private const val REST_HR_MARGIN = 3.0
    private const val UNKNOWN_MET = 1.2
    /** Stress VFC sous lequel on est au repos (échelle de Garmin : 0–25) ; au-dessus, il vide la batterie. */
    private const val CALM_STRESS = 25.0
    /** Le drain de stress atteint [STRESS_DRAIN] à CALM_STRESS + STRESS_SPAN (stress élevé, 75). */
    private const val STRESS_SPAN = 50.0
    /** La recharge au calme atteint [REST_RECHARGE] à CALM_STRESS − CALM_SPAN (stress 10). */
    private const val CALM_SPAN = 15.0

    /**
     * Part du déficit regagnée par une nuit de [quality] (0 à 1) : 80 % pour une nuit habituelle (0,6),
     * 100 % dès 0,75 environ, 35 % au minimum. Pour une nuit jugée sur la seule ANS charge, cela donne
     * 37 % à −4, 80 % à 0 et 100 % à +4.
     */
    fun restoredShare(quality: Double): Double =
        (USUAL_RESTORE + RESTORE_PER_QUALITY * (quality - DEFAULT_QUALITY)).coerceIn(MIN_RESTORE, 1.0)

    fun simulate(
        slots: List<BatterySlot>,
        restingHr: Double,
        startLevel: Double = DEFAULT_START_LEVEL,
    ): BatterySimulation {
        val n = slots.size
        val levels = DoubleArray(n)
        val drained = DoubleArray(n)
        val charged = DoubleArray(n)
        val nights = mutableListOf<IntRange>()

        var level = startLevel.coerceIn(0.0, 100.0)
        var nightEnd = -1
        var chargePerWeight = 0.0

        for (i in 0 until n) {
            val slot = slots[i]

            if (i > nightEnd && slot.sleepWeight > 0.0) {
                // Début d'une nuit : on la délimite, puis on fixe ce qu'elle va regagner au total.
                var last = i
                var k = i
                while (k < n && k - last <= MAX_WAKE_GAP_SLOTS) {
                    if (slots[k].sleepWeight > 0.0) last = k
                    k++
                }
                val night = i..last
                val totalWeight = night.sumOf { slots[it].sleepWeight }
                val quality = night.mapNotNull { slots[it].nightQuality }
                    .let { if (it.isEmpty()) DEFAULT_QUALITY else it.average() }
                    .coerceIn(0.0, 1.0)
                chargePerWeight = (100.0 - level) * restoredShare(quality) / totalWeight
                nightEnd = last
                nights += night
            }

            when {
                i <= nightEnd -> {
                    val gain = chargePerWeight * slot.sleepWeight
                    charged[i] = gain
                    level += gain
                }
                !slot.hasData -> Unit // bracelet non porté ou pas de donnée : le niveau est figé
                else -> {
                    val met = slot.met ?: UNKNOWN_MET
                    val activity = ACTIVITY_DRAIN_PER_MET * max(0.0, met - ACTIVITY_MET_THRESHOLD)
                    var stress = 0.0
                    var rest = 0.0
                    val hrvStress = slot.stress
                    if (hrvStress != null && met < STRESS_MAX_MET) {
                        stress = STRESS_DRAIN * ((hrvStress - CALM_STRESS) / STRESS_SPAN).coerceIn(0.0, 1.0)
                        rest = REST_RECHARGE * ((CALM_STRESS - hrvStress) / CALM_SPAN).coerceIn(0.0, 1.0)
                    } else slot.hr?.let { hr ->
                        if (met < STRESS_MAX_MET) {
                            stress = STRESS_DRAIN * ((hr - restingHr - STRESS_HR_MARGIN) / STRESS_HR_RANGE).coerceIn(0.0, 1.0)
                        }
                        if (met < REST_MAX_MET && hr <= restingHr + REST_HR_MARGIN) rest = REST_RECHARGE
                    }
                    val cost = (BASE_DRAIN + activity + stress) * SLOT_HOURS
                    val gain = rest * SLOT_HOURS
                    // On ne descend pas sous 0 ni ne dépasse 100 ; ce qui est réellement perdu ou gagné est compté.
                    val after = (level - cost + gain).coerceIn(0.0, 100.0)
                    if (after < level) drained[i] = level - after else charged[i] = after - level
                    level = after
                }
            }
            levels[i] = level
        }
        return BatterySimulation(levels, drained, charged, nights)
    }

    /**
     * Résume la journée occupant les créneaux [dayFrom, dayTo) de la simulation ; `null` s'il n'y a
     * aucune donnée ce jour-là.
     */
    fun summarize(
        simulation: BatterySimulation,
        slots: List<BatterySlot>,
        start: LocalDateTime,
        dayFrom: Int,
        dayTo: Int,
    ): BodyBatteryDay? {
        val lastWithData = (dayTo - 1 downTo dayFrom).firstOrNull { slots[it].hasData } ?: return null
        val range = dayFrom..lastWithData
        val points = range.map { BatteryPoint(start.plusMinutes(it.toLong() * SLOT_MINUTES), simulation.levels[it]) }

        val morningEnd = dayFrom + 14 * 60 / SLOT_MINUTES
        val wake = simulation.nights.lastOrNull { it.last in dayFrom until morningEnd }
            ?.let { simulation.levels[it.last].roundToInt() }

        return BodyBatteryDay(
            points = points,
            current = simulation.levels[lastWithData].roundToInt(),
            wake = wake,
            high = points.maxOf { it.level }.roundToInt(),
            low = points.minOf { it.level }.roundToInt(),
            drained = range.sumOf { simulation.drained[it] }.roundToInt(),
            charged = range.sumOf { simulation.charged[it] }.roundToInt(),
        )
    }
}
