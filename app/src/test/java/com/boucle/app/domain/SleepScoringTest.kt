package com.boucle.app.domain

import com.boucle.app.data.db.SleepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScoringTest {

    private fun night(
        sleepH: Double = 8.0,
        deepShare: Double = 0.16,
        remShare: Double = 0.21,
        wasoMin: Long = 10,
        awakenings: Int = 0,
        bedtime: Int? = 11 * 60, // 23:00
    ): SleepNight {
        val sleepSec = (sleepH * 3600).toLong()
        return SleepNight(bedtime, sleepSec, (sleepSec * deepShare).toLong(), (sleepSec * remShare).toLong(), wasoMin * 60, awakenings)
    }

    @Test
    fun durationIsPerfectBetweenSevenAndNineHours() {
        assertEquals(100.0, SleepScoring.durationScore(7.0), 0.001)
        assertEquals(100.0, SleepScoring.durationScore(8.0), 0.001)
        assertEquals(100.0, SleepScoring.durationScore(9.0), 0.001)
    }

    @Test
    fun durationDropsSteeplyBelowSevenHours() {
        val five = SleepScoring.durationScore(5.0)
        assertEquals(50.6, five, 0.5)
        assertTrue(SleepScoring.durationScore(6.0) in 85.0..95.0)
        assertTrue(SleepScoring.durationScore(3.0) < 5.0)
    }

    @Test
    fun oversleepingIsOnlyPenalisedGently() {
        val ten = SleepScoring.durationScore(10.0)
        assertTrue("ten=$ten", ten in 70.0..80.0)
        assertTrue(SleepScoring.durationScore(14.0) >= 50.0)
    }

    @Test
    fun stagesReachFullScoreAtTargetShares() {
        assertEquals(100.0, SleepScoring.stagesScore(night()), 0.5)
        assertEquals(50.0, SleepScoring.stagesScore(night(deepShare = 0.0)), 0.5)
        assertEquals(100.0, SleepScoring.stagesScore(night(deepShare = 0.30, remShare = 0.30)), 0.001)
    }

    @Test
    fun consistencyNeedsEnoughHistory() {
        assertNull(SleepScoring.consistencyScore(660, listOf(660, 660)))
        assertNotNull(SleepScoring.consistencyScore(660, listOf(660, 660, 660)))
    }

    @Test
    fun consistencyToleratesFifteenMinutesBothWays() {
        val history = List(7) { 660 }
        assertEquals(100.0, SleepScoring.consistencyScore(675, history)!!, 0.001)
        assertEquals(100.0, SleepScoring.consistencyScore(645, history)!!, 0.001)
    }

    @Test
    fun lateBedtimeCostsMoreThanEarly() {
        val history = List(7) { 660 }
        // 60 minutes de retard : (60 - 15) / 105 ≈ 43 points perdus.
        assertEquals(57.1, SleepScoring.consistencyScore(720, history)!!, 0.2)
        // 60 minutes d'avance : même écart mais pénalité plafonnée à 20.
        assertEquals(80.0, SleepScoring.consistencyScore(600, history)!!, 0.001)
        // Très en retard : plancher à 0.
        assertEquals(0.0, SleepScoring.consistencyScore(900, history)!!, 0.001)
    }

    @Test
    fun interruptionsGraceThenLinearThenZero() {
        assertEquals(100.0, SleepScoring.interruptionsScore(20 * 60, 0), 0.001)
        assertEquals(20.0 + 40.0, SleepScoring.interruptionsScore(55 * 60, 1), 0.001)
        assertEquals(20.0, SleepScoring.interruptionsScore(90 * 60, 1), 0.001)
        assertEquals(0.0, SleepScoring.interruptionsScore(120 * 60, 5), 0.001)
        assertEquals(95.0, SleepScoring.interruptionsScore(10 * 60, 2), 0.001)
    }

    @Test
    fun goodNightScoresHighAndIsRenormalisedWithoutHistory() {
        val good = night()
        val history = List(7) { night() }
        assertEquals(100, SleepScoring.score(good, history).total)

        val withoutHistory = SleepScoring.score(good, emptyList())
        assertNull(withoutHistory.consistency)
        assertEquals(100, withoutHistory.total)
    }

    @Test
    fun shortFragmentedNightScoresLow() {
        val bad = night(sleepH = 5.0, deepShare = 0.05, remShare = 0.10, wasoMin = 80, awakenings = 4, bedtime = 14 * 60)
        val score = SleepScoring.score(bad, List(7) { night() })
        assertTrue("score=${score.total}", score.total < 40)
    }

    @Test
    fun entityIsReducedToNightIgnoringLatencyAndFinalWake() {
        // Au lit 8 h (28800 s) : éveil initial 10 min, sommeil, éveil de 8 min au milieu, éveil final 5 min.
        val entity = SleepEntity(
            date = "2026-11-16",
            startTime = "2026-11-15T23:00+01:00[Europe/Paris]",
            endTime = "2026-11-16T07:00+01:00[Europe/Paris]",
            goalMinutes = 480,
            phases = "0:WAKE,600:NONREM12,3600:NONREM3,7200:WAKE,7680:REM,28500:WAKE",
            cycles = "",
            userRating = null,
            batteryRanOut = false,
            skinTemperatureC = null,
            skinTemperatureDeviationC = null,
        )
        val night = entity.toNight()!!
        assertEquals(11 * 60, night.bedtimeMinutesAfterNoon)
        assertEquals(3600L, night.deepSec)
        assertEquals(480L, night.wasoSec)
        assertEquals(1, night.awakenings)
        // Dormi : 28800 - 600 (latence) - 480 (éveil) - 300 (éveil final).
        assertEquals(28800L - 600 - 480 - 300, night.sleepSec)
    }

    @Test
    fun unusablePhasesGiveNoNight() {
        val entity = SleepEntity("2026-11-16", null, null, null, "", "", null, null, null, null)
        assertNull(entity.toNight())
    }
}
