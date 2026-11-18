package com.boucle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.CalorieEstimate
import com.boucle.app.domain.Feel
import com.boucle.app.domain.HrZones
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.Sport
import com.boucle.app.domain.Terrain
import com.boucle.app.tracking.RecordedActivity
import com.boucle.app.tracking.RecordingPhase
import com.boucle.app.tracking.RecordingState
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.defaultSessionName
import com.boucle.app.ui.map.FitPadding
import com.boucle.app.ui.map.MapAttribution
import com.boucle.app.ui.map.MapCamera
import com.boucle.app.ui.map.MapStyle
import com.boucle.app.ui.map.RouteGeometry
import com.boucle.app.ui.map.RouteMap
import com.boucle.app.ui.map.RouteMetric
import com.boucle.app.ui.map.rememberTileCache
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.RouteColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Activité en cours et résumé de fin de séance, dans la palette indigo du détail d'activité. Maquettes :
 * ActiviteEnCours (course, vélo, marche) et ActiviteResume.
 */

private val tileShapes = listOf(
    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 8.dp, bottomStart = 28.dp),
    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp),
    RoundedCornerShape(topStart = 28.dp, topEnd = 8.dp, bottomEnd = 28.dp, bottomStart = 28.dp),
    RoundedCornerShape(topStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 28.dp),
)

private val tileColors = listOf(
    RouteColors.PrimaryContainer to RouteColors.OnPrimaryContainer,
    RouteColors.ContainerHigh to RouteColors.OnSurface,
    RouteColors.TertiaryContainer to RouteColors.OnTertiaryContainer,
    RouteColors.SecondaryContainer to RouteColors.OnSecondaryContainer,
)

private val summaryDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val summaryClock = DateTimeFormatter.ofPattern("HH:mm")

/** Une valeur avec son unité, pour une tuile. */
private data class Figure(val label: String, val value: String, val unit: String = "")

private fun paceOf(speedMps: Double?): String = RouteFormat.pace(speedMps?.takeIf { it > 0 }?.let { 1000 / it })

private fun speedOf(speedMps: Double?): String = speedMps?.let { ActivityFormat.decimal(it * 3.6, 1) } ?: "—"

private fun meters(value: Double?): String = value?.let { "${it.roundToInt()}${NBSP}m" } ?: "—"

/** Tracé prêt à dessiner d'après les points enregistrés ; `null` tant qu'il y en a moins de deux. */
private fun geometryOf(points: List<RoutePoint>): RouteGeometry? = RouteAnalysis.analyze(points)?.let(RouteGeometry::from)

/**
 * Activité en cours : carte qui suit le tracé, chrono, quatre chiffres selon le sport, échelle des zones cardiaques,
 * puis Pause, qui donne Reprendre ou Terminer.
 */
