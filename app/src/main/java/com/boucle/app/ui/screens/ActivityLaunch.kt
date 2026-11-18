package com.boucle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.ActivityDetection
import com.boucle.app.domain.DetectedActivity
import com.boucle.app.domain.Sport
import com.boucle.app.tracking.GpsPreview
import com.boucle.app.tracking.RecordingPhase
import com.boucle.app.tracking.RecordingState
import com.boucle.app.ui.ActivityHomeUiState
import com.boucle.app.ui.SessionItem
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.RowChevron
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Lancer une activité depuis l'onglet Forme : carte de lancement, activité détectée, activités récentes, puis les
 * écrans « Nouvelle activité », « Autorisation de position » et « Activité détectée ». Maquettes : ActiviteLancer,
 * ActiviteChoix, ActiviteAutorisation, ActiviteDetectee.
 */

internal val Sport.icon: ImageVector
    get() = when (this) {
        Sport.WALK -> BoucleIcons.Steps
        Sport.RUN -> BoucleIcons.Run
        Sport.BIKE -> BoucleIcons.Bike
    }

/** Icône d'une séance : celle de son sport, sinon l'icône générique. */
internal fun sportIcon(sport: Sport?): ImageVector = sport?.icon ?: BoucleIcons.Pulse

private val recentDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
private val clock = DateTimeFormatter.ofPattern("HH:mm")

/** Bouton pilule de la maquette (64 dp par défaut), avec icône facultative. */
@Composable
internal fun PillButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    icon: ImageVector? = null,
    shape: Shape = BoucleShapes.pill,
    style: TextStyle = BoucleType.buttonLarge,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (enabled) color else BoucleColors.Track)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        val tint = if (enabled) contentColor else BoucleColors.Disabled
        icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
        Text(text, style = style, color = tint, textAlign = TextAlign.Center)
    }
}

/** Action secondaire en texte seul, 48 dp de haut. */
@Composable
internal fun TextAction(text: String, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(BoucleShapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = BoucleType.button, color = color, textAlign = TextAlign.Center)
    }
}

/** Les trois sports côte à côte : grands coins à l'extérieur, le sport choisi rempli. */
@Composable
internal fun SportSelector(
    selected: Sport?,
    onSelect: (Sport) -> Unit,
    selectedColor: Color,
    selectedContent: Color,
    idleColor: Color,
    idleContent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
) {
    val haptics = rememberHaptics()
    val shapes = listOf(BoucleShapes.pairStart, RoundedCornerShape(8.dp), BoucleShapes.pairEnd)
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Sport.entries.forEachIndexed { i, sport ->
            val on = sport == selected
            val content = if (on) selectedContent else idleContent
            Column(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(shapes[i])
                    .background(if (on) selectedColor else idleColor)
                    .selectable(selected = on, role = Role.RadioButton) {
                        haptics.select()
                        onSelect(sport)
                    }
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(sport.icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
                Text(sport.label, style = BoucleType.valueS, color = content)
            }
        }
    }
}

/** Choix exclusif en pilules dans une piste (terrain d'une course). */
@Composable
internal fun SegmentedChoice(
    options: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    track: Color,
    selectedColor: Color,
    selectedContent: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .background(track, RoundedCornerShape(28.dp))
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (on) selectedColor else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) {
                        haptics.select()
                        onSelect(i)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = BoucleType.button.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (on) selectedContent else content,
                )
            }
        }
    }
}

