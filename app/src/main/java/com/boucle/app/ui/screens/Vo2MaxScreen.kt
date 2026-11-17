package com.boucle.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.HrMaxSource
import com.boucle.app.domain.Vo2MaxCategory
import com.boucle.app.domain.Vo2MaxEstimate
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.StatTile
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette
import kotlin.math.roundToInt

/** Détail de la VO2max : valeur, niveau pour l'âge et le sexe, méthodes d'estimation et FC utilisées. */
@Composable
fun Vo2MaxScreen(
    state: DayUiState,
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        DetailHeader("VO2max", onBack, backLabel = "Retour à Forme")
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val vo2 = state.vo2Max
            if (vo2 == null) {
                ActivityEmptyCard(
                    icon = BoucleIcons.Heart,
                    palette = MetricPalette.Body,
                    title = "Pas encore d'estimation",
                    message = "La VO2max s'estime à partir de ton profil (âge, sexe) et de ta FC de repos, mesurée " +
                        "pendant tes nuits avec le bracelet. Complète ton profil puis porte-le quelques nuits.",
                    modifier = Modifier.staggeredEntrance(0),
                    action = "Compléter mon profil",
                    onAction = { onNavigate(Destination.Profile) },
                )
            } else {
                Vo2MaxHero(vo2, Modifier.sharedContainer(SharedKeys.VO2MAX, Radii.Hero))
                MethodsCard(vo2, Modifier.staggeredEntrance(1))
                HeartRateTiles(vo2, Modifier.staggeredEntrance(2))
                AccuracyNote(Modifier.staggeredEntrance(3))
                if (vo2.hrMaxSource != HrMaxSource.PROFILE) {
                    ActivityPrimaryButton(
                        "Renseigner ma FC max",
                        onClick = { onNavigate(Destination.Profile) },
                        modifier = Modifier.staggeredEntrance(4),
                    )
                }
            }
        }
    }
}

@Composable
private fun Vo2MaxHero(vo2: Vo2MaxEstimate, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Body
    val value = animatedValue(vo2.value.toFloat()).roundToInt()
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("VO2max estimée", style = BoucleType.smallStrong)
                Text(
                    buildAnnotatedString {
                        append("$value ")
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)) { append("ml/kg/min") }
                    },
                    style = BoucleType.heroL,
                    modifier = Modifier.semantics { contentDescription = "${vo2.value} ml/kg/min" },
                )
            }
            Text(
                vo2.category.label,
                style = BoucleType.smallStrong,
                modifier = Modifier
                    .background(BoucleColors.Surface, BoucleShapes.pill)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        CategoryScale(vo2)
    }
}

/** Échelle Faible / Moyen / Bon / Excellent ; le niveau atteint se remplit avec un ressort à l'apparition. */
@Composable
private fun CategoryScale(vo2: Vo2MaxEstimate) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val accent = MetricPalette.Body.accent
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .semantics {
                    contentDescription = "Position sur l'échelle : ${vo2.value}, dans la zone ${vo2.category.label}"
                },
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Vo2MaxCategory.entries.forEach { category ->
                val fill by animateFloatAsState(
                    targetValue = if (shown && category == vo2.category) 1f else 0f,
                    animationSpec = spec,
                    label = "scale-${category.name}",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(BoucleColors.Surface, BoucleShapes.pill),
                ) {
                    if (fill > 0f) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fill.coerceIn(0f, 1f))
                                .background(accent, BoucleShapes.pill),
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Vo2MaxCategory.entries.forEach { category ->
                Text(
                    category.label,
                    style = if (category == vo2.category) BoucleType.captionStrong else BoucleType.caption,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Les deux méthodes d'estimation, chacune avec sa valeur. */
@Composable
private fun MethodsCard(vo2: Vo2MaxEstimate, modifier: Modifier = Modifier) {
    val rows = listOfNotNull(
        vo2.runningEstimate?.let { value ->
            val runs = if (vo2.runCount > 1) "${vo2.runCount} courses" else "${vo2.runCount} course"
            Triple("Estimation par la course", "D'après $runs", value)
        },
        vo2.restingEstimate?.let { value ->
            Triple("Estimation par la FC de repos", "D'après ta FC de repos et ta FC max", value)
        },
    )
    if (rows.isEmpty()) return
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, (title, detail, value) ->
            if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = BoucleType.bodyStrong)
                    Text(detail, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
                }
                Text(value.toString(), style = BoucleType.valueM)
            }
        }
    }
}

@Composable
private fun HeartRateTiles(vo2: Vo2MaxEstimate, modifier: Modifier = Modifier) {
    val source = when (vo2.hrMaxSource) {
        HrMaxSource.PROFILE -> "profil"
        HrMaxSource.OBSERVED -> "observée"
        HrMaxSource.AGE_FORMULA -> "estimée par l'âge"
    }
    Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("FC max ($source)", "${vo2.hrMax}${NBSP}bpm", Radii.Tile, Modifier.weight(1f).fillMaxHeight())
        StatTile("FC de repos", "${vo2.restingHr}${NBSP}bpm", Radii.Tile, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun AccuracyNote(modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Track,
        radii = Radii.Tile,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(BoucleIcons.Info, contentDescription = null, tint = BoucleColors.OnSurface, modifier = Modifier.size(20.dp))
            Text(
                "Estimation indicative : la distance du Loop vient de l'accéléromètre, sans GPS. Une FC max " +
                    "mesurée dans ton profil améliore nettement la précision.",
                style = BoucleType.small,
                color = BoucleColors.OnSurface,
            )
        }
    }
}