@Composable
fun ActivityRecordingScreen(
    state: RecordingState,
    weightKg: Float?,
    track: () -> List<RoutePoint>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val paused = state.phase == RecordingPhase.PAUSED
    // En pause, la vitesse du moment n'a plus de sens : elle n'est pas affichée.
    val stats = if (paused) state.stats.copy(speedMps = null) else state.stats
    val hours = state.activeSec / 3600.0
    val km = stats.distanceMeters / 1000
    val averageSpeed = if (state.activeSec > 0 && stats.distanceMeters > 0) stats.distanceMeters / state.activeSec else null
    val heart = Figure("Fréq. cardiaque", state.heartRate?.toString() ?: "—", "bpm")
    val distance = Figure("Distance", RouteFormat.km(stats.distanceMeters), "km")
    val gain = Figure("Dénivelé +", stats.gainMeters.roundToInt().toString(), "m")
    val calories = "${CalorieEstimate.kcal(state.sport, stats.distanceMeters, state.activeSec.toDouble(), weightKg)}${NBSP}kcal"
    val (tiles, minor) = when (state.sport) {
        Sport.RUN -> listOf(distance, Figure("Allure", paceOf(stats.speedMps), "/km"), heart, gain) to listOf(
            "Allure moy." to "${paceOf(averageSpeed)}${NBSP}/km",
            "Altitude" to meters(stats.altitude),
            "Dernier km" to (stats.lastKmSec?.let { RouteFormat.pace(it) } ?: "—"),
        )
        Sport.BIKE -> listOf(distance, Figure("Vitesse", speedOf(stats.speedMps), "km/h"), heart, gain) to listOf(
            "Vitesse moy." to "${if (hours > 0) ActivityFormat.decimal(km / hours, 1) else "—"}${NBSP}km/h",
            "Altitude" to meters(stats.altitude),
            "Calories" to calories,
        )
        Sport.WALK -> listOf(
            distance,
            Figure("Pas", state.steps?.let(::formatThousands) ?: "—"),
            heart,
            Figure("Allure", paceOf(stats.speedMps), "/km"),
        ) to listOf(
            "Calories" to calories,
            "Altitude" to meters(stats.altitude),
            "Dénivelé +" to meters(stats.gainMeters),
        )
    }

    // Écran verrouillé : rien ne réagit au toucher (poche, pluie) jusqu'à un appui long sur « Déverrouiller ».
    var locked by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Box(modifier.background(RouteColors.Background)) {
    Column(Modifier.fillMaxSize()) {
        LiveMap(state, track, onBack)
        Column(
            Modifier
                .offset(y = (-28).dp)
                .fillMaxSize()
                .background(RouteColors.Background, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (paused) "En pause" else "Durée",
                    style = BoucleType.smallStrong,
                    color = if (paused) RouteColors.FastestSplit else RouteColors.OnSurfaceVariant,
                )
                Text(
                    RouteFormat.clock(state.activeSec),
                    style = BoucleType.heroS.copy(letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
                    color = RouteColors.OnSurface,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { column, figure ->
                            val index = row * 2 + column
                            FigureTile(figure, tileColors[index].first, tileColors[index].second, tileShapes[index], Modifier.weight(1f))
                        }
                    }
                }
            }
            val heartRate = state.heartRate
            if (heartRate != null && state.maxHr > 0) {
                ZoneScale(heartRate, state.maxHr)
            } else {
                Text(
                    "Bracelet non connecté : pas de fréquence cardiaque ni de zones.",
                    Modifier.padding(horizontal = 4.dp),
                    style = BoucleType.small,
                    color = RouteColors.OnSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(RouteColors.Container, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                minor.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = BoucleType.caption, color = RouteColors.OnSurfaceVariant)
                        Text(value, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) {
                    PillButton(
                        "Reprendre", onResume, RouteColors.Primary, RouteColors.OnPrimary, Modifier.weight(1f), height = 72.dp,
                        icon = BoucleIcons.Play,
                        shape = RoundedCornerShape(topStart = 36.dp, bottomStart = 36.dp, topEnd = 12.dp, bottomEnd = 12.dp),
                    )
                    PillButton(
                        "Terminer", onFinish, RouteColors.Finish, RouteColors.OnPrimary, Modifier.weight(1f), height = 72.dp,
                        icon = BoucleIcons.Stop,
                        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 36.dp, bottomEnd = 36.dp),
                    )
                } else {
                    PillButton(
                        "Pause", onPause, RouteColors.Primary, RouteColors.OnPrimary, Modifier.weight(1f), height = 72.dp,
                        icon = BoucleIcons.Pause, style = BoucleType.buttonXL,
                    )
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(RouteColors.ContainerHigh)
                            .clickable(role = Role.Button, onClickLabel = "Verrouiller l'écran") {
                                haptics.tap()
                                locked = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(BoucleIcons.Lock, contentDescription = "Verrouiller l'écran", tint = RouteColors.OnSurface, modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
    if (locked) {
        Box(
            Modifier
                .fillMaxSize()
                // Absorbe tous les touchers sous le verrou.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        ) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(RouteColors.Finish)
                    .combinedClickable(
                        role = Role.Button,
                        onLongClickLabel = "Déverrouiller l'écran",
                        onLongClick = {
                            haptics.success()
                            locked = false
                        },
                        onClick = { },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                Icon(BoucleIcons.Lock, contentDescription = null, tint = RouteColors.OnPrimary, modifier = Modifier.size(22.dp))
                Text("Appui long pour déverrouiller", style = BoucleType.buttonLarge, color = RouteColors.OnPrimary)
            }
        }
    }
    }
}

/** Carte du haut : le tracé parcouru, recadré à mesure, avec le sport et la précision du GPS. */
@Composable
private fun LiveMap(state: RecordingState, track: () -> List<RoutePoint>, onBack: () -> Unit) {
    val tiles = rememberTileCache()
    val camera = remember { MapCamera() }
    // Le tracé n'est recalculé que tous les quelques points : inutile de le refaire à chaque seconde.
    val geometry = remember(state.trackVersion / TRACK_REFRESH_POINTS) { geometryOf(track()) }
    val accuracy = state.stats.accuracyMeters
    Box(
        Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(RouteColors.MapGround),
    ) {
        if (geometry != null) {
            RouteMap(
                geometry = geometry,
                metric = RouteMetric.Pace,
                style = MapStyle.Plan,
                tiles = tiles,
                camera = camera,
                contentDescription = "Carte du parcours en cours",
                modifier = Modifier.fillMaxSize(),
                fitPadding = { FitPadding(96f, 160f, 96f, 160f) },
            ) {
                MapAttribution(MapStyle.Plan, Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 38.dp))
            }
        } else {
            Text(
                "Recherche du signal GPS…",
                Modifier.align(Alignment.Center),
                style = BoucleType.bodyStrong,
                color = RouteColors.OnSurfaceVariant,
            )
        }
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .shadow(2.dp, RoundedCornerShape(22.dp))
                    .background(RouteColors.Background, RoundedCornerShape(22.dp)),
            ) {
                Icon(BoucleIcons.ArrowBack, contentDescription = "Retour à Forme, l'activité continue", tint = RouteColors.OnSurface, modifier = Modifier.size(22.dp))
            }
            Row(
                Modifier
                    .height(40.dp)
                    .shadow(2.dp, RoundedCornerShape(20.dp))
                    .background(RouteColors.Background, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (accuracy != null && accuracy <= 35f) RouteColors.Start else RouteColors.FastestSplit, RoundedCornerShape(4.dp)),
                )
                Text(
                    if (accuracy != null) "${state.sport.label} · GPS ${accuracy.roundToInt()}${NBSP}m" else "${state.sport.label} · GPS en attente",
                    style = BoucleType.buttonSmall,
                    color = RouteColors.OnSurface,
                )
            }
        }
    }
}

@Composable
private fun FigureTile(figure: Figure, container: Color, content: Color, shape: RoundedCornerShape, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(container, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(figure.label, style = BoucleType.small, color = content)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                figure.value,
                Modifier.alignByBaseline(),
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", fontFamily = BoucleType.valueL.fontFamily),
                color = content,
            )
            if (figure.unit.isNotEmpty()) {
                Text(figure.unit, Modifier.alignByBaseline(), style = BoucleType.body.copy(fontWeight = FontWeight.Medium), color = content)
            }
        }
    }
}

