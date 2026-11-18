package com.boucle.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boucle.app.domain.Sport
import com.boucle.app.domain.Terrain
import com.boucle.app.ui.ActivityEditForm
import com.boucle.app.ui.ActivityEditUiState
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.RouteColors
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val editClock = DateTimeFormatter.ofPattern("HH:mm")

/**
 * « Modifier l'activité » : nom, sport, terrain d'une course, rognage du début et de la fin (séances avec tracé GPS),
 * notes et suppression. Maquette : ActiviteModifier.
 */
@Composable
fun ActivityEditScreen(
    state: ActivityEditUiState,
    onSave: (ActivityEditForm) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    Column(modifier.background(RouteColors.Background)) {
        if (session == null) {
            EditTopBar(onBack, onSave = null)
            return@Column
        }
        val key = state.path
        var name by rememberSaveable(key) { mutableStateOf(session.name.orEmpty()) }
        var sportName by rememberSaveable(key) { mutableStateOf(state.sport?.name) }
        var terrainName by rememberSaveable(key) { mutableStateOf(state.edit?.terrain) }
        var notes by rememberSaveable(key) { mutableStateOf(state.edit?.notes.orEmpty()) }
        val total = state.routeDurationSec
        var trimStart by rememberSaveable(key) { mutableFloatStateOf((state.edit?.trimStartSec ?: 0).toFloat()) }
        var trimEnd by rememberSaveable(key) { mutableFloatStateOf((state.edit?.trimEndSec ?: 0).toFloat()) }
        var confirmDelete by rememberSaveable { mutableStateOf(false) }
        val sport = Sport.named(sportName)

        EditTopBar(
            onBack,
            onSave = {
                onSave(
                    ActivityEditForm(
                        name = name,
                        sport = sport,
                        terrain = Terrain.named(terrainName),
                        trimStartSec = trimStart.roundToInt(),
                        trimEndSec = trimEnd.roundToInt(),
                        notes = notes,
                    ),
                )
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            EditField(
                label = "Nom",
                value = name,
                onChange = { name = it.take(60) },
                singleLine = true,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Sport")
                SportSelector(
                    selected = sport,
                    onSelect = { sportName = it.name },
                    selectedColor = RouteColors.Primary,
                    selectedContent = RouteColors.OnPrimary,
                    idleColor = RouteColors.ContainerHigh,
                    idleContent = RouteColors.OnSurface,
                    height = 88.dp,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(RouteColors.Container, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(BoucleIcons.Info, contentDescription = null, tint = RouteColors.OnSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        when (sport) {
                            Sport.WALK -> "En marche, l'allure est mise en avant. Cette séance ne compte pas pour la VO2max estimée."
                            Sport.BIKE -> "À vélo, l'allure se lit comme une vitesse. Cette séance ne compte pas pour la VO2max estimée."
                            Sport.RUN -> "Le tracé, la fréquence cardiaque et le dénivelé ne changent pas. Une course compte pour la VO2max estimée."
                            null -> "Choisis un sport pour que la séance soit rangée avec les marches, les courses ou les sorties vélo."
                        },
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        color = RouteColors.OnSurfaceVariant,
                    )
                }
            }

            if (sport == Sport.RUN) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Terrain")
                    SegmentedChoice(
                        options = Terrain.entries.map { it.label },
                        selected = Terrain.named(terrainName)?.ordinal,
                        onSelect = { terrainName = Terrain.entries[it].name },
                        track = RouteColors.ContainerHigh,
                        selectedColor = RouteColors.Primary,
                        selectedContent = RouteColors.OnPrimary,
                        content = RouteColors.OnSurface,
                    )
                }
            }

            if (total != null && total > MIN_TRIM_DURATION_SEC) {
                // Le début rogné va de 0 à la fin gardée ; il reste toujours au moins une minute.
                val from = trimStart.coerceIn(0f, total - MIN_KEPT_SEC)
                val to = (total - trimEnd).coerceIn(from + MIN_KEPT_SEC, total.toFloat())
                val start = session.startTime?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                    ?.minusSeconds((state.edit?.trimStartSec ?: 0).toLong())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionTitle("Début et fin", Modifier.alignByBaseline())
                        Text(
                            "${ActivityFormat.duration((to - from).roundToInt())} conservées",
                            Modifier.alignByBaseline(),
                            style = BoucleType.small,
                            color = RouteColors.OnSurfaceVariant,
                        )
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(RouteColors.Container, RoundedCornerShape(28.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.profile.size >= 2) ElevationStrip(state.profile, from / total, to / total)
                        RangeSlider(
                            value = from..to,
                            onValueChange = { range ->
                                trimStart = range.start
                                trimEnd = total - range.endInclusive
                            },
                            valueRange = 0f..total.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = RouteColors.Primary,
                                activeTrackColor = RouteColors.Primary,
                                inactiveTrackColor = RouteColors.Outline,
                            ),
                            modifier = Modifier.semantics { contentDescription = "Rogner le début et la fin de la séance" },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "Début" to (start?.plusSeconds(from.toLong())?.format(editClock) ?: "+${RouteFormat.clock(from)}"),
                                "Fin" to (start?.plusSeconds(to.toLong())?.format(editClock) ?: "+${RouteFormat.clock(to)}"),
                            ).forEach { (label, value) ->
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .background(RouteColors.Background, RoundedCornerShape(16.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(label, style = BoucleType.caption, color = RouteColors.OnSurfaceVariant)
                                    Text(value, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
                                }
                            }
                        }
                    }
                }
            }

            EditField(
                label = "Notes",
                value = notes,
                onChange = { notes = it.take(500) },
                singleLine = false,
                placeholder = "Sensations, chaussures, parcours…",
                style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            )

            PillButton(
                "Supprimer l'activité",
                onClick = { confirmDelete = true },
                color = DeleteContainer,
                contentColor = RouteColors.Zones[4].first,
                modifier = Modifier.fillMaxWidth(),
                height = 56.dp,
                icon = BoucleIcons.Trash,
                style = BoucleType.button,
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Supprimer cette activité ?") },
                text = { Text("La séance, son parcours et tes notes seront effacés de ce téléphone. Elle ne reviendra pas à la prochaine synchronisation.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDelete()
                    }) { Text("Supprimer", color = RouteColors.HeartLine) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
            )
        }
    }
}

