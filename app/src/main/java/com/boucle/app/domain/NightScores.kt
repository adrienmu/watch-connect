package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.SleepEntity

/**
 * @param ansCharge ANS charge du Nightly Recharge Polar, de −10 à +10 (0 = ton niveau habituel).
 */
data class NightScores(
    val sleep: SleepScore?,
    val recovery: RecoveryScore?,
    val ansCharge: Double? = null,
) {
    /**
     * Qualité de la nuit entre 0 et 1, pour la recharge du Body Battery ; `null` sans score.
     *
     * Comme le Nightly Recharge de Polar, elle combine à parts égales le sommeil et l'ANS charge
     * (récupération du système nerveux autonome mesurée par le bracelet). Sans ANS charge, notre score de
     * récupération prend sa place.
     */
    val quality: Double?
        get() {
            val ans = ansQuality(ansCharge)
            return when {
                sleep != null && ans != null -> 0.5 * sleep.total / 100.0 + 0.5 * ans
                sleep != null && recovery != null -> (0.6 * sleep.total + 0.4 * recovery.total) / 100.0
                sleep != null -> sleep.total / 100.0
                ans != null -> ans
                recovery != null -> recovery.total / 100.0
                else -> null
            }
        }

    companion object {
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
    return NightScores(sleepScore, recovery, recharge?.ansStatus?.toDouble())
}
