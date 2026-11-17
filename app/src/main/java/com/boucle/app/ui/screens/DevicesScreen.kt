package com.boucle.app.ui.screens

import com.boucle.app.ui.components.rememberHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boucle.app.polar.Connection
import com.boucle.app.ui.DeviceUiState
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.ScreenHeader
import com.boucle.app.ui.components.SegmentedBar
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.navigation.Destination
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleShapes
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricColors
import com.boucle.app.ui.theme.MetricPalette

/** Rayon des deux grandes cartes de l'écran (bracelet, Health Connect). */
internal val DevicesCardRadii = Radii(36.dp)

/** Valeur de la grille d'informations (14, demi-gras). */
private val infoValueStyle = BoucleType.body.copy(fontWeight = FontWeight.SemiBold, lineHeight = BoucleType.caption.lineHeight)

/** Ce qu'affiche la première carte, dans l'ordre de priorité. */
private enum class TopCard { Permission, BluetoothOff, NoDevice, Device }

/** Action principale de la carte du bracelet. */
private enum class BandAction { Reconnect, Connecting, ReadingSetup, Configure, Sync }

/**
 * Onglet Appareils : le bracelet (état, batterie, synchronisation), Health Connect et les réglages
 * (configuration, démo, export, effacement, oubli du bracelet).
 */
