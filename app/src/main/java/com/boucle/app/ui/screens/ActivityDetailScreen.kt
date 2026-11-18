package com.boucle.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RouteSource
import com.boucle.app.domain.RouteStats
import com.boucle.app.domain.RouteStatus
import com.boucle.app.domain.Vo2MaxEstimation
import com.boucle.app.ui.RouteAccess
import com.boucle.app.ui.SessionDetailUiState
import com.boucle.app.ui.PHONE_SESSION_PREFIX
import com.boucle.app.domain.DetectedActivity
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.cookieShape
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.components.rememberReducedMotion
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.map.FitPadding
import com.boucle.app.ui.map.MapAttribution
import com.boucle.app.ui.map.MapCamera
import com.boucle.app.ui.map.MapStyle
import com.boucle.app.ui.map.RouteGeometry
import com.boucle.app.ui.map.RouteMap
import com.boucle.app.ui.map.RouteMapAnimation
import com.boucle.app.ui.map.RouteMetric
import com.boucle.app.ui.map.StylizedMap
import com.boucle.app.ui.map.gradient
import com.boucle.app.ui.map.rememberTileCache
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.RouteColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val headerDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

/** Hauteur de la carte dans le détail, et de la carte floutée à autoriser. */
private val MAP_HEIGHT = 232.dp
private val CONSENT_MAP_HEIGHT = 264.dp
private val MAP_RADIUS = 28.dp

/** Durée de la transformation de la carte vers le plein écran. */
private const val EXPAND_MS = 550

