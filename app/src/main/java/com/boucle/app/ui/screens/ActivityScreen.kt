package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.Vo2MaxEstimate
import com.boucle.app.domain.StepHistory
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.RowChevron
import com.boucle.app.ui.components.ScreenHeader
import com.boucle.app.ui.components.StatTile
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricPalette
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val headerDate = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.FRENCH)

/**
 * Onglet Forme : historique des pas (semaine ou mois, jusqu'à la première mesure), dépense, objectif d'activité,
 * séances du jour et VO2max.
 */
@Composable
fun ActivityScreen(
    state: DayUiState,
    history: StepHistory,
    onSelectDate: (LocalDate) -> Unit,
    onEditGoals: () -> Unit,
    onNavigate: (Destination) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Forme",
            modifier = Modifier.staggeredEntrance(0),
            trailing = {
                Text(
                    state.date.format(headerDate).replaceFirstChar { it.titlecase(Locale.FRENCH) },
                    style = BoucleType.smallStrong.copy(fontWeight = FontWeight.Medium),
                    color = BoucleColors.OnSurfaceVariant,
                )
            },
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var index = 1
            val activity = state.activity
            StepsHistoryTile(
                selected = state.date,
                history = history,
                today = LocalDate.now(),
                onSelectDate = onSelectDate,
                onEditGoals = onEditGoals,
                modifier = Modifier.sharedContainer(SharedKeys.ACTIVITY, Radii.Hero, entranceIndex = index++),
            )
            if (activity == null) {
                ActivityEmptyCard(
                    icon = BoucleIcons.Pulse,
                    palette = MetricPalette.Activity,
                    title = "Pas encore d'activité",
                    message = "Les pas, les calories et le temps actif de cette journée apparaîtront ici après " +
                        "une synchronisation du bracelet ou de Health Connect.",
                    modifier = Modifier.staggeredEntrance(index++),
                )
            } else {
                EnergyTiles(activity, Modifier.staggeredEntrance(index++))
                if (activity.hasActivityGoal()) ActivityGoalCard(activity, Modifier.staggeredEntrance(index++))
            }
            state.sessions.forEach { session ->
                SessionCard(session, onClick = { onOpenSession(session.path) }, modifier = Modifier.staggeredEntrance(index++))
            }
            state.vo2Max?.let { vo2 ->
                Vo2MaxRow(
                    vo2,
                    onClick = { onNavigate(Destination.Vo2Max) },
                    modifier = Modifier.sharedContainer(SharedKeys.VO2MAX, Radii.Tile, entranceIndex = index),
                )
            }
        }
    }
}

