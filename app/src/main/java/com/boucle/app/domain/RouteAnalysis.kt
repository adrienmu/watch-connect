package com.boucle.app.domain

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Tracé GPS d'une séance : points horodatés (bracelet ou Health Connect), associés à la FC, puis analysés pour
 * le détail d'activité (allure, FC, altitude point par point, temps par km, zones cardiaques, dénivelé).
 * Pur Kotlin, testable sur la JVM.
 */

/** État du tracé d'une séance. */
enum class RouteStatus {
    /** Points disponibles. */
    AVAILABLE,
    /** Health Connect a un tracé, mais l'utilisateur doit autoriser l'app à le lire. */
    CONSENT_REQUIRED,
}

/** Origine des points d'un tracé. */
enum class RouteSource {
    BAND,
    HEALTH_CONNECT,
    /** GPS du téléphone, activité lancée depuis l'app. */
    PHONE,
}

/** Point GPS horodaté, avec la FC mesurée à cet instant quand on la connaît. */
data class RoutePoint(
    /** Instant en millisecondes depuis l'époque Unix. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** Altitude en mètres, si le capteur la donne. */
    val altitude: Double? = null,
    val heartRate: Int? = null,
)

/**
 * Stockage compact d'un tracé dans une seule colonne : « temps:lat:lon:alt:fc » séparés par des points-virgules,
 * champs vides pour les valeurs absentes.
 */
object RouteCodec {
    fun encode(points: List<RoutePoint>): String = points.joinToString(";") { p ->
        buildString {
            append(p.timeMs).append(':')
            append(String.format(Locale.ROOT, "%.6f", p.latitude)).append(':')
            append(String.format(Locale.ROOT, "%.6f", p.longitude)).append(':')
            p.altitude?.let { append(String.format(Locale.ROOT, "%.1f", it)) }
            append(':')
            p.heartRate?.let { append(it) }
        }
    }

    fun decode(encoded: String): List<RoutePoint> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(';').mapNotNull { item ->
            val f = item.split(':')
            if (f.size < 3) return@mapNotNull null
            RoutePoint(
                timeMs = f[0].toLongOrNull() ?: return@mapNotNull null,
                latitude = f[1].toDoubleOrNull() ?: return@mapNotNull null,
                longitude = f[2].toDoubleOrNull() ?: return@mapNotNull null,
                altitude = f.getOrNull(3)?.toDoubleOrNull(),
                heartRate = f.getOrNull(4)?.toIntOrNull(),
            )
        }
    }
}

/** Point du tracé enrichi : distance parcourue, allure lissée, altitude lissée. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Secondes depuis le premier point. */
    val elapsedSec: Double,
    /** Mètres parcourus depuis le départ. */
    val distanceMeters: Double,
    /** Allure en secondes par km ; `null` à l'arrêt ou sans assez de points. */
    val paceSecPerKm: Double?,
    val heartRate: Int?,
    /** Altitude lissée (m). */
    val altitude: Double?,
)

/** Un kilomètre (ou la fraction finale) : durée, allure et dénivelé. */
data class KmSplit(
    /** Numéro du kilomètre, à partir de 1. */
    val number: Int,
    val distanceMeters: Double,
    val durationSec: Double,
    val paceSecPerKm: Double,
    val gainMeters: Double,
    val lossMeters: Double,
)

/** Temps passé dans une zone cardiaque. [lowerBpm] `null` pour la première, [upperBpm] `null` pour la dernière. */
data class HrZoneTime(val number: Int, val lowerBpm: Int?, val upperBpm: Int?, val seconds: Int)

data class RouteStats(
    val points: List<TrackPoint>,
    val distanceMeters: Double,
    val durationSec: Double,
    /** Allure moyenne sur tout le parcours (s/km). */
    val averagePace: Double?,
    /** Allures extrêmes de l'échelle de couleur (s/km) : la plus lente et la plus rapide, hors valeurs aberrantes. */
    val slowestPace: Double?,
    val fastestPace: Double?,
    val minHr: Int?,
    val maxHr: Int?,
    val averageHr: Int?,
    val minAltitude: Double?,
    val maxAltitude: Double?,
    /** Indice dans [points] du point le plus haut. */
    val highestIndex: Int?,
    val gainMeters: Double?,
    val lossMeters: Double?,
    val splits: List<KmSplit>,
    /** Indice dans [splits] du kilomètre complet le plus rapide. */
    val fastestSplitIndex: Int?,
    /** Indice dans [points] du point le plus proche de chaque kilomètre complet. */
    val kmMarkers: List<Int>,
) {
    val hasAltitude: Boolean get() = minAltitude != null
    val hasHeartRate: Boolean get() = minHr != null
}

