package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boucle.app.healthconnect.HealthConnectAvailability
import com.boucle.app.ui.HealthConnectUiState
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.RowChevron
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Format des horodatages de synchronisation enregistrés par le modèle de vue. */
private val syncMomentFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("dd/MM")

/** Titre de ligne de réglage (15, demi-gras). */
private val rowTitleStyle = BoucleType.bodyStrong

/**
 * « 16/11/2026 16:42 » → « Aujourd'hui à 16:42 » (ou « 16:42 » avec [timeOnlyToday]),
 * « Hier à 16:42 », « 12/11 à 16:42 ». Une valeur illisible est rendue telle quelle.
 */
internal fun formatSyncMoment(raw: String, timeOnlyToday: Boolean): String {
    val moment = try {
        LocalDateTime.parse(raw, syncMomentFormat)
    } catch (e: DateTimeParseException) {
        return raw
    }
    val time = moment.format(timeFormat)
    val today = LocalDate.now()
    return when (moment.toLocalDate()) {
        today -> if (timeOnlyToday) time else "Aujourd'hui à $time"
        today.minusDays(1) -> if (timeOnlyToday) "hier à $time" else "Hier à $time"
        else -> "${moment.format(dayFormat)} à $time"
    }
}

/** Action proposée par la carte Health Connect, selon sa disponibilité et ses autorisations. */
private enum class HealthConnectAction { None, Install, Update, Authorize, Read }

/**
 * Carte Health Connect : autorisations accordées, rôle de la balance et de la montre, dernière
 * lecture et bouton « Lire maintenant » (ou installation, mise à jour, autorisation).
 */
@Composable
internal fun HealthConnectCard(
    state: HealthConnectUiState,
    onRequest: () -> Unit,
    onSync: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = MetricPalette.Body
    val action = when (state.availability) {
        null -> HealthConnectAction.None
        HealthConnectAvailability.NOT_INSTALLED -> HealthConnectAction.Install
        HealthConnectAvailability.UPDATE_REQUIRED -> HealthConnectAction.Update
        HealthConnectAvailability.AVAILABLE -> if (state.granted == 0) HealthConnectAction.Authorize else HealthConnectAction.Read
    }
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = DevicesCardRadii,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(palette.container, BoucleShapes.inner),
                contentAlignment = Alignment.Center,
            ) {
                Icon(BoucleIcons.Link, contentDescription = null, tint = palette.onContainer, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Health Connect", style = BoucleType.bodyLarge.copy(fontWeight = rowTitleStyle.fontWeight, lineHeight = rowTitleStyle.lineHeight))
                Text(healthConnectStatus(state), style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
            AnimatedVisibility(
                visible = action == HealthConnectAction.Read,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    expandHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                    shrinkHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()),
            ) {
                DevicesTextButton("Autorisations", onRequest)
            }
        }
        Text(healthConnectDescription(state), style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = action,
            transitionSpec = { fadeIn(effects).togetherWith(fadeOut(fastEffects)) },
            label = "healthConnectAction",
        ) { current ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                when (current) {
                    HealthConnectAction.None -> Unit
                    HealthConnectAction.Install -> TonalButton("Installer Health Connect", onInstall)
                    HealthConnectAction.Update -> TonalButton("Mettre à jour", onInstall)
                    HealthConnectAction.Authorize -> TonalButton("Autoriser la lecture", onRequest)
                    HealthConnectAction.Read -> {
                        Text(lastReadLabel(state), style = BoucleType.small, color = BoucleColors.OnSurfaceVariant, modifier = Modifier.weight(1f))
                        TonalButton("Lire maintenant", onSync, loading = state.syncing, loadingText = "Lecture…")
                    }
                }
            }
        }
    }
}

/** Bouton tonal de la carte Health Connect (44 dp, rayon 16). */
@Composable
private fun TonalButton(text: String, onClick: () -> Unit, loading: Boolean = false, loadingText: String = text) {
    SetupFilledButton(
        text = text,
        onClick = onClick,
        height = 44.dp,
        textStyle = BoucleType.buttonSmall,
        shape = BoucleShapes.inner,
        containerColor = BoucleColors.PrimaryContainer,
        contentColor = BoucleColors.OnPrimaryContainer,
        loading = loading,
        loadingText = loadingText,
        contentPadding = PaddingValues(horizontal = 20.dp),
    )
}