/** Carte verte « Qu'est-ce qu'on lance ? » : un sport par tuile, puis l'état du bracelet. */
@Composable
internal fun ActivityLaunchCard(bandConnected: Boolean, onStart: (Sport) -> Unit, modifier: Modifier = Modifier) {
    val hints = mapOf(Sport.WALK to "Balade, rando", Sport.RUN to "Route, trail, ultra", Sport.BIKE to "Route, gravel, VTT")
    val shapes = listOf(
        RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp),
        RoundedCornerShape(6.dp),
        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp),
    )
    val haptics = rememberHaptics()
    Column(
        modifier
            .fillMaxWidth()
            .background(BoucleColors.Primary, RoundedCornerShape(44.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Nouvelle activité", style = BoucleType.smallStrong, color = ChartColors.RingSteps)
            Text(
                "Qu'est-ce qu'on lance ?",
                Modifier.semantics { heading() },
                style = BoucleType.statusTitle,
                color = BoucleColors.OnPrimary,
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Sport.entries.forEachIndexed { i, sport ->
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shapes[i])
                        .background(ChartColors.StepsInset)
                        .clickable(role = Role.Button, onClickLabel = "Lancer : ${sport.label}") {
                            haptics.tap()
                            onStart(sport)
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconBadge(sport.icon, ChartColors.RingSteps, BoucleColors.OnPrimaryContainer, shape = BadgeShape.Circle, iconSize = 22.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(sport.label, style = BoucleType.rowTitle, color = BoucleColors.OnPrimary)
                        Text(hints.getValue(sport), style = BoucleType.caption, color = ChartColors.RingSteps)
                    }
                }
            }
        }
        Row(
            Modifier
                .height(32.dp)
                .background(ChartColors.RingTrack, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (bandConnected) Box(Modifier.size(8.dp).background(ChartColors.RingSteps, BoucleShapes.pill))
            Text(
                if (bandConnected) "Bracelet connecté : fréquence cardiaque en direct" else "Bracelet non connecté : sans fréquence cardiaque",
                style = BoucleType.captionStrong,
                color = BoucleColors.OnPrimary,
            )
        }
    }
}

/** Remplace la carte de lancement pendant une activité : retour à l'écran « en cours ». */
@Composable
internal fun RecordingBanner(state: RecordingState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val status = if (state.phase == RecordingPhase.PAUSED) "en pause" else "en cours"
    Column(
        modifier
            .fillMaxWidth()
            .background(BoucleColors.Primary, RoundedCornerShape(44.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(state.sport.icon, ChartColors.RingSteps, BoucleColors.OnPrimaryContainer, shape = BadgeShape.Circle, iconSize = 22.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${state.sport.label} $status", style = BoucleType.sectionTitle, color = BoucleColors.OnPrimary)
                Text(
                    "${RouteFormat.clock(state.activeSec)} · ${RouteFormat.km(state.stats.distanceMeters)}${NBSP}km",
                    style = BoucleType.bodyStrong,
                    color = ChartColors.RingSteps,
                )
            }
        }
        PillButton("Revenir à l'activité", onOpen, ChartColors.RingSteps, BoucleColors.OnPrimaryContainer, Modifier.fillMaxWidth(), height = 48.dp, style = BoucleType.button)
    }
}

/** « On dirait une marche » : activité repérée par le bracelet, à vérifier ou à ignorer. */
@Composable
internal fun DetectedCard(activity: DetectedActivity, onReview: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Energy
    val end = activity.start.plusSeconds(activity.durationSec.toLong())
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(activity.sport.icon, palette.container, palette.onContainer, shape = BadgeShape.Cookie)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Détectée par ton bracelet", style = BoucleType.captionStrong, color = ChartColors.EnergyDrain)
                Text(detectedTitle(activity.sport), style = BoucleType.rowTitle)
                Text(
                    "${activity.start.format(clock)} – ${end.format(clock)} · ${ActivityFormat.duration(activity.durationSec)} · " +
                        "${formatThousands(activity.steps)}${NBSP}pas",
                    style = BoucleType.small,
                    color = BoucleColors.OnSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Vérifier", onReview, BoucleColors.Primary, BoucleColors.OnPrimary, Modifier.weight(1f), height = 44.dp, style = BoucleType.button)
            PillButton("Ignorer", onDismiss, BoucleColors.Background, BoucleColors.OnSurface, Modifier.weight(1f), height = 44.dp, style = BoucleType.button)
        }
    }
}

internal fun detectedTitle(sport: Sport): String = when (sport) {
    Sport.WALK -> "On dirait une marche"
    Sport.RUN -> "Plutôt une course"
    Sport.BIKE -> "Plutôt du vélo"
}

/** « Activités récentes » : liste groupée, la plus récente en haut. */
@Composable
internal fun RecentActivities(items: List<SessionItem>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Activités récentes",
            Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            style = BoucleType.sectionTitle,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, item ->
                val radii = when {
                    items.size == 1 -> Radii.Tile
                    i == 0 -> Radii.ListTop
                    i == items.lastIndex -> Radii.ListBottom
                    else -> Radii(6.dp)
                }
                RecentRow(item, radii, onClick = { onOpen(item.session.path) })
            }
        }
    }
}