object RouteAnalysis {
    /** Moins de points que ça : pas de tracé exploitable. */
    const val MIN_POINTS = 2
    private const val EARTH_RADIUS_M = 6_371_000.0
    /** Demi-fenêtre de lissage de l'allure. */
    private const val PACE_WINDOW_SEC = 15.0
    /** En dessous de cette vitesse (m/s), on considère l'arrêt : pas d'allure. */
    private const val MIN_SPEED = 0.5
    /** Variation d'altitude ignorée (bruit du GPS ou du baromètre). */
    private const val ELEVATION_THRESHOLD_M = 2.0
    /** Un écart plus long entre deux points est une pause : il ne compte dans aucune zone. */
    private const val MAX_GAP_SEC = 30.0
    /** Une fraction finale plus courte n'est pas affichée comme un kilomètre. */
    private const val MIN_LAST_SPLIT_M = 50.0

    /** Pourcentages de la FC max qui séparent les 5 zones. */
    val ZONE_PERCENTS = listOf(0.6, 0.7, 0.8, 0.9)

    /** Distance orthodromique (m) entre deux points. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun analyze(raw: List<RoutePoint>): RouteStats? {
        val sorted = raw.sortedBy { it.timeMs }.distinctBy { it.timeMs }
        if (sorted.size < MIN_POINTS) return null
        val t0 = sorted.first().timeMs
        val elapsed = sorted.map { (it.timeMs - t0) / 1000.0 }
        val distance = DoubleArray(sorted.size)
        for (i in 1 until sorted.size) {
            val a = sorted[i - 1]
            val b = sorted[i]
            distance[i] = distance[i - 1] + haversine(a.latitude, a.longitude, b.latitude, b.longitude)
        }
        val altitude = smoothAltitude(sorted.map { it.altitude })
        val points = sorted.indices.map { i ->
            TrackPoint(
                latitude = sorted[i].latitude,
                longitude = sorted[i].longitude,
                elapsedSec = elapsed[i],
                distanceMeters = distance[i],
                paceSecPerKm = paceAt(i, elapsed, distance),
                heartRate = sorted[i].heartRate?.takeIf { it > 0 },
                altitude = altitude[i],
            )
        }
        val total = distance.last()
        val duration = elapsed.last()

        val paces = points.mapNotNull { it.paceSecPerKm }.sorted()
        val hrs = points.mapNotNull { it.heartRate }
        val alts = points.mapNotNull { it.altitude }
        val highest = points.indices.filter { points[it].altitude != null }.maxByOrNull { points[it].altitude!! }
        val elevation = elevationChanges(points)
        val splits = splits(points, elevation.perPoint)
        val fullSplits = splits.indices.filter { splits[it].distanceMeters >= 999.0 }
        return RouteStats(
            points = points,
            distanceMeters = total,
            durationSec = duration,
            averagePace = if (total > 0) duration / (total / 1000) else null,
            slowestPace = paces.takeIf { it.isNotEmpty() }?.let { it[(it.size * 0.9).toInt().coerceAtMost(it.lastIndex)] },
            fastestPace = paces.takeIf { it.isNotEmpty() }?.let { it[(it.size * 0.1).toInt()] },
            minHr = hrs.minOrNull(),
            maxHr = hrs.maxOrNull(),
            averageHr = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            minAltitude = alts.minOrNull(),
            maxAltitude = alts.maxOrNull(),
            highestIndex = highest,
            gainMeters = if (alts.isEmpty()) null else elevation.gain,
            lossMeters = if (alts.isEmpty()) null else elevation.loss,
            splits = splits,
            fastestSplitIndex = fullSplits.minByOrNull { splits[it].paceSecPerKm },
            kmMarkers = kmMarkers(points),
        )
    }

    /** Allure lissée sur ±15 s autour du point. */
    private fun paceAt(i: Int, elapsed: List<Double>, distance: DoubleArray): Double? {
        var from = i
        while (from > 0 && elapsed[i] - elapsed[from] < PACE_WINDOW_SEC) from--
        var to = i
        while (to < elapsed.lastIndex && elapsed[to] - elapsed[i] < PACE_WINDOW_SEC) to++
        val dt = elapsed[to] - elapsed[from]
        val dd = distance[to] - distance[from]
        if (dt <= 0 || dd / dt < MIN_SPEED) return null
        return dt / (dd / 1000)
    }

    /** Moyenne glissante sur 5 points ; les trous restent des trous. */
    private fun smoothAltitude(values: List<Double?>): List<Double?> = values.indices.map { i ->
        if (values[i] == null) return@map null
        val window = (maxOf(0, i - 2)..minOf(values.lastIndex, i + 2)).mapNotNull { values[it] }
        window.average()
    }

    private class Elevation(val gain: Double, val loss: Double, val perPoint: DoubleArray)

