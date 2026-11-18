package com.boucle.app.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.RouteStats
import com.boucle.app.ui.theme.RouteColors
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ce qui colore le tracé : allure, fréquence cardiaque ou altitude. */
enum class RouteMetric { Pace, Heart, Altitude }

/** Couleurs du dégradé d'une métrique, du minimum au maximum. */
val RouteMetric.gradient: List<Color>
    get() = when (this) {
        RouteMetric.Pace -> RouteColors.PaceGradient
        RouteMetric.Heart -> RouteColors.HeartGradient
        RouteMetric.Altitude -> RouteColors.AltitudeGradient
    }

/** Couleur du dégradé à la position [t] (0 à 1). */
fun List<Color>.at(t: Float): Color {
    val x = t.coerceIn(0f, 1f) * (size - 1)
    val i = floor(x).toInt().coerceAtMost(size - 2)
    return lerp(this[i], this[i + 1], x - i)
}

/**
 * Tracé prêt à dessiner, réduit à 600 points au plus : position Web Mercator, distance parcourue (fraction du
 * total) et, par métrique, la valeur ramenée entre 0 et 1 (0 = allure la plus lente, FC ou altitude la plus basse).
 */
@Immutable
class RouteGeometry(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val fractions: FloatArray,
    private val values: Map<RouteMetric, FloatArray>,
    /** Indices des repères de kilomètre dans ces tableaux. */
    val kmMarkers: List<Int>,
) {
    val size get() = xs.size
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()

    fun values(metric: RouteMetric): FloatArray = values.getValue(metric)

    companion object {
        private const val MAX_POINTS = 600

        fun from(stats: RouteStats): RouteGeometry {
            val all = stats.points
            val step = max(1, (all.size + MAX_POINTS - 1) / MAX_POINTS)
            val kept = (all.indices step step).toMutableList()
            if (kept.last() != all.lastIndex) kept += all.lastIndex
            val total = stats.distanceMeters.coerceAtLeast(1.0)
            fun normalized(value: (Int) -> Double?, low: Double?, high: Double?, invert: Boolean): FloatArray {
                val raw = kept.map { i -> value(i)?.let { v -> if (low == null || high == null || high <= low) 0.5 else (v - low) / (high - low) } }
                // Les trous (arrêt, capteur absent) prennent la valeur voisine.
                val filled = FloatArray(raw.size)
                var last = raw.firstOrNull { it != null } ?: 0.5
                raw.forEachIndexed { k, v ->
                    if (v != null) last = v
                    val c = last.coerceIn(0.0, 1.0).toFloat()
                    filled[k] = if (invert) 1f - c else c
                }
                return filled
            }
            val values = mapOf(
                // Allure : une valeur plus petite est plus rapide, donc plus haut dans le dégradé.
                RouteMetric.Pace to normalized({ all[it].paceSecPerKm }, stats.fastestPace, stats.slowestPace, invert = true),
                RouteMetric.Heart to normalized({ all[it].heartRate?.toDouble() }, stats.minHr?.toDouble(), stats.maxHr?.toDouble(), invert = false),
                RouteMetric.Altitude to normalized({ all[it].altitude }, stats.minAltitude, stats.maxAltitude, invert = false),
            )
            return RouteGeometry(
                xs = DoubleArray(kept.size) { Mercator.x(all[kept[it]].longitude) },
                ys = DoubleArray(kept.size) { Mercator.y(all[kept[it]].latitude) },
                fractions = FloatArray(kept.size) { (all[kept[it]].distanceMeters / total).toFloat() },
                values = values,
                kmMarkers = stats.kmMarkers.map { marker -> kept.indices.minBy { kotlin.math.abs(kept[it] - marker) } },
            )
        }
    }
}

/** Vue de la carte : centre en coordonnées Web Mercator et niveau de zoom (fractionnaire). */
@Stable
class MapCamera {
    var x by mutableDoubleStateOf(0.5)
    var y by mutableDoubleStateOf(0.5)
    var zoom by mutableFloatStateOf(2f)