@Composable
fun DevicesScreen(
    state: DeviceUiState,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onReconnect: () -> Unit,
    onForgetDevice: () -> Unit,
    onRecheckSetup: () -> Unit,
    onSync: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onSyncHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onLoadDemo: () -> Unit,
    onExportData: () -> Unit,
    onClearData: () -> Unit,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val topCard = when {
        !permissionsGranted -> TopCard.Permission
        !state.link.bluetoothOn -> TopCard.BluetoothOff
        state.savedDevice == null -> TopCard.NoDevice
        else -> TopCard.Device
    }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()

    Column(modifier.verticalScroll(rememberScrollState())) {
        ScreenHeader(
            title = "Appareils",
            trailing = {
                AnimatedVisibility(
                    visible = state.savedDevice == null,
                    enter = fadeIn(effects) + scaleIn(spatial),
                    exit = fadeOut(effects) + scaleOut(spatial),
                ) {
                    AddBandButton { onNavigate(Destination.Pairing) }
                }
            },
        )
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(
                targetState = topCard,
                transitionSpec = { (fadeIn(effects) + scaleIn(spatial, initialScale = 0.94f)).togetherWith(fadeOut(effects)) },
                modifier = Modifier.staggeredEntrance(0),
                label = "devicesTopCard",
            ) { card ->
                when (card) {
                    TopCard.Permission -> DevicesMessageCard(
                        icon = BoucleIcons.Bluetooth,
                        badge = BadgeShape.Cookie,
                        palette = MetricPalette.Activity,
                        title = "Autorisation Bluetooth",
                        text = "L'app a besoin du Bluetooth pour parler au bracelet. Aucune donnée ne quitte le téléphone.",
                    ) {
                        SetupFilledButton("Autoriser", onRequestPermissions, Modifier.fillMaxWidth(), height = 56.dp)
                    }
                    TopCard.BluetoothOff -> DevicesMessageCard(
                        icon = BoucleIcons.Bluetooth,
                        badge = BadgeShape.Square,
                        palette = MetricPalette.Energy,
                        title = "Bluetooth désactivé",
                        text = "Active le Bluetooth pour joindre le bracelet.",
                    )
                    TopCard.NoDevice -> DevicesMessageCard(
                        icon = BoucleIcons.Band,
                        badge = BadgeShape.Cookie,
                        palette = MetricPalette.Activity,
                        title = "Aucun bracelet associé",
                        text = "Associe ton Polar Loop pour suivre ton sommeil, ta récupération et ton activité.",
                    ) {
                        SetupFilledButton(
                            text = "Associer un bracelet",
                            onClick = { onNavigate(Destination.Pairing) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = BoucleIcons.Plus,
                        )
                    }
                    TopCard.Device -> BandCard(
                        state = state,
                        onReconnect = onReconnect,
                        onRecheckSetup = onRecheckSetup,
                        onSync = onSync,
                        onConfigure = { onNavigate(Destination.Profile) },
                    )
                }
            }
            HealthConnectCard(
                state = state.healthConnect,
                onRequest = onRequestHealthConnect,
                onSync = onSyncHealthConnect,
                onInstall = onInstallHealthConnect,
                modifier = Modifier.staggeredEntrance(1),
            )
            DevicesSettingsCard(
                hasBand = state.savedDevice != null,
                connected = state.link.connection == Connection.CONNECTED,
                hasSyncedData = state.lastSyncAt != null,
                onConfigure = { onNavigate(Destination.Profile) },
                onLoadDemo = onLoadDemo,
                exporting = state.exporting,
                onExport = onExportData,
                onClear = { confirmClear = true },
                onForget = { confirmForget = true },
                modifier = Modifier.staggeredEntrance(2),
            )
        }
    }

    if (confirmForget) {
        DevicesConfirmDialog(
            title = "Oublier ce bracelet ?",
            text = "L'app se déconnecte du bracelet, mais il reste appairé à ce téléphone : tu pourras le " +
                "retrouver ici.\n\n" +
                "Ne le supprime pas des réglages Bluetooth du téléphone : le Polar Loop refuse un nouvel " +
                "appairage avec le même téléphone, il faudrait le réinitialiser (bouton sous le bracelet, " +
                "pendant qu'il est sur son chargeur). Il faut aussi le réinitialiser pour l'utiliser avec " +
                "un autre téléphone.\n\n" +
                "Les données déjà synchronisées restent dans l'app.",
            confirmLabel = "Oublier",
            onConfirm = {
                confirmForget = false
                onForgetDevice()
            },
            onDismiss = { confirmForget = false },
        )
    }
    if (confirmClear) {
        DevicesConfirmDialog(
            title = "Tout effacer ?",
            text = "Toutes les données enregistrées dans l'app sont supprimées de ce téléphone. Les jours déjà " +
                "synchronisés ont été effacés du bracelet : ils seront perdus.\n\n" +
                "Pour les garder, exporte-les d'abord (« Exporter mes données »).",
            confirmLabel = "Tout effacer",
            onConfirm = {
                confirmClear = false
                onClearData()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/** Bouton rond blanc « + » de l'en-tête (44 dp). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddBandButton(onClick: () -> Unit) {
    val haptics = rememberHaptics()
    FilledIconButton(
        onClick = {
            haptics.tap()
            onClick()
        },
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = BoucleColors.Surface,
            contentColor = BoucleColors.OnSurface,
        ),
    ) {
        Icon(BoucleIcons.Plus, contentDescription = "Associer un bracelet", modifier = Modifier.size(22.dp))
    }
}

/** Carte d'information (autorisation, Bluetooth, aucun bracelet), avec une action facultative. */
@Composable
private fun DevicesMessageCard(
    icon: ImageVector,
    badge: BadgeShape,
    palette: MetricColors,
    title: String,
    text: String,
    action: (@Composable () -> Unit)? = null,
) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = DevicesCardRadii,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, palette.container, palette.onContainer, shape = badge, size = 56.dp, iconSize = 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = BoucleType.sectionTitle)
                Text(text, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
        }
        action?.invoke()
    }
}

/** Carte du bracelet enregistré : état, batterie, informations, action principale et journal. */
@Composable
private fun BandCard(
    state: DeviceUiState,
    onReconnect: () -> Unit,
    onRecheckSetup: () -> Unit,
    onSync: () -> Unit,
    onConfigure: () -> Unit,
) {
    val link = state.link
    val battery = link.batteryPercent
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = DevicesCardRadii,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(
                icon = BoucleIcons.Band,
                container = BoucleColors.PrimaryContainer,
                content = BoucleColors.OnPrimaryContainer,
                shape = BadgeShape.Cookie,
                size = 56.dp,
                iconSize = 28.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    state.savedDevice?.name ?: link.deviceName.orEmpty(),
                    style = BoucleType.valueLBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ConnectionStatus(link.connection)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(battery?.let { "$it %" } ?: "—", style = BoucleType.valueLBold)
                Text(
                    if (link.charging == true) "Batterie (en charge)" else "Batterie",
                    style = BoucleType.caption,
                    color = BoucleColors.OnSurfaceVariant,
                )
            }
        }
        SegmentedBar(fraction = (battery ?: 0) / 100f, color = BoucleColors.Primary, trackColor = BoucleColors.Track)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BandInfo("Dernière synchronisation", state.lastSyncAt?.let { formatSyncMoment(it, timeOnlyToday = false) }, Modifier.weight(1f))
                BandInfo("FC en direct", link.liveHr?.let { "$it bpm" }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BandInfo("Firmware", link.firmware, Modifier.weight(1f))
                BandInfo("Identifiant", link.deviceId ?: state.savedDevice?.id, Modifier.weight(1f))
            }
        }
        val action = when (link.connection) {
            Connection.DISCONNECTED -> BandAction.Reconnect
            Connection.CONNECTING -> BandAction.Connecting
            Connection.CONNECTED -> when (state.setupDone) {
                null -> BandAction.ReadingSetup
                false -> BandAction.Configure
                true -> BandAction.Sync
            }
        }
        val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val fastEffects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val slide = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        AnimatedContent(
            targetState = action,
            transitionSpec = {
                (fadeIn(effects) + slideInVertically(slide) { it / 3 }).togetherWith(fadeOut(fastEffects))
            },
            label = "bandAction",
        ) { current ->
            when (current) {
                BandAction.Reconnect -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    link.lastDisconnectReason?.let {
                        Text("Dernière déconnexion : $it", style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
                    }
                    SetupFilledButton("Reconnecter", onReconnect, Modifier.fillMaxWidth(), icon = BoucleIcons.Bluetooth)
                }
                BandAction.Connecting -> BandPending("Connexion au bracelet…")
                BandAction.ReadingSetup -> BandPending("Lecture de la configuration du bracelet…") {
                    DevicesTextButton("Revérifier", onRecheckSetup)
                }
                BandAction.Configure -> SetupFilledButton("Configurer le bracelet", onConfigure, Modifier.fillMaxWidth())
                BandAction.Sync -> SetupFilledButton(
                    text = "Synchroniser",
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth(),
                    icon = BoucleIcons.Sync,
                    loading = state.syncing,
                    loadingText = "Synchronisation…",
                )
            }
        }
        AnimatedVisibility(
            visible = state.log.isNotEmpty(),
            enter = fadeIn(effects) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(effects) + shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            SyncLog(state.log)
        }
    }
}

