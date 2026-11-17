package com.boucle.app.ui.screens

import com.boucle.app.ui.components.rememberHaptics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.ui.ProfileForm
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.polar.sdk.api.model.PolarFirstTimeUseConfig.TypicalDay
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private val trainingBackgrounds = listOf(
    10 to "Occasionnel",
    20 to "Régulier",
    30 to "Fréquent",
    40 to "Intensif",
    50 to "Semi-pro",
    60 to "Pro",
)

private val typicalDays = listOf(
    TypicalDay.MOSTLY_SITTING to "Plutôt assis",
    TypicalDay.MOSTLY_STANDING to "Plutôt debout",
    TypicalDay.MOSTLY_MOVING to "Plutôt en mouvement",
)

private val birthDateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu")

/**
 * Formulaire de profil envoyé au bracelet (« first time use »). Les champs sont préremplis avec
 * [initial] et gardés localement jusqu'à l'envoi ; la VO2max, absente du formulaire, est renvoyée
 * telle quelle.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileScreen(
    initial: ProfileForm,
    saving: Boolean,
    canSubmit: Boolean,
    onSubmit: (ProfileForm) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var female by rememberSaveable { mutableStateOf(initial.female) }
    var birthDigits by rememberSaveable { mutableStateOf(initial.birthDate.filter(Char::isDigit).take(8)) }
    var height by rememberSaveable { mutableStateOf(initial.heightCm) }
    var weight by rememberSaveable { mutableStateOf(initial.weightKg) }
    var restingHr by rememberSaveable { mutableStateOf(initial.restingHr) }
    var maxHr by rememberSaveable { mutableStateOf(initial.maxHr) }
    var background by rememberSaveable { mutableIntStateOf(initial.trainingBackground) }
    var typicalDay by rememberSaveable { mutableStateOf(initial.typicalDay) }
    var sleepGoal by rememberSaveable {
        mutableFloatStateOf(initial.sleepGoalHours.replace(',', '.').toFloatOrNull()?.coerceIn(5f, 11f) ?: 8f)
    }

    fun submit() {
        val birthDate = formatBirthDate(birthDigits)
        onSubmit(
            initial.copy(
                female = female,
                birthDate = birthDate,
                heightCm = height,
                weightKg = weight,
                restingHr = restingHr,
                maxHr = maxHr.ifBlank { defaultMaxHr(birthDate) ?: "" },
                trainingBackground = background,
                typicalDay = typicalDay,
                sleepGoalHours = formatSleepGoalValue(sleepGoal),
            ),
        )
    }

    Column(modifier) {
        ProfileHeader(onBack)
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .imePadding(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Le bracelet a besoin de ton profil pour calculer calories, activité et sommeil. " +
                            "Ces valeurs restent sur le bracelet et le téléphone.",
                        style = BoucleType.body,
                        color = BoucleColors.OnSurfaceVariant,
                        modifier = Modifier.staggeredEntrance(0),
                    )
                    SexChoice(female, { female = it }, Modifier.staggeredEntrance(1))
                    Column(Modifier.staggeredEntrance(2), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileTextField(
                                label = "Date de naissance",
                                value = birthDigits,
                                onValueChange = { birthDigits = it.filter(Char::isDigit).take(8) },
                                keyboardType = KeyboardType.Number,
                                placeholder = "JJ/MM/AAAA",
                                visualTransformation = BirthDateTransformation,
                                modifier = Modifier.weight(1f),
                            )
                            ProfileTextField("Taille (cm)", height, { height = it }, KeyboardType.Number, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileTextField("Poids (kg)", weight, { weight = it }, KeyboardType.Decimal, Modifier.weight(1f))
                            ProfileTextField("FC de repos (bpm)", restingHr, { restingHr = it }, KeyboardType.Number, Modifier.weight(1f))
                        }
                    }
                    ProfileTextField(
                        label = "FC max (bpm)",
                        value = maxHr,
                        onValueChange = { maxHr = it },
                        keyboardType = KeyboardType.Number,
                        helper = "Laisse vide pour utiliser 220 − âge.",
                        modifier = Modifier.staggeredEntrance(3),
                    )
                    ProfileDropdown(
                        label = "Niveau d'entraînement",
                        options = trainingBackgrounds,
                        selected = background,
                        onSelect = { background = it },
                        modifier = Modifier.staggeredEntrance(4),
                    )
                    ProfileDropdown(
                        label = "Journée type",
                        options = typicalDays,
                        selected = typicalDay,
                        onSelect = { typicalDay = it },
                        modifier = Modifier.staggeredEntrance(5),
                    )
                    SleepGoal(sleepGoal, { sleepGoal = it }, Modifier.staggeredEntrance(6))
                }
                Column(
                    Modifier
                        .padding(top = 16.dp)
                        .staggeredEntrance(7),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SetupFilledButton(
                        text = "Configurer le bracelet",
                        onClick = ::submit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canSubmit,
                        loading = saving,
                        loadingText = "Envoi au bracelet…",
                    )
                    AnimatedVisibility(
                        visible = !canSubmit && !saving,
                        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
                    ) {
                        Text(
                            "Connecte le bracelet pour lui envoyer ton profil.",
                            style = BoucleType.caption,
                            color = BoucleColors.OnSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * En-tête de l'écran : celui des écrans de détail, avec le titre de 22 de la maquette (un titre de
 * 26 passerait sur deux lignes).
 */
