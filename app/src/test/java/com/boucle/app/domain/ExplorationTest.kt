package com.boucle.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationTest {

    /** Un degré de latitude ≈ 111,2 km. */
    private fun north(meters: Double) = meters / 111_195.0

    private fun point(timeMs: Long, latitude: Double, longitude: Double = 4.8) = RoutePoint(timeMs, latitude, longitude)

    private fun cellOf(latitude: Double, longitude: Double): Long {
        val scale = (1 shl Exploration.CELL_ZOOM).toDouble()
        return Exploration.key((Exploration.mercatorX(longitude) * scale).toInt(), (Exploration.mercatorY(latitude) * scale).toInt())
    }

    @Test
    fun keyRoundTrip() {
        val key = Exploration.key(66_300, 46_800)
        assertEquals(66_300, Exploration.cellX(key))
        assertEquals(46_800, Exploration.cellY(key))
    }

    @Test
    fun noRouteNoSquare() {
        val grid = Exploration.explore(listOf(emptyList()))
        assertTrue(grid.isEmpty)
        assertNull(grid.focus)
        assertEquals(0, grid.count)
    }

    @Test
    fun singlePointExploresOneSquare() {
        val grid = Exploration.explore(listOf(listOf(point(1L, 45.0))))
        assertEquals(listOf(cellOf(45.0, 4.8)), grid.cells.toList())
    }

    @Test
    fun squaresBetweenTwoClosePointsAreExplored() {
        // 900 m plein nord en deux points : à 45° de latitude un carré fait ≈ 216 m, donc 5 ou 6 carrés
        // d'affilée dans la même colonne, sans trou.
        val grid = Exploration.explore(listOf(listOf(point(1L, 45.0), point(2L, 45.0 + north(900.0)))))
        val cells = grid.cells
        assertTrue("${cells.size} carrés", cells.size in 5..6)
        assertEquals(1, cells.map(Exploration::cellX).distinct().size)
        val rows = cells.map(Exploration::cellY).sorted()
        assertEquals((rows.first()..rows.last()).toList(), rows)
    }

    @Test
    fun distantPointsAreNotJoined() {
        // 5 km entre deux points (GPS coupé) : seuls les deux carrés des extrémités comptent.
        val grid = Exploration.explore(listOf(listOf(point(1L, 45.0), point(2L, 45.0 + north(5_000.0)))))
        assertEquals(2, grid.count)
    }

    @Test
    fun sameSquareCountsOnceAcrossRoutes() {
        val route = listOf(point(1L, 45.0), point(2L, 45.0 + north(900.0)))
        val once = Exploration.explore(listOf(route))
        val twice = Exploration.explore(listOf(route, route.map { it.copy(timeMs = it.timeMs + 10_000) }))
        assertEquals(once.count, twice.count)
    }

    @Test
    fun longRouteExploresEverySquareOnItsWay() {
        // 20 km vers le nord, un point tous les 100 m : ≈ 93 carrés de 216 m.
        val route = (0..200).map { point(it.toLong(), 45.0 + north(100.0 * it)) }
        assertTrue(Exploration.explore(listOf(route)).count in 90..96)
    }

    @Test
    fun focusIsTheMostRecentRoute() {
        val old = listOf(point(1_000L, 45.0), point(2_000L, 45.01))
        val recent = listOf(point(9_000L, 48.85, 2.35), point(9_500L, 48.86, 2.36))
        val focus = Exploration.explore(listOf(recent, old)).focus!!
        assertEquals(Exploration.mercatorX(2.35), focus.minX, 1e-12)
        assertEquals(Exploration.mercatorX(2.36), focus.maxX, 1e-12)
        // y augmente vers le sud.
        assertEquals(Exploration.mercatorY(48.86), focus.minY, 1e-12)
        assertEquals(Exploration.mercatorY(48.85), focus.maxY, 1e-12)
    }
}