    /**
     * Dénivelé avec hystérésis : l'altitude de référence ne bouge qu'au-delà de 2 m d'écart. [Elevation.perPoint]
     * donne la variation comptée à chaque point (positive en montée), pour la répartir par kilomètre.
     */
    private fun elevationChanges(points: List<TrackPoint>): Elevation {
        val perPoint = DoubleArray(points.size)
        var reference: Double? = null
        var gain = 0.0
        var loss = 0.0
        points.forEachIndexed { i, p ->
            val alt = p.altitude ?: return@forEachIndexed
            val ref = reference
            if (ref == null) {
                reference = alt
            } else if (abs(alt - ref) >= ELEVATION_THRESHOLD_M) {
                val delta = alt - ref
                if (delta > 0) gain += delta else loss -= delta
                perPoint[i] = delta
                reference = alt
            }
        }
        return Elevation(gain, loss, perPoint)
    }

    /** Temps de passage interpolé à chaque kilomètre, puis la fraction finale si elle dépasse 50 m. */
    private fun splits(points: List<TrackPoint>, elevation: DoubleArray): List<KmSplit> {
        val total = points.last().distanceMeters
        val out = ArrayList<KmSplit>()
        var startTime = 0.0
        var startDistance = 0.0
        var startIndex = 0
        var km = 1
        while (km * 1000.0 <= total) {
            val target = km * 1000.0
            val j = points.indexOfFirst { it.distanceMeters >= target }
            val a = points[j - 1]
            val b = points[j]
            val f = (target - a.distanceMeters) / (b.distanceMeters - a.distanceMeters)
            val time = a.elapsedSec + f * (b.elapsedSec - a.elapsedSec)
            out += split(km, target - startDistance, time - startTime, elevation, startIndex, j)
            startTime = time
            startDistance = target
            startIndex = j
            km++
        }
        val rest = total - startDistance
        if (rest >= MIN_LAST_SPLIT_M) {
            out += split(km, rest, points.last().elapsedSec - startTime, elevation, startIndex, points.size)
        }
        return out
    }

    private fun split(number: Int, meters: Double, seconds: Double, elevation: DoubleArray, from: Int, to: Int): KmSplit {
        val deltas = (from until to).map { elevation[it] }
        return KmSplit(
            number = number,
            distanceMeters = meters,
            durationSec = seconds,
            paceSecPerKm = seconds / (meters / 1000),
            gainMeters = deltas.filter { it > 0 }.sum(),
            lossMeters = -deltas.filter { it < 0 }.sum(),
        )
    }

    private fun kmMarkers(points: List<TrackPoint>): List<Int> {
        val total = points.last().distanceMeters
        return (1..(total / 1000).toInt()).map { km ->
            points.indices.minBy { abs(points[it].distanceMeters - km * 1000.0) }
        }
    }

    /** Bornes des zones (bpm) pour une FC max : 60, 70, 80 et 90 %. */
    fun zoneBounds(maxHr: Int): List<Int> = ZONE_PERCENTS.map { (maxHr * it).roundToInt() }

    /**
     * Temps passé dans chacune des 5 zones. Chaque intervalle entre deux points compte pour la FC de son premier
     * point ; les pauses (plus de 30 s sans point) ne comptent pas.
     */
    fun hrZones(points: List<TrackPoint>, maxHr: Int): List<HrZoneTime> {
        val bounds = zoneBounds(maxHr)
        val seconds = DoubleArray(5)
        for (i in 0 until points.lastIndex) {
            val hr = points[i].heartRate ?: continue
            val dt = points[i + 1].elapsedSec - points[i].elapsedSec
            if (dt <= 0 || dt > MAX_GAP_SEC) continue
            seconds[zoneOf(hr, bounds)] += dt
        }
        return (0 until 5).map { z ->
            HrZoneTime(
                number = z + 1,
                lowerBpm = bounds.getOrNull(z - 1),
                upperBpm = bounds.getOrNull(z),
                seconds = seconds[z].roundToInt(),
            )
        }
    }

    /** Zone (0 à 4) d'une FC : une FC égale à une borne appartient à la zone du dessus. */
    fun zoneOf(hr: Int, bounds: List<Int>): Int = bounds.count { hr >= it }

    /**
     * FC à un instant, d'après des mesures triées (temps ms, bpm) : interpolée entre les deux mesures qui l'encadrent,
     * `null` si la plus proche est à plus de [maxGapMs].
     */
    fun heartRateAt(timeMs: Long, samples: List<Pair<Long, Int>>, maxGapMs: Long = 30_000): Int? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.lastIndex
        if (timeMs <= samples[lo].first) return samples[lo].second.takeIf { samples[lo].first - timeMs <= maxGapMs }
        if (timeMs >= samples[hi].first) return samples[hi].second.takeIf { timeMs - samples[hi].first <= maxGapMs }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (samples[mid].first <= timeMs) lo = mid else hi = mid
        }
        val (ta, a) = samples[lo]
        val (tb, b) = samples[hi]
        if (minOf(timeMs - ta, tb - timeMs) > maxGapMs) return null
        if (tb - ta > 2 * maxGapMs) return if (timeMs - ta < tb - timeMs) a else b
        return (a + (b - a) * (timeMs - ta).toDouble() / (tb - ta)).roundToInt()
    }
}