    /** Vrai tant que la vue suit le cadrage du tracé (pas encore déplacée à la main). */
    var following by mutableStateOf(true)

    fun set(x: Double, y: Double, zoom: Float) {
        this.x = x
        this.y = y
        this.zoom = zoom
    }
}

/** Marges (px) à laisser autour du tracé quand on le cadre. */
data class FitPadding(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Centre et zoom qui font tenir tout le tracé dans [size], marges comprises. */
fun fitCamera(geometry: RouteGeometry, size: IntSize, tilePx: Float, padding: FitPadding, maxZoom: Int): Triple<Double, Double, Float> {
    val width = (size.width - padding.left - padding.right).coerceAtLeast(1f)
    val height = (size.height - padding.top - padding.bottom).coerceAtLeast(1f)
    val dx = (geometry.maxX - geometry.minX).coerceAtLeast(1e-9)
    val dy = (geometry.maxY - geometry.minY).coerceAtLeast(1e-9)
    val zoom = (ln(min(width / (dx * tilePx), height / (dy * tilePx))) / ln(2.0)).toFloat().coerceIn(2f, maxZoom.toFloat())
    val world = tilePx * 2.0.pow(zoom.toDouble())
    // Le centre du tracé va au centre de la zone utile, décalée par les marges.
    val cx = (geometry.minX + geometry.maxX) / 2 - (padding.left - padding.right) / 2 / world
    val cy = (geometry.minY + geometry.maxY) / 2 - (padding.top - padding.bottom) / 2 / world
    return Triple(cx, cy, zoom)
}

/** Taille d'une tuile à l'écran au zoom entier : 256 dp. */
@Composable
fun rememberTilePx(): Float = with(LocalDensity.current) { 256.dp.toPx() }

/** Animation de la carte, lue au moment du dessin pour ne pas recomposer à chaque image. */
class RouteMapAnimation(
    /** Part du tracé dessinée (0 à 1, par distance). */
    val draw: () -> Float = { 1f },
    /** Échelle des repères de départ et d'arrivée (rebond). */
    val start: () -> Float = { 1f },
    val finish: () -> Float = { 1f },
    /** Opacité des repères de kilomètre. */
    val kmMarkers: () -> Float = { 0f },
    /** Position du point de relecture (fraction de la distance), `null` sans relecture. */
    val runner: () -> Float? = { null },
    /** Phase (0 à 1) du halo du point de relecture. */
    val halo: () -> Float = { 0f },
)

/**
 * Fond de carte OpenStreetMap et tracé coloré selon [metric] : sous-trait blanc, dégradé point par point (fondu de
 * 450 ms quand la métrique change), repères de départ, d'arrivée et de kilomètre, point de relecture. Avec
 * [interactive], la carte se déplace et se zoome aux doigts.
 */
@Composable
fun RouteMap(
    geometry: RouteGeometry,
    metric: RouteMetric,
    style: MapStyle,
    tiles: TileCache,
    camera: MapCamera,
    contentDescription: String,
    modifier: Modifier = Modifier,
    animation: RouteMapAnimation = RouteMapAnimation(),
    interactive: Boolean = false,
    fitPadding: (IntSize) -> FitPadding = { FitPadding(0f, 0f, 0f, 0f) },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val tilePx = rememberTilePx()
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    // Cadrage automatique tant que l'utilisateur n'a pas bougé la carte, et à chaque changement de taille : le
    // tracé grandit avec la carte pendant la transformation vers le plein écran.
    fun fit(to: IntSize) {
        if (camera.following && to.width > 0 && to.height > 0) {
            val (x, y, z) = fitCamera(geometry, to, tilePx, fitPadding(to), style.maxZoom)
            camera.set(x, y, z)
        }
    }
    LaunchedEffect(camera.following, geometry, style) { fit(size) }

    // Fondu des couleurs d'une métrique à l'autre.
    val palette = remember(geometry) {
        RouteMetric.entries.associateWith { m -> geometry.values(m).map { m.gradient.at(it) } }
    }
    val blend = remember { Animatable(1f) }
    var from by remember { mutableStateOf(metric) }
    var to by remember { mutableStateOf(metric) }
    LaunchedEffect(metric) {
        if (metric != to) {
            from = if (blend.value < 0.5f) from else to
            to = metric
            blend.snapTo(0f)
            blend.animateTo(1f, tween(450))
        }
    }

    // Tuiles visibles (et leurs parentes, pour combler pendant le chargement).
    val tileZoom by remember(style) { derivedStateOf { camera.zoom.roundToInt().coerceIn(1, style.maxZoom) } }
    val visible by remember(style) {
        derivedStateOf { if (size == IntSize.Zero) emptyList() else visibleTiles(camera, size, tilePx, tileZoom, style) }
    }
    val owner = remember { Any() }
    LaunchedEffect(visible) {
        val parents = visible.map { it.copy(zoom = it.zoom - 1, x = it.x / 2, y = it.y / 2) }.distinct()
        tiles.request(owner, parents + visible)
    }
    DisposableEffect(tiles) { onDispose { tiles.release(owner) } }

    val measurer = rememberTextMeasurer()
    val kmStyle = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = RouteColors.OnSurface)

    Box(
        modifier
            .background(RouteColors.MapGround)
            .onSizeChanged {
                size = it
                fit(it)
            }
            .then(
                if (interactive) {
                    Modifier.pointerInput(camera, tilePx) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            camera.following = false
                            val before = screenToWorld(camera, centroid, size, tilePx)
                            camera.zoom = (camera.zoom + (ln(zoomChange.toDouble()) / ln(2.0)).toFloat()).coerceIn(2f, style.maxZoom.toFloat())
                            val after = screenToWorld(camera, centroid, size, tilePx)
                            val world = tilePx * 2.0.pow(camera.zoom.toDouble())
                            camera.x = (camera.x + before.first - after.first - pan.x / world).coerceIn(0.0, 1.0)
                            camera.y = (camera.y + before.second - after.second - pan.y / world).coerceIn(0.0, 1.0)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription }) {
            val world = tilePx * 2.0.pow(camera.zoom.toDouble())
            fun sx(x: Double) = ((x - camera.x) * world + this.size.width / 2).toFloat()
            fun sy(y: Double) = ((y - camera.y) * world + this.size.height / 2).toFloat()

            drawTiles(visible, tiles, sx = ::sx, sy = ::sy, world = world)

            val n = geometry.size
            val drawn = animation.draw().coerceIn(0f, 1f)
            val last = lastDrawnIndex(geometry, drawn)
            val ends = endPoint(geometry, drawn, ::sx, ::sy)
            if (last >= 0) {
                val under = Path().apply {
                    moveTo(sx(geometry.xs[0]), sy(geometry.ys[0]))
                    for (i in 1..last) lineTo(sx(geometry.xs[i]), sy(geometry.ys[i]))
                    if (last < n - 1) lineTo(ends.x, ends.y)
                }
                drawPath(under, Color.White, style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val fromColors = palette.getValue(from)
                val toColors = palette.getValue(to)
                val t = blend.value
                val width = 5.5.dp.toPx()
                for (i in 1..min(last + 1, n - 1)) {
                    val end = if (i <= last) Offset(sx(geometry.xs[i]), sy(geometry.ys[i])) else ends
                    drawLine(
                        color = lerp(fromColors[i], toColors[i], t),
                        start = Offset(sx(geometry.xs[i - 1]), sy(geometry.ys[i - 1])),
                        end = end,
                        strokeWidth = width,
                        cap = StrokeCap.Round,
                    )
                }
            }

            val kmAlpha = animation.kmMarkers()
            if (kmAlpha > 0f) {
                geometry.kmMarkers.forEachIndexed { k, i ->
                    val c = Offset(sx(geometry.xs[i]), sy(geometry.ys[i]))
                    drawCircle(Color.White.copy(alpha = kmAlpha), 8.dp.toPx(), c)
                    drawCircle(RouteColors.OnSurface.copy(alpha = kmAlpha), 8.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                    val label = measurer.measure((k + 1).toString(), kmStyle.copy(color = kmStyle.color.copy(alpha = kmAlpha)))
                    drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
                }
            }

            val startScale = animation.start()
            if (startScale > 0f) {
                val c = Offset(sx(geometry.xs[0]), sy(geometry.ys[0]))
                drawCircle(Color.White, 10.dp.toPx() * startScale, c)
                drawCircle(RouteColors.Start, 6.5.dp.toPx() * startScale, c)
            }
            val finishScale = animation.finish()
            if (finishScale > 0f) {
                val c = Offset(sx(geometry.xs[n - 1]), sy(geometry.ys[n - 1]))
                drawCircle(Color.White, 12.dp.toPx() * finishScale, c)
                drawCircle(RouteColors.Finish, 9.5.dp.toPx() * finishScale, c)
                scale(finishScale * density.density, pivot = c) {
                    // Drapeau du repère d'arrivée (coordonnées de la maquette, centrées sur le repère).
                    val flag = Path().apply {
                        moveTo(c.x - 3.5f, c.y + 5f)
                        lineTo(c.x - 3.5f, c.y - 4.5f)
                        lineTo(c.x + 2.5f, c.y - 4.5f)
                        lineTo(c.x + 1.3f, c.y - 2.1f)
                        lineTo(c.x + 2.5f, c.y + 0.3f)
                        lineTo(c.x - 3.5f, c.y + 0.3f)
                    }
                    drawPath(flag, Color.White, style = Stroke(1.6f, join = StrokeJoin.Round))
                }
            }

            animation.runner()?.let { f ->
                val c = endPoint(geometry, f.coerceIn(0f, 1f), ::sx, ::sy)
                val phase = animation.halo()
                val r = 10.dp.toPx()
                drawCircle(RouteColors.Primary.copy(alpha = 0.55f * (1 - phase)), r * (1 + 1.6f * phase), c)
                drawCircle(Color.Black.copy(alpha = 0.18f), r + 1.dp.toPx(), c + Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White, r, c)
                drawCircle(RouteColors.Primary, r - 3.dp.toPx(), c)
            }
        }
        overlay()
    }
}

/** Mention obligatoire des sources du fond de carte. */
@Composable
fun MapAttribution(style: MapStyle, modifier: Modifier = Modifier) {
    Text(
        style.attribution,
        style = TextStyle(fontSize = 9.sp, color = RouteColors.OnSurfaceVariant),
        modifier = modifier
            .background(RouteColors.Background.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

internal fun screenToWorld(camera: MapCamera, point: Offset, size: IntSize, tilePx: Float): Pair<Double, Double> {
    val world = tilePx * 2.0.pow(camera.zoom.toDouble())
    return (camera.x + (point.x - size.width / 2.0) / world) to (camera.y + (point.y - size.height / 2.0) / world)
}

internal fun visibleTiles(camera: MapCamera, size: IntSize, tilePx: Float, zoom: Int, style: MapStyle): List<TileKey> {
    val count = 1 shl zoom
    val world = tilePx * 2.0.pow(camera.zoom.toDouble())
    val left = camera.x - size.width / 2.0 / world
    val right = camera.x + size.width / 2.0 / world
    val top = camera.y - size.height / 2.0 / world
    val bottom = camera.y + size.height / 2.0 / world
    val x0 = floor(left * count).toInt()
    val x1 = floor(right * count).toInt()
    val y0 = floor(top * count).toInt().coerceAtLeast(0)
    val y1 = floor(bottom * count).toInt().coerceAtMost(count - 1)
    if (x1 - x0 > 16 || y1 - y0 > 16) return emptyList()
    return buildList {
        for (y in y0..y1) for (x in x0..x1) add(TileKey(style, zoom, x, y))
    }
}

internal fun DrawScope.drawTiles(keys: List<TileKey>, tiles: TileCache, sx: (Double) -> Float, sy: (Double) -> Float, world: Double) {
    keys.forEach { key ->
        val count = (1 shl key.zoom).toDouble()
        val left = sx(key.x / count)
        val top = sy(key.y / count)
        val right = sx((key.x + 1) / count)
        val bottom = sy((key.y + 1) / count)
        val dstOffset = IntOffset(floor(left).toInt(), floor(top).toInt())
        val dstSize = IntSize(floor(right).toInt() - dstOffset.x, floor(bottom).toInt() - dstOffset.y)
        // Les tuiles de part et d'autre de l'antiméridien reprennent les mêmes images.
        val wrapped = key.copy(x = Math.floorMod(key.x, 1 shl key.zoom))
        val image = tiles.get(wrapped)
        if (image != null) {
            drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), dstOffset, dstSize, filterQuality = FilterQuality.Low)
        } else {
            // En attendant la tuile, le quart correspondant de sa tuile parente, agrandi.
            val parent = tiles.get(wrapped.copy(zoom = key.zoom - 1, x = wrapped.x / 2, y = key.y / 2)) ?: return@forEach
            val half = IntSize(parent.width / 2, parent.height / 2)
            val src = IntOffset((wrapped.x % 2) * half.width, (key.y % 2) * half.height)
            drawImage(parent, src, half, dstOffset, dstSize, filterQuality = FilterQuality.Low)
        }
    }
}

/** Dernier point entièrement dessiné quand [fraction] de la distance est tracée. */
private fun lastDrawnIndex(geometry: RouteGeometry, fraction: Float): Int {
    if (fraction <= 0f) return -1
    var lo = 0
    var hi = geometry.size - 1
    if (geometry.fractions[hi] <= fraction) return hi
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (geometry.fractions[mid] <= fraction) lo = mid else hi = mid
    }
    return lo
}

/** Point du tracé à [fraction] de la distance, interpolé entre deux points. */
private fun endPoint(geometry: RouteGeometry, fraction: Float, sx: (Double) -> Float, sy: (Double) -> Float): Offset {
    val i = lastDrawnIndex(geometry, fraction).coerceAtLeast(0)
    if (i >= geometry.size - 1) return Offset(sx(geometry.xs.last()), sy(geometry.ys.last()))
    val fa = geometry.fractions[i]
    val fb = geometry.fractions[i + 1]
    val t = if (fb > fa) ((fraction - fa) / (fb - fa)).coerceIn(0f, 1f) else 0f
    val x = geometry.xs[i] + (geometry.xs[i + 1] - geometry.xs[i]) * t
    val y = geometry.ys[i] + (geometry.ys[i + 1] - geometry.ys[i]) * t
    return Offset(sx(x), sy(y))
}

/**
 * Fond de carte stylisé de la maquette (bois, rivière, rues), affiché flou tant qu'un tracé n'est pas autorisé :
 * l'emplacement réel n'est pas encore connu.
 */
@Composable
fun StylizedMap(modifier: Modifier = Modifier) {
    val shapes = remember {
        listOf(
            "M100 40C140 20 220 18 270 50S280 170 230 180S120 190 100 150S80 60 100 40Z",
        ).map { PathParser().parsePathString(it).toPath() }
    }
    val river = remember { PathParser().parsePathString("M-10 250C80 220 160 214 240 204S330 180 370 170").toPath() }
    val roads = remember {
        listOf("M0 110L358 80", "M60 0L90 264", "M290 0L260 264").map { PathParser().parsePathString(it).toPath() }
    }
    Canvas(modifier.background(RouteColors.MapGround)) {
        // Dessin de la maquette (358 × 264), étiré à la taille de la carte.
        scale(size.width / 358f, size.height / 264f, pivot = Offset.Zero) {
            shapes.forEach { drawPath(it, Color(0xFFD5EAD0)) }
            drawPath(river, Color(0xFFBCD5EE), style = Stroke(18f))
            roads.forEach { drawPath(it, Color.White, style = Stroke(7f, cap = StrokeCap.Round)) }
        }
    }
}
