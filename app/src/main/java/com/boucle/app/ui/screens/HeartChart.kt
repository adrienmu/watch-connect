package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.HourHeartRate
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.MetricPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bornes de l'échelle du graphique heure par heure. */
internal const val HEART_CHART_MIN_BPM = 40
internal const val HEART_CHART_MAX_BPM = 180

private const val HOURS = 24
/** Écart entre deux barres qui poussent, comme l'entrée échelonnée des blocs. */
private const val BAR_STAGGER_MS = 20L

/** État animé d'une barre : centre et demi-hauteur en bpm, et pousse de 0 à 1 depuis le centre. */
private class HeartBar {
    val center = Animatable((HEART_CHART_MIN_BPM + HEART_CHART_MAX_BPM) / 2f)
    val half = Animatable(0f)
    val growth = Animatable(0f)
}

/**
 * Plage de FC de chaque heure : une barre arrondie du minimum au maximum, sur une échelle de 40 à
 * 180 bpm, dans une bande de 120 dp bordée de deux filets. Les heures sans mesure restent vides.
 * Chaque barre pousse depuis son centre avec le ressort Expressive, l'une après l'autre ; quand la
 * journée change, les barres glissent vers leurs nouvelles bornes.
 */
@Composable
internal fun HeartHourlyChart(hours: List<HourHeartRate>, modifier: Modifier = Modifier) {
    val byHour = remember(hours) { hours.associateBy { it.hour } }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()

    val bars = remember { List(HOURS) { HeartBar() } }
    LaunchedEffect(byHour) {
        var order = 0
        for (hour in 0 until HOURS) {
            val bar = bars[hour]
            val data = byHour[hour]
            if (data == null) {
                launch { bar.growth.animateTo(0f, spatial) }
                continue
            }
            val center = (data.min + data.max) / 2f
            val half = (data.max - data.min) / 2f
            val delayMs = BAR_STAGGER_MS * order++
            launch {
                if (bar.growth.value <= 0.001f) {
                    // Barre absente : elle part de son centre.
                    bar.center.snapTo(center)
                    bar.half.snapTo(half)
                    delay(delayMs)
                    bar.growth.animateTo(1f, spatial)
                } else {
                    launch { bar.center.animateTo(center, spatial) }
                    launch { bar.half.animateTo(half, spatial) }
                    bar.growth.animateTo(1f, spatial)
                }
            }
        }
    }

    val description = remember(hours) {
        if (hours.isEmpty()) {
            "Aucune fréquence cardiaque mesurée"
        } else {
            val peak = hours.maxBy { it.max }
            "Plage de fréquence cardiaque par heure, de ${hours.minOf { it.min }} à ${peak.max} bpm, " +
                "avec un pic à ${peak.max} bpm entre ${peak.hour} h et ${peak.hour + 1} h"
        }
    }

    val accent = MetricPalette.Heart.accent
    Spacer(
        modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description }
            .drawBehind {
                val line = 1.dp.toPx()
                drawLine(BoucleColors.Divider, Offset(0f, line / 2), Offset(size.width, line / 2), line)
                drawLine(BoucleColors.Divider, Offset(0f, size.height - line / 2), Offset(size.width, size.height - line / 2), line)

                val gap = 4.dp.toPx()
                val barWidth = (size.width - gap * (HOURS - 1)) / HOURS
                val minHeight = 6.dp.toPx()
                val pxPerBpm = (size.height - 2 * line) / (HEART_CHART_MAX_BPM - HEART_CHART_MIN_BPM)
                for (hour in 0 until HOURS) {
                    val bar = bars[hour]
                    val grow = bar.growth.value
                    if (grow <= 0.001f) continue
                    val center = line + (HEART_CHART_MAX_BPM - bar.center.value) * pxPerBpm
                    val half = (bar.half.value * pxPerBpm).coerceAtLeast(minHeight / 2) * grow.coerceAtLeast(0f)
                    val top = (center - half).coerceIn(line, size.height - line)
                    val bottom = (center + half).coerceIn(line, size.height - line)
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(hour * (barWidth + gap), top),
                        size = Size(barWidth, bottom - top),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
            },
    )
}