@Composable
private fun RecentRow(item: SessionItem, radii: Radii, onClick: () -> Unit) {
    val session = item.session
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    val details = listOfNotNull(
        start?.format(recentDate)?.replaceFirstChar { it.titlecase(Locale.FRENCH) },
        session.distanceMeters?.takeIf { it > 0f }?.let { "${ActivityFormat.decimal(it / 1000.0, 2)}${NBSP}km" },
        session.durationSec?.let { RouteFormat.clock(it) },
    ).joinToString(" · ")
    val chip = item.terrain?.label ?: "Détectée".takeIf { item.detected }
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = radii,
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        onClickLabel = "Voir le détail de la séance",
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(sportIcon(item.sport), MetricPalette.Activity.container, MetricPalette.Activity.onContainer)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.name?.takeIf { it.isNotBlank() } ?: item.sport?.label ?: "Entraînement", style = BoucleType.rowTitle)
                if (details.isNotEmpty()) Text(details, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
            chip?.let {
                Text(
                    it,
                    style = BoucleType.captionStrong,
                    modifier = Modifier
                        .background(BoucleColors.Background, BoucleShapes.pill)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            RowChevron()
        }
    }
}

/** Haut de l'onglet Forme : lancement (ou activité en cours), activité détectée, activités récentes. */
@Composable
internal fun ActivityHomeSection(
    home: ActivityHomeUiState,
    recording: RecordingState,
    bandConnected: Boolean,
    onStart: (Sport) -> Unit,
    onOpenRecording: () -> Unit,
    onReviewDetected: () -> Unit,
    onDismissDetected: (DetectedActivity) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    if (recording.active) {
        RecordingBanner(recording, onOpenRecording, Modifier.staggeredEntrance(1))
    } else {
        ActivityLaunchCard(bandConnected, onStart, Modifier.staggeredEntrance(1))
    }
    home.detected?.let { DetectedCard(it, onReviewDetected, { onDismissDetected(it) }, Modifier.staggeredEntrance(2)) }
    if (home.recent.isNotEmpty()) RecentActivities(home.recent, onOpenSession, Modifier.staggeredEntrance(3))
}

/** Ligne d'état d'un capteur avant le départ : pastille, libellé, précision et valeur. */
@Composable
private fun StatusRow(icon: ImageVector, container: Color, content: Color, title: String, detail: String, value: String, valueColor: Color, radii: Radii) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = radii,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, container, content)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = BoucleType.rowTitle)
                Text(detail, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
            Text(value, style = BoucleType.smallStrong, color = valueColor)
        }
    }
}

private data class SportInfo(val title: String, val blurb: String, val fields: List<String>)

private fun sportInfo(sport: Sport): SportInfo = when (sport) {
    Sport.WALK -> SportInfo(
        "Marche",
        "Balade ou randonnée : les pas complètent le tracé GPS.",
        listOf("Durée", "Distance", "Pas", "Allure", "Fréq. cardiaque", "Dénivelé", "Calories"),
    )
    Sport.RUN -> SportInfo(
        "Course à pied",
        "Un seul profil pour la route, le trail et l'ultra-trail. Tu précises le terrain à la fin de la séance.",
        listOf("Durée", "Distance", "Allure", "Allure moyenne", "Zones cardiaques", "Dénivelé + / −", "Altitude", "Temps au km"),
    )
    Sport.BIKE -> SportInfo(
        "Vélo",
        "La vitesse remplace l'allure, avec le dénivelé et l'altitude tout au long de la sortie.",
        listOf("Durée", "Distance", "Vitesse", "Vitesse moyenne", "Zones cardiaques", "Dénivelé + / −", "Altitude", "Calories"),
    )
}

