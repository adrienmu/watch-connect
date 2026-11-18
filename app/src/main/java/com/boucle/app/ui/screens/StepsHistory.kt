package com.boucle.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.StepDay
import com.boucle.app.domain.StepHistory
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import java.time.LocalDate
import java.time.YearMonth

/** Période affichée par la tuile de l'historique des pas. */
private enum class StepsView(val label: String) { Week("Semaine"), Month("Mois") }

/**
 * Tuile de l'historique des pas (bloc vert plein) : choix Semaine / Mois, navigation d'une période à l'autre
 * jusqu'à la première mesure, barres de la semaine ou calendrier du mois, et bilan de la période. Toucher un
 * jour le choisit pour toute l'app ([onSelectDate]) ; le crayon ouvre le réglage des objectifs.
 */
@Composable
internal fun StepsHistoryTile(
    selected: LocalDate,
    history: StepHistory,
    today: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    onEditGoals: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewName by rememberSaveable { mutableStateOf(StepsView.Week.name) }
    val view = StepsView.valueOf(viewName)
    // Jour qui situe la période affichée : il suit le jour choisi, puis les flèches le déplacent.
    var anchorDay by rememberSaveable { mutableLongStateOf(selected.toEpochDay()) }
    LaunchedEffect(selected) { anchorDay = selected.toEpochDay() }
    val anchor = LocalDate.ofEpochDay(anchorDay)

    val days = when (view) {
        StepsView.Week -> history.week(anchor)
        StepsView.Month -> history.month(YearMonth.from(anchor))
    }
    val stats = StepHistory.stats(days)
    val picked = days.firstOrNull { it.date == selected }
    val firstDate = history.firstDate

    ExpressiveSurface(
        color = BoucleColors.Primary,
        radii = Radii.Hero,
        modifier = modifier.fillMaxWidth(),
        contentColor = BoucleColors.OnPrimary,
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconBadge(
                BoucleIcons.Steps,
                ChartColors.RingSteps,
                BoucleColors.OnPrimaryContainer,
                shape = BadgeShape.Cookie,
                size = 36.dp,
                iconSize = 18.dp,
            )
            Text(
                "Pas · objectif ${formatThousands(history.goals.on(selected).steps)}",
                style = BoucleType.bodyStrong.copy(fontWeight = FontWeight.Bold),
                color = ChartColors.RingSteps,
                modifier = Modifier.weight(1f),
            )
            RoundIconButton(
                icon = BoucleIcons.Edit,
                description = "Régler les objectifs",
                container = ChartColors.RingTrack,
                content = Color.White,
                enabled = true,
                onClick = onEditGoals,
            )
        }
        ViewSwitch(view) { viewName = it.name }
        PeriodNavigator(
            label = when (view) {
                StepsView.Week -> StepsFormat.weekRange(days.first().date, days.last().date)
                StepsView.Month -> StepsFormat.month(YearMonth.from(anchor))
            },
            onPrevious = { anchorDay = anchor.shifted(view, -1).toEpochDay() },
            onNext = { anchorDay = anchor.shifted(view, 1).toEpochDay() },
            previousEnabled = firstDate != null && days.first().date > firstDate,
            nextEnabled = days.last().date < today,
            previousLabel = if (view == StepsView.Week) "Semaine précédente" else "Mois précédent",
            nextLabel = if (view == StepsView.Week) "Semaine suivante" else "Mois suivant",
            buttonColor = ChartColors.RingTrack,
            contentColor = Color.White,
        )
        Headline(view, picked?.takeIf { it.date <= today }, stats.average)
        when (view) {
            StepsView.Week -> StepBars(days, selected, today, height = 120.dp, showNumbers = true, onSelect = onSelectDate)
            StepsView.Month -> StepMonthGrid(days, selected, today, onSelectDate)
        }
        val reached = "${stats.reachedDays}${SleepFormat.NBSP}j sur ${stats.recordedDays}"
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            InsetStat("Objectif atteint", reached, Radii(20.dp, 6.dp, 6.dp, 20.dp))
            when (view) {
                StepsView.Week -> {
                    InsetStat("Moyenne", stats.average?.let(::formatThousands) ?: "—", Radii(6.dp))
                    InsetStat("Total", formatThousands(stats.total), Radii(6.dp, 20.dp, 20.dp, 6.dp))
                }
                StepsView.Month -> {
                    InsetStat("Meilleur jour", stats.best?.steps?.let(::formatThousands) ?: "—", Radii(6.dp))
                    InsetStat("Plus longue série", "${stats.longestStreak}${SleepFormat.NBSP}j", Radii(6.dp, 20.dp, 20.dp, 6.dp))
                }
            }
        }
    }
}

private fun LocalDate.shifted(view: StepsView, periods: Long): LocalDate = when (view) {
    StepsView.Week -> plusWeeks(periods)
    StepsView.Month -> plusMonths(periods)
}

/** Le jour choisi s'il est dans la période affichée, sinon la moyenne de la période. */
@Composable
private fun Headline(view: StepsView, picked: StepDay?, average: Int?) {
    Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val kicker = when {
            picked != null -> StepsFormat.longDay(picked.date)
            view == StepsView.Week -> "Moyenne de la semaine"
            else -> "Moyenne du mois"
        }
        Text(kicker, style = BoucleType.smallStrong, color = ChartColors.RingSteps)
        val value = if (picked != null) picked.steps else average
        if (value != null) AnimatedNumber(value, BoucleType.heroXS) else Text("—", style = BoucleType.heroXS)
        Text(
            if (picked != null) StepsFormat.progress(picked) else "pas par jour · touche un jour pour le détail",
            style = BoucleType.body.copy(fontWeight = FontWeight.SemiBold),
            color = ChartColors.RingSteps,
        )
    }
}

/** Choix Semaine / Mois : deux segments dans une pilule sombre, le segment actif en clair. */
@Composable
private fun ViewSwitch(current: StepsView, onChange: (StepsView) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChartColors.StepsInset, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepsView.entries.forEach { view ->
            val active = view == current
            val container by animateColorAsState(
                if (active) ChartColors.RingSteps else ChartColors.StepsInset,
                MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "viewSegment",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(container, CircleShape)
                    .selectable(selected = active, role = Role.Tab) {
                        if (!active) haptics.select()
                        onChange(view)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    view.label,
                    style = BoucleType.bodyStrong.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (active) BoucleColors.OnPrimaryContainer else Color.White,
                )
            }
        }
    }
}

/** Chiffre du bilan dans un encart sombre. */
@Composable
private fun RowScope.InsetStat(label: String, value: String, radii: Radii) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .background(ChartColors.StepsInset, radii.shape())
            .padding(12.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = BoucleType.caption.copy(fontWeight = FontWeight.Medium), color = ChartColors.RingSteps)
        Text(value, style = BoucleType.valueS)
    }
}