/** Point de couleur et libellé de l'état de la liaison ; couleur et texte changent avec des ressorts. */
@Composable
private fun ConnectionStatus(connection: Connection) {
    val target = if (connection == Connection.CONNECTED) BoucleColors.Primary else BoucleColors.OnSurfaceVariant
    val color by animateColorAsState(target, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "statusColor")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val slide = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
        AnimatedContent(
            targetState = connection,
            transitionSpec = {
                (fadeIn(effects) + slideInVertically(slide) { it / 2 })
                    .togetherWith(fadeOut(effects) + slideOutVertically(slide) { -it / 2 })
            },
            label = "statusLabel",
        ) { current ->
            Text(
                when (current) {
                    Connection.CONNECTED -> "Connecté"
                    Connection.CONNECTING -> "Connexion en cours…"
                    Connection.DISCONNECTED -> "Déconnecté"
                },
                style = BoucleType.smallStrong,
                color = color,
            )
        }
    }
}

/** Case de la grille d'informations ; « — » quand la valeur manque. */
@Composable
private fun BandInfo(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
        Text(value ?: "—", style = infoValueStyle)
    }
}

/** Attente du bracelet : indicateur Expressive, message et action facultative. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BandPending(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(Modifier.size(36.dp), color = BoucleColors.Primary)
        Text(text, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Journal de synchronisation, en petite chasse fixe : chaque ligne apparaît en se dépliant. */
@Composable
private fun SyncLog(lines: List<String>) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    Column(
        Modifier
            .fillMaxWidth()
            .background(BoucleColors.Background, BoucleShapes.inner)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEachIndexed { index, line ->
            key(index) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = visible, enter = fadeIn(effects) + expandVertically(spatial)) {
                    Text(
                        line,
                        style = BoucleType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = BoucleColors.OnSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Bouton texte de la maquette (44 dp de haut, couleur primaire), forme transformée à l'appui. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DevicesTextButton(text: String, onClick: () -> Unit, color: Color = BoucleColors.Primary) {
    val haptics = rememberHaptics()
    TextButton(
        onClick = {
            haptics.tap()
            onClick()
        },
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.heightIn(min = 44.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text, style = BoucleType.buttonSmall)
    }
}

/** Boîte de confirmation d'une action destructrice (bouton de confirmation en rouge). */
@Composable
private fun DevicesConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = BoucleType.sectionTitle) },
        text = { Text(text, style = BoucleType.body) },
        confirmButton = { DevicesTextButton(confirmLabel, onConfirm, color = BoucleColors.Error) },
        dismissButton = { DevicesTextButton("Annuler", onDismiss) },
        containerColor = BoucleColors.Surface,
        titleContentColor = BoucleColors.OnSurface,
        textContentColor = BoucleColors.OnSurfaceVariant,
    )
}
