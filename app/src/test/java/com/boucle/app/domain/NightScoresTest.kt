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
        assertEquals(0.8, scores.quality!!, 1e-6)
        assertNull(scoreNight("2026-11-17", emptyList(), listOf(recharge)).ansCharge)
    }
}
