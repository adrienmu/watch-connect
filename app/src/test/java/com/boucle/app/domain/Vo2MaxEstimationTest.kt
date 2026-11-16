package com.boucle.app.domain

import com.boucle.app.data.db.TrainingSessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Vo2MaxEstimationTest {

    private fun session(
        sportId: Long? = 1L,
        minutes: Int = 40,
        km: Float = 4.0f,
        avgHr: Int? = 150,
        maxHr: Int? = 170,
        date: String = "2026-11-10",
    ) = TrainingSessionEntity(
        path = "/U/0/$date/$minutes/$km/$avgHr",
        date = date,
        startTime = null,
        durationSec = minutes * 60,
        sportId = sportId,
        name = null,
        calories = null,
        distanceMeters = km * 1000f,
        averageHr = avgHr,
        maxHr = maxHr,
        cardioLoad = null,
        muscleLoad = null,
        hrZoneSec = "",
        exerciseCount = 1,
        summaryProto = null,
    )

    @Test
    fun uthFormulaMatchesThePaper() {
        // 15,3 × 190 / 60 = 48,45 chez l'homme ; 14,5 × 190 / 60 = 45,9 chez la femme.
        assertEquals(48.45, Vo2MaxEstimation.restingEstimate(190.0, 60.0, female = false), 0.01)
        assertEquals(45.92, Vo2MaxEstimation.restingEstimate(190.0, 60.0, female = true), 0.01)
    }

    @Test
    fun tanakaAgeFormula() {
        assertEquals(187.0, Vo2MaxEstimation.ageMaxHr(30), 0.001)
    }

    @Test
    fun hrMaxPrefersProfileThenAgeButNeverBelowWhatWasObserved() {
        assertEquals(195 to HrMaxSource.PROFILE, Vo2MaxEstimation.resolveHrMax(195, 30, 180))
        assertEquals(187 to HrMaxSource.AGE_FORMULA, Vo2MaxEstimation.resolveHrMax(null, 30, 180))
        assertEquals(192 to HrMaxSource.OBSERVED, Vo2MaxEstimation.resolveHrMax(null, 30, 192))
    }

    @Test
    fun runEstimateFollowsAcsmAndHeartRateReserve() {
        // 10 km/h = 166,7 m/min → VO2 = 36,8 ; %FCR = (150 − 50) / (190 − 50) = 0,714.
        val estimate = Vo2MaxEstimation.runEstimate(session(minutes = 60, km = 10f, avgHr = 150), 190.0, 50.0)!!
        val expected = (0.2 * (10_000.0 / 60.0)) / (100.0 / 140.0) + 3.5
        assertEquals(expected, estimate, 0.01)
        assertTrue(estimate in 45.0..55.0)
    }

    @Test
    fun unsuitableSessionsAreIgnored() {
        assertNull(Vo2MaxEstimation.runEstimate(session(sportId = 2L), 190.0, 50.0)) // vélo
        assertNull(Vo2MaxEstimation.runEstimate(session(minutes = 10, km = 1.5f), 190.0, 50.0)) // trop court
        assertNull(Vo2MaxEstimation.runEstimate(session(km = 0f), 190.0, 50.0)) // sans distance
        assertNull(Vo2MaxEstimation.runEstimate(session(avgHr = 100), 190.0, 50.0)) // trop facile
        assertNull(Vo2MaxEstimation.runEstimate(session(avgHr = 188), 190.0, 50.0)) // quasi maximal
        assertNull(Vo2MaxEstimation.runEstimate(session(avgHr = null), 190.0, 50.0))
    }

    @Test
    fun runningMethodWinsWhenRunsAreAvailable() {
        val runs = listOf(session(), session(km = 4.4f), session(km = 3.8f))
        val estimate = Vo2MaxEstimation.estimate(age = 36, female = false, profileMaxHr = null, restingHr = 54.0, sessions = runs)!!
        assertEquals(Vo2Method.RUNNING, estimate.method)
        assertEquals(3, estimate.runCount)
        assertEquals(estimate.runningEstimate, estimate.value)
        assertTrue(estimate.restingEstimate != null)
    }

    @Test
    fun fallsBackToRestingHeartRateWithoutRuns() {
        val estimate = Vo2MaxEstimation.estimate(30, female = false, profileMaxHr = null, restingHr = 60.0, sessions = listOf(session(sportId = 2L, maxHr = 160)))!!
        assertEquals(Vo2Method.RESTING_HR, estimate.method)
        assertEquals(0, estimate.runCount)
        // 15,3 × 187 / 60 = 47,7.
        assertEquals(48, estimate.value)
        assertEquals(HrMaxSource.AGE_FORMULA, estimate.hrMaxSource)
    }

    @Test
    fun onlyTheLatestRunsAreUsed() {
        val old = List(5) { session(km = 3.0f) }
        val recent = List(5) { session(km = 5.0f) }
        val estimate = Vo2MaxEstimation.estimate(36, false, 190, 54.0, old + recent)!!
        val recentOnly = Vo2MaxEstimation.estimate(36, false, 190, 54.0, recent)!!
        assertEquals(recentOnly.value, estimate.value)
    }

    @Test
    fun noEstimateWithoutRestingHeartRate() {
        assertNull(Vo2MaxEstimation.estimate(30, false, null, null, listOf(session())))
    }
}