/**
 * Détail d'une séance : en-tête, carte du tracé GPS (ou demande d'accès au tracé), choix de la métrique qui colore le
 * tracé, distance, tuiles de chiffres et contenu de l'onglet choisi. Sans tracé (séance en intérieur), le détail
 * commence par les chiffres. Toucher la carte l'ouvre en plein écran par une transformation de conteneur.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActivityDetailScreen(
    state: SessionDetailUiState,
    onBack: () -> Unit,
    onRequestRoute: () -> Unit,
    onOpenHealthConnect: (() -> Unit)?,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val reducedMotion = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val session = state.session
    val stats = state.stats
    val geometry = remember(stats) { stats?.let(RouteGeometry::from) }
    val tiles = rememberTileCache()
    var metric by rememberSaveable { mutableStateOf(RouteMetric.Pace) }
    var mapStyle by rememberSaveable { mutableStateOf(MapStyle.Plan) }
    val title = session?.name?.takeIf { it.isNotBlank() } ?: "Entraînement"
    val hours = session?.let(::hoursOf)
    val share: () -> Unit = {
        val text = shareText(title, session, stats)
        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)) }
    }

    // Transformation de conteneur carte → plein écran.
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var cardBounds by remember { mutableStateOf<Rect?>(null) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    val expand = remember { Animatable(if (fullScreen) 1f else 0f) }
    val fullCamera = remember { MapCamera() }
    val expandSpec = tween<Float>(if (reducedMotion) 0 else EXPAND_MS, easing = ExpressiveSpatial)
    fun openMap() {
        haptics.tap()
        fullCamera.following = true
        fullScreen = true
        scope.launch { expand.animateTo(1f, expandSpec) }
    }
    fun closeMap() {
        fullScreen = false
        scope.launch { expand.animateTo(0f, expandSpec) }
    }
    BackHandler(enabled = fullScreen) { closeMap() }
    // La carte n'a plus de tracé (données effacées) : on referme le plein écran.
    LaunchedEffect(geometry == null) { if (geometry == null && fullScreen) closeMap() }

    val scroll = rememberScrollState()
    // Tracé autorisé pendant que l'écran est affiché : la carte le dessine puis propose « Voir le détail ».
    var justGranted by rememberSaveable { mutableStateOf(false) }
    var previousStatus by remember { mutableStateOf(state.routeStatus) }
    LaunchedEffect(state.routeStatus) {
        if (previousStatus == RouteStatus.CONSENT_REQUIRED && state.routeStatus == RouteStatus.AVAILABLE) justGranted = true
        previousStatus = state.routeStatus
    }
    var detailTop by remember { mutableStateOf(0f) }
    var columnTop by remember { mutableStateOf(0f) }

    Box(
        modifier
            .background(RouteColors.Background)
            .onGloballyPositioned {
                rootOffset = it.positionInRoot()
                rootSize = it.size
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(
                onBack = onBack,
                onShare = share,
                onEdit = onEdit.takeIf { session != null },
                onOpenHealthConnect = onOpenHealthConnect,
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { columnTop = it.positionInRoot().y }
                    .verticalScroll(scroll, enabled = !fullScreen)
                    .padding(bottom = 32.dp),
            ) {
                if (session == null) {
                    if (state.loaded) {
                        Text(
                            "Cette séance n'existe plus.",
                            Modifier.padding(16.dp),
                            style = TextStyle(fontSize = 15.sp),
                            color = RouteColors.OnSurfaceVariant,
                        )
                    }
                    return@Column
                }
                DetailHeader(title, session, state, reducedMotion, Modifier.staggeredEntrance(0))

                when {
                    state.routeStatus == RouteStatus.AVAILABLE && stats != null && geometry != null -> {
                        MapCard(
                            stats = stats,
                            geometry = geometry,
                            metric = metric,
                            style = mapStyle,
                            tiles = tiles,
                            hidden = fullScreen || expand.value > 0f,
                            reducedMotion = reducedMotion,
                            showDetailButton = justGranted,
                            onOpen = ::openMap,
                            onShowDetail = {
                                scope.launch { scroll.animateScrollTo((scroll.value + detailTop - columnTop).roundToInt().coerceAtLeast(0)) }
                            },
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .onGloballyPositioned { cardBounds = it.boundsInRoot() },
                        )
                        if (state.routeSource == RouteSource.HEALTH_CONNECT) RevokeNote()
                    }
                    state.routeStatus == RouteStatus.CONSENT_REQUIRED -> {
                        ConsentMap(state.access, onRequestRoute)
                        RevokeNote()
                    }
                }
                Column(Modifier.onGloballyPositioned { detailTop = it.positionInRoot().y }) {
                    if (stats != null) {
                        MetricSelector(
                            metric = metric,
                            stats = stats,
                            onSelect = {
                                if (it != metric) haptics.select()
                                metric = it
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).staggeredEntrance(2),
                        )
                    }
                    SessionFigures(session, stats, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp))
                    if (stats != null) {
                        AnimatedContent(
                            targetState = metric,
                            transitionSpec = {
                                val offset = with(density) { 20.dp.roundToPx() }
                                (fadeIn(tween(450, easing = EmphasizedDecelerate)) + slideInVertically(tween(450, easing = EmphasizedDecelerate)) { offset })
                                    .togetherWith(fadeOut(tween(150)))
                                    .using(SizeTransform(clip = false))
                            },
                            label = "metricContent",
                        ) { shown ->
                            Column(
                                Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                when (shown) {
                                    RouteMetric.Pace -> PaceContent(stats)
                                    RouteMetric.Heart -> HeartContent(stats, state)
                                    RouteMetric.Altitude -> AltitudeContent(stats)
                                }
                            }
                        }
                    }
                    SourceNote(session, state, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
                }
            }
        }

        // Carte en plein écran, qui grandit depuis la carte du détail (et y revient à la fermeture).
        val card = cardBounds
        if (stats != null && geometry != null && card != null && (fullScreen || expand.value > 0f)) {
            val p = expand.value
            val left = (card.left - rootOffset.x) * (1 - p)
            val top = (card.top - rootOffset.y) * (1 - p)
            val width = card.width + (rootSize.width - card.width) * p
            val height = card.height + (rootSize.height - card.height) * p
            val radius = lerp(MAP_RADIUS, 0.dp, p.coerceIn(0f, 1f))
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(density) { width.coerceAtLeast(1f).toDp() }, with(density) { height.coerceAtLeast(1f).toDp() })
                    .clip(RoundedCornerShape(radius)),
            ) {
                RouteFullScreen(
                    title = title,
                    hours = hours,
                    stats = stats,
                    geometry = geometry,
                    metric = metric,
                    style = mapStyle,
                    tiles = tiles,
                    camera = fullCamera,
                    open = fullScreen,
                    reducedMotion = reducedMotion,
                    progress = { expand.value },
                    onClose = ::closeMap,
                    onToggleStyle = { mapStyle = if (mapStyle == MapStyle.Plan) MapStyle.Relief else MapStyle.Plan },
                    onShare = share,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Barre du haut : retour, titre, partager, plus d'options. */
