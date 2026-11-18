package com.boucle.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.DailyGoals
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.LinearGauge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricColors
import com.boucle.app.ui.theme.MetricPalette

/**
 * Réglage des objectifs quotidiens de pas et de calories actives. Chaque appui sur − ou + est enregistré
 * aussitôt ([onChange]) et vaut à partir d'aujourd'hui. [todaySteps] et [todayActiveCalories] donnent un aperçu
 * de la journée en cours face à l'objectif ; `null` sans mesure.
 */
@Composable
fun GoalsScreen(
    goals: DailyGoals,
    todaySteps: Int?,
    todayActiveCalories: Int?,
    onChange: (DailyGoals) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Objectifs quotidiens", onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GoalCard(
                    label = "Pas",
                    icon = BoucleIcons.Steps,
                    shape = BadgeShape.Cookie,
                    colors = MetricPalette.Activity,
                    radii = Radii(32.dp, 32.dp, 8.dp, 8.dp),
                    value = formatThousands(goals.steps),
                    unit = null,
                    spoken = "Objectif de pas : ${formatThousands(goals.steps)}",
                    step = "${DailyGoals.STEP_INCREMENT} pas",
                    onLess = { onChange(goals.copy(steps = goals.steps - DailyGoals.STEP_INCREMENT)) },
                    onMore = { onChange(goals.copy(steps = goals.steps + DailyGoals.STEP_INCREMENT)) },
                    lessEnabled = goals.steps > DailyGoals.MIN_STEPS,
                    moreEnabled = goals.steps < DailyGoals.MAX_STEPS,
                    today = todaySteps?.let { "${formatThousands(it)} pas" },
                    fraction = todaySteps?.let { it.toFloat() / goals.steps },
                    modifier = Modifier.staggeredEntrance(0),
                )
                GoalCard(
                    label = "Calories actives",
                    icon = BoucleIcons.Flame,
                    shape = BadgeShape.Sunny,
                    colors = MetricPalette.Energy,
                    radii = Radii(8.dp, 8.dp, 32.dp, 32.dp),
                    value = formatThousands(goals.activeCalories),
                    unit = "kcal",
                    spoken = "Objectif de calories actives : ${formatThousands(goals.activeCalories)} kilocalories",
                    step = "${DailyGoals.ACTIVE_CALORIE_INCREMENT} kilocalories",
                    onLess = { onChange(goals.copy(activeCalories = goals.activeCalories - DailyGoals.ACTIVE_CALORIE_INCREMENT)) },
                    onMore = { onChange(goals.copy(activeCalories = goals.activeCalories + DailyGoals.ACTIVE_CALORIE_INCREMENT)) },
                    lessEnabled = goals.activeCalories > DailyGoals.MIN_ACTIVE_CALORIES,
                    moreEnabled = goals.activeCalories < DailyGoals.MAX_ACTIVE_CALORIES,
                    today = todayActiveCalories?.let { "${formatThousands(it)} kcal" },
                    fraction = todayActiveCalories?.let { it.toFloat() / goals.activeCalories },
                    modifier = Modifier.staggeredEntrance(1),
                )
            }
            Text(
                "Les calories actives sont celles dépensées en bougeant, hors métabolisme de base. Les jauges, les " +
                    "macarons et les séries utilisent ces objectifs à partir d'aujourd'hui ; les jours passés gardent " +
                    "l'objectif qu'ils avaient.",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .staggeredEntrance(2),
            )
        }
    }
}

@Composable
private fun GoalCard(
    label: String,
    icon: ImageVector,
    shape: BadgeShape,
    colors: MetricColors,
    radii: Radii,
    value: String,
    unit: String?,
    spoken: String,
    step: String,
    onLess: () -> Unit,
    onMore: () -> Unit,
    lessEnabled: Boolean,
    moreEnabled: Boolean,
    today: String?,
    fraction: Float?,
    modifier: Modifier = Modifier,
) {
    ExpressiveSurface(
        color = colors.container,
        radii = radii,
        modifier = modifier.fillMaxWidth(),
        contentColor = colors.onContainer,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(icon, colors.accent, Color.White, shape = shape, size = 32.dp, iconSize = 16.dp)
            Text(label, style = BoucleType.body.copy(fontWeight = FontWeight.Bold))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RoundIconButton(BoucleIcons.Minus, "Diminuer de $step", BoucleColors.Surface, colors.onContainer, lessEnabled, onLess, size = 52.dp)
            Row(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(value, style = BoucleType.scoreLabel, modifier = Modifier.alignByBaseline())
                unit?.let { Text("${SleepFormat.NBSP}$it", style = BoucleType.bodyStrong, modifier = Modifier.alignByBaseline()) }
            }
            RoundIconButton(BoucleIcons.Plus, "Augmenter de $step", colors.accent, Color.White, moreEnabled, onMore, size = 52.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearGauge(fraction ?: 0f, colors.accent, colors.accent.copy(alpha = 0.22f), Modifier.clearAndSetSemantics { })
            Text(
                if (today != null && fraction != null) {
                    "Aujourd'hui : $today, soit ${Math.round(fraction * 100)}${SleepFormat.NBSP}% de cet objectif"
                } else {
                    "Pas encore de mesure aujourd'hui"
                },
                style = BoucleType.small,
                textAlign = TextAlign.Start,
            )
        }
    }
}
