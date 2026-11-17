package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.RouteStats
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.map.FitPadding
import com.boucle.app.ui.map.MapAttribution
import com.boucle.app.ui.map.MapCamera
import com.boucle.app.ui.map.MapStyle
import com.boucle.app.ui.map.RouteGeometry
import com.boucle.app.ui.map.RouteMap
import com.boucle.app.ui.map.RouteMapAnimation
import com.boucle.app.ui.map.RouteMetric
import com.boucle.app.ui.map.TileCache
import com.boucle.app.ui.map.fitCamera
import com.boucle.app.ui.map.rememberTilePx
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.RouteColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rebond des boutons qui apparaissent (cubic-bezier(0.34, 1.6, 0.5, 1) de la maquette). */
private val PopEasing = CubicBezierEasing(0.34f, 1.6f, 0.5f, 1f)

/** Durée de la relecture du parcours. */
private const val REPLAY_MS = 6_000

/** Hauteur du panneau du bas et position de la barre d'outils au-dessus. */
private val SHEET_HEIGHT = 264.dp
private val TOOLBAR_BOTTOM = 280.dp

/**
 * Contenu de la carte en plein écran : carte interactive (déplacement, zoom), repères de kilomètres, boutons fermer
 * et type de carte, barre d'outils flottante (Rejouer, Recentrer, Partager) et panneau du bas. [progress] est la
 * transformation depuis la carte du détail (0 = carte, 1 = plein écran) : elle règle les marges du cadrage.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RouteFullScreen(
    title: String,
    hours: String?,
    stats: RouteStats,
    geometry: RouteGeometry,
    metric: RouteMetric,
    style: MapStyle,
    tiles: TileCache,
    camera: MapCamera,
    open: Boolean,
    reducedMotion: Boolean,
    progress: () -> Float,
    onClose: () -> Unit,
    onToggleStyle: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tilePx = rememberTilePx()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()

    val replay = remember { Animatable(0f) }
    var replaying by remember { mutableStateOf(false) }
    val halo = rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "haloPhase",
    )
    val kmMarkers = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) kmMarkers.animateTo(1f, tween(if (reducedMotion) 0 else 300, delayMillis = if (reducedMotion) 0 else 350)) else kmMarkers.snapTo(0f)
    }

    fun padding(size: IntSize): FitPadding {
        val p = progress().coerceIn(0f, 1f)
        with(density) {
            val small = 24.dp.toPx()
            val side = small + (40.dp.toPx() - small) * p
            val top = small + (88.dp.toPx() - small) * p
            val bottom = small + ((TOOLBAR_BOTTOM + 80.dp).toPx() - small) * p
            // Un écran trop bas pour les marges du plein écran garde les marges de la carte.
            return if (size.height - top - bottom < 120.dp.toPx()) FitPadding(small, small, small, small) else FitPadding(side, top, side, bottom)
        }
    }

    fun recenter(size: IntSize) {
        val (x, y, z) = fitCamera(geometry, size, tilePx, padding(size), style.maxZoom)
        val from = Triple(camera.x, camera.y, camera.zoom)
        scope.launch {
            val t = Animatable(0f)
            t.animateTo(1f, tween(if (reducedMotion) 0 else 550, easing = ExpressiveSpatial)) {
                camera.set(
                    from.first + (x - from.first) * value,
                    from.second + (y - from.second) * value,
                    from.third + (z - from.third) * value,
                )
            }
            camera.following = true
        }
    }

    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    val runner: () -> Float? = { if (replaying || replay.value > 0f) replay.value else null }
    // Taille de la carte, pour recentrer.
    Box(modifier.onSizeChanged { mapSize = it }) {
        RouteMap(
            geometry = geometry,
            metric = metric,
            style = style,
            tiles = tiles,
            camera = camera,
            contentDescription = "Carte du parcours en plein écran : ${RouteFormat.km(stats.distanceMeters)} km, " +
                "${geometry.kmMarkers.size} repères de kilomètre. Déplacez et zoomez avec deux doigts.",
            modifier = Modifier.fillMaxSize(),
            animation = RouteMapAnimation(kmMarkers = { kmMarkers.value }, runner = runner, halo = { if (reducedMotion) 0.5f else halo.value }),
            interactive = open,
            fitPadding = ::padding,
        ) {
            Box(Modifier.fillMaxSize().padding(bottom = SHEET_HEIGHT + 8.dp, start = 12.dp), contentAlignment = Alignment.BottomStart) {
                MapAttribution(style)
            }
        }

        PopIn(open, delay = 300, reducedMotion, Modifier.align(Alignment.TopStart).padding(16.dp)) {
            RoundMapButton(BoucleIcons.Close, "Fermer la carte", RoundedCornerShape(24.dp), onClick = onClose)
        }
        PopIn(open, delay = 380, reducedMotion, Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            RoundMapButton(BoucleIcons.Layers, "Type de carte : ${style.label}", RoundedCornerShape(16.dp)) {
                haptics.select()
                onToggleStyle()
            }
        }

        PopIn(open, delay = 450, reducedMotion, Modifier.align(Alignment.BottomCenter).padding(bottom = TOOLBAR_BOTTOM)) {
            // Barre d'outils flottante d'Expressive : action principale en pilule, actions secondaires en icônes.
            Row(
                Modifier
                    .shadow(6.dp, RoundedCornerShape(32.dp))
                    .background(RouteColors.PrimaryContainer, RoundedCornerShape(32.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        haptics.tap()
                        scope.launch {
                            replaying = true
                            replay.snapTo(0f)
                            replay.animateTo(1f, tween(if (reducedMotion) 0 else REPLAY_MS, delayMillis = if (reducedMotion) 0 else 300, easing = FastOutSlowInEasing))
                            replaying = false
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = RouteColors.Primary, contentColor = RouteColors.OnPrimary),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(BoucleIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Rejouer", Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                }
                IconButton(onClick = { haptics.tap(); recenter(mapSize) }, modifier = Modifier.size(48.dp)) {
                    Icon(BoucleIcons.Recenter, contentDescription = "Recentrer", tint = RouteColors.OnPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
                    Icon(BoucleIcons.Share, contentDescription = "Partager le tracé", tint = RouteColors.OnPrimaryContainer, modifier = Modifier.size(22.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = open,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(if (reducedMotion) 0 else 600, delayMillis = if (reducedMotion) 0 else 200, easing = ExpressiveSpatial)) { it },
            exit = slideOutVertically(tween(if (reducedMotion) 0 else 250)) { it } + fadeOut(tween(150)),
        ) {
            BottomPanel(title, hours, stats, replay = { replay.value })
        }
    }
}

/** Panneau du bas : titre, heures, progression de la relecture, allure, FC et dénivelé. */
@Composable
private fun BottomPanel(title: String, hours: String?, stats: RouteStats, replay: () -> Float) {
    Surface(
        color = RouteColors.Container,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().height(SHEET_HEIGHT),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .background(RouteColors.OnSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(title, Modifier.weight(1f, fill = false), style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = RouteColors.OnSurface, maxLines = 1)
                hours?.let { Text(it, Modifier.padding(start = 12.dp), style = TextStyle(fontSize = 14.sp), color = RouteColors.OnSurfaceVariant) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Barre linéaire (pas ondulée), synchronisée avec le point qui parcourt le tracé.
                LinearProgressIndicator(
                    progress = replay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .semantics { contentDescription = "Progression de la relecture du parcours" },
                    color = RouteColors.Primary,
                    trackColor = RouteColors.PrimaryContainer,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                DistanceAxis(stats)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Allure moy.", RouteFormat.pace(stats.averagePace), "/km"),
                    Triple("FC moy.", stats.averageHr?.toString() ?: "—", "bpm"),
                    Triple("Dénivelé", stats.gainMeters?.let { "+${it.roundToInt()}" } ?: "—", "m"),
                ).forEach { (label, value, unit) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .background(RouteColors.Background, RoundedCornerShape(20.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(label, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
                        Text(value, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = RouteColors.OnSurface)
                        Text(unit, style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Bouton rond posé sur la carte (48 dp, fond clair, ombre). */
@Composable
internal fun RoundMapButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = RouteColors.Background,
        contentColor = RouteColors.OnSurface,
        shadowElevation = 3.dp,
        modifier = Modifier.size(size).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (size < 48.dp) 20.dp else 22.dp))
        }
    }
}

/** Apparition d'un contrôle avec un rebond, [delay] ms après l'ouverture. */
@Composable
internal fun PopIn(visible: Boolean, delay: Int, reducedMotion: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) fadeIn(tween(0)) else scaleIn(tween(420, delayMillis = delay, easing = PopEasing), initialScale = 0.5f) + fadeIn(tween(200, delayMillis = delay)),
        exit = fadeOut(tween(120)),
    ) {
        content()
    }
}
