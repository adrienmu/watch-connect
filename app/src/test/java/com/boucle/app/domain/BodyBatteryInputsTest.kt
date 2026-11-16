package com.boucle.app.domain

import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.SleepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BodyBatteryInputsTest {

    private val date = LocalDate.of(2026, 11, 16)
    private val perDay = BodyBatteryModel.SLOTS_PER_DAY

    /** Index du créneau de 5 min commençant à [hour]:[minute] le jour affiché. */
    private fun slot(hour: Int, minute: Int = 0) = 2 * perDay + (hour * 60 + minute) / 5

    @Test
    fun metHeartRateAndSleepLandInTheRightSlots() {
        val activity = ActivitySamplesEntity(
            date = date.toString(),
            startTime = "${date}T10:00",
            stepIntervalSec = 60,
            stepSamples = "",
            metIntervalSec = 30,
            // 10 valeurs = un créneau de 5 min à 10:00, puis une valeur à 10:05.
            metSamples = (List(10) { "2.0" } + "4.0").joinToString(","),
            activityInfo = "",
        )
        val heartRate = HrBlockEntity(date.toString(), "10:00", "TRIGGER_TYPE_LOW_ACTIVITY", "70,90,0")
        val sleep = SleepEntity(
            date = date.toString(),
            startTime = "2026-11-15T23:00+01:00[Europe/Paris]",
            endTime = "2026-11-16T07:00+01:00[Europe/Paris]",
            goalMinutes = 480,
            phases = "0:WAKE,600:NONREM3,28800:WAKE",
            cycles = "",
            userRating = null,
            batteryRanOut = false,
            skinTemperatureC = null,
            skinTemperatureDeviationC = null,
        )

        val grid = BodyBatteryInputs.build(
            date = date,
            now = LocalDateTime.of(2026, 11, 17, 0, 0),
            activity = listOf(activity),
            heartRate = listOf(heartRate),
            sleeps = listOf(sleep),
            qualityByWakeDate = mapOf(date.toString() to 0.8),
        )

        assertEquals(4 * perDay, grid.slots.size)
        assertEquals(slot(0), grid.dayFrom)
        assertEquals(slot(0) + perDay, grid.dayTo)
        assertEquals(2.0, grid.slots[slot(10)].met!!, 0.001)
        assertEquals(4.0, grid.slots[slot(10, 5)].met!!, 0.001)
        // FC : 5 min par mesure au repos, les zéros sont ignorés.
        assertEquals(70.0, grid.slots[slot(10)].hr!!, 0.001)
        assertEquals(90.0, grid.slots[slot(10, 5)].hr!!, 0.001)
        assertNull(grid.slots[slot(10, 10)].hr)

        // Les 10 premières minutes au lit sont éveillées, puis sommeil profond (poids 1) jusqu'au réveil.
        val bedtime = slot(0) - 12 // 23:00 la veille
        assertEquals(0.0, grid.slots[bedtime].sleepWeight, 0.001)
        assertEquals(1.0, grid.slots[bedtime + 2].sleepWeight, 0.001)
        assertEquals(1.0, grid.slots[slot(6, 55)].sleepWeight, 0.001)
        assertEquals(0.0, grid.slots[slot(7)].sleepWeight, 0.001)
        assertEquals(0.8, grid.slots[slot(3)].nightQuality!!, 0.001)
    }

    @Test
    fun nightStartingBeforeMidnightIsNotCompressedIntoTheLastMinutes() {
        // Journée assise de 8 h à 23 h 40, puis coucher à 23 h 40 pour une nuit qui se termine le lendemain.
        val heartRate = (8..23).map { hour ->
            HrBlockEntity(date.toString(), "%02d:00".format(hour), "TRIGGER_TYPE_LOW_ACTIVITY", List(12) { "65" }.joinToString(","))
        }
        val activity = ActivitySamplesEntity(
            date = date.toString(),
            startTime = "${date}T08:00",
            stepIntervalSec = 60,
            stepSamples = "",
            metIntervalSec = 30,
            metSamples = List(2 * 60 * 15 + 2 * 40) { "1.3" }.joinToString(","),
            activityInfo = "",
        )
        val night = SleepEntity(
            date = date.plusDays(1).toString(),
            startTime = "2026-11-16T23:40+01:00[Europe/Paris]",
            endTime = "2026-11-17T07:30+01:00[Europe/Paris]",
            goalMinutes = 480,
            phases = "0:NONREM12,3600:NONREM3,10800:REM,20000:NONREM12",
            cycles = "",
            userRating = null,
            batteryRanOut = false,
            skinTemperatureC = null,
            skinTemperatureDeviationC = null,
        )
        val grid = BodyBatteryInputs.build(
            date = date,
            now = LocalDateTime.of(2026, 11, 18, 0, 0),
            activity = listOf(activity),
            heartRate = heartRate,
            sleeps = listOf(night),
            qualityByWakeDate = mapOf(date.plusDays(1).toString() to 0.9),
        )
        val simulation = BodyBatteryModel.simulate(grid.slots, restingHr = 55.0, startLevel = 60.0)
        val day = BodyBatteryModel.summarize(simulation, grid.slots, grid.start, grid.dayFrom, grid.dayTo)!!

        val lastHour = day.points.takeLast(12)
        val jump = lastHour.last().level - lastHour.first().level
        assertTrue("saut de $jump points dans la dernière heure", jump < 10.0)
    }

    @Test
    fun slotsAfterNowAreLeftEmpty() {
        val heartRate = HrBlockEntity(date.toString(), "08:00", "TRIGGER_TYPE_LOW_ACTIVITY", List(48) { "70" }.joinToString(","))
        val grid = BodyBatteryInputs.build(
            date = date,
            now = LocalDateTime.of(2026, 11, 16, 9, 0),
            activity = emptyList(),
            heartRate = listOf(heartRate),
            sleeps = emptyList(),
            qualityByWakeDate = emptyMap(),
        )
        assertTrue(grid.slots[slot(8, 55)].hasData)
        assertNull(grid.slots[slot(9)].hr)
    }

    @Test
    fun restingHeartRatePrefersTheNightlyValue() {
        assertEquals(50.0, BodyBatteryInputs.restingHeartRate(1_200, emptyList()), 0.001)
        val blocks = listOf(HrBlockEntity("2026-11-16", "00:00", "T", (40..139).joinToString(",")))
        assertEquals(45.0, BodyBatteryInputs.restingHeartRate(null, blocks), 0.001)
        assertEquals(60.0, BodyBatteryInputs.restingHeartRate(null, emptyList()), 0.001)
    }

    @Test
    fun hrvStressLandsInItsSlot() {
        val grid = BodyBatteryInputs.build(
            date = date,
            now = LocalDateTime.of(2026, 11, 17, 0, 0),
            activity = emptyList(),
            heartRate = emptyList(),
            sleeps = emptyList(),
            qualityByWakeDate = emptyMap(),
            stress = mapOf(date.atTime(14, 35) to 42.0, date.plusDays(1).atTime(9, 0) to 10.0),
        )
        assertEquals(42.0, grid.slots[slot(14, 35)].stress!!, 0.0)
        assertTrue(grid.slots[slot(14, 35)].hasData)
        assertNull(grid.slots[slot(14, 30)].stress)
        // Tenue 25 min faute de nouvelle mesure, puis plus rien.
        assertEquals(42.0, grid.slots[slot(15, 0)].stress!!, 0.0)
        assertNull(grid.slots[slot(15, 5)].stress)
        // Après « now » : ignoré.
        assertNull(grid.slots[slot(9) + perDay].stress)
    }
}
