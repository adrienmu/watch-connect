package com.boucle.app.healthconnect

import com.boucle.app.domain.BodyBatteryInputs
import com.boucle.app.domain.Vo2MaxEstimation
import com.boucle.app.domain.toNight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class HealthConnectMappingTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 11, 16)

    private fun at(hour: Int, minute: Int = 0, date: LocalDate = day): Instant =
        date.atTime(hour, minute).atZone(zone).toInstant()

    @Test
    fun heartRateIsAveragedPer5MinutesInHourlyBlocks() {
        val samples = listOf(
            HcHeartRate(at(10, 1), 60.0),
            HcHeartRate(at(10, 3), 70.0),
            HcHeartRate(at(10, 12), 80.0),
            HcHeartRate(at(11, 55), 90.0),
            HcHeartRate(at(11, 56), 0.0),
        )
        val blocks = HealthConnectMapping.hrBlocks(samples, zone)
        assertEquals(listOf("10:00", "11:00"), blocks.map { it.startTime })
        val ten = blocks[0].samples.split(',')
        assertEquals(12, ten.size)
        assertEquals(listOf("65", "0", "80"), ten.take(3))
        assertEquals("90", blocks[1].samples.split(',').last())
        assertEquals(HealthConnectMapping.HR_TRIGGER, blocks[0].trigger)
        // Le Body Battery lit ces blocs à raison d'une valeur par 5 minutes.
        val grid = BodyBatteryInputs.build(day, day.atTime(23, 0), emptyList(), blocks, emptyList(), emptyMap())
        assertEquals(65.0, grid.slots[grid.dayFrom + 10 * 12].hr!!, 1e-9)
        assertEquals(80.0, grid.slots[grid.dayFrom + 10 * 12 + 2].hr!!, 1e-9)
    }

    @Test
    fun stepsAreSpreadPerMinuteWithCadenceMet() {
        // 1 000 pas sur 10 minutes de 8 h à 8 h 10 (100 pas/min), 1 600 sur 10 min de course à 18 h.
        val steps = listOf(
            HcSteps(at(8), at(8, 10), 1_000),
            HcSteps(at(18), at(18, 10), 1_600),
        )
        val day0 = HealthConnectMapping.activitySamples(steps, zone).single()
        assertEquals(day.toString(), day0.date)
        assertEquals(LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).toString(), day0.startTime)
        val perMinute = day0.stepSamples.split(',').map { it.toInt() }
        val met = day0.metSamples.split(',').map { it.toDouble() }
        assertEquals(1_440, perMinute.size)
        assertEquals(2_600, perMinute.sum())
        assertEquals(100, perMinute[8 * 60 + 5])
        assertEquals(3.7, met[8 * 60 + 5], 0.01)
        assertEquals(9.7, met[18 * 60], 0.01)
        assertEquals(1.2, met[3 * 60], 0.01)
    }

    @Test
    fun metFromCadenceIsContinuous() {
        assertEquals(1.2, HealthConnectMapping.metFromCadence(0.0), 1e-9)
        assertEquals(1.2 + 0.025 * 130, HealthConnectMapping.metFromCadence(130.0), 1e-9)
        assertEquals(0.07 * 140 - 1.5, HealthConnectMapping.metFromCadence(140.0), 1e-9)
        assertTrue(HealthConnectMapping.metFromCadence(135.0) in 4.45..8.3)
    }

    @Test
    fun sleepSessionBecomesAScorableNight() {
        val session = HcSleepSession(
            start = at(23, 0, day.minusDays(1)),
            end = at(7),
            zone = null,
            stages = listOf(
                HcSleepStage(at(23, 15, day.minusDays(1)), at(1), "NONREM12"),
                HcSleepStage(at(1), at(2, 30), "NONREM3"),
                // Trou de 2 h 30 à 2 h 40 : compté comme éveil.
                HcSleepStage(at(2, 40), at(5), "NONREM12"),
                HcSleepStage(at(5), at(6, 30), "REM"),
                HcSleepStage(at(6, 30), at(7), "WAKE"),
            ),
        )
        val nap = HcSleepSession(at(14), at(14, 30), null, emptyList())
        val sleeps = HealthConnectMapping.sleeps(listOf(session, nap), zone)
        val night = sleeps.single()
        assertEquals(day.toString(), night.date)
        assertEquals("0:WAKE,900:NONREM12,7200:NONREM3,12600:WAKE,13200:NONREM12,21600:REM,27000:WAKE", night.phases)
        val scored = night.toNight()!!
        assertEquals(90 * 60L, scored.deepSec)
        assertEquals(90 * 60L, scored.remSec)
        assertEquals(10 * 60L, scored.wasoSec)
        assertEquals(1, scored.awakenings)
    }

    @Test
    fun nightWithoutStagesCountsAsLightSleep() {
        val night = HealthConnectMapping.sleeps(listOf(HcSleepSession(at(0), at(7), zone, emptyList())), zone).single()
        assertEquals("0:NONREM12", night.phases)
        assertEquals(7 * 3600L, night.toNight()!!.sleepSec)
    }

    @Test
    fun nightlyMeasuresAverageDuringSleepOnly() {
        val sleeps = HealthConnectMapping.sleeps(listOf(HcSleepSession(at(0), at(7), zone, emptyList())), zone)
        val heart = listOf(50.0, 52.0, 54.0).mapIndexed { i, bpm -> HcHeartRate(at(2 + i), bpm) } + HcHeartRate(at(12), 90.0)
        val hrv = listOf(HcValue(at(3), 40.0), HcValue(at(4), 60.0), HcValue(at(15), 10.0))
        val breath = listOf(HcValue(at(3), 15.0))
        val night = HealthConnectMapping.nightlyMeasures(sleeps, heart, hrv, breath).single()
        assertEquals(day.toString(), night.date)
        assertEquals(1_154, night.meanRriMs) // 60 000 / 52 bpm, arrondi
        assertEquals(50, night.meanRmssdMs)
        assertEquals(4_000, night.meanRespirationIntervalMs)
        assertNull(night.ansStatus)
        assertNull(night.recoveryIndicator)
        // Sans FC ni VFC, pas de mesures nocturnes.
        assertTrue(HealthConnectMapping.nightlyMeasures(sleeps, emptyList(), emptyList(), breath).isEmpty())
    }

    @Test
    fun runsAreUsableForVo2Max() {
        val run = HcExercise("abc", at(18), at(18, 40), null, running = true, name = "Course à pied", distanceMeters = 7_000.0, calories = 450.0)
        val heart = listOf(HcHeartRate(at(18, 10), 150.0), HcHeartRate(at(18, 20), 170.0), HcHeartRate(at(19, 30), 80.0))
        val session = HealthConnectMapping.trainingSessions(listOf(run), heart, zone).single()
        assertEquals("healthconnect:abc", session.path)
        assertEquals(day.toString(), session.date)
        assertEquals("2026-11-16T18:00", session.startTime)
        assertEquals(2_400, session.durationSec)
        assertEquals(Vo2MaxEstimation.RUNNING_SPORT_ID, session.sportId)
        assertEquals(160, session.averageHr)
        assertEquals(170, session.maxHr)
        assertEquals(7_000f, session.distanceMeters!!, 0.1f)
    }

    @Test
    fun dailyTotalsSkipEmptyDays() {
        val daily = HealthConnectMapping.dailyActivity(
            listOf(HcDayTotals(day, 8_000, 6_000.0, 320.4), HcDayTotals(day.plusDays(1), null, null, null)),
        )
        val today = daily.single()
        assertEquals(8_000, today.steps)
        assertEquals(320, today.activityCalories)
        assertNotNull(today.distanceMeters)
    }
}
