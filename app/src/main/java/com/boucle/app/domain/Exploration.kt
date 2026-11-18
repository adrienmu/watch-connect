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
 * Carte d'exploration : le monde est découpé en carrés (les tuiles Web Mercator d'un niveau de zoom) et chaque
 * carré traversé par un tracé GPS est « exploré ». Trois tailles de carré, de la ville à la France : un grand carré
 * est exploré dès qu'un de ses petits carrés l'est. Pur Kotlin, testable sur la JVM.
 */

/** Taille des carrés. [zoom] est le niveau de tuile Web Mercator ; [size] le côté d'un carré en France. */
enum class ExplorationLevel(val zoom: Int, val label: String, val size: String) {
    City(17, "Ville", "200 m"),
    Region(14, "Région", "1,7 km"),
    Country(11, "France", "14 km"),
}

/** Rectangle en coordonnées Web Mercator normalisées (x et y entre 0 et 1, y vers le sud). */
data class MercatorBounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

/** Carrés explorés, par niveau. Un carré est codé par [Exploration.key]. */
class ExploredGrid(
    private val cells: Map<ExplorationLevel, LongArray>,
    /** Zone du tracé le plus récent, pour y ouvrir la carte. */
    val focus: MercatorBounds?,
) {
    fun cells(level: ExplorationLevel): LongArray = cells[level] ?: NONE

    fun count(level: ExplorationLevel): Int = cells(level).size

    val isEmpty: Boolean get() = count(ExplorationLevel.City) == 0

    companion object {
        private val NONE = LongArray(0)
        val Empty = ExploredGrid(emptyMap(), null)
    }
}

object Exploration {
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
        val fine = ExplorationLevel.City
        val scale = (1 shl fine.zoom).toDouble()
        val last = (1 shl fine.zoom) - 1
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
        val cells = ExplorationLevel.entries.associateWith { level ->
            val shift = fine.zoom - level.zoom
            if (shift == 0) {
                visited.toLongArray()
            } else {
                visited.mapTo(HashSet()) { key(cellX(it) shr shift, cellY(it) shr shift) }.toLongArray()
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
        return ExploredGrid(cells, focus)
    }
}