@Composable
private fun EditTopBar(onBack: () -> Unit, onSave: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(BoucleIcons.Close, contentDescription = "Annuler", tint = RouteColors.OnSurface, modifier = Modifier.size(24.dp))
        }
        Text(
            "Modifier l'activité",
            Modifier.weight(1f).semantics { heading() },
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = RouteColors.OnSurface,
        )
        if (onSave != null) {
            PillButton("Enregistrer", onSave, RouteColors.Primary, RouteColors.OnPrimary, height = 44.dp, style = BoucleType.button)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 4.dp).semantics { heading() }, style = BoucleType.rowTitle, color = RouteColors.OnSurface)
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean,
    style: TextStyle,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        textStyle = style.copy(color = RouteColors.OnSurface, fontFamily = BoucleType.body.fontFamily),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RouteColors.Primary,
            unfocusedBorderColor = RouteColors.Outline,
            focusedLabelColor = RouteColors.Primary,
            unfocusedLabelColor = RouteColors.OnSurfaceVariant,
            cursorColor = RouteColors.Primary,
            focusedContainerColor = androidx.compose.ui.graphics.Color.White,
            unfocusedContainerColor = androidx.compose.ui.graphics.Color.White,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Profil d'altitude du tracé complet ; les parties rognées sont estompées. */
@Composable
private fun ElevationStrip(profile: List<Float>, from: Float, to: Float) {
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val low = profile.min()
        val span = (profile.max() - low).coerceAtLeast(1f)
        fun x(i: Int) = size.width * i / profile.lastIndex
        fun y(v: Float) = size.height - (v - low) / span * (size.height - 8.dp.toPx()) - 2.dp.toPx()
        val line = Path().apply {
            moveTo(0f, y(profile.first()))
            profile.forEachIndexed { i, v -> if (i > 0) lineTo(x(i), y(v)) }
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, RouteColors.AltitudeFill)
        drawPath(line, RouteColors.AltitudeLine, style = Stroke(2.dp.toPx()))
        val veil = RouteColors.Container.copy(alpha = 0.8f)
        drawRect(veil, Offset.Zero, Size(size.width * from, size.height))
        drawRect(veil, Offset(size.width * to, 0f), Size(size.width * (1 - to), size.height))
    }
}

private val DeleteContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC)

/** En dessous de cette durée, le rognage n'est pas proposé ; il laisse toujours au moins [MIN_KEPT_SEC]. */
private const val MIN_TRIM_DURATION_SEC = 120
private const val MIN_KEPT_SEC = 60f
