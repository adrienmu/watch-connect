package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.SleepScore
import com.boucle.app.domain.SleepScoring
import com.boucle.app.domain.band
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.SleepStats
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.LegendDot
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.ScreenHeader
import com.boucle.app.ui.components.SegmentedBar
import com.boucle.app.ui.components.StatTile
import com.boucle.app.ui.components.cookieShape
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Durée d'un tour complet de la forme festonnée du score : lente, pour rester un simple décor. */
private const val COOKIE_TURN_MS = 30_000

private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Onglet Sommeil : score de la nuit dans sa forme festonnée, hypnogramme et durée de chaque
 * stade, piliers du score, cycles et température cutanée.
 */
@Composable
fun SleepScreen(
    state: DayUiState,
    modifier: Modifier = Modifier,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Sommeil",
            subtitle = nightTitle(state),
            modifier = Modifier.staggeredEntrance(0),
        )
        AnimatedContent(
            targetState = state,
            contentKey = { it.date },
            transitionSpec = { fadeIn(effects).togetherWith(fadeOut(effects)) },
            label = "night",
        ) { day ->
            // L'en-tête commun laisse 12 dp sous le titre ; la maquette en veut 8 + 8 avant le contenu.
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val sleep = day.sleep
                if (sleep == null) {
                    NoNightCard(Modifier.staggeredEntrance(1))
                } else {
                    ScoreHero(sleep, day.sleepScore, Modifier.staggeredEntrance(1))
                    StagesCard(sleep, Modifier.staggeredEntrance(2))
                    day.sleepScore?.let { ScoreBreakdown(it, Modifier.staggeredEntrance(3)) }
                    NightTiles(sleep, Modifier.staggeredEntrance(4))
                }
            }
        }
    }
}

/** Bloc héros : score dans la forme festonnée, catégorie, temps dormi, objectif et horaires. */
@Composable
private fun ScoreHero(sleep: SleepStats, score: SleepScore?, modifier: Modifier = Modifier) {
    val colors = MetricPalette.Sleep
    ExpressiveSurface(
        color = colors.container,
        radii = Radii.Hero,
        modifier = modifier.fillMaxWidth(),
        contentColor = colors.onContainer,
        contentPadding = PaddingValues(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreCookie(score?.total)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Score de sommeil", style = BoucleType.smallStrong)
                Text(score?.band?.let(SleepFormat::bandLabel) ?: "—", style = BoucleType.scoreLabel)
                Spacer(Modifier.height(2.dp))
                SleepFormat.asleepSec(sleep)?.let { Text("${SleepFormat.duration(it)} dormies", style = BoucleType.small) }
                sleep.goalMinutes?.takeIf { it > 0 }?.let {
                    Text("Objectif ${SleepFormat.duration(it * 60L)}", style = BoucleType.small)
                }
                if (sleep.start != null && sleep.end != null) {
                    Text("${sleep.start.format(HOUR)} → ${sleep.end.format(HOUR)}", style = BoucleType.smallStrong)
                }
            }
        }
    }
}

/**
 * Forme festonnée de 148 dp qui porte le score. Elle apparaît avec un ressort puis tourne
 * lentement sur elle-même ; le chiffre, lui, reste droit.
 */
@Composable
private fun ScoreCookie(score: Int?) {
    val entrance = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, spatial) }
    // Rotation continue à vitesse constante : un ressort n'a pas de sens pour un mouvement sans fin.
    val rotation by rememberInfiniteTransition(label = "cookie").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(COOKIE_TURN_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "cookieRotation",
    )
    val shape = cookieShape()
    Box(
        Modifier
            .size(148.dp)
            .graphicsLayer {
                scaleX = entrance.value
                scaleY = entrance.value
            },
        contentAlignment = Alignment.Center,
    ) {
        // La maquette dessine la forme avec une marge de 3 unités sur 106 autour de son tracé.
        Box(
            Modifier
                .matchParentSize()
                .padding(8.dp)
                .graphicsLayer { rotationZ = rotation }
                .background(MetricPalette.Sleep.accent, shape),
        )
        if (score != null) {
            AnimatedNumber(score, BoucleType.heroS, color = BoucleColors.OnPrimary)
        } else {
            Text("—", style = BoucleType.heroS, color = BoucleColors.OnPrimary)
        }
    }
}

