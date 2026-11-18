package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.Exploration
import com.boucle.app.domain.ExploredGrid
import com.boucle.app.domain.MercatorBounds
import com.boucle.app.ui.ExplorationUiState
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.components.rememberReducedMotion
import com.boucle.app.ui.map.MapAttribution
import com.boucle.app.ui.map.MapCamera
import com.boucle.app.ui.map.MapStyle
import com.boucle.app.ui.map.TileCache
import com.boucle.app.ui.map.drawTiles
import com.boucle.app.ui.map.rememberTileCache
import com.boucle.app.ui.map.rememberTilePx
import com.boucle.app.ui.map.screenToWorld
import com.boucle.app.ui.map.visibleTiles
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.RouteColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** France métropolitaine, pour la vue d'ensemble. */
private val FRANCE = MercatorBounds(
    minX = Exploration.mercatorX(-5.3),
    minY = Exploration.mercatorY(51.2),
    maxX = Exploration.mercatorX(9.7),
    maxY = Exploration.mercatorY(41.2),
)

private const val MIN_ZOOM = 3f

/** Zoom d'ouverture au plus près : les carrés y font 16 dp. */
private const val HOME_ZOOM = 13f

/** Centre et zoom qui font tenir [bounds] dans la carte, au-dessus du panneau du bas ([bottomInset] px). */
private fun cameraFor(bounds: MercatorBounds, size: IntSize, tilePx: Float, bottomInset: Float, maxZoom: Float): Triple<Double, Double, Float> {
    val margin = 0.12f
    val width = (size.width * (1 - 2 * margin)).coerceAtLeast(1f)
    val height = ((size.height - bottomInset) * (1 - 2 * margin)).coerceAtLeast(1f)
    val dx = (bounds.maxX - bounds.minX).coerceAtLeast(1e-9)
    val dy = (bounds.maxY - bounds.minY).coerceAtLeast(1e-9)
    val zoom = (ln(min(width / (dx * tilePx), height / (dy * tilePx))) / ln(2.0)).toFloat().coerceIn(MIN_ZOOM, maxZoom)
    val world = tilePx * 2.0.pow(zoom.toDouble())
    return Triple((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2 + bottomInset / 2 / world, zoom)
}

/**
 * Carte d'exploration : fond OpenStreetMap et, par-dessus, les carrés de 200 m traversés par tous les tracés GPS ; le
 * panneau du bas en donne le nombre.
 */
@Composable
fun ExplorationScreen(state: ExplorationUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tiles = rememberTileCache()
    val tilePx = rememberTilePx()
    val camera = remember { MapCamera() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val reducedMotion = rememberReducedMotion()
    var style by remember { mutableStateOf(MapStyle.Plan) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var panelHeight by remember { mutableStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf<Job?>(null) }
    val grid = state.grid

    /** Vue d'ouverture : la zone de la dernière activité ; la France sans aucun tracé. */
    fun home(): Triple<Double, Double, Float> =
        cameraFor(grid.focus ?: FRANCE, size, tilePx, panelHeight.toFloat(), if (grid.focus != null) HOME_ZOOM else 6f)

    fun moveTo(target: Triple<Double, Double, Float>) {
        val (x, y, z) = target
        val from = Triple(camera.x, camera.y, camera.zoom)
        move?.cancel()
        move = scope.launch {
            Animatable(0f).animateTo(1f, tween(if (reducedMotion) 0 else 600, easing = FastOutSlowInEasing)) {
                camera.set(from.first + (x - from.first) * value, from.second + (y - from.second) * value, from.third + (z - from.third) * value)
            }
        }
    }

    // La carte s'ouvre sur la France, puis se place sur la dernière activité dès que les tracés sont lus.
    LaunchedEffect(size, panelHeight, state.loaded) {
        if (placed || size == IntSize.Zero || panelHeight == 0) return@LaunchedEffect
        if (state.loaded) {
            home().let { (x, y, z) -> camera.set(x, y, z) }
            placed = true
        } else {
            cameraFor(FRANCE, size, tilePx, panelHeight.toFloat(), 6f).let { (x, y, z) -> camera.set(x, y, z) }
        }
    }

    Box(modifier.onSizeChanged { size = it }) {
        ExplorationMap(
            grid = grid,
            style = style,
            tiles = tiles,
            camera = camera,
            tilePx = tilePx,
            onGesture = { move?.cancel() },
            contentDescription = "Carte d'exploration : ${formatThousands(grid.count)} carrés de ${Exploration.CELL_SIZE} explorés. " +
                "Déplacez et zoomez avec deux doigts.",
            modifier = Modifier.fillMaxSize(),
        )

        Box(Modifier.align(Alignment.TopStart).padding(16.dp)) {
            RoundMapButton(BoucleIcons.ArrowBack, "Retour", RoundedCornerShape(24.dp), onClick = onBack)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundMapButton(BoucleIcons.Layers, "Type de carte : ${style.label}", RoundedCornerShape(16.dp)) {
                haptics.select()
                style = if (style == MapStyle.Plan) MapStyle.Relief else MapStyle.Plan
            }
            RoundMapButton(BoucleIcons.Recenter, "Revenir à la dernière activité", RoundedCornerShape(16.dp)) {
                haptics.select()
                moveTo(home())
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            MapAttribution(style, Modifier.padding(start = 12.dp, bottom = 8.dp))
            ExplorationPanel(state, Modifier.onSizeChanged { panelHeight = it.height })
        }
    }
}

@Composable
private fun ExplorationMap(
    grid: ExploredGrid,
    style: MapStyle,
    tiles: TileCache,
    camera: MapCamera,
    tilePx: Float,
    onGesture: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
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

    Box(
        modifier
            .background(RouteColors.MapGround)
            .onSizeChanged { size = it }
            .pointerInput(camera, tilePx, style) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    onGesture()
                    val before = screenToWorld(camera, centroid, size, tilePx)
                    camera.zoom = (camera.zoom + (ln(zoomChange.toDouble()) / ln(2.0)).toFloat()).coerceIn(MIN_ZOOM, style.maxZoom.toFloat())
                    val after = screenToWorld(camera, centroid, size, tilePx)
                    val world = tilePx * 2.0.pow(camera.zoom.toDouble())
                    camera.x = (camera.x + before.first - after.first - pan.x / world).coerceIn(0.0, 1.0)
                    camera.y = (camera.y + before.second - after.second - pan.y / world).coerceIn(0.0, 1.0)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription }) {
            val world = tilePx * 2.0.pow(camera.zoom.toDouble())
            fun sx(x: Double) = ((x - camera.x) * world + this.size.width / 2).toFloat()
            fun sy(y: Double) = ((y - camera.y) * world + this.size.height / 2).toFloat()
            drawTiles(visible, tiles, sx = ::sx, sy = ::sy, world = world)
            drawSquares(grid.cells, camera, world)
        }
    }
}

/**
 * Carrés explorés et, quand ils sont assez grands, le quadrillage des carrés qui restent à remplir. Vus de loin, les
 * carrés gardent une taille minimale : les zones explorées restent visibles à l'échelle d'une région ou du pays.
 */
private fun DrawScope.drawSquares(cells: LongArray, camera: MapCamera, world: Double) {
    val n = (1 shl Exploration.CELL_ZOOM).toDouble()
    val cell = (world / n).toFloat()
    // Position à l'écran du bord gauche (haut) de la colonne (ligne) d'indice donné.
    fun left(x: Int) = ((x / n - camera.x) * world + size.width / 2).toFloat()
    fun top(y: Int) = ((y / n - camera.y) * world + size.height / 2).toFloat()

    if (cell >= 28.dp.toPx()) {
        val line = RouteColors.OnSurfaceVariant.copy(alpha = 0.28f)
        val x0 = floor((camera.x - size.width / 2 / world) * n).toInt()
        val x1 = ceil((camera.x + size.width / 2 / world) * n).toInt()
        val y0 = floor((camera.y - size.height / 2 / world) * n).toInt()
        val y1 = ceil((camera.y + size.height / 2 / world) * n).toInt()
        for (x in x0..x1) left(x).let { drawLine(line, Offset(it, 0f), Offset(it, size.height), 1.dp.toPx()) }
        for (y in y0..y1) top(y).let { drawLine(line, Offset(0f, it), Offset(size.width, it), 1.dp.toPx()) }
    }

    val border = RouteColors.Primary.copy(alpha = 0.85f)
    val gap = if (cell >= 6.dp.toPx()) 1.dp.toPx() else 0f
    val outlined = cell >= 12.dp.toPx()
    // Taille dessinée : jamais moins de 3 dp, centrée sur le carré.
    val drawn = max(cell - gap, 3.dp.toPx())
    val inset = (cell - drawn) / 2
    // De loin, les carrés se recouvrent : une couleur pleine garde une teinte égale.
    val fill = if (cell >= 3.dp.toPx()) RouteColors.Primary.copy(alpha = 0.42f) else RouteColors.Primary
    val side = Size(drawn, drawn)
    for (key in cells) {
        val x = left(Exploration.cellX(key)) + inset
        if (x > size.width || x + drawn < 0f) continue
        val y = top(Exploration.cellY(key)) + inset
        if (y > size.height || y + drawn < 0f) continue
        val corner = Offset(x, y)
        drawRect(fill, corner, side)
        if (outlined) drawRect(border, corner, side, style = Stroke(1.dp.toPx()))
    }
}

/** Panneau du bas : nombre de carrés explorés et de tracés GPS. */
@Composable
private fun ExplorationPanel(state: ExplorationUiState, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(RouteColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Carrés explorés", style = BoucleType.sectionTitle, color = RouteColors.OnSurface, modifier = Modifier.alignByBaseline())
            if (state.loaded && state.routes > 0) {
                Text(
                    if (state.routes == 1) "1 tracé GPS" else "${formatThousands(state.routes)} tracés GPS",
                    style = BoucleType.small,
                    color = RouteColors.OnSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (state.loaded && state.grid.isEmpty) {
            Text(
                "Aucun tracé GPS pour l'instant. Lancez une activité avec le GPS : chaque carré de ${Exploration.CELL_SIZE} traversé se colorera ici.",
                style = BoucleType.small,
                color = RouteColors.OnSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (state.loaded) formatThousands(state.grid.count) else "—",
                    style = BoucleType.detailTitle,
                    color = RouteColors.Primary,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    "carrés de ${Exploration.CELL_SIZE}",
                    style = BoucleType.small,
                    color = RouteColors.OnSurfaceVariant,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}
