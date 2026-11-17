package com.boucle.app.ui.screens

import com.boucle.app.ui.components.rememberHaptics
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_LABELS = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
private val SPOKEN_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

/** Rayons des boutons du groupe : grand coin à l'extérieur, 10 dp entre deux boutons. */
private val FirstDay = Radii(20.dp, 10.dp, 10.dp, 20.dp)
private val MiddleDay = Radii(10.dp)
private val LastDay = Radii(10.dp, 20.dp, 20.dp, 10.dp)

/**
 * Groupe de boutons connectés des 7 jours qui finissent [today]. Le jour choisi devient une pilule
 * plus large, de la couleur primaire : largeur, forme et couleurs suivent les ressorts du thème.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TodayDaySelector(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val days = remember(today) { (6L downTo 0L).map { today.minusDays(it) } }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEachIndexed { index, day ->
            val checked = day == selected
            val radii = when (index) {
                0 -> FirstDay
                days.lastIndex -> LastDay
                else -> MiddleDay
            }
            val weight by animateFloatAsState(
                targetValue = if (checked) 1.3f else 1f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                label = "dayWeight",
            )
            val container by animateColorAsState(
                targetValue = if (checked) BoucleColors.Primary else BoucleColors.Divider,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "dayContainer",
            )
            val content by animateColorAsState(
                targetValue = if (checked) BoucleColors.OnPrimary else BoucleColors.OnSurface,
                animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                label = "dayContent",
            )
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    if (!checked) haptics.select()
                    onSelect(day)
                },
                modifier = Modifier
                    .weight(weight)
                    .heightIn(min = 56.dp),
                shapes = ToggleButtonShapes(
                    shape = radii.shape(),
                    pressedShape = RoundedCornerShape(6.dp),
                    checkedShape = RoundedCornerShape(percent = 50),
                ),
                colors = ToggleButtonDefaults.colors(
                    containerColor = container,
                    contentColor = content,
                    checkedContainerColor = container,
                    checkedContentColor = content,
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Column(
                    Modifier.clearAndSetSemantics { contentDescription = day.format(SPOKEN_DAY) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        DAY_LABELS[day.dayOfWeek.value - 1],
                        style = BoucleType.caption.copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium),
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = BoucleType.rowTitle.copy(fontWeight = if (checked) FontWeight.ExtraBold else FontWeight.Bold),
                    )
                }
            }
        }
    }
}
