package com.boucle.app.domain

import com.boucle.app.data.db.ActivityEditEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.ui.applyEdits
import com.boucle.app.ui.defaultSessionName
import com.boucle.app.ui.trimRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ActivityTrackingTest {

    /** Un point tous les [stepMeters] vers le nord, une fois par seconde. */
    private fun point(index: Int, stepMeters: Double = 3.0, altitude: Double? = null) = RoutePoint(
        timeMs = 1_000L * index,
        latitude = 45.0 + index * stepMeters / 111_195.0,
        longitude = 5.0,
        altitude = altitude,
    )

    @Test
    fun `distance et vitesse d'une course régulière`() {
        val tracker = LiveTracker()
        var stats = LiveStats()
        for (i in 0..400) stats = tracker.add(point(i), 5f, i.toDouble())
        assertEquals(1200.0, stats.distanceMeters, 5.0)
        assertEquals(3.0, stats.speedMps!!, 0.1)
        // Le premier kilomètre est bouclé en 334 s environ.
        assertEquals(334.0, stats.lastKmSec!!, 3.0)
        assertEquals(401, tracker.points.size)
    }

    @Test
    fun `positions imprécises et sur-place ignorés`() {
        val tracker = LiveTracker()
        tracker.add(point(0), 5f, 0.0)
        tracker.add(point(1), 80f, 1.0)
        val stats = tracker.add(point(0), 5f, 2.0)
        assertEquals(0.0, stats.distanceMeters, 0.01)
        assertEquals(1, tracker.points.size)
    }

    @Test
    fun `à l'arrêt la vitesse disparaît`() {
        val tracker = LiveTracker()
        for (i in 0..30) tracker.add(point(i), 5f, i.toDouble())
        var stats = LiveStats()
        for (i in 31..40) stats = tracker.add(point(30), 5f, i.toDouble())
        assertNull(stats.speedMps)
        assertEquals(90.0, stats.distanceMeters, 1.0)
    }

    @Test
    fun `le trajet fait pendant une pause ne compte pas`() {
        val tracker = LiveTracker()
        for (i in 0..10) tracker.add(point(i), 5f, i.toDouble())
        tracker.resume()
        // Reprise 300 m plus loin : la distance repart de ce point.
        tracker.add(point(110), 5f, 11.0)
        val stats = tracker.add(point(111), 5f, 12.0)
        assertEquals(33.0, stats.distanceMeters, 1.0)
    }

    @Test
    fun `dénivelé avec hystérésis`() {
        val tracker = LiveTracker()
        val altitudes = listOf(100.0, 100.8, 101.5, 103.0, 106.0, 105.2, 101.0)
        var stats = LiveStats()
        altitudes.forEachIndexed { i, alt -> stats = tracker.add(point(i, altitude = alt), 5f, i.toDouble()) }
        assertEquals(6.0, stats.gainMeters, 0.01)
        assertEquals(5.0, stats.lossMeters, 0.01)
    }

    private fun day(vararg ranges: Pair<IntRange, Int>): List<Triple<LocalDateTime, Int, List<Int>>> {
        val minutes = IntArray(1440)
        ranges.forEach { (range, steps) -> range.forEach { minutes[it] = steps } }
        return listOf(Triple(DATE.atStartOfDay(), 60, minutes.toList()))
    }

    @Test
    fun `marche soutenue de 28 minutes détectée`() {
        val start = 12 * 60 + 41
        val found = ActivityDetection.detect(DATE, day(start until start + 28 to 112), emptyList())
        assertEquals(1, found.size)
        val walk = found.single()
        assertEquals(DATE.atTime(12, 41), walk.start)
        assertEquals(28 * 60, walk.durationSec)
        assertEquals(Sport.WALK, walk.sport)
        assertEquals(112, walk.cadence)
        assertEquals(28 * 112, walk.steps)
    }

    @Test
    fun `cadence élevée reconnue comme une course, effort court ignoré`() {
        val found = ActivityDetection.detect(DATE, day(420 until 450 to 165, 600 until 608 to 110), emptyList())
        assertEquals(listOf(Sport.RUN), found.map { it.sport })
    }

    @Test
    fun `une pause de deux minutes ne coupe pas l'effort, une plus longue oui`() {
        val joined = ActivityDetection.detect(DATE, day(600 until 610 to 100, 612 until 622 to 100), emptyList())
        assertEquals(1, joined.size)
        assertEquals(22 * 60, joined.single().durationSec)
        val split = ActivityDetection.detect(DATE, day(600 until 610 to 100, 613 until 623 to 100), emptyList())
        assertEquals(2, split.size)
    }

    @Test
    fun `séance déjà enregistrée non proposée`() {
        val busy = listOf(DATE.atTime(12, 30) to DATE.atTime(13, 15))
        val start = 12 * 60 + 41
        assertTrue(ActivityDetection.detect(DATE, day(start until start + 28 to 112), busy).isEmpty())
    }

    @Test
    fun `sport d'après l'identifiant Polar ou le nom`() {
        assertEquals(Sport.RUN, Sport.of(1L, null))
        assertEquals(Sport.RUN, Sport.of(27L, null))
        assertEquals(Sport.BIKE, Sport.of(2L, "Entraînement"))
        assertEquals(Sport.WALK, Sport.of(null, "Marche"))
        assertEquals(Sport.BIKE, Sport.of(null, "Vélo"))
        assertNull(Sport.of(15L, "Renforcement"))
    }

    private fun session(path: String, sportId: Long? = 1L) = TrainingSessionEntity(
        path = path, date = "2026-11-18", startTime = "2026-11-18T07:12", durationSec = 2778, sportId = sportId, name = "Course",
        calories = null, distanceMeters = 8420f, averageHr = null, maxHr = null, cardioLoad = null, muscleLoad = null,
        hrZoneSec = "", exerciseCount = 0, summaryProto = null,
    )

    @Test
    fun `corrections appliquées aux séances`() {
        val edits = listOf(
            ActivityEditEntity(sessionPath = "a", name = "Sortie longue", sport = "BIKE", trimStartSec = 60, trimmedDurationSec = 2000, trimmedDistanceMeters = 7000f),
            ActivityEditEntity(sessionPath = "b", deleted = true),
        ).associateBy { it.sessionPath }
        val result = applyEdits(listOf(session("a"), session("b"), session("c")), edits)
        assertEquals(listOf("a", "c"), result.map { it.path })
        val edited = result.first()
        assertEquals("Sortie longue", edited.name)
        assertEquals(Sport.BIKE.sportId, edited.sportId)
        assertEquals(2000, edited.durationSec)
        assertEquals(7000f, edited.distanceMeters)
        assertEquals("2026-11-18T07:13", edited.startTime)
        assertEquals("Course", result.last().name)
    }

    @Test
    fun `rognage du tracé`() {
        val points = (0..100).map { point(it) }
        val kept = trimRoute(points, 10, 20)
        assertEquals(10_000L, kept.first().timeMs)
        assertEquals(80_000L, kept.last().timeMs)
        assertEquals(points, trimRoute(points, null, 0))
    }

    @Test
    fun `nom par défaut et estimation des calories`() {
        assertEquals("Course du matin", defaultSessionName(Sport.RUN, LocalTime.of(7, 12)))
        assertEquals("Sortie vélo de l'après-midi", defaultSessionName(Sport.BIKE, LocalTime.of(15, 0)))
        assertEquals("Marche du soir", defaultSessionName(Sport.WALK, LocalTime.of(19, 30)))
        assertEquals(700, CalorieEstimate.kcal(Sport.RUN, 10_000.0, 3000.0, 70f))
        assertEquals(262, CalorieEstimate.kcal(Sport.WALK, 5_000.0, 3600.0, 70f))
        // 30 km en 1 h 30 : 20 km/h, soit 8 MET.
        assertEquals(840, CalorieEstimate.kcal(Sport.BIKE, 30_000.0, 5400.0, 70f))
    }

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 11, 18)
    }
}
