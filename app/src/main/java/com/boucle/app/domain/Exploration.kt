package com.boucle.app.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/*
 * Carte d'exploration : le monde est découpé en carrés (les tuiles Web Mercator de zoom 17, environ 200 m de côté
 * en France) et chaque carré traversé par un tracé GPS est « exploré ». Pur Kotlin, testable sur la JVM.
 */

/** Rectangle en coordonnées Web Mercator normalisées (x et y entre 0 et 1, y vers le sud). */
data class MercatorBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** Carrés explorés, chacun codé par [Exploration.key]. */
class ExploredGrid(
    val cells: LongArray,
    /** Zone du tracé le plus récent, pour y ouvrir la carte. */
    val focus: MercatorBounds?,
) {
    val count: Int get() = cells.size

    val isEmpty: Boolean get() = cells.isEmpty()

    companion object {
        val Empty = ExploredGrid(LongArray(0), null)
    }
}

object Exploration {
    /** Niveau de tuile Web Mercator des carrés. */
    const val CELL_ZOOM = 17

    /** Côté d'un carré en France, pour l'affichage. */
    const val CELL_SIZE = "200 m"

    /** Deux points plus éloignés que ça ne sont pas reliés (GPS coupé, pause, trajet en train). */
    const val MAX_GAP_METERS = 1_000.0

    fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    fun cellX(key: Long): Int = (key shr 32).toInt()

    fun cellY(key: Long): Int = key.toInt()

    fun mercatorX(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun mercatorY(latitude: Double): Double {
        val lat = latitude.coerceIn(-85.05, 85.05) * PI / 180
        return (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2
    }

    /** Carrés traversés par [routes], chacun étant les points d'une séance. */
    fun explore(routes: List<List<RoutePoint>>): ExploredGrid {
        val scale = (1 shl CELL_ZOOM).toDouble()
        val last = (1 shl CELL_ZOOM) - 1
        val visited = HashSet<Long>()
        fun mark(x: Double, y: Double) {
            visited += key(floor(x).toInt().coerceIn(0, last), floor(y).toInt().coerceIn(0, last))
        }
        var latest: List<RoutePoint>? = null
        routes.forEach { raw ->
            val points = raw.sortedBy { it.timeMs }
            if (points.isEmpty()) return@forEach
            if (latest == null || points.first().timeMs > latest!!.first().timeMs) latest = points
            var px = mercatorX(points[0].longitude) * scale
            var py = mercatorY(points[0].latitude) * scale
            mark(px, py)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val x = mercatorX(b.longitude) * scale
                val y = mercatorY(b.latitude) * scale
                // Entre deux points proches, les carrés du segment sont explorés aussi : un point tous les demi-carrés.
                if (RouteAnalysis.haversine(a.latitude, a.longitude, b.latitude, b.longitude) <= MAX_GAP_METERS) {
                    val steps = ceil(max(abs(x - px), abs(y - py)) * 2).toInt()
                    for (s in 1 until steps) mark(px + (x - px) * s / steps, py + (y - py) * s / steps)
                }
                mark(x, y)
                px = x
                py = y
            }
        }
        val focus = latest?.let { points ->
            MercatorBounds(
                minX = points.minOf { mercatorX(it.longitude) },
                minY = points.minOf { mercatorY(it.latitude) },
                maxX = points.maxOf { mercatorX(it.longitude) },
                maxY = points.maxOf { mercatorY(it.latitude) },
            )
        }
        return ExploredGrid(visited.toLongArray(), focus)
    }
}
