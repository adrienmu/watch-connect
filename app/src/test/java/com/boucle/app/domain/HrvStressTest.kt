package com.boucle.app.domain

import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SleepEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

class HrvStressTest {

    private val date = LocalDate.of(2026, 11, 16)

    private fun block(start: String, ppi: List<Number>, errors: List<Number>? = null) = PpiBlockEntity(
        date = date.toString(),
        startTime = start,
        trigger = "TRIGGER_TYPE_AUTOMATIC",
        ppiMs = ppi.joinToString(","),
        errorEstimateMs = (errors ?: ppi.map { 8 }).joinToString(","),
    )

    /** Intervalles alternant autour de [mean] : RMSSD exactement égal à [rmssd]. */
    private fun alternating(n: Int, mean: Int = 1_000, rmssd: Int = 40) =
        List(n) { if (it % 2 == 0) mean - rmssd / 2 else mean + rmssd / 2 }

    private fun window(at: LocalDateTime, rmssd: Double, hr: Double = 70.0) =
        HrvStress.Window(at, rmssd, hr, beats = 200, validRatio = 1.0)

    /** 3 jours d'éveil (8 h–22 h), un créneau par quart d'heure, RMSSD ≈ 40 ms ± 25 %. */
    private fun history(): List<HrvStress.Window> = (3 downTo 1).flatMap { d ->
        (8 * 4 until 22 * 4).map { q ->
            window(date.minusDays(d.toLong()).atStartOfDay().plusMinutes(q * 15L), 40.0 * exp(0.3 * sin(q.toDouble())))
        }
    }

    @Test
    fun windowsComputeRmssdAndHeartRatePerSlot() {
        val windows = HrvStress.windows(listOf(block("10:00", alternating(230))))
        assertEquals(1, windows.size)
        val w = windows.single()
        assertEquals(date.atTime(10, 0), w.start)
        assertEquals(40.0, w.rmssd, 0.01)
        assertEquals(60.0, w.hr, 0.01)
        assertEquals(1.0, w.validRatio, 0.001)
    }

    @Test
    fun beatsAreSplitAcrossSlotBoundaries() {
        // 8 min de battements à 1 s à partir de 10:02 : créneaux 10:00, 10:05 (et 10:10 pour le dernier).
        val windows = HrvStress.windows(listOf(block("10:02", alternating(480, rmssd = 30))))
        assertEquals(listOf(date.atTime(10, 0), date.atTime(10, 5)), windows.map { it.start }.take(2))
        windows.forEach { assertEquals(30.0, it.rmssd, 0.01) }
    }

    @Test
    fun cleaningRemovesArtifactsAndEctopicBeats() {
        val ppi = alternating(200, rmssd = 20).map { it.toDouble() }.toMutableList()
        val errors = MutableList(ppi.size) { 8.0 }
        ppi[30] = 0.0 // détection manquée
        ppi[60] = 3_000.0 // hors bornes
        ppi[90] = 1_400.0
        errors[90] = 80.0 // estimation d'erreur du capteur trop forte
        ppi[120] = 600.0 // extrasystole…
        ppi[121] = 1_400.0 // … et pause compensatrice
        ppi[160] = 1_350.0 // pic isolé

        val w = HrvStress.windows(listOf(block("10:00", ppi, errors))).single()
        assertTrue("rmssd=${w.rmssd}", w.rmssd in 14.0..24.0)
        assertTrue(w.validRatio in 0.95..0.99)
    }

    @Test
    fun poorWindowsAreDropped() {
        assertTrue(HrvStress.windows(listOf(block("10:00", alternating(20)))).isEmpty())
        val mostlyBad = alternating(30) + List(40) { 0 }
        assertTrue(HrvStress.windows(listOf(block("10:00", mostlyBad))).isEmpty())
        assertTrue(HrvStress.windows(listOf(block("10:00", emptyList()))).isEmpty())
    }

    @Test
    fun stressIsRelativeToThePersonalBaseline() {
        val at = date.atTime(10, 0)
        val probes = listOf(80.0, 40.0, 20.0)
        val stress = probes.map { rmssd ->
            HrvStress.score(history() + window(at, rmssd), emptyList(), restingHr = 55.0).getValue(at)
        }
        assertTrue("VFC haute → calme : ${stress[0]}", stress[0] < 5.0)
        assertTrue("VFC habituelle → stress faible : ${stress[1]}", stress[1] in 20.0..40.0)
        assertTrue("VFC basse → stress élevé : ${stress[2]}", stress[2] > 90.0)
    }

    @Test
    fun coldStartUsesTheDefaultBaseline() {
        val at = date.atTime(10, 0)
        val stress = HrvStress.score(listOf(window(at, exp(3.8))), emptyList(), 55.0).getValue(at)
        // Z = 0 face au prior : environ 30.
        assertEquals(100.0 / (1.0 + exp(1.5 * 0.55)), stress, 0.01)
        assertEquals(0.0, HrvStress.BaselineTracker().baseline(at).weight, 0.0)
    }

    @Test
    fun sleepIsSkippedAndExerciseDoesNotFeedTheBaseline() {
        val at = date.atTime(18, 0)
        val night = date.minusDays(1).atTime(23, 0)..date.atTime(7, 0)
        val sleeping = window(date.atTime(3, 0), 90.0)
        val exercise = (0 until 12).map { window(date.atTime(17, 0).plusMinutes(it * 5L), 6.0, hr = 160.0) }

        val calm = HrvStress.score(history() + window(at, 40.0), listOf(night), 55.0)
        val withRun = HrvStress.score(history() + sleeping + exercise + window(at, 40.0), listOf(night), 55.0)

        assertFalse(date.atTime(3, 0) in withRun)
        assertTrue(exercise.all { withRun.getValue(it.start) > 90.0 })
        assertEquals(calm.getValue(at), withRun.getValue(at), 1e-9)
    }

    @Test
    fun baselineOnlyLooksBackward() {
        val windows = history()
        val first = windows.first().start
        val alone = HrvStress.score(windows.take(1), emptyList(), 55.0).getValue(first)
        val withFuture = HrvStress.score(windows, emptyList(), 55.0).getValue(first)
        assertEquals(alone, withFuture, 1e-9)
    }

    @Test
    fun sleepIntervalsAreParsedInLocalTime() {
        val sleep = SleepEntity(
            date = date.toString(),
            startTime = "2026-11-15T23:00+01:00[Europe/Paris]",
            endTime = "2026-11-16T07:00+01:00[Europe/Paris]",
            goalMinutes = 480, phases = "", cycles = "", userRating = null, batteryRanOut = false,
            skinTemperatureC = null, skinTemperatureDeviationC = null,
        )
        assertEquals(listOf(date.minusDays(1).atTime(23, 0)..date.atTime(7, 0)), HrvStress.sleepIntervals(listOf(sleep)))
        assertEquals(ln(40.0), window(date.atStartOfDay(), 40.0).lnRmssd, 1e-12)
    }

    @Test
    fun watchRmssdBecomesSlotWindows() {
        val windows = HrvStress.windowsFromRmssd(
            listOf(date.atTime(10, 1) to 30.0, date.atTime(10, 4) to 50.0, date.atTime(10, 7) to 0.0, date.atTime(11, 0) to 45.0),
        )
        assertEquals(listOf(date.atTime(10, 0), date.atTime(11, 0)), windows.map { it.start })
        assertEquals(40.0, windows[0].rmssd, 1e-9)
        val stress = HrvStress.score(windows, emptyList(), restingHr = 55.0)
        assertEquals(2, stress.size)
    }
}
