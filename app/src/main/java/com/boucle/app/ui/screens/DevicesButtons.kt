package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType

/*
 * Boutons communs aux écrans Appareils, Association, Configuration et Bienvenue : boutons
 * Material 3 Expressive dont la forme se transforme à l'appui, aux tailles de la maquette.
 */

/**
 * Bouton plein de la maquette. Pendant [loading], il garde ses couleurs mais n'est plus cliquable,
 * et son contenu laisse place, avec les ressorts du thème, à un indicateur de chargement
 * Expressive suivi de [loadingText].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SetupFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    textStyle: TextStyle = BoucleType.buttonLarge,
    shape: Shape = BoucleShapes.pill,
    containerColor: Color = BoucleColors.Primary,
    contentColor: Color = BoucleColors.OnPrimary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingText: String = text,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(shape = shape, pressedShape = ButtonDefaults.pressedShape),
        modifier = modifier.heightIn(min = height),
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = if (loading) containerColor else BoucleColors.Track,
            disabledContentColor = if (loading) contentColor else BoucleColors.Disabled,
        ),
        contentPadding = contentPadding,
    ) {
        ButtonContent(text, textStyle, icon, loading, loadingText)
    }
}

/** Bouton à contour de la maquette (trait de 1 dp couleur contour). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SetupOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    textStyle: TextStyle = BoucleType.buttonSmall,
    shape: Shape = BoucleShapes.pill,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    OutlinedButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(shape = shape, pressedShape = ButtonDefaults.pressedShape),
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BoucleColors.OnSurface),
        border = BorderStroke(1.dp, if (enabled) BoucleColors.Outline else BoucleColors.Divider),
        contentPadding = contentPadding,
    ) {
        Text(text, style = textStyle)
    }
}

/** Contenu d'un bouton : icône facultative et libellé, ou indicateur de chargement. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonContent(
    text: String,
    textStyle: TextStyle,
    icon: ImageVector?,
    loading: Boolean,
    loadingText: String,
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedContent(
        targetState = loading,
        transitionSpec = { (fadeIn(effects) + scaleIn(spatial, initialScale = 0.85f)).togetherWith(fadeOut(effects)) },
        contentAlignment = Alignment.Center,
        label = "buttonContent",
    ) { isLoading ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoading) {
                LoadingIndicator(Modifier.size(28.dp), color = LocalContentColor.current)
                Text(loadingText, style = textStyle)
            } else {
                icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) }
                Text(text, style = textStyle)
            }
        }
    }
}
