package com.boucle.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.domain.Vo2MaxCategory
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.animatedValue
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricColors
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Briques communes aux écrans Forme, VO2max et Corps : mise en forme française des valeurs, barre de
 * répartition, carte d'état vide et bouton principal.
 */

/** Mise en forme française : virgule décimale, vrai signe moins, espace insécable avant l'unité. */
internal object ActivityFormat {
    /** Espace insécable entre une valeur et son unité. */
    const val NBSP = ' '

    /** 6.2391 avec 2 décimales → « 6,24 » ; −0.3 → « −0,3 ». */
    fun decimal(value: Double, decimals: Int): String =
        String.format(Locale.FRANCE, "%.${decimals}f", value).replace('-', '−')

    /** Écart signé à une décimale : « −0,3 kg », « +0,2 kg », « 0,0 kg ». */
    fun signedKg(value: Double): String {
        val tenths = (value * 10).roundToInt()
        val sign = when {
            tenths > 0 -> "+"
            tenths < 0 -> "−"
            else -> ""
        }
        return "$sign${decimal(abs(tenths) / 10.0, 1)}${NBSP}kg"
    }

    /** Durée en secondes : « 52 min » sous l'heure, « 9 h 08 » au-delà. */
    fun duration(seconds: Int): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) "$minutes${NBSP}min" else "${minutes / 60}${NBSP}h${NBSP}%02d".format(minutes % 60)
    }

    /** « lun. » → « Lun » : jour de la semaine abrégé, sans point. */
    fun shortDay(day: java.time.DayOfWeek): String =
        day.getDisplayName(TextStyle.SHORT, Locale.FRENCH).removeSuffix(".").replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

/** Libellé français d'un niveau de VO2max. */
internal val Vo2MaxCategory.label: String
    get() = when (this) {
        Vo2MaxCategory.POOR -> "Faible"
        Vo2MaxCategory.FAIR -> "Moyen"
        Vo2MaxCategory.GOOD -> "Bon"
        Vo2MaxCategory.EXCELLENT -> "Excellent"
    }

/**
 * Barre de répartition (pilules séparées de 3 dp, largeur proportionnelle à chaque valeur). Chaque segment
 * s'allonge avec le ressort du thème ; un espace vide en fin de barre garde le total fixe, si bien que la
 * barre se remplit de gauche à droite.
 */
@Composable
internal fun ActivityProportionBar(
    segments: List<Pair<Float, Color>>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat()
    val animated = segments.map { (value, _) -> animatedValue(value).coerceAtLeast(0f) }
    val remaining = total - animated.sum()
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        segments.forEachIndexed { index, (_, color) ->
            val weight = animated[index]
            if (total > 0f && weight / total > 0.002f) {
                Box(
                    Modifier
                        .weight(weight)
                        .height(height)
                        .background(color, BoucleShapes.pill),
                )
            }
        }
        if (total > 0f && remaining / total > 0.002f) Spacer(Modifier.weight(remaining))
    }
}

/** Bouton principal de la maquette (pilule verte, 44 dp de haut), avec le morphing au toucher d'Expressive. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActivityPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BoucleColors.Primary, contentColor = BoucleColors.OnPrimary),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(text, style = BoucleType.buttonSmall)
    }
}

/** Carte affichée à la place des données absentes : pastille, titre, explication et action facultative. */
@Composable
internal fun ActivityEmptyCard(
    icon: ImageVector,
    palette: MetricColors,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        IconBadge(icon, container = palette.container, content = palette.onContainer, shape = BadgeShape.Cookie)
        Text(title, style = BoucleType.sectionTitle)
        Text(message, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        if (action != null) ActivityPrimaryButton(action, onAction)
    }
}
