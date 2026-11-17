package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.BodySummary
import com.boucle.app.domain.Spo2Stats
import com.boucle.app.ui.DayUiState
import com.boucle.app.ui.HealthConnectUiState
import com.boucle.app.ui.WeightPoint
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.LegendDot
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.RowChevron
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.components.SharedKeys
import com.boucle.app.ui.components.sharedContainer
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.screens.ActivityFormat.NBSP
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH)

/** Format dans lequel le view model enregistre l'heure de la dernière lecture Health Connect. */
private val syncTimeFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Corps : poids et son évolution, composition corporelle, SpO2 nocturne et source des mesures. */
@Composable
fun BodyScreen(
    state: DayUiState,
    healthConnect: HealthConnectUiState,
    onBack: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        DetailHeader("Corps", onBack)
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var index = 0
            val body = state.body
            if (body == null) {
                ActivityEmptyCard(
                    icon = BoucleIcons.Scale,
                    palette = MetricPalette.Body,
                    title = "Aucune pesée",
                    message = "Le poids et la composition corporelle viennent d'une balance connectée, lus dans " +
                        "Health Connect. Associe ta balance à son app, puis autorise Health Connect dans Appareils.",
                    modifier = Modifier.staggeredEntrance(index++),
                    action = "Voir les appareils",
                    onAction = { onNavigate(Destination.Devices) },
                )
            } else {
                WeightHero(body, state.date, state.weightHistory, Modifier.sharedContainer(SharedKeys.BODY, Radii.Hero, entranceIndex = index++))
                if (body.hasComposition()) CompositionCard(body, Modifier.staggeredEntrance(index++))
            }
            state.spo2?.let { Spo2Card(it, Modifier.staggeredEntrance(index++)) }
            if (body != null) {
                SourceRow(
                    source = body.source,
                    lastSyncAt = healthConnect.lastSyncAt,
                    onClick = { onNavigate(Destination.Devices) },
                    modifier = Modifier.staggeredEntrance(index),
                )
            }
        }
    }
}

private fun kg(value: Double) = "${ActivityFormat.decimal(value, 1)}${NBSP}kg"

@Composable
private fun WeightHero(body: BodySummary, date: LocalDate, history: List<WeightPoint>, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Body
    val weight = animatedValue(body.weightKg.toFloat())
    val weighed = if (body.weighedOn == date) "pesée ce matin" else "pesée le ${body.weighedOn.format(dayMonth)}"
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Poids · $weighed", style = BoucleType.smallStrong)
            Text(
                buildAnnotatedString {
                    append(ActivityFormat.decimal(weight.toDouble(), 1))
                    append(" ")
                    withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)) { append("kg") }
                },
                style = BoucleType.heroL,
                modifier = Modifier.semantics { contentDescription = kg(body.weightKg) },
            )
        }
        if (history.size >= 2) WeightChart(history)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChangeTile("Sur 7 jours", body.change7Days, Modifier.weight(1f).fillMaxHeight())
            ChangeTile("Sur 30 jours", body.change30Days, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/**
 * Courbe du poids sur 30 jours, placée selon la date de chaque pesée et mise à l'échelle entre le minimum et
 * le maximum (avec une marge). Elle se trace de gauche à droite, puis le point final apparaît avec un ressort.
 */
@Composable
private fun WeightChart(history: List<WeightPoint>) {
    val accent = MetricPalette.Body.accent
    val draw = remember { Animatable(0f) }
    val dot = remember { Animatable(0f) }
    val drawSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val popSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(history) {
        dot.snapTo(0f)
        draw.snapTo(0f)
        draw.animateTo(1f, drawSpec)
        dot.animateTo(1f, popSpec)
    }
    val description = "Poids sur 30 jours : de ${kg(history.first().kg)} à ${kg(history.last().kg)}"
    val first = history.first().date
    val span = ChronoUnit.DAYS.between(first, history.last().date).coerceAtLeast(1).toFloat()
    val low = history.minOf { it.kg }
    val high = history.maxOf { it.kg }
    // Au moins 1 kg d'amplitude, pour qu'une variation de quelques grammes ne paraisse pas énorme.
    val range = (high - low).coerceAtLeast(1.0)
    val bottom = low - (range - (high - low)) / 2 - range * 0.15
    val top = bottom + range * 1.3
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .semantics { contentDescription = description },
    ) {
        val inset = 7.dp.toPx()
        val width = size.width - 2 * inset
        val height = size.height - 2 * inset
        val points = history.map { point ->
            Offset(
                inset + width * ChronoUnit.DAYS.between(first, point.date) / span,
                inset + height * (1f - ((point.kg - bottom) / (top - bottom)).toFloat()),
            )
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val visible = Path()
        measure.getSegment(0f, measure.length * draw.value.coerceIn(0f, 1f), visible, true)
        drawPath(visible, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val scale = dot.value.coerceAtLeast(0f)
        if (scale > 0f) {
            drawCircle(BoucleColors.Surface, radius = 7.dp.toPx() * scale, center = points.last())
            drawCircle(accent, radius = 5.dp.toPx() * scale, center = points.last())
        }
    }
}

/** Petite tuile blanche d'évolution du poids, signée (« −0,3 kg »). */
@Composable
private fun ChangeTile(label: String, change: Double?, modifier: Modifier = Modifier) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.Inner,
        contentColor = MetricPalette.Body.onContainer,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = BoucleType.caption)
        Text(change?.let(ActivityFormat::signedKg) ?: "—", style = BoucleType.valueM)
    }
}

