package com.boucle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.BatteryBand
import com.boucle.app.domain.BodyBatteryDay
import com.boucle.app.domain.band
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.AnimatedNumber
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.LegendDot
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.SegmentedBar
import com.boucle.app.ui.components.StatTile
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Libellé de la pastille et conseil de chaque plage de niveau. */
private val BatteryBand.label: String
    get() = when (this) {
        BatteryBand.FULL -> "Pleine forme"
        BatteryBand.GOOD -> "Bonne"
        BatteryBand.MEDIUM -> "Modérée"
        BatteryBand.LOW -> "Basse"
    }

private val BatteryBand.advice: String
    get() = when (this) {
        BatteryBand.FULL -> "Tu as de l'énergie à revendre. C'est le bon moment pour une séance exigeante ou une journée chargée."
        BatteryBand.GOOD -> "Tu as encore une bonne réserve. Garde un rythme régulier et pense à faire quelques pauses."
        BatteryBand.MEDIUM -> "Il te reste de quoi finir la journée. Une soirée calme préparera la recharge de cette nuit."
        BatteryBand.LOW -> "Ta réserve est basse. Lève le pied si tu peux et couche-toi tôt pour bien recharger cette nuit."
    }

/**
 * Détail du Body Battery : niveau actuel et sa plage, courbe de la journée sur le sommeil, puis le
 * réveil, les extrêmes, ce qui a été rechargé et dépensé.
 */
@Composable
fun EnergyScreen(
    state: DayUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        DetailHeader("Body Battery", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val battery = state.battery
            if (battery == null || battery.points.isEmpty()) {
                EnergyEmpty(Modifier.staggeredEntrance(0))
                return@Column
            }
            EnergyHero(battery, state.date, Modifier.sharedContainer(SharedKeys.ENERGY, Radii.Hero))
            EnergyDayCard(battery, state, Modifier.staggeredEntrance(1))
            EnergyTiles(battery, Modifier.staggeredEntrance(2))
            Text(
                "Modèle maison : le sommeil recharge, l'activité et le stress consomment, le calme recharge un peu. Ce n'est pas l'algorithme de Garmin.",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .staggeredEntrance(3),
            )
        }
    }
}

/** Bloc orange : niveau, pastille de la plage, jauge et conseil. */
@Composable
private fun EnergyHero(battery: BodyBatteryDay, date: LocalDate, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Energy
    val band = battery.band
    val caption = remember(date, battery.points) {
        if (date == LocalDate.now()) {
            "Niveau à ${battery.points.last().time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        } else {
            "Fin de journée"
        }
    }
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(caption, style = BoucleType.smallStrong)
                AnimatedNumber(battery.current, BoucleType.heroL)
            }
            Text(
                band.label,
                style = BoucleType.smallStrong,
                modifier = Modifier
                    .background(BoucleColors.Surface, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        SegmentedBar(battery.current / 100f, palette.accent, BoucleColors.Surface, height = 12.dp)
        Text(band.advice, style = BoucleType.small)
    }
}

/** Carte « Sur la journée » : courbe, heures et légende. */
@Composable
private fun EnergyDayCard(battery: BodyBatteryDay, state: DayUiState, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sur la journée", style = BoucleType.sectionTitle)
        EnergyChart(battery, state.date, state.sleep?.start, state.sleep?.end)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0 h", "6 h", "12 h", "18 h", "24 h").forEach {
                Text(it, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val swatch = RoundedCornerShape(3.dp)
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MetricPalette.Sleep.container, swatch)
                        .border(1.dp, ChartColors.SleepLight, swatch),
                )
                Spacer(Modifier.width(6.dp))
                Text("Sommeil", style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
            }
            LegendDot("Niveau d'énergie", MetricPalette.Energy.accent, square = false)
        }
    }
}

/** Quatre tuiles : réveil, extrêmes, rechargé, dépensé. */
@Composable
private fun EnergyTiles(battery: BodyBatteryDay, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Au réveil",
                value = battery.wake?.toString() ?: "—",
                radii = Radii.Tile,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Plus haut / plus bas",
                value = "${battery.high} / ${battery.low}",
                radii = Radii.Tile,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Rechargé",
                value = "+${battery.charged}",
                valueColor = BoucleColors.Primary,
                radii = Radii.Tile,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Dépensé",
                value = if (battery.drained > 0) "−${battery.drained}" else "0",
                valueColor = ChartColors.EnergyDrain,
                radii = Radii.Tile,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Pas de Body Battery : aucune donnée ce jour-là. */
@Composable
private fun EnergyEmpty(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Card,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Pas encore de Body Battery", style = BoucleType.rowTitle)
        Text(
            "Le niveau d'énergie se calcule à partir de ton sommeil, de ton activité et de ta fréquence cardiaque. Porte le bracelet et synchronise-le pour le voir apparaître.",
            style = BoucleType.small,
            color = BoucleColors.OnSurfaceVariant,
        )
    }
}