private fun healthConnectStatus(state: HealthConnectUiState): String = when (state.availability) {
    null -> "Vérification…"
    HealthConnectAvailability.NOT_INSTALLED -> "Health Connect n'est pas installé sur ce téléphone."
    HealthConnectAvailability.UPDATE_REQUIRED -> "Health Connect doit être mis à jour."
    HealthConnectAvailability.AVAILABLE -> when (state.granted) {
        0 -> "Aucun type de données autorisé"
        1 -> "1 type de données autorisé sur ${state.requested}"
        else -> "${state.granted} types de données autorisés sur ${state.requested}"
    }
}

private fun healthConnectDescription(state: HealthConnectUiState): String {
    val source = if (state.replacesBand) {
        "Sans bracelet Polar, la montre fournit aussi la FC, les pas, le sommeil et les séances."
    } else {
        "Le bracelet Polar reste la source de la FC, des pas et du sommeil."
    }
    val setup = if (state.granted == 0) " Dans l'app Withings : Partager › Health Connect, puis coche ces données." else ""
    return "Lit les pesées de ta balance Withings, la SpO2 et la VFC de ta montre. $source$setup"
}

private fun lastReadLabel(state: HealthConnectUiState): String {
    val moment = state.lastSyncAt?.let { "Dernière lecture : ${formatSyncMoment(it, timeOnlyToday = true)}" } ?: "Jamais lu."
    val count = state.lastCount?.let { if (it == 1) " · 1 mesure" else " · $it mesures" }.orEmpty()
    return moment + count
}

/** Une ligne de la carte de réglages, identifiée pour animer son apparition. */
private class SettingsRow(val key: String, val content: @Composable () -> Unit)

/**
 * Carte de réglages : configuration du bracelet (bracelet connecté), mode démo, export et effacement
 * des données, oubli du bracelet (bracelet enregistré).
 */
@Composable
internal fun DevicesSettingsCard(
    hasBand: Boolean,
    connected: Boolean,
    hasSyncedData: Boolean,
    onConfigure: () -> Unit,
    onLoadDemo: () -> Unit,
    exporting: Boolean,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        if (hasBand && connected) {
            add(
                SettingsRow("configure") {
                    SettingsLinkRow("Configuration du bracelet", "Profil, objectifs de sommeil et d'activité", onConfigure)
                },
            )
        }
        add(SettingsRow("demo") { DemoRow(warn = hasBand || hasSyncedData, onLoadDemo = onLoadDemo) })
        add(SettingsRow("export") { ExportRow(exporting, onExport) })
        add(SettingsRow("clear") { SettingsActionRow("Tout effacer", BoucleColors.OnSurface, onClear) })
        if (hasBand) add(SettingsRow("forget") { SettingsActionRow("Oublier ce bracelet", BoucleColors.Error, onForget) })
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            key(row.key) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = visible, enter = fadeIn(effects) + expandVertically(spatial)) {
                    Column {
                        if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
                        row.content()
                    }
                }
            }
        }
    }
}

/** Ligne qui ouvre un écran : titre, sous-titre et chevron. */
@Composable
private fun SettingsLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = rowTitleStyle)
            Text(subtitle, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        }
        RowChevron()
    }
}

/** Ligne du mode démo, avec l'avertissement quand des données réelles seraient remplacées. */
@Composable
private fun DemoRow(warn: Boolean, onLoadDemo: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mode démo", style = rowTitleStyle)
            Text("14 jours de données fictives", style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            if (warn) {
                Text(
                    "Remplace les données locales. Attention : les jours déjà synchronisés sont effacés du " +
                        "bracelet, ils seraient perdus.",
                    style = BoucleType.caption,
                    color = MetricPalette.Energy.accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SetupOutlinedButton("Charger la démo", onLoadDemo)
    }
}

/** Export de toutes les données (droit à la portabilité) dans un fichier choisi par l'utilisateur. */
@Composable
private fun ExportRow(exporting: Boolean, onExport: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Exporter mes données", style = rowTitleStyle)
            Text(
                "Archive ZIP : toutes les mesures en JSON, les parcours en GPX",
                style = BoucleType.small,
                color = BoucleColors.OnSurfaceVariant,
            )
        }
        SetupOutlinedButton(if (exporting) "Export…" else "Exporter", onExport, enabled = !exporting)
    }
}

/** Ligne d'action seule (texte coloré, sans chevron). */
@Composable
private fun SettingsActionRow(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = rowTitleStyle, color = color)
    }
}