private fun BodySummary.hasComposition(): Boolean =
    listOf(bodyFatPct, leanMassKg, boneMassKg, waterMassKg).any { it != null }

/** Composition corporelle : part de masse grasse et masses en kilos. */
@Composable
private fun CompositionCard(body: BodySummary, modifier: Modifier = Modifier) {
    val fat = body.bodyFatPct?.coerceIn(0.0, 100.0)
    val values = listOfNotNull(
        fat?.let { Triple("Masse grasse", "${ActivityFormat.decimal(it, 1)}${NBSP}%", ChartColors.Fat) },
        body.leanMassKg?.let { Triple("Masse maigre", kg(it), ChartColors.Lean.takeIf { fat != null }) },
        body.boneMassKg?.let { Triple("Masse osseuse", kg(it), null) },
        body.waterMassKg?.let { Triple("Masse hydrique", kg(it), null) },
    )
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Composition", style = BoucleType.sectionTitle)
        if (fat != null) {
            ActivityProportionBar(
                segments = listOf(fat.toFloat() to ChartColors.Fat, (100 - fat).toFloat() to ChartColors.Lean),
                description = "Masse grasse ${ActivityFormat.decimal(fat, 1)} %, le reste en masse maigre",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            values.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (label, value, color) ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (color != null) {
                                LegendDot(label, color)
                            } else {
                                Text(label, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
                            }
                            Text(value, style = BoucleType.valueM)
                        }
                    }
                    // Garde la demi-largeur d'une valeur seule sur sa ligne.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** SpO2 nocturne : moyenne (une décimale) et minimum. */
@Composable
private fun Spo2Card(spo2: Spo2Stats, modifier: Modifier = Modifier) {
    fun percent(value: Double, decimals: Int) = "${ActivityFormat.decimal(value, decimals)}${NBSP}%"
    val min = if (spo2.min % 1.0 == 0.0) percent(spo2.min, 0) else percent(spo2.min, 1)
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Saturation en oxygène", style = BoucleType.sectionTitle, modifier = Modifier.alignByBaseline())
            Text("SpO2 · nuit", style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant, modifier = Modifier.alignByBaseline())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Moyenne" to percent(spo2.average, 1), "Min" to min).forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
                    Text(value, style = BoucleType.valueXL)
                }
            }
        }
    }
}

/** Nom lisible de l'app qui a écrit la pesée dans Health Connect, d'après son nom de paquet. */
private fun sourceName(packageName: String): String = when {
    packageName.startsWith("com.withings.") -> "Withings"
    else -> packageName
}

/** « aujourd'hui à 07:05 », « hier à 21:40 » ou « le 14 novembre à 08:12 » ; `null` si l'heure est illisible. */
private fun lastReading(lastSyncAt: String): String? {
    val time = runCatching { LocalDateTime.parse(lastSyncAt, syncTimeFormat) }.getOrNull() ?: return null
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> "aujourd'hui"
        today.minusDays(1) -> "hier"
        else -> "le ${time.toLocalDate().format(dayMonth)}"
    }
    return "$day à %02d:%02d".format(time.hour, time.minute)
}

/** Ligne de source des mesures, vers l'écran Appareils. */
@Composable
private fun SourceRow(source: String, lastSyncAt: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = source.takeIf { it.isNotBlank() }?.let(::sourceName)
    ExpressiveSurface(
        color = BoucleColors.Track,
        radii = Radii.Tile,
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onClickLabel = "Voir les appareils",
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (app != null) "Source : $app via Health Connect" else "Source : Health Connect",
                    style = BoucleType.buttonSmall,
                )
                lastSyncAt?.let(::lastReading)?.let {
                    Text("Dernière lecture $it", style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
                }
            }
            RowChevron()
        }
    }
}
