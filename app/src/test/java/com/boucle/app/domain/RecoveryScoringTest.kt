package com.boucle.app.domain

import com.boucle.app.data.db.NightlyRechargeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryScoringTest {

    private fun night(
        rmssd: Int? = 50,
        rri: Int? = 1_100,
        resp: Int? = 4_200,
        polarBaseline: Boolean = true,
    ) = NightlyRechargeEntity(
        date = "2026-11-16",
        ansStatus = null,
        recoveryIndicator = null,
        recoveryIndicatorSubLevel = null,
        ansRate = null,
        meanRriMs = rri,
        meanRmssdMs = rmssd,
        meanRespirationIntervalMs = resp,
        baselineRriMs = if (polarBaseline) 1_100 else null,
        baselineRriSd = if (polarBaseline) 55 else null,
        baselineRmssdMs = if (polarBaseline) 50 else null,
        baselineRmssdSd = if (polarBaseline) 10 else null,
        baselineRespirationIntervalMs = if (polarBaseline) 4_200 else null,
        baselineRespirationIntervalSd = if (polarBaseline) 250 else null,
        sleepTip = null,
        vitalityTip = null,
        exerciseTip = null,
    )

    @Test
    fun nightExactlyOnBaselineScoresSeventy() {
        val score = RecoveryScoring.score(night(), emptyList(), sleepScore = null, skinTemperatureDeviationC = null)!!
        assertEquals(70, score.hrv)
        assertEquals(70, score.heartRate)
        assertEquals(100, score.respiration)
        assertFalse(score.personalBaseline)
        // 0,50 × 70 + 0,25 × 70 + 0,10 × 100 renormalisé sur 0,85.
        assertEquals(Math.round((0.5 * 70 + 0.25 * 70 + 0.1 * 100) / 0.85).toInt(), score.total)
    }

    @Test
    fun higherHrvAndLowerHeartRateImproveTheScore() {
        val base = RecoveryScoring.score(night(), emptyList(), 80, null)!!
        val better = RecoveryScoring.score(night(rmssd = 65, rri = 1_200), emptyList(), 80, null)!!
        assertTrue(better.total > base.total)
        assertEquals(100, better.hrv)
    }

    @Test
    fun suppressedHrvAndRaisedHeartRateLowerTheScore() {
        val tired = RecoveryScoring.score(night(rmssd = 30, rri = 950, resp = 3_700), emptyList(), 50, null)!!
        assertTrue("total=${tired.total}", tired.total < 40)
        assertEquals(20, tired.hrv)
        assertEquals(RecoveryBand.LOW, tired.band)
    }

    @Test
    fun personalBaselineReplacesTheBraceletsOnceEnoughNights() {
        val history = (1..10).map { night(rmssd = 80, rri = 1_000, polarBaseline = false) }
        val score = RecoveryScoring.score(night(rmssd = 50, rri = 1_100, polarBaseline = false), history, null, null)!!
        assertTrue(score.personalBaseline)
        assertEquals(80, score.rmssdBaselineMs)
        // Écart-type plancher (10 % de 80 = 8 ms) : (50 - 80) / 8 = -3,75 → score nul.
        assertEquals(0, score.hrv)
    }

    @Test
    fun feverishSkinTemperatureSubtractsPoints() {
        val normal = RecoveryScoring.score(night(), emptyList(), 80, 0.2f)!!
        val feverish = RecoveryScoring.score(night(), emptyList(), 80, 1.5f)!!
        assertEquals(0, normal.temperaturePenalty)
        assertEquals(15, feverish.temperaturePenalty)
        assertEquals(normal.total - 15, feverish.total)
    }

    @Test
    fun noScoreWithoutHrvNorHeartRate() {
        assertNull(RecoveryScoring.score(night(rmssd = null, rri = null), emptyList(), 80, null))
        assertNotNull(RecoveryScoring.score(night(rmssd = null), emptyList(), 80, null))
    }
}
