package com.boucle.app.domain

import com.boucle.app.healthconnect.HcExercise
import com.boucle.app.healthconnect.HcHeartRate
import com.boucle.app.healthconnect.HcLocation
import com.boucle.app.healthconnect.HcRoute
import com.boucle.app.healthconnect.HealthConnectMapping
import com.boucle.app.polar.bandRoutePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RouteAnalysisTest {

    /** Un degré de latitude ≈ 111,2 km : ce pas fait ≈ 10 m. */
    private val tenMetersLat = 10.0 / 111_195.0

    /** Ligne droite vers le nord, un point toutes les [stepSec] s, [metersPerStep] m par point. */
    private fun straight(
        count: Int,
        stepSec: Int = 3,
        metersPerStep: Double = 10.0,
        altitude: (Int) -> Double? = { null },
        hr: (Int) -> Int? = { null },
    ): List<RoutePoint> = (0 until count).map { i ->
        RoutePoint(
            timeMs = 1_000_000L + i * stepSec * 1000L,
            latitude = 45.0 + i * tenMetersLat * metersPerStep / 10,
            longitude = 4.8,
            altitude = altitude(i),
            heartRate = hr(i),
        )
    }

    @Test
    fun codecRoundTripKeepsMissingValues() {
        val points = listOf(
            RoutePoint(1L, 45.123456, 4.654321, 172.4, 151),
            RoutePoint(2L, 45.2, 4.7, null, null),
        )
        val decoded = RouteCodec.decode(RouteCodec.encode(points))
        assertEquals(points, decoded)
        assertTrue(RouteCodec.decode("").isEmpty())
    }

    @Test
    fun distanceDurationAndSplits() {
        // 251 points de 10 m toutes les 3 s : 2,5 km en 750 s, soit 5'00"/km.
        val stats = RouteAnalysis.analyze(straight(251))!!
        assertEquals(2_500.0, stats.distanceMeters, 5.0)
        assertEquals(750.0, stats.durationSec, 0.01)
        assertEquals(300.0, stats.averagePace!!, 1.0)
        assertEquals(listOf(1, 2, 3), stats.splits.map { it.number })
        assertEquals(1_000.0, stats.splits[0].distanceMeters, 0.01)
        assertEquals(300.0, stats.splits[0].paceSecPerKm, 1.5)
        // Fraction finale de 0,5 km.
        assertEquals(500.0, stats.splits[2].distanceMeters, 5.0)
        assertEquals(2, stats.kmMarkers.size)
        assertEquals(100, stats.kmMarkers[0])
    }

    @Test
    fun fastestSplitIgnoresTheFinalFraction() {
        // 1er km à 6'00"/km, 2e à 4'00"/km, puis 300 m très rapides.
        val points = ArrayList<RoutePoint>()
        var t = 0L
        var lat = 45.0
        fun add(meters: Double, seconds: Int) {
            t += seconds * 1000L
            lat += tenMetersLat * meters / 10
            points += RoutePoint(t, lat, 4.8)
        }
        points += RoutePoint(0, lat, 4.8)
        repeat(100) { add(10.0, 6) }
        repeat(100) { add(10.0, 4) }
        repeat(30) { add(10.0, 2) }
        val stats = RouteAnalysis.analyze(points)!!
        assertEquals(1, stats.fastestSplitIndex)
        assertEquals(3, stats.splits.size)
    }

    @Test
    fun pausesHaveNoPace() {
        val moving = straight(20)
        val stopped = (1..20).map { moving.last().copy(timeMs = moving.last().timeMs + it * 3000L) }
        val stats = RouteAnalysis.analyze(moving + stopped)!!
        assertNull(stats.points.last().paceSecPerKm)
        assertNotNull(stats.points[5].paceSecPerKm)
    }

    @Test
    fun elevationIgnoresSmallNoiseAndIsSplitPerKm() {
        // Montée de 0 à 50 m sur le 1er km, descente de 30 m sur le 2e, bruit de ±0,5 m.
        val stats = RouteAnalysis.analyze(
            straight(201, altitude = { i ->
                val base = if (i <= 100) i * 0.5 else 50 - (i - 100) * 0.3
                base + if (i % 2 == 0) 0.5 else -0.5
            }),
        )!!
        assertEquals(50.0, stats.gainMeters!!, 4.0)
        assertEquals(30.0, stats.lossMeters!!, 4.0)
        assertTrue(stats.splits[0].gainMeters > 40)
        assertTrue(stats.splits[1].lossMeters > 20)
        assertEquals(0.0, stats.splits[1].gainMeters, 0.01)
        assertTrue(stats.highestIndex!! in 97..103)
    }

    @Test
    fun withoutAltitudeOrHeartRateTheirStatsAreAbsent() {
        val stats = RouteAnalysis.analyze(straight(10))!!
        assertNull(stats.gainMeters)
        assertNull(stats.minHr)
        assertTrue(!stats.hasAltitude && !stats.hasHeartRate)
        assertNull(RouteAnalysis.analyze(straight(1)))
    }

    @Test
    fun zoneBoundsFollowMaxHrPercentages() {
        // FC max 191 : 115, 134, 153, 172 bpm, comme la maquette.
        assertEquals(listOf(115, 134, 153, 172), RouteAnalysis.zoneBounds(191))
        val bounds = RouteAnalysis.zoneBounds(191)
        assertEquals(0, RouteAnalysis.zoneOf(114, bounds))
        assertEquals(1, RouteAnalysis.zoneOf(115, bounds))
        assertEquals(4, RouteAnalysis.zoneOf(185, bounds))
    }

    @Test
    fun timeInZonesSkipsPauses() {
        // 10 intervalles de 3 s en zone 3, puis une pause de 2 min, puis 10 intervalles en zone 5.
        val first = straight(11, hr = { 140 })
        val second = straight(11, hr = { 180 }).map { it.copy(timeMs = it.timeMs + 150_000L) }
        val stats = RouteAnalysis.analyze(first + second)!!
        val zones = RouteAnalysis.hrZones(stats.points, 191)
        assertEquals(listOf(0, 0, 30, 0, 30), zones.map { it.seconds })
        assertNull(zones.first().lowerBpm)
        assertNull(zones.last().upperBpm)
    }

    @Test
    fun heartRateIsInterpolatedBetweenNearbySamples() {
        val samples = listOf(0L to 100, 10_000L to 120, 200_000L to 150)
        assertEquals(110, RouteAnalysis.heartRateAt(5_000, samples))
        assertEquals(100, RouteAnalysis.heartRateAt(-20_000, samples))
        // Trop loin de toute mesure.
        assertNull(RouteAnalysis.heartRateAt(100_000, samples))
        assertNull(RouteAnalysis.heartRateAt(5_000, emptyList()))
    }

    @Test
    fun healthConnectRoutesKeepConsentStateAndHeartRate() {
        val start = Instant.parse("2026-11-17T06:12:00Z")
        val locations = (0 until 5).map { HcLocation(start.plusSeconds(it * 3L), 45.0 + it * 0.0001, 4.8, 170.0) }
        fun exercise(id: String, route: HcRoute) = HcExercise(id, start, start.plusSeconds(600), null, true, "Course", null, null, route)
        val routes = HealthConnectMapping.routes(
            listOf(
                exercise("a", HcRoute.Points(locations)),
                exercise("b", HcRoute.ConsentRequired),
                exercise("c", HcRoute.None),
            ),
            heart = listOf(HcHeartRate(start, 120.0), HcHeartRate(start.plusSeconds(12), 132.0)),
        )
        assertEquals(listOf("healthconnect:a", "healthconnect:b"), routes.map { it.sessionPath })
        assertEquals(RouteStatus.AVAILABLE.name, routes[0].status)
        assertEquals(RouteStatus.CONSENT_REQUIRED.name, routes[1].status)
        assertEquals("b", routes[1].healthConnectId)
        val points = RouteCodec.decode(routes[0].points)
        assertEquals(listOf(120, 123, 126, 129, 132), points.map { it.heartRate })
    }

    @Test
    fun bandRouteSkipsPointsWithoutFixAndReadsHeartRateByInterval() {
        val points = bandRoutePoints(
            startMs = 1_000,
            offsetsMs = listOf(0, 1_000, 2_000, 3_000),
            latitudes = listOf(0.0, 45.0, 45.0001, 45.0002),
            longitudes = listOf(0.0, 4.8, 4.8, 4.8),
            altitudes = listOf(0.0, 170.0, 171.0, 172.0),
            hrIntervalMs = 1_000,
            heartRates = listOf(0, 120, 121, 0),
        )
        assertEquals(3, points.size)
        assertEquals(2_000L, points[0].timeMs)
        assertEquals(listOf(120, 121, null), points.map { it.heartRate })
        assertEquals(170.0, points[0].altitude!!, 0.01)
    }
}