/** « Nouvelle activité » : choix du sport, ce qui sera enregistré, état du GPS, de la FC et de l'altitude, départ. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityStartScreen(
    initialSport: Sport,
    preview: GpsPreview,
    locationGranted: Boolean,
    gpsEnabled: Boolean,
    liveHeartRate: Int?,
    bandConnected: Boolean,
    hasBarometer: Boolean,
    onEnter: () -> Unit,
    onLeave: () -> Unit,
    onStart: (Sport) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sportName by rememberSaveable { mutableStateOf(initialSport.name) }
    val sport = Sport.named(sportName) ?: initialSport
    val info = sportInfo(sport)
    // Le GPS est écouté tant que l'écran est affiché, pour montrer la qualité du signal avant le départ.
    DisposableEffect(locationGranted) {
        onEnter()
        onDispose(onLeave)
    }
    Column(modifier) {
        DetailHeader("Nouvelle activité", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SportSelector(
                selected = sport,
                onSelect = { sportName = it.name },
                selectedColor = BoucleColors.Primary,
                selectedContent = BoucleColors.OnPrimary,
                idleColor = BoucleColors.Surface,
                idleContent = BoucleColors.OnSurface,
            )
            ExpressiveSurface(
                color = BoucleColors.Surface,
                radii = Radii.CardSmall,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.title, Modifier.semantics { heading() }, style = BoucleType.sectionTitle)
                    Text(info.blurb, style = BoucleType.body, color = BoucleColors.OnSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.fields.forEach { field ->
                        Box(
                            Modifier
                                .height(32.dp)
                                .background(BoucleColors.PrimaryContainer, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(field, style = BoucleType.smallStrong, color = BoucleColors.OnPrimaryContainer)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val accuracy = preview.accuracyMeters
                val (gpsDetail, gpsValue) = when {
                    !locationGranted -> "Autorisation demandée au départ" to "À autoriser"
                    !gpsEnabled -> "Localisation désactivée sur le téléphone" to "Coupé"
                    accuracy == null -> "Recherche des satellites…" to "En attente"
                    accuracy <= 10f -> "Signal fort · précision ${accuracy.roundToInt()}${NBSP}m" to "Prêt"
                    accuracy <= 35f -> "Signal moyen · précision ${accuracy.roundToInt()}${NBSP}m" to "Prêt"
                    else -> "Signal faible · précision ${accuracy.roundToInt()}${NBSP}m" to "Faible"
                }
                StatusRow(
                    BoucleIcons.Pin, MetricPalette.Activity.container, MetricPalette.Activity.onContainer,
                    "GPS du téléphone", gpsDetail, gpsValue, BoucleColors.Primary, Radii.ListTop,
                )
                StatusRow(
                    BoucleIcons.Heart, MetricPalette.Heart.container, MetricPalette.Heart.onContainer,
                    "Fréquence cardiaque",
                    when {
                        !bandConnected -> "Bracelet non connecté"
                        liveHeartRate == null -> "Bracelet connecté · en attente"
                        else -> "Bracelet · en direct"
                    },
                    liveHeartRate?.let { "$it${NBSP}bpm" } ?: "—",
                    MetricPalette.Heart.accent,
                    Radii(6.dp),
                )
                StatusRow(
                    BoucleIcons.Mountain, MetricPalette.Body.container, MetricPalette.Body.onContainer,
                    "Altitude",
                    if (hasBarometer) "Baromètre du téléphone, calé sur le GPS" else "GPS du téléphone",
                    preview.altitude?.let { "${it.roundToInt()}${NBSP}m" } ?: "—",
                    MetricPalette.Body.accent,
                    Radii.ListBottom,
                )
            }
        }
        PillButton(
            "Démarrer",
            onClick = { onStart(sport) },
            color = BoucleColors.Primary,
            contentColor = BoucleColors.OnPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            height = 72.dp,
            icon = BoucleIcons.Play,
            style = BoucleType.buttonXL,
        )
    }
}

/** Explique pourquoi l'app demande la position, avant la demande du système. */
@Composable
fun ActivityPermissionScreen(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reasons = listOf(
        Triple(BoucleIcons.Timer, "Seulement pendant une activité", "Rien n'est relevé avant « Démarrer » ni après « Terminer »."),
        Triple(BoucleIcons.Lock, "Le tracé reste sur ton téléphone", "Comme le reste de tes données Boucle."),
    )
    Column(modifier) {
        Row(Modifier.padding(start = 12.dp, top = 16.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(BoucleShapes.pill)
                    .background(BoucleColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = "Fermer", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(BoucleIcons.Close, contentDescription = "Fermer", tint = BoucleColors.OnSurface, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            IconBadge(BoucleIcons.Pin, BoucleColors.PrimaryContainer, BoucleColors.OnPrimaryContainer, shape = BadgeShape.Cookie, size = 96.dp, iconSize = 44.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Ta position, le temps d'une sortie",
                    Modifier.semantics { heading() },
                    style = BoucleType.screenTitle.copy(lineHeight = BoucleType.screenTitle.fontSize * 1.05f),
                )
                Text(
                    "Pour tracer ton parcours, Boucle utilise le GPS de ton téléphone. Ton bracelet n'en a pas.",
                    style = BoucleType.bodyLarge,
                    color = BoucleColors.OnSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                reasons.forEachIndexed { i, (icon, title, detail) ->
                    ExpressiveSurface(
                        color = BoucleColors.Surface,
                        radii = if (i == 0) Radii.ListTop else Radii.ListBottom,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconBadge(icon, MetricPalette.Activity.container, MetricPalette.Activity.onContainer)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(title, style = BoucleType.rowTitle)
                                Text(detail, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (denied) {
                Text(
                    "La position a été refusée. Tu peux l'autoriser dans les réglages de l'application.",
                    style = BoucleType.body,
                    color = BoucleColors.Error,
                )
            }
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillButton(
                if (denied) "Ouvrir les réglages" else "Autoriser la position",
                onClick = if (denied) onOpenSettings else onAllow,
                color = BoucleColors.Primary,
                contentColor = BoucleColors.OnPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            TextAction("Plus tard", onBack, BoucleColors.Primary, Modifier.fillMaxWidth())
        }
    }
}

/** « Activité détectée » : l'effort relevé par le bracelet, le sport à confirmer et le réglage de la détection. */
@Composable
fun ActivityDetectedScreen(
    activity: DetectedActivity?,
    detectionEnabled: Boolean,
    onConfirm: (DetectedActivity, Sport) -> Unit,
    onDismiss: (DetectedActivity) -> Unit,
    onToggleDetection: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Activité détectée", onBack)
        if (activity == null) {
            Text(
                "Aucune activité à vérifier pour le moment.",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = BoucleType.bodyLarge,
                color = BoucleColors.OnSurfaceVariant,
            )
            DetectionToggle(detectionEnabled, onToggleDetection, Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        var sportName by rememberSaveable(activity.path) { mutableStateOf(activity.sport.name) }
        val sport = Sport.named(sportName) ?: activity.sport
        val end = activity.start.plusSeconds(activity.durationSec.toLong())
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BoucleColors.Primary, RoundedCornerShape(44.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${activity.start.format(recentDate).replaceFirstChar { it.titlecase(Locale.FRENCH) }} · " +
                            "${activity.start.format(clock)} – ${end.format(clock)}",
                        style = BoucleType.smallStrong,
                        color = ChartColors.RingSteps,
                    )
                    Text(
                        detectedTitle(sport),
                        Modifier.semantics { heading() },
                        style = BoucleType.valueXL.copy(lineHeight = BoucleType.valueXL.fontSize * 1.05f),
                        color = BoucleColors.OnPrimary,
                    )
                }
                StepBars(activity)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val shapes = listOf(
                        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                        RoundedCornerShape(6.dp),
                        RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp),
                    )
                    listOf(
                        "Durée" to ActivityFormat.duration(activity.durationSec),
                        "Cadence" to "${activity.cadence}${NBSP}pas/min",
                        "Pas" to formatThousands(activity.steps),
                    ).forEachIndexed { i, (label, value) ->
                        Column(
                            Modifier
                                .weight(1f)
                                .background(ChartColors.StepsInset, shapes[i])
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(label, style = BoucleType.caption.copy(fontWeight = FontWeight.Medium), color = ChartColors.RingSteps)
                            Text(value, style = BoucleType.valueS, color = BoucleColors.OnPrimary)
                        }
                    }
                }
            }
            ExpressiveSurface(
                color = BoucleColors.Surface,
                radii = Radii.CardSmall,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("C'était bien ça ?", Modifier.semantics { heading() }, style = BoucleType.sectionTitle)
                    Text(
                        "Le bracelet a relevé des pas réguliers pendant au moins ${ActivityDetection.MIN_MINUTES} minutes. Corrige le sport si besoin.",
                        style = BoucleType.body,
                        color = BoucleColors.OnSurfaceVariant,
                    )
                }
                SportSelector(
                    selected = sport,
                    onSelect = { sportName = it.name },
                    selectedColor = BoucleColors.Primary,
                    selectedContent = BoucleColors.OnPrimary,
                    idleColor = BoucleColors.Background,
                    idleContent = BoucleColors.OnSurface,
                    height = 88.dp,
                )
            }
            ExpressiveSurface(
                color = BoucleColors.Surface,
                radii = Radii.Tile,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBadge(BoucleIcons.Pin, MetricPalette.Activity.container, MetricPalette.Activity.onContainer)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Sans tracé GPS", style = BoucleType.rowTitle)
                        Text(
                            if (sport == Sport.BIKE) {
                                "Sans tracé, la distance à vélo ne peut pas être estimée. Lance l'activité depuis Boucle pour avoir la carte."
                            } else {
                                "La distance est estimée à partir des pas. Lance l'activité depuis Boucle pour avoir la carte."
                            },
                            style = BoucleType.small,
                            color = BoucleColors.OnSurfaceVariant,
                        )
                    }
                }
            }
            DetectionToggle(detectionEnabled, onToggleDetection)
        }
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PillButton(
                when (sport) {
                    Sport.WALK -> "Ajouter cette marche"
                    Sport.RUN -> "Ajouter cette course"
                    Sport.BIKE -> "Ajouter cette sortie vélo"
                },
                onClick = { onConfirm(activity, sport) },
                color = BoucleColors.Primary,
                contentColor = BoucleColors.OnPrimary,
                modifier = Modifier.fillMaxWidth(),
            )
            TextAction("Ce n'était pas une activité", { onDismiss(activity) }, BoucleColors.Primary, Modifier.fillMaxWidth())
        }
    }
}

