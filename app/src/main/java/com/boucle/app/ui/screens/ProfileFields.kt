package com.boucle.app.ui.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleType

/*
 * Champs du formulaire de configuration, au style de la maquette : libellé de 13 au-dessus, champ
 * blanc de 52 dp à contour fin (rayon 14), texte de 16.
 */

private val fieldShape = RoundedCornerShape(14.dp)

/** Libellé d'un champ (13, demi-gras). */
@Composable
internal fun ProfileLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = BoucleType.smallStrong, color = BoucleColors.OnSurface, modifier = modifier)
}

/**
 * Champ de saisie avec son libellé, regroupés pour l'accessibilité (le lecteur d'écran annonce le
 * libellé avec la valeur), et une aide facultative en dessous.
 */
@Composable
internal fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileLabel(label)
        ProfileFieldBox(
            value = value,
            onValueChange = onValueChange,
            keyboardType = keyboardType,
            placeholder = placeholder,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
        )
        helper?.let { Text(it, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant) }
    }
}

/** Le champ lui-même (52 dp), modifiable ou en lecture seule (listes déroulantes). */
@Composable
internal fun ProfileFieldBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = BoucleColors.Surface,
        unfocusedContainerColor = BoucleColors.Surface,
        focusedBorderColor = BoucleColors.Primary,
        unfocusedBorderColor = BoucleColors.Outline,
        focusedTextColor = BoucleColors.OnSurface,
        unfocusedTextColor = BoucleColors.OnSurface,
        focusedPlaceholderColor = BoucleColors.OnSurfaceVariant,
        unfocusedPlaceholderColor = BoucleColors.OnSurfaceVariant,
        focusedTrailingIconColor = BoucleColors.OnSurface,
        unfocusedTrailingIconColor = BoucleColors.OnSurfaceVariant,
        cursorColor = BoucleColors.Primary,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 52.dp),
        readOnly = readOnly,
        singleLine = true,
        textStyle = BoucleType.bodyLarge.copy(color = BoucleColors.OnSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        cursorBrush = SolidColor(BoucleColors.Primary),
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = true,
                singleLine = true,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                placeholder = placeholder?.let { { Text(it, style = BoucleType.bodyLarge) } },
                trailingIcon = trailing,
                colors = colors,
                contentPadding = PaddingValues(horizontal = 14.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interaction,
                        colors = colors,
                        shape = fieldShape,
                        focusedBorderThickness = 2.dp,
                        unfocusedBorderThickness = 1.dp,
                    )
                },
            )
        },
    )
}

/**
 * Affiche les chiffres d'une date de naissance sous la forme JJ/MM/AAAA pendant la saisie (le
 * clavier numérique n'a pas toujours de « / »).
 */
internal object BirthDateTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val formatted = buildString {
            digits.forEachIndexed { index, char ->
                append(char)
                if ((index == 1 || index == 3) && index < digits.lastIndex) append('/')
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var shift = 0
                if (offset >= 2 && digits.length > 2) shift++
                if (offset >= 4 && digits.length > 4) shift++
                return offset + shift
            }

            override fun transformedToOriginal(offset: Int): Int {
                var shift = 0
                if (offset > 2 && digits.length > 2) shift++
                if (offset > 5 && digits.length > 4) shift++
                return (offset - shift).coerceIn(0, digits.length)
            }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}