/**
 * Échelle des 5 zones cardiaques : la zone du moment est plus haute, un curseur marque la FC, et les seuils en bpm
 * sont écrits dessous.
 */
@Composable
private fun ZoneScale(heartRate: Int, maxHr: Int) {
    val bounds = listOf(HrZones.floor(maxHr)) + RouteAnalysis.zoneBounds(maxHr) + maxHr
    val zone = RouteAnalysis.zoneOf(heartRate, RouteAnalysis.zoneBounds(maxHr))
    val fraction = ((heartRate - bounds.first()).toFloat() / (bounds.last() - bounds.first())).coerceIn(0f, 1f)
    val range = when (zone) {
        0 -> "moins de ${bounds[1]}${NBSP}bpm"
        4 -> "${bounds[4]}${NBSP}bpm et plus"
        else -> "${bounds[zone]} – ${bounds[zone + 1]}${NBSP}bpm"
    }
    Column(
        Modifier
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Zones cardiaques : $heartRate bpm, zone ${zone + 1} sur 5, ${HrZones.NAMES[zone]}, $range"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Zone ${zone + 1} · ${HrZones.NAMES[zone]}", style = BoucleType.buttonSmall.copy(fontWeight = FontWeight.Bold), color = RouteColors.OnSurface)
            Text(range, style = BoucleType.small, color = RouteColors.OnSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                RouteColors.Zones.forEachIndexed { i, (container, content) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(if (i == zone) 32.dp else 22.dp)
                            .background(container, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${i + 1}",
                            style = BoucleType.small.copy(fontWeight = if (i == zone) FontWeight.ExtraBold else FontWeight.SemiBold),
                            color = content,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .offset(x = maxWidth * fraction - 4.dp)
                    .width(8.dp)
                    .height(32.dp)
                    .background(RouteColors.Background, RoundedCornerShape(4.dp))
                    .padding(2.dp)
                    .background(RouteColors.OnSurface, RoundedCornerShape(2.dp)),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            bounds.forEach { Text("$it", style = BoucleType.caption.copy(fontFeatureSettings = "tnum"), color = RouteColors.OnSurfaceVariant) }
        }
    }
}

/**
 * Fin de séance : nom, carte, chiffres, relief, terrain (course) et ressenti, puis enregistrer ou supprimer.
 */
@Composable
fun ActivitySummaryScreen(
    activity: RecordedActivity,
    bandConnected: Boolean,
    onSave: (Terrain?, Int?) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = remember(activity.startMs) { Instant.ofEpochMilli(activity.startMs).atZone(ZoneId.systemDefault()).toLocalDateTime() }
    val end = start.plusSeconds(activity.durationSec.toLong())
    val geometry = remember(activity) { geometryOf(activity.points) }
    val tiles = rememberTileCache()
    val camera = remember { MapCamera() }
    var terrain by rememberSaveable { mutableStateOf<String?>(null) }
    var feel by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val averageSpeed = if (activity.durationSec > 0 && activity.distanceMeters > 0) activity.distanceMeters / activity.durationSec else null
    val figures = listOf(
        Figure("Distance", RouteFormat.km(activity.distanceMeters), "km"),
        Figure("Durée", RouteFormat.clock(activity.durationSec)),
        Figure("FC moyenne", activity.averageHr?.toString() ?: "—", "bpm"),
        if (activity.sport == Sport.BIKE) {
            Figure("Vitesse moy.", speedOf(averageSpeed), "km/h")
        } else {
            Figure("Allure moy.", paceOf(averageSpeed), "/km")
        },
    )

    Column(modifier.background(RouteColors.Background)) {
        Row(Modifier.height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Séance terminée", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium), color = RouteColors.OnSurface)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    defaultSessionName(activity.sport, start.toLocalTime()),
                    Modifier.semantics { heading() },
                    style = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight(750), letterSpacing = (-0.4).sp),
                    color = RouteColors.OnSurface,
                )
                Text(
                    "${start.format(summaryDate).replaceFirstChar { it.titlecase(Locale.FRENCH) }} · ${start.format(summaryClock)} – ${end.format(summaryClock)} · " +
                        if (bandConnected || activity.averageHr != null) "Téléphone + bracelet" else "Téléphone",
                    style = TextStyle(fontSize = 14.sp),
                    color = RouteColors.OnSurfaceVariant,
                )
            }
            if (geometry != null) {
                RouteMap(
                    geometry = geometry,
                    metric = RouteMetric.Pace,
                    style = MapStyle.Plan,
                    tiles = tiles,
                    camera = camera,
                    contentDescription = "Carte du parcours, ${RouteFormat.km(activity.distanceMeters)} km",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(28.dp)),
                    fitPadding = { FitPadding(72f, 72f, 72f, 72f) },
                ) {
                    MapAttribution(MapStyle.Plan, Modifier.align(Alignment.TopStart).padding(10.dp))
                }
            } else {
                Text(
                    "Pas assez de points GPS pour dessiner le parcours.",
                    Modifier
                        .fillMaxWidth()
                        .background(RouteColors.Container, RoundedCornerShape(20.dp))
                        .padding(16.dp),
                    style = BoucleType.small,
                    color = RouteColors.OnSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                figures.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEachIndexed { column, figure ->
                            val index = row * 2 + column
                            FigureTile(figure, tileColors[index].first, tileColors[index].second, tileShapes[index], Modifier.weight(1f))
                        }
                    }
                }
            }
            if (activity.minAltitude != null && activity.maxAltitude != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(RouteColors.Container, RoundedCornerShape(28.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Relief", Modifier.semantics { heading() }, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "Dénivelé +" to meters(activity.gainMeters),
                            "Dénivelé −" to meters(activity.lossMeters),
                            "Alt. min" to meters(activity.minAltitude),
                            "Alt. max" to meters(activity.maxAltitude),
                        ).forEach { (label, value) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(label, style = BoucleType.caption, color = RouteColors.OnSurfaceVariant)
                                Text(value, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                            }
                        }
                    }
                }
            }
            if (activity.sport == Sport.RUN) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Terrain", Modifier.semantics { heading() }, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                        Text("Une étiquette, pas un autre sport : la séance reste une course.", style = BoucleType.small, color = RouteColors.OnSurfaceVariant)
                    }
                    SegmentedChoice(
                        options = Terrain.entries.map { it.label },
                        selected = Terrain.named(terrain)?.ordinal,
                        onSelect = { terrain = Terrain.entries[it].name },
                        track = RouteColors.ContainerHigh,
                        selectedColor = RouteColors.Primary,
                        selectedContent = RouteColors.OnPrimary,
                        content = RouteColors.OnSurface,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Comment c'était ?", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                FeelSelector(feel, onSelect = { feel = it })
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillButton(
                "Enregistrer la séance",
                onClick = { onSave(Terrain.named(terrain), feel) },
                color = RouteColors.Primary,
                contentColor = RouteColors.OnPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            TextAction("Supprimer sans enregistrer", { confirmDiscard = true }, RouteColors.HeartLine, Modifier.fillMaxWidth())
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Supprimer cette séance ?") },
            text = { Text("Le parcours et les chiffres de cette activité seront perdus.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDiscard()
                }) { Text("Supprimer", color = RouteColors.HeartLine) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Garder") } },
        )
    }
}

/** Ressenti en cinq niveaux, du plus facile au plus dur. */
@Composable
private fun FeelSelector(selected: Int?, onSelect: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Feel.LABELS.forEachIndexed { i, label ->
            val on = i == selected
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp)
                Feel.LABELS.lastIndex -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp)
                else -> RoundedCornerShape(6.dp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(shape)
                    .background(if (on) RouteColors.TertiaryContainer else RouteColors.Container)
                    .selectable(selected = on, role = Role.RadioButton) {
                        haptics.select()
                        onSelect(i)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = BoucleType.small.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (on) RouteColors.OnTertiaryContainer else RouteColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}

/** Le tracé de la carte en direct est recalculé tous les [TRACK_REFRESH_POINTS] points. */
private const val TRACK_REFRESH_POINTS = 3