/** Pas de chaque minute autour de l'effort : les minutes soutenues en clair. */
@Composable
private fun StepBars(activity: DetectedActivity) {
    val peak = activity.minuteSteps.maxOrNull()?.coerceAtLeast(1) ?: 1
    // Au plus une quarantaine de barres : les efforts longs sont regroupés.
    val group = maxOf(1, (activity.minuteSteps.size + 39) / 40)
    val bars = activity.minuteSteps.chunked(group).map { it.average().toFloat() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics {
                contentDescription = "Pas par minute autour de l'effort : ${activity.cadence} pas par minute en moyenne " +
                    "pendant ${ActivityFormat.duration(activity.durationSec)}"
            },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { steps ->
            val sustained = steps >= ActivityDetection.WALK_CADENCE
            Box(
                Modifier
                    .weight(1f)
                    .height((72 * steps / peak).coerceAtLeast(6f).dp)
                    .background(if (sustained) ChartColors.RingSteps else ChartColors.RingTrack, RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
private fun DetectionToggle(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(BoucleShapes.tile)
            .background(BoucleColors.Surface)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Détection automatique", style = BoucleType.rowTitle)
            Text(
                "Proposer une activité après ${ActivityDetection.MIN_MINUTES} min d'effort continu",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = BoucleColors.Primary, checkedThumbColor = BoucleColors.OnPrimary),
        )
        Spacer(Modifier.width(0.dp))
    }
}
