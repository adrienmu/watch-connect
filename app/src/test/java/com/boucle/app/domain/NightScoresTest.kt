package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightScoresTest {

    @Test
    fun ansChargeMapsToANightQuality() {
        assertEquals(0.6, NightScores.ansQuality(0.0)!!, 1e-9)
        assertEquals(1.0, NightScores.ansQuality(5.0)!!, 1e-9)
        assertEquals(1.0, NightScores.ansQuality(10.0)!!, 1e-9)
        assertEquals(0.36, NightScores.ansQuality(-3.0)!!, 1e-9)
        assertEquals(0.0, NightScores.ansQuality(-10.0)!!, 1e-9)
        assertNull(NightScores.ansQuality(null))
        assertNull(NightScores.ansQuality(Double.NaN))
        assertNull(NightScores.ansQuality(42.0))
    }

    @Test
    fun ansChargeAloneDrivesQualityWithoutOtherScores() {
        assertEquals(0.84, NightScores(null, null, 3.0).quality!!, 1e-9)
        assertNull(NightScores(null, null, null).quality)
    }

    @Test
    fun scoreNightCarriesTheAnsChargeOfThatNight() {
        val recharge = NightlyRechargeEntity(
            date = "2026-11-16", ansStatus = 2.5f, recoveryIndicator = 4, recoveryIndicatorSubLevel = 1,
            ansRate = 60, meanRriMs = null, meanRmssdMs = null, meanRespirationIntervalMs = null,
            baselineRriMs = null, baselineRriSd = null, baselineRmssdMs = null, baselineRmssdSd = null,
            baselineRespirationIntervalMs = null, baselineRespirationIntervalSd = null,
            sleepTip = null, vitalityTip = null, exerciseTip = null,
        )
        val scores = scoreNight("2026-11-16", emptyList(), listOf(recharge))
        assertEquals(2.5, scores.ansCharge!!, 1e-6)
        assertEquals(4, scores.rechargeStatus)
        // Statut « OK » affiné par +2,5 d'ANS charge.
        assertEquals(0.625, scores.quality!!, 1e-6)
        assertNull(scoreNight("2026-11-17", emptyList(), listOf(recharge)).ansCharge)
    }

    @Test
    fun rechargeStatusDrivesTheQuality() {
        val q = (1..6).map { NightScores.polarQuality(it, null)!! }
        assertEquals(listOf(0.15, 0.3, 0.45, 0.6, 0.75, 0.9), q.map { Math.round(it * 100) / 100.0 })
        // Restitution du déficit : très mauvaise → minimum, OK → 80 %, bonne → plein.
        assertEquals(BodyBatteryModel.MIN_RESTORE, BodyBatteryModel.restoredShare(q[0]), 1e-9)
        assertEquals(0.8, BodyBatteryModel.restoredShare(q[3]), 1e-9)
        assertEquals(1.0, BodyBatteryModel.restoredShare(q[4]), 1e-9)
    }

    @Test
    fun ansChargeOnlyRefinesTheStatus() {
        assertEquals(0.65, NightScores.polarQuality(4, 5.0)!!, 1e-9)
        assertEquals(0.65, NightScores.polarQuality(4, 9.0)!!, 1e-9)
        assertEquals(0.55, NightScores.polarQuality(4, -8.0)!!, 1e-9)
        assertEquals(0.6, NightScores.polarQuality(4, Double.NaN)!!, 1e-9)
    }

    @Test
    fun invalidStatusFallsBackToAnsChargeThenToOurScores() {
        assertEquals(NightScores.ansQuality(2.0)!!, NightScores.polarQuality(0, 2.0)!!, 1e-9)
        assertNull(NightScores.polarQuality(null, null))
        assertNull(NightScores.polarQuality(7, null))
        // Sans rien de Polar : nos scores, ici aucun.
        assertNull(NightScores(null, null, null, null).quality)
        assertEquals(0.9, NightScores(null, null, null, 6).quality!!, 1e-9)
    }
}
