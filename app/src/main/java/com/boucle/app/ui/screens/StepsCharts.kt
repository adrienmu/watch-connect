package com.boucle.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.StepDay
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.RingGauge
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.components.toShape
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Graphiques et navigation de l'historique des pas, partagés par la tuile Pas de l'écran Aujourd'hui et par
 * celle de l'onglet Forme. Les graphiques se dessinent sur le bloc vert plein.
 */

private val WEEK_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")

/** Mise en forme française des dates et des pas de l'historique. */
internal object StepsFormat {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    private val dayFullMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)
    private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)

    /** « 16 – 22 novembre », ou « 26 oct. – 1 nov. » à cheval sur deux mois. */
    fun weekRange(from: LocalDate, to: LocalDate): String =
        if (from.month == to.month) "${from.dayOfMonth} – ${to.format(dayFullMonth)}" else "${from.format(dayMonth)} – ${to.format(dayMonth)}"

    /** « Novembre 2026 ». */
    fun month(month: YearMonth): String = month.format(monthYear).capitalized()

    /** « Mercredi 18 novembre ». */
    fun longDay(date: LocalDate): String = date.format(longDay).capitalized()

    /** « 8 412 pas ». */
    fun steps(steps: Int): String = "${formatThousands(steps)}${SleepFormat.NBSP}pas"

    /** « 84 % de l'objectif · encore 1 588 pas », ou « Objectif atteint · +1 240 pas ». */
    fun progress(day: StepDay): String {
        val steps = day.steps ?: return "Aucune mesure de pas"
        return if (day.reached) {
            if (steps > day.goal) "Objectif atteint · +${steps(steps - day.goal)}" else "Objectif atteint"
        } else {
            "${Math.round(day.fraction * 100)}${SleepFormat.NBSP}% de l'objectif · encore ${steps(day.goal - steps)}"
        }
    }

    private fun String.capitalized() = replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

/**
 * Ligne « ‹ période › ». Avec [onLabelClick], la période devient un bouton blanc précédé d'un calendrier (choix
 * d'une date) ; sinon c'est un simple titre centré.
 */
