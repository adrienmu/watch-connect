package com.boucle.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.HeartRateStats
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.components.formatThousands
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Détail de la fréquence cardiaque : FC de repos nocturne et extrêmes de la journée, plage heure par
 * heure, puis VFC, respiration et nombre d'intervalles PPI.
 */
@Composable
fun HeartScreen(
    state: DayUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Fréquence cardiaque", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stats = state.heartRate
            if (stats == null) {
                HeartEmpty(Modifier.staggeredEntrance(0))
                return@Column
            }
            val resting = state.recovery?.nightHeartRate
                ?: state.recharge?.meanRriMs?.takeIf { it > 0 }?.let { (60_000f / it).roundToInt() }
                ?: stats.min
            HeartHero(resting, stats, Modifier.staggeredEntrance(0))
            val hasHourly = state.hourlyHeartRate.isNotEmpty()
            if (hasHourly) HeartHourlyCard(state, Modifier.staggeredEntrance(1))
            HeartDetails(state, Modifier.staggeredEntrance(if (hasHourly) 2 else 1))
        }
    }
}

/** Bloc rose : FC de repos, puis trois tuiles blanches min / moyenne / max. */
@Composable
private fun HeartHero(resting: Int, stats: HeartRateStats, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Heart
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("FC de repos (nocturne)", style = BoucleType.smallStrong)
            Row {
                AnimatedNumber(resting, BoucleType.heroL, Modifier.alignByBaseline())
                Text(
                    " bpm",
                    style = BoucleType.sectionTitle.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Min" to stats.min, "Moyenne" to stats.average, "Max" to stats.max).forEach { (label, value) ->
                ExpressiveSurface(
                    color = BoucleColors.Surface,
                    radii = Radii.Inner,
                    contentColor = palette.onContainer,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(label, style = BoucleType.caption)
                    AnimatedNumber(value, BoucleType.valueM)
                }
            }
        }
    }
}

/** Carte « Heure par heure » : barres de plage de FC sur 24 h. */
@Composable
private fun HeartHourlyCard(state: DayUiState, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Heure par heure", style = BoucleType.sectionTitle, modifier = Modifier.alignByBaseline())
            Text(
                "$HEART_CHART_MIN_BPM à $HEART_CHART_MAX_BPM bpm",
                style = BoucleType.caption,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
        HeartHourlyChart(state.hourlyHeartRate)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0 h", "6 h", "12 h", "18 h", "24 h").forEach {
                Text(it, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
        }
    }
}

/** Liste VFC, respiration, intervalles PPI ; seules les mesures disponibles apparaissent. */
@Composable
private fun HeartDetails(state: DayUiState, modifier: Modifier = Modifier) {
    val recharge = state.recharge
    val rows = buildList<@Composable () -> Unit> {
        recharge?.meanRmssdMs?.takeIf { it > 0 }?.let { rmssd ->
            val baseline = state.recovery?.rmssdBaselineMs ?: recharge.baselineRmssdMs
            add {
                HeartDetailRow(
                    title = "VFC (RMSSD)",
                    subtitle = baseline?.let { "Référence $it ms" } ?: "Pendant le sommeil",
                    value = rmssd.toFloat(),
                    format = { "${it.roundToInt()} ms" },
                )
            }
        }
        recharge?.meanRespirationIntervalMs?.takeIf { it > 0 }?.let { interval ->
            add {
                HeartDetailRow(
                    title = "Fréquence respiratoire",
                    subtitle = "Pendant le sommeil",
                    value = 60_000f / interval,
                    format = { "${"%.1f".format(Locale.FRENCH, it)} /min" },
                )
            }
        }
        state.ppiIntervalCount.takeIf { it > 0 }?.let { count ->
            add {
                HeartDetailRow(
                    title = "Intervalles PPI",
                    subtitle = "Battements utilisés pour la VFC",
                    value = count.toFloat(),
                    format = { formatThousands(it.roundToInt()) },
                )
            }
        }
    }
    if (rows.isEmpty()) return
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
            row()
        }
    }
}

/** Ligne « nom et précision / valeur qui compte ». */
@Composable
private fun HeartDetailRow(title: String, subtitle: String, value: Float, format: (Float) -> String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = BoucleType.bodyStrong)
            Text(subtitle, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        }
        Text(format(animatedValue(value)), style = BoucleType.valueM)
    }
}

/** Pas de fréquence cardiaque ce jour-là. */
@Composable
private fun HeartEmpty(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pas de fréquence cardiaque ce jour-là", style = BoucleType.rowTitle)
        Text(
            "Porte le bracelet et synchronise-le pour voir ta fréquence cardiaque heure par heure.",
            style = BoucleType.small,
            color = BoucleColors.OnSurfaceVariant,
        )
    }
}
