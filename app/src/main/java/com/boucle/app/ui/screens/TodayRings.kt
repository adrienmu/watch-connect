package com.boucle.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.theme.ChartColors

/** Repère de dessin de la maquette : anneaux dessinés dans un carré de 150 unités. */
private const val VIEWBOX = 150f
private const val RING_STROKE = 12f

/**
 * Les trois anneaux du bloc héros (pas, calories actives, objectif d'activité), de l'extérieur
 * vers l'intérieur. Chaque fraction (0 à 1, `null` sans donnée) se trace depuis midi avec le ressort
 * du thème, puis suit les changements de valeur.
 */
@Composable
internal fun TodayRings(
    steps: Float?,
    calories: Float?,
    activityGoal: Float?,
    modifier: Modifier = Modifier,
) {
    val rings = listOf(
        Ring(62f, ChartColors.RingSteps, animatedValue(steps.toFraction())),
        Ring(47f, ChartColors.RingCalories, animatedValue(calories.toFraction())),
        Ring(32f, ChartColors.RingGoal, animatedValue(activityGoal.toFraction())),
    )
    val description = "Anneaux : pas ${steps.percentLabel()}, calories actives ${calories.percentLabel()}, " +
        "objectif d'activité ${activityGoal.percentLabel()}"
    Canvas(modifier.size(112.dp).semantics { contentDescription = description }) {
        val unit = size.minDimension / VIEWBOX
        val stroke = RING_STROKE * unit
        rings.forEach { ring ->
            val radius = ring.radius * unit
            drawCircle(ChartColors.RingTrack, radius, style = Stroke(stroke))
            // Le ressort peut dépasser sa cible : l'arc reste borné à un tour complet.
            val progress = ring.progress.coerceIn(0f, 1f)
            if (progress > 0.002f) {
                drawArc(
                    color = ring.color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = center - Offset(radius, radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

private class Ring(val radius: Float, val color: Color, val progress: Float)

private fun Float?.toFraction(): Float = (this ?: 0f).coerceIn(0f, 1f)

private fun Float?.percentLabel(): String = this?.let { "${Math.round(it * 100)}${SleepFormat.NBSP}%" } ?: "indisponible"
