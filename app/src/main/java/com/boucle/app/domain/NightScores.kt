package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.SleepEntity

data class NightScores(val sleep: SleepScore?, val recovery: RecoveryScore?) {
    /** Qualité de la nuit entre 0 et 1, pour la recharge du Body Battery ; `null` sans score. */
    val quality: Double?
        get() = when {
            sleep != null && recovery != null -> (0.6 * sleep.total + 0.4 * recovery.total) / 100.0
            sleep != null -> sleep.total / 100.0
            recovery != null -> recovery.total / 100.0
            else -> null
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
    val recovery = recharges.firstOrNull { it.date == wakeDate }?.let { recharge ->
        RecoveryScoring.score(
            night = recharge,
            history = recharges.filter { it.date < wakeDate },
            sleepScore = sleepScore?.total,
            skinTemperatureDeviationC = sleepEntity?.skinTemperatureDeviationC,
        )
    }
    return NightScores(sleepScore, recovery)
}