@Composable
internal fun PeriodNavigator(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
    previousLabel: String,
    nextLabel: String,
    buttonColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    previousEnabled: Boolean = true,
    onLabelClick: (() -> Unit)? = null,
    labelClickDescription: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundIconButton(BoucleIcons.ChevronLeft, previousLabel, buttonColor, contentColor, previousEnabled, onPrevious)
        if (onLabelClick != null) {
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(BoucleColors.Surface, CircleShape)
                    .clickable(role = Role.Button, onClickLabel = labelClickDescription, onClick = onLabelClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Icon(BoucleIcons.Calendar, contentDescription = null, tint = BoucleColors.Primary, modifier = Modifier.size(18.dp))
                Text(label, style = BoucleType.bodyStrong.copy(fontWeight = FontWeight.Bold), color = BoucleColors.OnSurface)
            }
        } else {
            Text(
                label,
                style = BoucleType.rowTitle,
                color = contentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
        RoundIconButton(BoucleIcons.ChevronRight, nextLabel, buttonColor, contentColor, nextEnabled, onNext)
    }
}

/** Bouton rond de 44 dp (cible tactile minimale) ; estompé quand il est désactivé. */
@Composable
internal fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val haptics = rememberHaptics()
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(container, CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

/**
 * Barres de pas d'une semaine sur le bloc vert, avec la ligne d'objectif en pointillés. Les jours à l'objectif
 * sont clairs, les autres plus sombres, les jours sans mesure réduits à un trait ; [selected] est cerclé de
 * blanc. Avec [onSelect], toucher une barre choisit ce jour.
 */
@Composable
internal fun StepBars(
    days: List<StepDay>,
    selected: LocalDate?,
    today: LocalDate,
    height: Dp,
    modifier: Modifier = Modifier,
    showNumbers: Boolean = false,
    onSelect: ((LocalDate) -> Unit)? = null,
) {
    val goal = (days.firstOrNull { it.date == selected } ?: days.last()).goal
    // L'objectif reste aux deux tiers de la hauteur tant qu'aucun jour ne le dépasse de moitié.
    val scale = maxOf(days.maxOf { it.steps ?: 0 }, goal * 3 / 2).coerceAtLeast(1)
    val summary = days.joinToString(prefix = "Pas de la semaine : ") { spoken(it, today) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .drawBehind {
                    val y = size.height * (1f - goal.toFloat() / scale)
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
                }
                .then(if (onSelect == null) Modifier.clearAndSetSemantics { contentDescription = summary } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                val future = day.date > today
                val target = if (day.steps == null) 0f else day.steps.toFloat() / scale
                val grown by animateFloatAsState(target, MaterialTheme.motionScheme.slowSpatialSpec(), label = "stepBar")
                val isSelected = day.date == selected
                val column = if (onSelect != null && !future) {
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = "Afficher ce jour") { onSelect(day.date) }
                        .clearAndSetSemantics { contentDescription = spoken(day, today) }
                } else {
                    Modifier
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(column),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (day.steps == null) {
                        Box(
                            Modifier
                                .widthIn(max = 34.dp)
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(ChartColors.RingTrack, CircleShape),
                        )
                    } else {
                        val shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
                        Box(
                            Modifier
                                .widthIn(max = 34.dp)
                                .fillMaxWidth()
                                .height((height * grown.coerceIn(0f, 1f)).coerceAtLeast(10.dp))
                                .background(if (day.reached) ChartColors.RingSteps else ChartColors.StepsBelowGoal, shape)
                                .then(if (isSelected) Modifier.border(3.dp, Color.White, shape) else Modifier),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { index, day ->
                val isSelected = day.date == selected
                val style = BoucleType.caption.copy(fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold)
                val color = if (isSelected) Color.White else ChartColors.RingSteps
                Column(
                    Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(WEEK_LETTERS[index % 7], style = style, color = color)
                    if (showNumbers) Text(day.date.dayOfMonth.toString(), style = style, color = color)
                }
            }
        }
    }
}

/**
 * Calendrier d'un mois sur le bloc vert : un macaron clair pour un jour à l'objectif, un anneau partiel sinon,
 * le seul numéro pour un jour à venir ou sans mesure. [selected] est encadré de blanc ; toucher un jour le choisit.
 */
@Composable
internal fun StepMonthGrid(
    days: List<StepDay>,
    selected: LocalDate?,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lead = days.first().date.dayOfWeek.value - 1
    val cells: List<StepDay?> = List(lead) { null } + days
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WEEK_LETTERS.forEach { letter ->
                Text(
                    letter,
                    style = BoucleType.captionStrong,
                    color = ChartColors.RingSteps,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics { },
                )
            }
        }
        cells.chunked(7).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f).height(46.dp))
                    } else {
                        MonthCell(day, day.date == selected, today, { onSelect(day.date) }, Modifier.weight(1f))
                    }
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f).height(46.dp)) }
            }
        }
    }
}

@Composable
private fun MonthCell(day: StepDay, selected: Boolean, today: LocalDate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val future = day.date > today
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(46.dp)
            .clip(shape)
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(enabled = !future, role = Role.Button, onClickLabel = "Afficher ce jour", onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken(day, today) },
        contentAlignment = Alignment.Center,
    ) {
        val number = day.date.dayOfMonth.toString()
        when {
            day.reached -> {
                Box(Modifier.size(38.dp).background(ChartColors.RingSteps, BadgeShape.Cookie.toShape()))
                Text(number, style = BoucleType.body.copy(fontWeight = FontWeight.ExtraBold), color = BoucleColors.OnPrimaryContainer)
            }
            day.steps != null -> {
                RingGauge(day.fraction, ChartColors.RingSteps, ChartColors.RingTrack, Modifier.size(34.dp), strokeWidth = 3.5.dp)
                Text(number, style = BoucleType.body.copy(fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold), color = Color.White)
            }
            else -> Text(number, style = BoucleType.body.copy(fontWeight = FontWeight.SemiBold), color = ChartColors.StepsMuted)
        }
    }
}

/** Cercle en pointillés derrière un jour à venir. */
internal fun Modifier.dashedCircle(color: Color, strokeWidth: Dp = 2.dp): Modifier = drawBehind {
    val stroke = strokeWidth.toPx()
    drawCircle(
        color = color,
        radius = (size.minDimension - stroke) / 2,
        style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))),
    )
}

private val spokenDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

private fun spoken(day: StepDay, today: LocalDate): String {
    val name = day.date.format(spokenDay)
    return when {
        day.date > today -> "$name, à venir"
        day.steps == null -> "$name, sans mesure"
        day.reached -> "$name, ${StepsFormat.steps(day.steps)}, objectif atteint"
        else -> "$name, ${StepsFormat.steps(day.steps)}"
    }
}
