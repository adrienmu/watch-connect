package com.boucle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BodyBatteryModelTest {

    private val slotsPerHour = 60 / BodyBatteryModel.SLOT_MINUTES
    private val resting = 55.0

    private fun hours(h: Int, slot: BatterySlot): List<BatterySlot> = List(h * slotsPerHour) { slot }

    private fun sleepHours(h: Int, quality: Double? = 0.8) =
        hours(h, BatterySlot(sleepWeight = 0.7, nightQuality = quality, hr = 52.0))

    private fun sitting() = BatterySlot(met = 1.2, hr = resting + 5)
    private fun walking() = BatterySlot(met = 3.5, hr = 100.0)
    private fun running() = BatterySlot(met = 10.0, hr = 160.0)
    private fun stressed() = BatterySlot(met = 1.3, hr = resting + 30)

    @Test
    fun goodNightRefillsMostOfTheDeficit() {
        val sim = BodyBatteryModel.simulate(sleepHours(8, quality = 1.0), resting, startLevel = 30.0)
        assertEquals(100.0, sim.levels.last(), 0.5)
    }

    @Test
    fun poorNightRestoresMuchLess() {
        val good = BodyBatteryModel.simulate(sleepHours(8, 1.0), resting, 30.0).levels.last()
        val poor = BodyBatteryModel.simulate(sleepHours(8, 0.0), resting, 30.0).levels.last()
        assertEquals(30.0 + 70.0 * BodyBatteryModel.MIN_RESTORE, poor, 0.5)
        assertTrue(poor < good - 30)
    }

    @Test
    fun chargeIsSpreadAcrossTheNightNotInstant() {
        val levels = BodyBatteryModel.simulate(sleepHours(8, 0.8), resting, 30.0).levels
        assertTrue(levels[levels.size / 2] in 40.0..90.0)
        assertTrue(levels.first() < levels[levels.size / 2])
    }

    @Test
    fun activeDayDrainsMoreThanSedentaryDay() {
        val sedentary = BodyBatteryModel.simulate(hours(16, sitting()), resting, 100.0).levels.last()
        val active = BodyBatteryModel.simulate(hours(15, sitting()) + hours(1, running()), resting, 100.0).levels.last()
        assertTrue("sedentary=$sedentary active=$active", active < sedentary - 20)
    }

    @Test
    fun walkingDrainsMoreThanSitting() {
        val sitting = BodyBatteryModel.simulate(hours(2, sitting()), resting, 80.0).levels.last()
        val walking = BodyBatteryModel.simulate(hours(2, walking()), resting, 80.0).levels.last()
        assertTrue(walking < sitting)
    }

    @Test
    fun stressWhileStillDrainsMoreThanCalmRest() {
        val calm = BodyBatteryModel.simulate(hours(3, BatterySlot(met = 1.2, hr = resting + 3)), resting, 80.0).levels.last()
        val stress = BodyBatteryModel.simulate(hours(3, stressed()), resting, 80.0).levels.last()
        assertTrue("calm=$calm stress=$stress", stress < calm - 10)
    }

    @Test
    fun calmRestingSlowlyRechargesDuringTheDay() {
        val calm = BodyBatteryModel.simulate(hours(2, BatterySlot(met = 1.1, hr = resting + 2)), resting, 50.0).levels.last()
        assertTrue(calm > 50.0)
    }

    @Test
    fun levelIsFrozenWhenThereIsNoData() {
        val slots = hours(1, sitting()) + hours(3, BatterySlot())
        val levels = BodyBatteryModel.simulate(slots, resting, 80.0).levels
        assertEquals(levels[slotsPerHour - 1], levels.last(), 0.0001)
    }

    @Test
    fun levelStaysWithinZeroAndHundred() {
        val levels = BodyBatteryModel.simulate(hours(24, running()), resting, 50.0).levels
        assertTrue(levels.all { it in 0.0..100.0 })
        assertEquals(0.0, levels.last(), 0.0001)
    }

    @Test
    fun shortAwakeningDoesNotSplitTheNight() {
        val slots = sleepHours(4) + hours(0, sitting()) + List(slotsPerHour / 2) { BatterySlot(met = 1.0, hr = 60.0) } + sleepHours(3)
        val sim = BodyBatteryModel.simulate(slots, resting, 30.0)
        assertEquals(1, sim.nights.size)
    }

    @Test
    fun summaryReportsWakeLevelAndTotals() {
        val start = LocalDateTime.of(2026, 11, 16, 0, 0)
        val slots = sleepHours(7) + hours(10, sitting()) + hours(7, BatterySlot())
        val sim = BodyBatteryModel.simulate(slots, resting, 40.0)
        val day = BodyBatteryModel.summarize(sim, slots, start, 0, BodyBatteryModel.SLOTS_PER_DAY)!!

        assertNotNull(day.wake)
        assertTrue(day.wake!! > 40)
        assertEquals(day.high, day.points.maxOf { it.level }.toInt().coerceAtLeast(day.high))
        assertTrue(day.current < day.wake!!)
        assertTrue(day.drained > 0 && day.charged > 0)
        // Les créneaux sans donnée à la fin ne comptent pas dans la courbe.
        assertEquals(17 * slotsPerHour, day.points.size)
    }

    @Test
    fun summaryIsNullWithoutAnyDataThatDay() {
        val start = LocalDateTime.of(2026, 11, 16, 0, 0)
        val slots = List(BodyBatteryModel.SLOTS_PER_DAY) { BatterySlot() }
        val sim = BodyBatteryModel.simulate(slots, resting)
        assertNull(BodyBatteryModel.summarize(sim, slots, start, 0, slots.size))
    }

    @Test
    fun calmHrvStressRechargesWhileSitting() {
        val calm = BodyBatteryModel.simulate(hours(2, BatterySlot(met = 1.2, hr = resting + 5, stress = 5.0)), resting, 50.0)
        assertTrue(calm.levels.last() > 50.5)
        assertTrue(calm.charged.sum() > 0.0)
    }

    @Test
    fun highHrvStressDrainsMoreThanLowStress() {
        val low = BodyBatteryModel.simulate(hours(4, BatterySlot(met = 1.2, hr = resting + 5, stress = 30.0)), resting, 80.0)
        val high = BodyBatteryModel.simulate(hours(4, BatterySlot(met = 1.2, hr = resting + 5, stress = 90.0)), resting, 80.0)
        assertTrue("low=${low.levels.last()} high=${high.levels.last()}", high.levels.last() < low.levels.last() - 15)
    }

    @Test
    fun hrvStressReplacesTheHeartRateProxy() {
        // FC élevée au repos (le proxy y verrait du stress) mais VFC habituelle : la VFC l'emporte.
        val proxy = BodyBatteryModel.simulate(hours(2, stressed()), resting, 80.0).levels.last()
        val hrv = BodyBatteryModel.simulate(hours(2, stressed().copy(stress = 30.0)), resting, 80.0).levels.last()
        assertTrue("proxy=$proxy hrv=$hrv", hrv > proxy + 5)
    }

    @Test
    fun hrvStressIsIgnoredDuringActivity() {
        val plain = BodyBatteryModel.simulate(hours(1, running()), resting, 80.0).levels.last()
        val withStress = BodyBatteryModel.simulate(hours(1, running().copy(stress = 100.0)), resting, 80.0).levels.last()
        assertEquals(plain, withStress, 1e-9)
    }
}
