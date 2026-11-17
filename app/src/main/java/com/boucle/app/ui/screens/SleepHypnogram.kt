package com.boucle.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.SleepPhase
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.theme.ChartColors

/** Un stade de sommeil tel que dessiné : couleur et décalage vertical dans l'hypnogramme (dp). */
internal enum class SleepStage(val state: String, val label: String, val color: Color, val offsetDp: Int) {
    Deep("NONREM3", "Sommeil profond", ChartColors.SleepDeep, 60),
    Rem("REM", "Sommeil paradoxal", ChartColors.SleepRem, 20),
    Light("NONREM12", "Sommeil léger", ChartColors.SleepLight, 40),
    Wake("WAKE", "Éveil", ChartColors.SleepWake, 0),
    ;

    companion object {
        fun of(state: String): SleepStage? = entries.firstOrNull { it.state == state }
    }
}

/** Part de la largeur sur laquelle un segment apparaît pendant le balayage de gauche à droite. */
private const val REVEAL_SPREAD = 0.25f

/**
 * Hypnogramme : une rangée de segments arrondis, larges en proportion de leur durée, décalés en
 * hauteur selon le stade (éveil en haut, profond en bas). Les segments apparaissent de gauche à
 * droite avec le ressort du thème. Les phases d'état inconnu laissent un vide.
 */
@Composable
internal fun SleepHypnogram(phases: List<SleepPhase>, description: String, modifier: Modifier = Modifier) {
    val segments = remember(phases) { mergeConsecutive(phases) }
    val reveal = animatedValue(1f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .semantics { contentDescription = description },
    ) {
        val total = segments.lastOrNull()?.endSec?.takeIf { it > 0 } ?: return@Canvas
        val first = segments.first().startSec
        val span = (total - first).toFloat()
        val gap = 1.dp.toPx()
        val height = 16.dp.toPx()
        val radius = 4.dp.toPx()
        segments.forEach { segment ->
            val stage = SleepStage.of(segment.state) ?: return@forEach
            val start = (segment.startSec - first) / span
            val end = (segment.endSec - first) / span
            // Chaque segment grandit depuis son centre quand le balayage atteint son début.
            val local = ((reveal * (1f + REVEAL_SPREAD) - start) / REVEAL_SPREAD).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val left = start * size.width
            val width = (end * size.width - left - gap).coerceAtLeast(1f)
            val shown = height * local
            val top = stage.offsetDp.dp.toPx() + (height - shown) / 2
            drawRoundRect(
                color = stage.color,
                topLeft = Offset(left, top),
                size = Size(width, shown),
                cornerRadius = CornerRadius(minOf(radius, width / 2, shown / 2)),
                alpha = local,
            )
        }
    }
}

/** Fusionne les phases successives du même état, pour ne dessiner qu'un segment par stade continu. */
private fun mergeConsecutive(phases: List<SleepPhase>): List<SleepPhase> =
    phases.fold(mutableListOf()) { merged, phase ->
        val last = merged.lastOrNull()
        if (last != null && last.state == phase.state && last.endSec == phase.startSec) {
            merged[merged.lastIndex] = last.copy(endSec = phase.endSec)
        } else {
            merged += phase
        }
        merged
    }
