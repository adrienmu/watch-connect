package com.boucle.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Jauges de la maquette. Chacune se remplit avec le ressort du thème à l'apparition, puis suit sa valeur.
 * Elles sont décoratives : la valeur est dite par le texte qui les accompagne.
 */

/** Ouverture de la jauge en arc : trois quarts de tour, ouverte vers le bas. */
private const val ARC_SWEEP = 270f
private const val ARC_START = 135f

/** Jauge en arc de trois quarts de tour ; [content] se place au centre. [fraction] va de 0 à 1. */
@Composable
fun ArcGauge(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val progress = animatedValue(fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val style = Stroke(stroke, cap = StrokeCap.Round)
            drawArc(trackColor, ARC_START, ARC_SWEEP, false, Offset(inset, inset), arcSize, style = style)
            if (progress > 0.002f) {
                drawArc(color, ARC_START, ARC_SWEEP * progress, false, Offset(inset, inset), arcSize, style = style)
            }
        }
        content()
    }
}

/** Demi-jauge d'un score sur 100 (64 × 40 dp dans la maquette). [fraction] va de 0 à 1. */
@Composable
fun HalfGauge(fraction: Float, color: Color, trackColor: Color, modifier: Modifier = Modifier) {
    val progress = animatedValue(fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Canvas(modifier.size(64.dp, 40.dp)) {
        // Repère de la maquette : 64 × 40, arc de rayon 24 centré en (32, 34), trait de 8.
        val unit = size.width / 64f
        val radius = 24f * unit
        val topLeft = Offset(32f * unit - radius, 34f * unit - radius)
        val arcSize = Size(radius * 2, radius * 2)
        val style = Stroke(8f * unit, cap = StrokeCap.Round)
        drawArc(trackColor, 180f, 180f, false, topLeft, arcSize, style = style)
        if (progress > 0.002f) drawArc(color, 180f, 180f * progress, false, topLeft, arcSize, style = style)
    }
}

/** Anneau complet, tracé depuis midi (jour du sélecteur, jour du calendrier). [fraction] va de 0 à 1. */
@Composable
fun RingGauge(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
) {
    val progress = animatedValue(fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (progress > 0.002f) {
            drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Jauge linéaire pleine largeur (objectifs secondaires, plage de valeurs). [fraction] va de 0 à 1. */
@Composable
fun LinearGauge(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    /** Début de la partie remplie, de 0 à 1 (plage de valeurs) ; 0 pour une progression. */
    start: Float = 0f,
) {
    val progress = animatedValue(fraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    val shape = RoundedCornerShape(percent = 50)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, shape),
    ) {
        val from = start.coerceIn(0f, 1f) * size.width
        val width = (progress * size.width - from).coerceAtLeast(0f)
        if (width > 0f) {
            val radius = size.height / 2
            drawRoundRect(color, Offset(from, 0f), Size(width.coerceAtLeast(size.height), size.height), androidx.compose.ui.geometry.CornerRadius(radius, radius))
        }
    }
}