@Composable
private fun DetailTopBar(onBack: () -> Unit, onShare: () -> Unit, onEdit: (() -> Unit)?, onOpenHealthConnect: (() -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(BoucleIcons.ArrowBack, contentDescription = "Retour", tint = RouteColors.OnSurface, modifier = Modifier.size(24.dp))
        }
        Text("Activité", Modifier.weight(1f), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = RouteColors.OnSurface)
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(BoucleIcons.Edit, contentDescription = "Modifier l'activité", tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        IconButton(onClick = onShare, modifier = Modifier.size(48.dp)) {
            Icon(BoucleIcons.Share, contentDescription = "Partager", tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                Icon(BoucleIcons.More, contentDescription = "Plus d'options", tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Partager la séance") },
                    leadingIcon = { Icon(BoucleIcons.Share, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        menu = false
                        onShare()
                    },
                )
                if (onOpenHealthConnect != null) {
                    DropdownMenuItem(
                        text = { Text("Accès dans Health Connect") },
                        leadingIcon = { Icon(BoucleIcons.Lock, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            menu = false
                            onOpenHealthConnect()
                        },
                    )
                }
            }
        }
    }
}

/** En-tête : icône du sport dans un cookie qui tourne lentement, titre, date et heures, pastilles de source. */
@Composable
private fun DetailHeader(title: String, session: TrainingSessionEntity, state: SessionDetailUiState, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val rotation = if (reducedMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "cookie").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
            label = "cookieRotation",
        )
    }
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val subtitle = listOfNotNull(
        start?.format(headerDate)?.replaceFirstChar { it.titlecase(Locale.FRENCH) },
        hoursOf(session),
    ).joinToString(" · ")
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val shape = cookieShape()
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer { rotationZ = rotation?.value ?: 0f }
                        .background(RouteColors.PrimaryContainer, shape),
                )
                Icon(sportIcon(state.sport), contentDescription = null, tint = RouteColors.OnPrimaryContainer, modifier = Modifier.size(28.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    Modifier.semantics { heading() },
                    style = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight(750), letterSpacing = (-0.4).sp),
                    color = RouteColors.OnSurface,
                )
                if (subtitle.isNotEmpty()) Text(subtitle, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = RouteColors.OnSurfaceVariant)
            }
        }
        val fromPhone = session.path.startsWith(PHONE_SESSION_PREFIX)
        val detected = session.path.startsWith(DetectedActivity.PATH_PREFIX)
        val fromHealthConnect = session.path.startsWith("healthconnect:") || state.routeSource == RouteSource.HEALTH_CONNECT
        val fromBand = !session.path.startsWith("healthconnect:") && !fromPhone
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fromPhone) SourceChip(BoucleIcons.Phone, "Téléphone")
            if (fromBand) SourceChip(BoucleIcons.Band, if (detected) "Détectée" else "Bracelet")
            state.terrain?.let { SourceChip(BoucleIcons.Mountain, it.label) }
            if (fromHealthConnect) SourceChip(BoucleIcons.Refresh, "Health Connect")
            if (state.routeStatus == RouteStatus.AVAILABLE) SourceChip(BoucleIcons.Pin, "GPS", filled = true)
        }
    }
}

@Composable
private fun SourceChip(icon: ImageVector, label: String, filled: Boolean = false) {
    Row(
        Modifier
            .height(32.dp)
            .then(
                if (filled) {
                    Modifier.background(RouteColors.SecondaryContainer, RoundedCornerShape(8.dp))
                } else {
                    Modifier.border(1.dp, RouteColors.Outline, RoundedCornerShape(8.dp))
                },
            )
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) RouteColors.OnSecondaryContainer else RouteColors.Primary, modifier = Modifier.size(18.dp))
        Text(
            label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = if (filled) RouteColors.OnSecondaryContainer else RouteColors.OnSurfaceVariant,
        )
    }
}