/** Carte « Stades de la nuit » : hypnogramme, heures repères et durée de chaque stade. */
@Composable
private fun StagesCard(sleep: SleepStats, modifier: Modifier = Modifier) {
    val legend = listOf(SleepStage.Deep, SleepStage.Rem, SleepStage.Light, SleepStage.Wake)
    val durations = legend.associateWith { sleep.secondsByState[it.state] }
    val description = buildString {
        append("Hypnogramme de la nuit")
        if (sleep.cycleCount > 0) append(", ${sleep.cycleCount} cycles")
        legend.forEach { stage -> durations[stage]?.let { append(", ${stage.label.lowercase()} ${SleepFormat.duration(it)}") } }
    }
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Stades de la nuit", style = BoucleType.sectionTitle)
        if (sleep.phases.isEmpty()) {
            Text(
                "Le bracelet n'a pas fourni le détail des stades pour cette nuit.",
                style = BoucleType.body,
                color = BoucleColors.OnSurfaceVariant,
            )
        } else {
            SleepHypnogram(sleep.phases, description)
            TimeAxis(sleep)
        }
        // Légende en grille 2 × 2.
        legend.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { stage ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        LegendDot(stage.label, stage.color)
                        Text(durations[stage]?.let(SleepFormat::duration) ?: "—", style = BoucleType.valueM)
                    }
                }
            }
        }
    }
}

/** Heures repères sous l'hypnogramme : coucher, trois heures régulièrement espacées, réveil. */
@Composable
private fun TimeAxis(sleep: SleepStats) {
    val start: ZonedDateTime = sleep.start ?: return
    val totalSec = sleep.totalSec?.takeIf { it > 0 } ?: return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (0..4).forEach { k ->
            Text(
                start.plusSeconds(totalSec * k / 4).format(HOUR),
                style = BoucleType.caption,
                color = BoucleColors.OnSurfaceVariant,
            )
        }
    }
}

/** Carte « Ce qui compose le score » : les quatre piliers, chacun sur 100. */
@Composable
private fun ScoreBreakdown(score: SleepScore, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ce qui compose le score", style = BoucleType.sectionTitle)
        PillarRow("Durée", score.duration)
        PillarRow("Stades (profond, paradoxal)", score.stages)
        PillarRow("Régularité", score.consistency)
        PillarRow("Continuité", score.interruptions)
        if (score.consistency == null) {
            Text(
                "Régularité indisponible : il faut au moins ${SleepScoring.CONSISTENCY_MIN_NIGHTS} nuits précédentes.",
                style = BoucleType.caption,
                color = BoucleColors.OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PillarRow(label: String, value: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = BoucleType.body)
            val valueStyle = BoucleType.body.copy(fontWeight = FontWeight.Bold)
            if (value != null) AnimatedNumber(value, valueStyle) else Text("—", style = valueStyle)
        }
        SegmentedBar(
            fraction = (value ?: 0) / 100f,
            color = MetricPalette.Sleep.accent,
            trackColor = MetricPalette.Sleep.container,
        )
    }
}

/** Paire de tuiles : nombre de cycles et écart de température cutanée. */
@Composable
private fun NightTiles(sleep: SleepStats, modifier: Modifier = Modifier) {
    Row(
        modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatTile(
            label = "Cycles",
            value = sleep.cycleCount.takeIf { it > 0 }?.toString() ?: "—",
            radii = Radii.PairStart,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        StatTile(
            label = "Température cutanée",
            value = sleep.skinTemperatureDeviationC?.let(::formatDeviation) ?: "—",
            radii = Radii.PairEnd,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
    }
}

/** Carte affichée quand le jour n'a pas de nuit enregistrée. */
@Composable
private fun NoNightCard(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(
            BoucleIcons.Moon,
            MetricPalette.Sleep.container,
            MetricPalette.Sleep.onContainer,
            shape = BadgeShape.Cookie,
            size = 56.dp,
            iconSize = 24.dp,
        )
        Text("Aucune nuit enregistrée.", style = BoucleType.sectionTitle, textAlign = TextAlign.Center)
        Text(
            "Garde ton bracelet la nuit puis synchronise-le pour suivre ton sommeil.",
            style = BoucleType.body,
            color = BoucleColors.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * « Nuit du 15 au 16 novembre », « Nuit du 31 octobre au 1er novembre ». Sans nuit enregistrée,
 * c'est la nuit qui précède le jour affiché.
 */
private fun nightTitle(state: DayUiState): String {
    val from = state.sleep?.start?.toLocalDate() ?: state.date.minusDays(1)
    val to = state.sleep?.end?.toLocalDate() ?: state.date
    return when {
        from == to -> "Nuit du ${dayAndMonth(to)}"
        from.month == to.month -> "Nuit du ${dayOfMonth(from)} au ${dayAndMonth(to)}"
        else -> "Nuit du ${dayAndMonth(from)} au ${dayAndMonth(to)}"
    }
}

private fun dayOfMonth(date: LocalDate): String = if (date.dayOfMonth == 1) "1er" else date.dayOfMonth.toString()

private fun dayAndMonth(date: LocalDate): String =
    "${dayOfMonth(date)} ${date.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)}"

/** +0,12 °C, −0,30 °C : signe explicite, virgule décimale. */
private fun formatDeviation(celsius: Float): String {
    val sign = if (celsius < 0f) "−" else "+"
    return "$sign${"%.2f".format(Locale.FRENCH, abs(celsius))}${SleepFormat.NBSP}°C"
}
