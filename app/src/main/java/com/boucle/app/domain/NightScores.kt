package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.SleepEntity

/**
 * @param ansCharge ANS charge du Nightly Recharge Polar, de −10 à +10 (0 = ton niveau habituel).
 * @param rechargeStatus statut Nightly Recharge Polar, de 1 (très mauvais) à 6 (très bon).
 */
data class NightScores(
    val sleep: SleepScore?,
    val recovery: RecoveryScore?,
    val ansCharge: Double? = null,
    val rechargeStatus: Int? = null,
) {
    /**
     * Qualité de la nuit entre 0 et 1, pour la recharge du Body Battery ; `null` sans score.
     *
     * Elle vient du Nightly Recharge de Polar, qui combine déjà sommeil et récupération du système nerveux
     * par rapport à tes 28 dernières nuits : le statut 1–6, affiné par l'ANS charge, à défaut l'ANS charge
     * seule. Nos scores de sommeil et de récupération ne servent qu'en secours, quand le bracelet n'a rien
     * fourni (premières nuits, synchronisation incomplète).
     */
    val quality: Double?
        get() = polarQuality(rechargeStatus, ansCharge) ?: when {
            sleep != null && recovery != null -> (0.6 * sleep.total + 0.4 * recovery.total) / 100.0
            sleep != null -> sleep.total / 100.0
            recovery != null -> recovery.total / 100.0
            else -> null
        }

    companion object {
        /** Qualité d'une nuit au statut « OK » (4), égale à celle d'une nuit sans score : 80 % du déficit regagné. */
        const val STATUS_USUAL_QUALITY = 0.6
        /**
         * Écart de qualité entre deux statuts : « bon » (5) remplit la batterie, « compromis » (3) regagne
         * 60 % du déficit, « mauvais » (2) 40 %, « très mauvais » (1) le minimum.
         */
        const val STATUS_QUALITY_STEP = 0.15
        /** Affinage par l'ANS charge, déjà comprise dans le statut : ±0,05 au plus (±5 points). */
        const val STATUS_ANS_REFINE_PER_POINT = 0.01

        /**
         * Qualité tirée du Nightly Recharge : le statut s'il est valide, affiné par l'ANS charge ; sinon
         * l'ANS charge seule ; `null` si le bracelet n'a fourni ni l'un ni l'autre.
         */
        fun polarQuality(rechargeStatus: Int?, ansCharge: Double?): Double? {
            val ans = ansCharge?.takeIf { it.isFinite() && it in -10.0..10.0 }
            val status = rechargeStatus?.takeIf { it in 1..6 } ?: return ansQuality(ans)
            val refine = STATUS_ANS_REFINE_PER_POINT * (ans ?: 0.0).coerceIn(-5.0, 5.0)
            return (STATUS_USUAL_QUALITY + STATUS_QUALITY_STEP * (status - 4) + refine).coerceIn(0.0, 1.0)
        }

        /** Qualité attribuée à une ANS charge habituelle (0), égale à celle d'une nuit sans score. */
        const val ANS_USUAL_QUALITY = 0.6
        /** Une ANS charge de +5 donne une qualité de 1, une de −7,5 une qualité de 0. */
        const val ANS_QUALITY_PER_POINT = 0.08

        /** Projette l'ANS charge (−10 à +10) sur une qualité de 0 à 1 ; `null` si absente ou invalide. */
        fun ansQuality(ansCharge: Double?): Double? = ansCharge
            ?.takeIf { it.isFinite() && it in -10.0..10.0 }
            ?.let { (ANS_USUAL_QUALITY + ANS_QUALITY_PER_POINT * it).coerceIn(0.0, 1.0) }
    }
}

/**
 * Scores de la nuit qui se termine le matin de [wakeDate], chacun comparé aux nuits précédentes
 * présentes dans [sleeps] et [recharges] (listes triées par date, pouvant contenir des nuits ultérieures).
 */
fun scoreNight(
    wakeDate: String,
    sleeps: List<SleepEntity>,
    recharges: List<NightlyRechargeEntity>,
): NightScores {
    val sleepEntity = sleeps.firstOrNull { it.date == wakeDate }
    val sleepScore = sleepEntity?.toNight()?.let { night ->
        SleepScoring.score(night, sleeps.filter { it.date < wakeDate }.mapNotNull { it.toNight() })
    }
    val recharge = recharges.firstOrNull { it.date == wakeDate }
    val recovery = recharge?.let {
        RecoveryScoring.score(
            night = recharge,
            history = recharges.filter { it.date < wakeDate },
            sleepScore = sleepScore?.total,
            skinTemperatureDeviationC = sleepEntity?.skinTemperatureDeviationC,
        )
    }
    return NightScores(sleepScore, recovery, recharge?.ansStatus?.toDouble(), recharge?.recoveryIndicator)
}