@Composable
private fun ProfileHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(BoucleIcons.ArrowBack, contentDescription = "Retour à Appareils", tint = BoucleColors.OnSurface, modifier = Modifier.size(22.dp))
        }
        Text(
            "Configuration du bracelet",
            style = BoucleType.detailTitle.copy(fontSize = 22.sp),
            color = BoucleColors.OnSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * Choix du sexe : deux boutons bascules reliés (groupe connecté Material 3 Expressive). Le bouton
 * choisi s'arrondit entièrement et se colore, avec les ressorts du thème.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SexChoice(female: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileLabel("Sexe")
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            listOf(false to "Homme", true to "Femme").forEachIndexed { index, (value, label) ->
                val checked = female == value
                val colors = ToggleButtonDefaults.colors(
                    containerColor = BoucleColors.Surface,
                    contentColor = BoucleColors.OnSurface,
                    checkedContainerColor = BoucleColors.PrimaryContainer,
                    checkedContentColor = BoucleColors.OnPrimaryContainer,
                )
                ToggleButton(
                    checked = checked,
                    onCheckedChange = {
                        if (!checked) haptics.select()
                        onChange(value)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shapes = if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                    colors = colors,
                ) {
                    RadioButton(
                        selected = checked,
                        onClick = null,
                        modifier = Modifier.size(18.dp),
                        colors = RadioButtonDefaults.colors(
                            selectedColor = BoucleColors.Primary,
                            unselectedColor = BoucleColors.Outline,
                        ),
                    )
                    Text(
                        label,
                        style = BoucleType.buttonSmall.copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Liste déroulante de la maquette (champ de 52 dp en lecture seule et menu exposé). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ProfileDropdown(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.first().second
    Column(modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileLabel(label)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            ProfileFieldBox(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailing = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = BoucleColors.Surface,
                shape = MaterialTheme.shapes.medium,
            ) {
                options.forEach { (value, optionLabel) ->
                    DropdownMenuItem(
                        text = { Text(optionLabel, style = BoucleType.bodyLarge) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

/** Objectif de sommeil : curseur Expressive de 5 à 11 h par demi-heure, valeur affichée à droite. */
@Composable
private fun SleepGoal(hours: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val label = formatSleepGoal(hours)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileLabel("Objectif de sommeil")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val state = remember { SliderState(hours, 11, 5f..11f) }
            Slider(
                state = state,
                onValueChange = {
                    state.value = it
                    val stepped = Math.round(it * 2) / 2f
                    if (stepped != hours) haptics.tick()
                    onChange(stepped)
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .semantics { stateDescription = label },
                colors = SliderDefaults.colors(
                    thumbColor = BoucleColors.Primary,
                    activeTrackColor = BoucleColors.Primary,
                    inactiveTrackColor = BoucleColors.PrimaryContainer,
                    activeTickColor = BoucleColors.PrimaryContainer,
                    inactiveTickColor = BoucleColors.Primary,
                ),
            )
            Text(
                label,
                style = BoucleType.valueMBold,
                color = BoucleColors.OnSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 44.dp),
            )
        }
    }
}

/** 8 → « 8 h », 7,5 → « 7 h 30 ». */
private fun formatSleepGoal(hours: Float): String {
    val whole = hours.toInt()
    return if (hours - whole >= 0.5f) "$whole h 30" else "$whole h"
}

/** Valeur transmise au modèle : « 8 » ou « 7.5 ». */
private fun formatSleepGoalValue(hours: Float): String =
    if (hours % 1f == 0f) hours.toInt().toString() else hours.toString()

/** « 14031991 » → « 14/03/1991 » ; une saisie incomplète est transmise telle quelle pour être signalée. */
private fun formatBirthDate(digits: String): String =
    if (digits.length == 8) "${digits.substring(0, 2)}/${digits.substring(2, 4)}/${digits.substring(4)}" else digits

/** FC max par défaut (220 − âge), si la date de naissance est valide. */
private fun defaultMaxHr(birthDate: String): String? = try {
    val birth = LocalDate.parse(birthDate, birthDateFormat)
    (220 - Period.between(birth, LocalDate.now()).years).toString()
} catch (e: DateTimeParseException) {
    null
}