/**
 * Carte arrondie du détail : le tracé se dessine (1,4 s, décélération accentuée), puis les repères de départ et
 * d'arrivée apparaissent avec un rebond, puis le bouton « Agrandir » et la pastille distance · points.
 */
@Composable
private fun MapCard(
    stats: RouteStats,
    geometry: RouteGeometry,
    metric: RouteMetric,
    style: MapStyle,
    tiles: com.boucle.app.ui.map.TileCache,
    hidden: Boolean,
    reducedMotion: Boolean,
    showDetailButton: Boolean,
    onOpen: () -> Unit,
    onShowDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = remember { MapCamera() }
    val draw = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val start = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val finish = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val controls = remember { Animatable(if (reducedMotion) 1f else 0f) }
    val pop = spring<Float>(dampingRatio = 0.45f, stiffness = 500f)
    LaunchedEffect(geometry) {
        if (reducedMotion) return@LaunchedEffect
        launch { draw.animateTo(1f, tween(1400, delayMillis = 200, easing = EmphasizedDecelerate)) }
        launch { delay(1300); start.animateTo(1f, pop) }
        launch { delay(1500); finish.animateTo(1f, pop) }
        launch { delay(1600); controls.animateTo(1f, pop) }
    }
    val (padX, padY) = with(LocalDensity.current) { 28.dp.toPx() to 36.dp.toPx() }
    val description = "Carte du parcours : ${RouteFormat.km(stats.distanceMeters)} km. Touchez pour l'ouvrir en plein écran."
    Box(
        modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT)
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .clip(RoundedCornerShape(MAP_RADIUS))
            .clickable(onClickLabel = "Ouvrir la carte en plein écran", role = Role.Button, onClick = onOpen),
    ) {
        RouteMap(
            geometry = geometry,
            metric = metric,
            style = style,
            tiles = tiles,
            camera = camera,
            contentDescription = description,
            modifier = Modifier.fillMaxSize(),
            animation = RouteMapAnimation(draw = { draw.value }, start = { start.value }, finish = { finish.value }),
            fitPadding = { FitPadding(padX, padY, padX, padY) },
        ) {
            MapAttribution(style, Modifier.align(Alignment.TopStart).padding(10.dp))
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .graphicsLayer {
                        scaleX = 0.5f + 0.5f * controls.value
                        scaleY = 0.5f + 0.5f * controls.value
                        alpha = controls.value.coerceIn(0f, 1f)
                    },
            ) {
                RoundMapButton(BoucleIcons.Expand, "Agrandir la carte", RoundedCornerShape(20.dp), size = 40.dp, onClick = onOpen)
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .graphicsLayer {
                        alpha = controls.value.coerceIn(0f, 1f)
                        translationY = (1 - controls.value.coerceIn(0f, 1f)) * 20.dp.toPx()
                    }
                    .height(28.dp)
                    .background(RouteColors.Background.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(8.dp).background(RouteColors.Start, RoundedCornerShape(4.dp)))
                Text(
                    "${RouteFormat.km(stats.distanceMeters)}${NBSP}km · ${formatThousands(stats.points.size)} points GPS",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = RouteColors.OnSurface,
                )
            }
            AnimatedVisibility(
                visible = showDetailButton,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                enter = scaleIn(tween(400, delayMillis = if (reducedMotion) 0 else 1400, easing = androidx.compose.animation.core.CubicBezierEasing(0.34f, 1.6f, 0.5f, 1f)), initialScale = 0.5f) +
                    fadeIn(tween(200, delayMillis = if (reducedMotion) 0 else 1400)),
            ) {
                Surface(
                    onClick = onShowDetail,
                    shape = RoundedCornerShape(20.dp),
                    color = RouteColors.Background,
                    contentColor = RouteColors.OnSurface,
                    shadowElevation = 2.dp,
                ) {
                    Row(Modifier.height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Voir le détail", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                        Icon(BoucleIcons.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * Tracé présent mais non autorisé : carte floutée, message et bouton « Autoriser l'itinéraire » ; indicateur de
 * chargement pendant la lecture ; message de refus avec « Réessayer ».
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConsentMap(access: RouteAccess, onRequest: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(CONSENT_MAP_HEIGHT)
            .clip(RoundedCornerShape(MAP_RADIUS)),
    ) {
        StylizedMap(Modifier.matchParentSize().blur(6.dp))
        AnimatedContent(
            targetState = access == RouteAccess.LOADING,
            transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(150))) },
            label = "consent",
            modifier = Modifier.matchParentSize(),
        ) { loading ->
            if (loading) {
                Box(Modifier.fillMaxSize().background(RouteColors.Background.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .background(RouteColors.PrimaryContainer, RoundedCornerShape(20.dp))
                            .semantics { contentDescription = "Import du tracé" },
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(Modifier.size(44.dp), color = RouteColors.Primary)
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(RouteColors.Background.copy(alpha = 0.55f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Box(Modifier.size(56.dp).background(RouteColors.PrimaryContainer, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Icon(BoucleIcons.Pin, contentDescription = null, tint = RouteColors.OnPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        if (access == RouteAccess.DENIED) "Accès au tracé refusé" else "Un tracé GPS est disponible",
                        style = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                        color = RouteColors.OnSurface,
                    )
                    Text(
                        if (access == RouteAccess.DENIED) {
                            "Health Connect n'a pas partagé l'itinéraire de cette séance. Vous pouvez réessayer."
                        } else {
                            "Health Connect demande votre accord pour partager l'itinéraire de cette séance."
                        },
                        Modifier.widthIn(max = 280.dp),
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center),
                        color = RouteColors.OnSurfaceVariant,
                    )
                    Button(
                        onClick = onRequest,
                        shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.buttonColors(containerColor = RouteColors.Primary, contentColor = RouteColors.OnPrimary),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (access == RouteAccess.DENIED) "Réessayer" else "Autoriser l'itinéraire",
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

/** Sous la carte d'un tracé Health Connect : l'accès se retire dans les réglages de Health Connect. */
@Composable
private fun RevokeNote() {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoucleIcons.Lock, contentDescription = null, tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("Vous pouvez retirer l'accès dans les réglages de Health Connect.", style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
    }
}

/**
 * Groupe de boutons connectés Allure / Fréq. card. / Altitude : le bouton choisi devient une pilule avec une coche.
 * Dessous, la légende du dégradé du tracé avec les valeurs extrêmes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MetricSelector(metric: RouteMetric, stats: RouteStats, onSelect: (RouteMetric) -> Unit, modifier: Modifier = Modifier) {
    val options = listOf(
        Triple(RouteMetric.Pace, "Allure", RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 8.dp, bottomEnd = 8.dp)),
        Triple(RouteMetric.Heart, "Fréq. card.", RoundedCornerShape(8.dp)),
        Triple(RouteMetric.Altitude, "Altitude", RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp)),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Colorer le tracé selon" },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            options.forEach { (value, label, shape) ->
                val checked = value == metric
                ToggleButton(
                    checked = checked,
                    onCheckedChange = { onSelect(value) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    shapes = ToggleButtonShapes(shape = shape, pressedShape = RoundedCornerShape(6.dp), checkedShape = RoundedCornerShape(percent = 50)),
                    colors = ToggleButtonDefaults.colors(
                        containerColor = RouteColors.ContainerHigh,
                        contentColor = RouteColors.OnSurfaceVariant,
                        checkedContainerColor = RouteColors.Primary,
                        checkedContentColor = RouteColors.OnPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    AnimatedVisibility(visible = checked, enter = scaleIn(spring(dampingRatio = 0.4f, stiffness = 600f)) + fadeIn(), exit = fadeOut(tween(100))) {
                        Icon(BoucleIcons.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                    }
                    Text(label, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }
        }
        val (low, high) = when (metric) {
            RouteMetric.Pace -> "Lent ${RouteFormat.pace(stats.slowestPace)}" to "Rapide ${RouteFormat.pace(stats.fastestPace)}"
            RouteMetric.Heart -> (stats.minHr?.let { "$it${NBSP}bpm" } ?: "—") to (stats.maxHr?.let { "$it${NBSP}bpm" } ?: "—")
            RouteMetric.Altitude -> (stats.minAltitude?.let(RouteFormat::meters) ?: "—") to (stats.maxAltitude?.let(RouteFormat::meters) ?: "—")
        }
        val colors = metric.gradient.mapIndexed { i, c ->
            animateColorAsState(c, tween(450), label = "legend$i").value
        }
        Row(
            Modifier.clearAndSetSemantics { contentDescription = "Échelle de couleur du tracé : de $low à $high" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(low, Modifier.widthIn(min = 64.dp), style = TextStyle(fontSize = 12.sp), color = RouteColors.OnSurfaceVariant)
            Box(Modifier.weight(1f).height(8.dp).background(Brush.horizontalGradient(colors), RoundedCornerShape(4.dp)))
            Text(high, Modifier.widthIn(min = 64.dp), style = TextStyle(fontSize = 12.sp, textAlign = TextAlign.End), color = RouteColors.OnSurfaceVariant)
        }
    }
}

/** Distance en très grand, puis 4 tuiles aux coins asymétriques : durée, allure, FC, dénivelé positif. */
@Composable
private fun SessionFigures(session: TrainingSessionEntity, stats: RouteStats?, modifier: Modifier = Modifier) {
    val distance = stats?.distanceMeters ?: session.distanceMeters?.toDouble()?.takeIf { it > 0 }
    val duration = session.durationSec?.toDouble() ?: stats?.durationSec
    val pace = stats?.averagePace ?: if (distance != null && duration != null && distance > 0) duration / (distance / 1000) else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (distance != null) {
            Row(
                Modifier.staggeredEntrance(3).clearAndSetSemantics { contentDescription = "Distance ${RouteFormat.km(distance)} kilomètres" },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    RouteFormat.km(distance),
                    style = TextStyle(fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp),
                    color = RouteColors.OnSurface,
                )
                Text(
                    "km",
                    Modifier.padding(start = 6.dp, bottom = 6.dp),
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
                    color = RouteColors.OnSurfaceVariant,
                )
            }
        }
        // Mêmes valeurs que l'onglet Fréq. card. quand le tracé porte la FC.
        val heart = stats?.averageHr ?: session.averageHr
        val maxHr = stats?.maxHr ?: session.maxHr
        val tiles = listOf(
            Tile(BoucleIcons.Timer, "Durée", duration?.let(RouteFormat::clock) ?: "—", null, RouteColors.PrimaryContainer, RouteColors.OnPrimaryContainer, Radii(28.dp, 28.dp, 28.dp, 8.dp)),
            Tile(BoucleIcons.Gauge, "Allure moyenne", RouteFormat.pace(pace), if (pace != null) "/km" else null, RouteColors.ContainerHigh, RouteColors.OnSurface, Radii(28.dp, 28.dp, 8.dp, 28.dp)),
            Tile(
                BoucleIcons.Heart,
                "Fréquence cardiaque",
                heart?.toString() ?: "—",
                if (heart != null) "bpm" + (maxHr?.let { " · max $it" } ?: "") else null,
                RouteColors.TertiaryContainer,
                RouteColors.OnTertiaryContainer,
                Radii(28.dp, 8.dp, 28.dp, 28.dp),
            ),
            if (stats?.gainMeters != null) {
                Tile(BoucleIcons.Mountain, "Dénivelé positif", "+${stats.gainMeters.roundToInt()}", "m", RouteColors.SecondaryContainer, RouteColors.OnSecondaryContainer, Radii(8.dp, 28.dp, 28.dp, 28.dp))
            } else {
                Tile(BoucleIcons.Bolt, "Calories", session.calories?.let(::formatThousands) ?: "—", if (session.calories != null) "kcal" else null, RouteColors.SecondaryContainer, RouteColors.OnSecondaryContainer, Radii(8.dp, 28.dp, 28.dp, 28.dp))
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tiles.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { col, tile ->
                        FigureTile(tile, Modifier.weight(1f).fillMaxHeight().staggeredEntrance(4 + row * 2 + col))
                    }
                }
            }
        }
    }
}

private class Tile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val unit: String?,
    val container: Color,
    val content: Color,
    val radii: Radii,
)

@Composable
private fun FigureTile(tile: Tile, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(tile.container, tile.radii.shape())
            .padding(16.dp)
            .clearAndSetSemantics { contentDescription = "${tile.label} : ${tile.value} ${tile.unit.orEmpty()}" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(tile.icon, contentDescription = null, tint = if (tile.container == RouteColors.ContainerHigh) RouteColors.Primary else tile.content, modifier = Modifier.size(22.dp))
        Text(tile.label, style = TextStyle(fontSize = 13.sp), color = if (tile.container == RouteColors.ContainerHigh) RouteColors.OnSurfaceVariant else tile.content)
        Text(
            buildAnnotatedString {
                append(tile.value)
                tile.unit?.let {
                    withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)) { append(" $it") }
                }
            },
            style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight(750)),
            color = tile.content,
        )
    }
}

@Composable
private fun PaceContent(stats: RouteStats) {
    val total = stats.distanceMeters
    val slowest = stats.slowestPace
    val fastest = stats.fastestPace
    if (slowest != null && fastest != null) {
        val pace = remember(stats) { series(stats.points, total) { it.paceSecPerKm } }
        val altitude = remember(stats) { series(stats.points, total) { it.altitude } }
        val alt = stats.minAltitude to stats.maxAltitude
        val span = ((alt.second ?: 0.0) - (alt.first ?: 0.0)).coerceAtLeast(1.0)
        DetailCard("Allure et altitude", trailing = "par distance") {
            DistanceChart(
                description = "Graphique de l'allure et de l'altitude selon la distance : allure de ${RouteFormat.pace(slowest)} à " +
                    "${RouteFormat.pace(fastest)} par kilomètre" +
                    (if (stats.hasAltitude) ", altitude de ${RouteFormat.meters(alt.first!!)} à ${RouteFormat.meters(alt.second!!)}" else "") + ".",
                line = pace,
                // Plus rapide = plus haut.
                lineRange = slowest.toFloat() to fastest.toFloat(),
                lineColor = RouteColors.Primary,
                area = altitude,
                areaRange = ((alt.first ?: 0.0) - span * 0.3).toFloat() to ((alt.second ?: 0.0) + span * 1.2).toFloat(),
                areaColor = RouteColors.PaceAltitudeFill,
            )
            ChartLegend(
                listOfNotNull(
                    Triple("Allure", RouteColors.Primary, true),
                    if (stats.hasAltitude) Triple("Altitude", RouteColors.PaceAltitudeFill, false) else null,
                ),
            )
        }
    }
    if (stats.splits.isNotEmpty()) SplitsCard(stats)
}

@Composable
private fun HeartContent(stats: RouteStats, state: SessionDetailUiState) {
    if (!stats.hasHeartRate) {
        MissingData("Aucune fréquence cardiaque n'a été enregistrée avec ce tracé.")
        return
    }
    val min = stats.minHr!!
    val max = stats.maxHr!!
    val average = stats.averageHr!!
    val line = remember(stats) { series(stats.points, stats.distanceMeters) { it.heartRate?.toDouble() } }
    DetailCard("Fréquence cardiaque", trailing = "par distance") {
        MiniStats(listOf("Moyenne" to "$average", "Max" to "$max", "Min" to "$min"))
        DistanceChart(
            description = "Graphique de la fréquence cardiaque selon la distance : de $min à $max bpm, moyenne $average bpm.",
            line = line,
            lineRange = (min - 6).toFloat() to (max + 4).toFloat(),
            lineColor = RouteColors.HeartLine,
            lineFill = RouteColors.HeartFill,
            dashedValue = average.toFloat(),
            dashedLabel = "moy. $average",
        )
        DistanceAxis(stats)
    }
    val maxHr = state.maxHr
    if (maxHr != null && state.zones.isNotEmpty()) {
        HeartZonesCard(state.zones, maxHr, state.maxHrEstimated)
    } else {
        MissingData("Renseignez votre profil (date de naissance ou FC max) dans la configuration du bracelet pour voir le temps passé dans chaque zone cardiaque.")
    }
}

@Composable
private fun AltitudeContent(stats: RouteStats) {
    if (!stats.hasAltitude) {
        MissingData("Aucune altitude n'a été enregistrée avec ce tracé.")
        return
    }
    val low = stats.minAltitude!!
    val high = stats.maxAltitude!!
    val span = (high - low).coerceAtLeast(1.0)
    val line = remember(stats) { series(stats.points, stats.distanceMeters) { it.altitude } }
    val peak = stats.highestIndex?.let { i -> (stats.points[i].distanceMeters / stats.distanceMeters).toFloat() to high.toFloat() }
    DetailCard("Profil d'altitude", trailing = "par distance") {
        MiniStats(
            listOf(
                "D+" to RouteFormat.meters(stats.gainMeters ?: 0.0),
                "D−" to RouteFormat.meters(stats.lossMeters ?: 0.0),
                "Min" to RouteFormat.meters(low),
                "Max" to RouteFormat.meters(high),
            ),
        )
        DistanceChart(
            description = "Profil d'altitude selon la distance : de ${RouteFormat.meters(low)} à ${RouteFormat.meters(high)}, " +
                "dénivelé positif ${RouteFormat.meters(stats.gainMeters ?: 0.0)}.",
            line = line,
            lineRange = (low - span * 0.15).toFloat() to (high + span * 0.2).toFloat(),
            lineColor = RouteColors.AltitudeLine,
            lineFill = RouteColors.AltitudeFill,
            peak = peak?.let { it to RouteFormat.meters(high) },
        )
        DistanceAxis(stats)
    }
    if (stats.splits.isNotEmpty()) ElevationSplitsCard(stats.splits)
}

/** Origine des données ; le tracé n'est visible que par l'utilisateur. */
@Composable
private fun SourceNote(session: TrainingSessionEntity, state: SessionDetailUiState, modifier: Modifier = Modifier) {
    val fromHealthConnect = session.path.startsWith("healthconnect:")
    val origin = when {
        session.path.startsWith(PHONE_SESSION_PREFIX) -> "Enregistré par Boucle avec le GPS du téléphone."
        session.path.startsWith(DetectedActivity.PATH_PREFIX) -> "Activité détectée dans les pas du bracelet, sans tracé GPS."
        fromHealthConnect -> "Enregistré par la montre, synchronisé via Health Connect."
        state.routeSource == RouteSource.HEALTH_CONNECT -> "Enregistré par le bracelet, synchronisé via Health Connect."
        else -> "Enregistré par le bracelet, synchronisé en Bluetooth."
    }
    val privacy = if (state.routeStatus != null) " Le tracé n'est visible que par vous." else " Les données restent sur votre téléphone."
    Row(
        modifier
            .fillMaxWidth()
            .border(1.dp, RouteColors.Outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(BoucleIcons.Refresh, contentDescription = null, tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(origin + privacy, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = RouteColors.OnSurfaceVariant)
    }
}

/** « 07:12 – 07:58 ». */
private fun hoursOf(session: TrainingSessionEntity): String? {
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
    val end = session.durationSec?.let { start.plusSeconds(it.toLong()) } ?: return start.format(clockFormat)
    return "${start.format(clockFormat)} – ${end.format(clockFormat)}"
}

/** Texte partagé : titre, date, distance, durée, allure, FC et dénivelé. */
private fun shareText(title: String, session: TrainingSessionEntity?, stats: RouteStats?): String {
    session ?: return title
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val distance = stats?.distanceMeters ?: session.distanceMeters?.toDouble()?.takeIf { it > 0 }
    return listOfNotNull(
        listOfNotNull(title, start?.format(headerDate)).joinToString(" · "),
        distance?.let { "${RouteFormat.km(it)} km" },
        session.durationSec?.let { "Durée ${RouteFormat.clock(it)}" },
        stats?.averagePace?.let { "Allure ${RouteFormat.pace(it)}/km" },
        session.averageHr?.let { "FC moyenne $it bpm" },
        stats?.gainMeters?.let { "D+ ${it.roundToInt()} m" },
    ).joinToString("\n")
}
