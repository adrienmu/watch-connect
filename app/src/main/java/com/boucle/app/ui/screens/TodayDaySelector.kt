package com.boucle.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.StepDay
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.RingGauge
import com.boucle.app.ui.components.rememberHaptics
import com.boucle.app.ui.components.toShape
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_LABELS = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
private val SPOKEN_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/**
 * Choix du jour, semaine par semaine. La première ligne recule ou avance d'une semaine et ouvre un calendrier
 * pour aller à n'importe quelle date ; la seconde montre les 7 jours de [week] (du lundi au dimanche) : un anneau
 * donne la part de l'objectif de pas, remplacé par un macaron plein une fois l'objectif atteint.
 */
@Composable
internal fun TodayWeekSelector(
    selected: LocalDate,
    today: LocalDate,
    week: List<StepDay>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PeriodNavigator(
            label = StepsFormat.weekRange(week.first().date, week.last().date),
            onPrevious = { onSelect(selected.minusDays(7)) },
            onNext = { onSelect(minOf(selected.plusDays(7), today)) },
            nextEnabled = week.last().date < today,
            previousLabel = "Semaine précédente",
            nextLabel = "Semaine suivante",
            buttonColor = BoucleColors.Divider,
            contentColor = BoucleColors.OnSurface,
            modifier = Modifier.padding(horizontal = 16.dp),
            onLabelClick = { pickingDate = true },
            labelClickDescription = "Choisir une date",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            week.forEachIndexed { index, day ->
                DayCell(
                    day = day,
                    label = DAY_LABELS[index],
                    selected = day.date == selected,
                    future = day.date > today,
                    onClick = {
                        if (day.date != selected) haptics.select()
                        onSelect(day.date)
                    },
                )
            }
        }
    }
    if (pickingDate) {
        DayPickerDialog(
            selected = selected,
            today = today,
            onPick = {
                pickingDate = false
                onSelect(it)
            },
            onDismiss = { pickingDate = false },
        )
    }
}

/** Un jour de la semaine : libellé, puis anneau de progression ou macaron plein autour du numéro. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.DayCell(
    day: StepDay,
    label: String,
    selected: Boolean,
    future: Boolean,
    onClick: () -> Unit,
) {
    val weight by animateFloatAsState(
        targetValue = if (selected) 1.25f else 1f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "dayWeight",
    )
    val container by animateColorAsState(
        targetValue = if (selected) BoucleColors.PrimaryContainer else BoucleColors.Background,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "dayContainer",
    )
    val shape = RoundedCornerShape(24.dp)
    val spoken = buildString {
        append(day.date.format(SPOKEN_DAY))
        append(
            when {
                future -> ", à venir"
                day.reached -> ", objectif de pas atteint"
                day.steps != null -> ", ${Math.round(day.fraction * 100)}${SleepFormat.NBSP}% de l'objectif de pas"
                else -> ", sans mesure de pas"
            },
        )
    }
    Column(
        Modifier
            .weight(weight)
            .height(76.dp)
            .clip(shape)
            .background(container, shape)
            .selectable(selected = selected, enabled = !future, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Text(
            label,
            style = BoucleType.caption.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) BoucleColors.OnPrimaryContainer else BoucleColors.OnSurfaceVariant,
        )
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val number = day.date.dayOfMonth.toString()
            when {
                future -> {
                    Box(Modifier.size(40.dp).dashedCircle(ChartDashes.FutureDay))
                    Text(number, style = BoucleType.bodyStrong, color = BoucleColors.OnSurfaceVariant)
                }
                day.reached -> {
                    Box(Modifier.size(40.dp).background(BoucleColors.Primary, BadgeShape.Cookie.toShape()))
                    Text(number, style = BoucleType.bodyStrong.copy(fontWeight = FontWeight.ExtraBold), color = BoucleColors.OnPrimary)
                }
                else -> {
                    RingGauge(
                        fraction = day.fraction,
                        color = BoucleColors.Primary,
                        trackColor = if (selected) BoucleColors.Surface else ChartDashes.RingTrack,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        number,
                        style = BoucleType.bodyStrong.copy(fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold),
                        color = if (selected) BoucleColors.OnPrimaryContainer else BoucleColors.OnSurface,
                    )
                }
            }
        }
    }
}

/** Calendrier pour aller directement à une date passée (ou à aujourd'hui). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val lastSelectable = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastSelectable
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis
                    if (millis == null) onDismiss() else onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                },
            ) { Text("Afficher") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    ) {
        DatePicker(state = state)
    }
}

/** Couleurs neutres du sélecteur. */
private object ChartDashes {
    val FutureDay = androidx.compose.ui.graphics.Color(0xFFC9D3CC)
    val RingTrack = androidx.compose.ui.graphics.Color(0xFFDCE4DE)
}