/** Grille 2 × 2 : distance, calories totales, calories actives et métabolisme de base. */
@Composable
private fun EnergyTiles(activity: DailyActivityEntity, modifier: Modifier = Modifier) {
    val active = activity.activityCalories
    val bmr = activity.bmrCalories
    val total = if (active != null || bmr != null) (active ?: 0) + (bmr ?: 0) else null
    fun kcal(value: Int?) = value?.let { "${formatThousands(it)}${NBSP}kcal" } ?: "—"
    val tiles = listOf(
        "Distance" to (activity.distanceMeters?.let { "${ActivityFormat.decimal(it / 1000.0, 2)}${NBSP}km" } ?: "—"),
        "Calories" to kcal(total),
        "Calories actives" to kcal(active),
        "Métabolisme de base" to kcal(bmr),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    StatTile(label, value, Radii.Tile, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

private fun DailyActivityEntity.hasActivityGoal(): Boolean =
    (activityGoal ?: 0f) > 0f && achievedActivity != null || intensitySeconds().sum() > 0

private fun DailyActivityEntity.intensitySeconds(): List<Int> =
    listOf(sedentarySec, lightSec, moderateSec, vigorousSec).map { it ?: 0 }

/** Objectif d'activité : pourcentage atteint et répartition du temps éveillé par intensité. */
@Composable
private fun ActivityGoalCard(activity: DailyActivityEntity, modifier: Modifier = Modifier) {
    val goal = activity.activityGoal?.takeIf { it > 0f }
    val achieved = activity.achievedActivity
    val seconds = activity.intensitySeconds()
    val levels = listOf(
        "Sédentaire" to ChartColors.Sedentary,
        "Légère" to ChartColors.Light,
        "Modérée" to ChartColors.Moderate,
        "Intense" to ChartColors.Vigorous,
    )
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Objectif d'activité", style = BoucleType.sectionTitle, modifier = Modifier.alignByBaseline())
            if (goal != null && achieved != null) {
                AnimatedNumber(
                    (achieved / goal * 100).roundToInt(),
                    BoucleType.smallStrong,
                    modifier = Modifier.alignByBaseline(),
                    color = BoucleColors.Primary,
                    format = { "$it${NBSP}%" },
                )
            }
        }
        if (seconds.sum() > 0) {
            ActivityProportionBar(
                segments = seconds.zip(levels) { s, (_, color) -> s.toFloat() to color },
                description = "Répartition du temps éveillé par intensité : " +
                    seconds.zip(levels) { s, (name, _) -> "${name.lowercase(Locale.FRENCH)} ${ActivityFormat.duration(s)}" }.joinToString(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                seconds.zip(levels).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (s, level) ->
                            IntensityLegend("${level.first} · ${ActivityFormat.duration(s)}", level.second, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntensityLegend(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = BoucleType.small)
    }
}

/** Une séance du jour : sport, heure de début, durée, charge cardio et principaux chiffres. */
@Composable
private fun SessionCard(session: TrainingSessionEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val start = session.startTime?.let { runCatching { LocalDateTime.parse(it).toLocalTime() }.getOrNull() }
    val timing = listOfNotNull(
        start?.let { "Début %02d:%02d".format(it.hour, it.minute) },
        session.durationSec?.let(ActivityFormat::duration),
    ).joinToString(" · ")
    val heartRate = if (session.averageHr != null || session.maxHr != null) {
        "${session.averageHr ?: "—"} / ${session.maxHr ?: "—"}"
    } else {
        "—"
    }
    val metrics = listOf(
        "Distance" to (session.distanceMeters?.takeIf { it > 0f }?.let { "${ActivityFormat.decimal(it / 1000.0, 2)}${NBSP}km" } ?: "—"),
        "FC moy. / max" to heartRate,
        "Calories" to (session.calories?.let { "${formatThousands(it)}${NBSP}kcal" } ?: "—"),
    )
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onClickLabel = "Voir le détail de la séance",
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(
                BoucleIcons.Pulse,
                container = MetricPalette.Activity.container,
                content = MetricPalette.Activity.onContainer,
                shape = BadgeShape.Cookie,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.name?.takeIf { it.isNotBlank() } ?: "Entraînement", style = BoucleType.bodyStrong)
                if (timing.isNotEmpty()) Text(timing, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
            session.cardioLoad?.let { load ->
                Text(
                    "Charge cardio ${load.roundToInt()}",
                    style = BoucleType.captionStrong,
                    modifier = Modifier
                        .background(BoucleColors.Background, BoucleShapes.pill)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            RowChevron()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            metrics.forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
                    Text(value, style = BoucleType.valueS.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

/** Ligne vers le détail de la VO2max, la valeur dans une pastille carrée. */
@Composable
private fun Vo2MaxRow(vo2: Vo2MaxEstimate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Body
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Tile,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onClickLabel = "Voir le détail de la VO2max",
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(palette.container, BoucleShapes.iconSquare),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedNumber(vo2.value, BoucleType.rowTitle, color = palette.onContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("VO2max estimée", style = BoucleType.bodyStrong)
                Text(
                    "${vo2.category.label} · ${vo2.value}${NBSP}ml/kg/min",
                    style = BoucleType.small,
                    color = BoucleColors.OnSurfaceVariant,
                )
            }
            RowChevron()
        }
    }
}
