package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.BodyBatteryDay
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.MetricPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

private const val MINUTES_PER_DAY = 24 * 60f

/** Position d'un instant dans la journée [date], de 0 (minuit) à 1 (minuit suivant), bornée. */
private fun dayShare(date: LocalDate, time: LocalDateTime): Float =
    (Duration.between(date.atStartOfDay(), time).toMinutes() / MINUTES_PER_DAY).coerceIn(0f, 1f)

/** Point de la courbe : position dans la journée (0 à 1) et niveau (0 à 1). */
private class EnergySample(val x: Float, val level: Float)

/**
 * Courbe du Body Battery de 0 h à 24 h, comme la maquette : bande du sommeil, trois lignes de
 * repère, aire et trait du niveau, point final plein et point creux au réveil. La courbe se dessine
 * de gauche à droite avec le ressort Expressive, puis les points apparaissent en rebondissant.
 */
@Composable
internal fun EnergyChart(
    battery: BodyBatteryDay,
    date: LocalDate,
    sleepStart: ZonedDateTime?,
    sleepEnd: ZonedDateTime?,
    modifier: Modifier = Modifier,
) {
    val samples = remember(battery.points, date) {
        battery.points.map { EnergySample(dayShare(date, it.time), (it.level / 100.0).toFloat().coerceIn(0f, 1f)) }
    }
    val sleepWindow = remember(sleepStart, sleepEnd, date) {
        if (sleepStart == null || sleepEnd == null) return@remember null
        val from = dayShare(date, sleepStart.toLocalDateTime())
        val to = dayShare(date, sleepEnd.toLocalDateTime())
        if (to > from) from to to else null
    }
    val wakeX = sleepEnd?.toLocalDateTime()
        ?.takeIf { battery.wake != null && it.toLocalDate() == date }
        ?.let { dayShare(date, it) }

    val reveal = remember { Animatable(0f) }
    val endDot = remember { Animatable(0f) }
    val wakeDot = remember { Animatable(0f) }
    val draw = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val pop = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(samples, wakeX) {
        endDot.snapTo(0f)
        wakeDot.snapTo(0f)
        reveal.snapTo(0f)
        launch { reveal.animateTo(1f, draw) }
        val firstX = samples.first().x
        val span = (samples.last().x - firstX).coerceAtLeast(0.0001f)
        if (wakeX != null) {
            launch {
                val wakeShare = ((wakeX - firstX) / span).coerceIn(0f, 0.95f)
                snapshotFlow { reveal.value }.first { it >= wakeShare }
                wakeDot.animateTo(1f, pop)
            }
        }
        snapshotFlow { reveal.value }.first { it >= 0.95f }
        endDot.animateTo(1f, pop)
    }

    val description = buildString {
        append("Courbe du niveau d'énergie : ${battery.low} au plus bas, ${battery.high} au plus haut")
        battery.wake?.let { append(", $it au réveil") }
        val last = battery.points.last().time
        append(", ${battery.current} à ${last.hour} h ${"%02d".format(last.minute)}")
    }

    val sleepColor = MetricPalette.Sleep.container
    val area = MetricPalette.Energy.container
    val accent = MetricPalette.Energy.accent
    Spacer(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .semantics { contentDescription = description }
            .drawWithCache {
                val plotHeight = 140.dp.toPx()
                val stroke = 3.dp.toPx()
                fun point(sample: EnergySample) = Offset(sample.x * size.width, (1f - sample.level) * plotHeight)
                val line = Path().apply {
                    samples.forEachIndexed { index, sample ->
                        val p = point(sample)
                        if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                val first = point(samples.first())
                val last = point(samples.last())
                val fill = Path().apply {
                    addPath(line)
                    lineTo(last.x, plotHeight)
                    lineTo(first.x, plotHeight)
                    close()
                }
                val wake = wakeX?.let { x -> Offset(x * size.width, (1f - (battery.wake ?: 0) / 100f) * plotHeight) }
                val gridStroke = 1.dp.toPx()
                val grid = listOf(gridStroke / 2, plotHeight / 2, plotHeight - gridStroke / 2)
                onDrawBehind {
                    sleepWindow?.let { (from, to) ->
                        drawRoundRect(
                            color = sleepColor,
                            topLeft = Offset(from * size.width, 0f),
                            size = Size((to - from) * size.width, plotHeight),
                            cornerRadius = CornerRadius(8.dp.toPx()),
                        )
                    }
                    grid.forEach { y ->
                        drawLine(BoucleColors.Divider, Offset(0f, y), Offset(size.width, y), gridStroke)
                    }
                    val visibleRight = first.x + (last.x - first.x) * reveal.value.coerceIn(0f, 1f) + stroke
                    clipRect(right = visibleRight) {
                        drawPath(fill, area)
                        drawPath(line, accent, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    wake?.let { center ->
                        val scale = wakeDot.value.coerceAtLeast(0f)
                        drawCircle(accent, radius = 5.dp.toPx() * scale, center = center)
                        drawCircle(BoucleColors.Surface, radius = 3.dp.toPx() * scale, center = center)
                    }
                    val scale = endDot.value.coerceAtLeast(0f)
                    drawCircle(BoucleColors.Surface, radius = 6.dp.toPx() * scale, center = last)
                    drawCircle(accent, radius = 4.dp.toPx() * scale, center = last)
                }
            },
    )
}
