package com.boucle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WellbeingTest {

    private val date = LocalDate.of(2026, 11, 18)

    /** Un créneau par demi-heure de 8 h à 20 h, tous à [value]. */
    private fun dayOf(day: LocalDate, value: Double): Map<LocalDateTime, Double> =
        (16 until 40).associate { half -> day.atStartOfDay().plusMinutes(half * 30L) to value }

    private fun stress(vararg days: Pair<Int, Double>): Map<LocalDateTime, Double> =
        days.flatMap { (ago, value) -> dayOf(date.minusDays(ago.toLong()), value).entries }.associate { it.key to it.value }

    private fun night(ago: Int, hrv: Int? = 70, sleep: Int? = 80) =
        WellbeingNight(date.minusDays(ago.toLong()), hrv, rmssdMs = 46, rmssdBaselineMs = 45, sleepScore = sleep)

    @Test
    fun `zones follow the stress scale`() {
        assertEquals(StressZone.REST, StressZone.of(25.0))
        assertEquals(StressZone.LOW, StressZone.of(25.1))
        assertEquals(StressZone.LOW, StressZone.of(50.0))
        assertEquals(StressZone.MEDIUM, StressZone.of(75.0))
        assertEquals(StressZone.HIGH, StressZone.of(75.1))
    }

    @Test
    fun `levels follow the four steps`() {
        assertEquals(WellbeingLevel.WATCH, WellbeingLevel.of(39))
        assertEquals(WellbeingLevel.FRAGILE, WellbeingLevel.of(40))
        assertEquals(WellbeingLevel.BALANCED, WellbeingLevel.of(60))
        assertEquals(WellbeingLevel.SERENE, WellbeingLevel.of(80))
    }

    @Test
    fun `a day needs enough measured windows`() {
        val few = (0 until Wellbeing.MIN_DAY_WINDOWS - 1).associate { date.atTime(10, it * 5) to 40.0 }
        assertTrue(Wellbeing.days(few).isEmpty())
        val enough = (0 until Wellbeing.MIN_DAY_WINDOWS).associate { date.atTime(10, it * 5) to 40.0 }
        assertEquals(40.0, Wellbeing.days(enough).getValue(date).mean, 1e-9)
    }

    @Test
    fun `rest share counts windows in the rest zone`() {
        val values = mapOf(
            date.atTime(9, 0) to 10.0,
            date.atTime(9, 5) to 20.0,
            date.atTime(9, 10) to 40.0,
            date.atTime(9, 15) to 80.0,
        )
        val day = Wellbeing.days(values).getValue(date)
        assertEquals(0.5, day.restShare, 1e-9)
        assertEquals(37.5, day.mean, 1e-9)
    }

    @Test
    fun `no score with too few measured days`() {
        assertNull(Wellbeing.build(date, stress(0 to 30.0, 1 to 30.0), listOf(night(0))).score)
    }

    @Test
    fun `calm week with good nights scores high`() {
        val day = Wellbeing.build(
            date,
            stress(0 to 20.0, 1 to 20.0, 2 to 20.0, 3 to 20.0),
            (0..3).map { night(it, hrv = 90, sleep = 90) },
        )
        val score = day.score!!
        // Stress 20 → 95, VFC 90, sommeil 90, tout en zone de repos → 100.
        assertEquals(95, score.stress)
        assertEquals(100, score.calm)
        assertEquals(93, score.total)
        assertEquals(WellbeingLevel.SERENE, score.level)
    }

    @Test
    fun `stressed week with poor nights scores low`() {
        val day = Wellbeing.build(
            date,
            stress(0 to 70.0, 1 to 70.0, 2 to 70.0, 3 to 70.0),
            (0..3).map { night(it, hrv = 30, sleep = 45) },
        )
        val score = day.score!!
        assertEquals(20, score.stress)
        assertEquals(0, score.calm)
        // 0,35 × 20 + 0,25 × 30 + 0,25 × 45 + 0,15 × 0 = 25,75.
        assertEquals(26, score.total)
        assertEquals(WellbeingLevel.WATCH, score.level)
    }

    @Test
    fun `missing components give their weight to the others`() {
        val day = Wellbeing.build(date, stress(0 to 30.0, 1 to 30.0, 2 to 30.0), emptyList())
        val score = day.score!!
        assertNull(score.hrv)
        assertNull(score.sleep)
        // Stress 30 → 80 (35 %), aucun créneau en zone de repos → 0 (15 %).
        assertEquals(56, score.total)
    }

    @Test
    fun `nights alone are enough for a score`() {
        val score = Wellbeing.build(date, emptyMap(), (0..2).map { night(it, hrv = 60, sleep = 80) }).score!!
        assertNull(score.stress)
        assertEquals(70, score.total)
        assertEquals(46, score.rmssdMs)
        assertEquals(45, score.rmssdBaselineMs)
    }

    @Test
    fun `nights outside the week are ignored`() {
        val nights = (0..2).map { night(it, hrv = 60) } + night(Wellbeing.SCORE_DAYS, hrv = 0, sleep = 0)
        assertEquals(60, Wellbeing.build(date, emptyMap(), nights).score!!.hrv)
    }

    @Test
    fun `chronic stress needs enough measured days`() {
        assertEquals(ChronicStress.UNKNOWN, Wellbeing.chronic(measuredDays = 13, elevatedDays = 13))
        assertEquals(ChronicStress.NONE, Wellbeing.chronic(measuredDays = 28, elevatedDays = 6))
        assertEquals(ChronicStress.WATCH, Wellbeing.chronic(measuredDays = 28, elevatedDays = 7))
        assertEquals(ChronicStress.SETTLED, Wellbeing.chronic(measuredDays = 28, elevatedDays = 14))
    }

    @Test
    fun `trend counts elevated days over four weeks`() {
        val values = (0 until 28).map { ago -> ago to if (ago in 3..5) 60.0 else 30.0 }.toTypedArray()
        val day = Wellbeing.build(date, stress(*values), emptyList())
        assertEquals(28, day.measuredDays)
        assertEquals(3, day.elevatedDays)
        assertEquals(ChronicStress.NONE, day.chronic)
        assertEquals(28, day.month.size)
        assertEquals(30.0, day.usualBand!!.start, 1e-9)
        // 9e décile : entre le dernier jour à 30 et le premier à 60.
        assertEquals(39.0, day.usualBand!!.endInclusive, 1e-9)
        assertEquals(7, day.week.size)
        assertEquals(date, day.week.last()!!.date)
    }

    @Test
    fun `days outside the trend window are ignored`() {
        val day = Wellbeing.build(date, stress(28 to 90.0, 0 to 30.0), emptyList())
        assertEquals(1, day.measuredDays)
        assertEquals(0, day.elevatedDays)
        assertNull(day.usualBand)
    }

    @Test
    fun `timeline averages by half hour between 6 and 22`() {
        val values = mapOf(
            date.atTime(5, 55) to 99.0,
            date.atTime(6, 0) to 20.0,
            date.atTime(6, 25) to 40.0,
            date.atTime(21, 55) to 60.0,
            date.atTime(22, 0) to 99.0,
            date.minusDays(1).atTime(12, 0) to 99.0,
        )
        val timeline = Wellbeing.timeline(values, date)
        assertEquals(32, timeline.size)
        assertEquals(30.0, timeline.first()!!, 1e-9)
        assertEquals(60.0, timeline.last()!!, 1e-9)
        assertEquals(2, timeline.count { it != null })
    }

    @Test
    fun `empty when nothing is measured`() {
        val day = Wellbeing.build(date, emptyMap(), emptyList())
        assertTrue(day.isEmpty)
        assertNull(day.today)
        assertEquals(ChronicStress.UNKNOWN, day.chronic)
    }
}
