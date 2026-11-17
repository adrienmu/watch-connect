package com.boucle.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.DeviceUiState
import com.boucle.app.ui.FoundDevice
import com.boucle.app.ui.components.DetailHeader
import com.boucle.app.ui.components.ExpressiveSurface
import com.boucle.app.ui.components.Radii
import com.boucle.app.ui.components.cookieShape
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.MetricPalette
import kotlin.math.abs

/**
 * Recherche et association d'un bracelet. La recherche démarre à l'ouverture de l'écran et
 * s'arrête quand on le quitte ; les bracelets trouvés sont classés du signal le plus fort au
 * plus faible.
 */
@Composable
fun PairingScreen(
    state: DeviceUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (FoundDevice) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val startScan by rememberUpdatedState(onStartScan)
    val stopScan by rememberUpdatedState(onStopScan)
    LaunchedEffect(Unit) { startScan() }
    DisposableEffect(Unit) { onDispose { stopScan() } }

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Column(modifier) {
        DetailHeader("Associer un bracelet", onBack, backLabel = "Retour à Appareils")
        BoxWithConstraints(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PairingHero(state.scanning, onStartScan, Modifier.staggeredEntrance(0))
                    PairingNearby(state.found, onSelectDevice, Modifier.staggeredEntrance(1))
                    PairingWarning(Modifier.staggeredEntrance(2))
                }
                AnimatedVisibility(
                    visible = state.scanning,
                    enter = fadeIn(effects) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(effects) + shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
                ) {
                    SetupOutlinedButton(
                        text = "Arrêter la recherche",
                        onClick = onStopScan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        height = 48.dp,
                        textStyle = BoucleType.button,
                    )
                }
            }
        }
    }
}

/** Bloc vert du haut : illustration, état de la recherche et, à l'arrêt, le bouton pour relancer. */
@Composable
private fun PairingHero(scanning: Boolean, onStartScan: () -> Unit, modifier: Modifier = Modifier) {
    val palette = MetricPalette.Activity
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Hero,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PairingIllustration(scanning)
        AnimatedContent(
            targetState = scanning,
            transitionSpec = { (fadeIn(effects) + scaleIn(spatial, initialScale = 0.9f)).togetherWith(fadeOut(effects)) },
            label = "scanTitle",
        ) { isScanning ->
            Text(
                if (isScanning) "Recherche en cours…" else "Prêt à chercher",
                style = BoucleType.statusTitle,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            "Garde le bracelet à moins d'un mètre du téléphone.",
            style = BoucleType.small,
            textAlign = TextAlign.Center,
        )
        AnimatedVisibility(
            visible = !scanning,
            enter = fadeIn(effects) + expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
            exit = fadeOut(effects) + shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            SetupFilledButton(
                text = "Rechercher",
                onClick = onStartScan,
                modifier = Modifier.padding(top = 4.dp),
                height = 56.dp,
                textStyle = BoucleType.button,
                contentPadding = PaddingValues(horizontal = 28.dp),
            )
        }
    }
}

/**
 * Illustration de 132 dp : disque blanc, forme verte et bracelet blanc. Pendant la recherche, la
 * forme fixe (« cookie ») laisse place à l'indicateur de chargement Expressive, qui change de forme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairingIllustration(scanning: Boolean) {
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val description = if (scanning) "Recherche de bracelets en cours" else null
    Box(
        Modifier
            .size(132.dp)
            .then(
                if (description != null) {
                    Modifier.clearAndSetSemantics { contentDescription = description }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(126.dp)
                .background(BoucleColors.Surface, CircleShape),
        )
        AnimatedContent(
            targetState = scanning,
            transitionSpec = {
                (fadeIn(effects) + scaleIn(spatial, initialScale = 0.6f))
                    .togetherWith(fadeOut(effects) + scaleOut(spatial, targetScale = 0.6f))
            },
            contentAlignment = Alignment.Center,
            label = "scanShape",
        ) { isScanning ->
            if (isScanning) {
                // La forme active occupe 38/48 du composant : 114 dp donnent la forme de 90 dp de la maquette.
                LoadingIndicator(Modifier.size(114.dp), color = BoucleColors.Primary)
            } else {
                Box(
                    Modifier
                        .size(90.dp)
                        .background(BoucleColors.Primary, cookieShape()),
                )
            }
        }
        Icon(BoucleIcons.Band, contentDescription = null, tint = BoucleColors.OnPrimary, modifier = Modifier.size(46.dp))
    }
}

/** Carte « Bracelets à proximité ». Chaque bracelet trouvé apparaît en se dépliant. */
@Composable
private fun PairingNearby(found: List<FoundDevice>, onSelect: (FoundDevice) -> Unit, modifier: Modifier = Modifier) {
    val sorted = found.sortedByDescending { it.rssi }
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = Radii.CardSmall,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    ) {
        Text(
            "Bracelets à proximité",
            style = BoucleType.sectionTitle,
            modifier = Modifier
                .padding(bottom = 4.dp)
                .semantics { heading() },
        )
        AnimatedVisibility(
            visible = sorted.isEmpty(),
            enter = fadeIn(effects) + expandVertically(spatial),
            exit = fadeOut(effects) + shrinkVertically(spatial),
        ) {
            Box(Modifier.heightIn(min = 68.dp), contentAlignment = Alignment.CenterStart) {
                Text("Aucun appareil Polar trouvé pour l'instant.", style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
            }
        }
        sorted.forEachIndexed { index, device ->
            key(device.id) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = visible,
                    enter = fadeIn(effects) + expandVertically(spatial),
                ) {
                    Column {
                        if (index > 0) HorizontalDivider(thickness = 1.dp, color = BoucleColors.Divider)
                        PairingDeviceRow(device, strongest = index == 0, onSelect = { onSelect(device) })
                    }
                }
            }
        }
    }
}

/** Ligne d'un bracelet : nom, qualité du signal et bouton « Associer » (plein pour le plus proche). */
@Composable
private fun PairingDeviceRow(device: FoundDevice, strongest: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(device.name, style = BoucleType.bodyStrong)
            Text(signalLabel(device.rssi), style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        }
        val padding = PaddingValues(horizontal = 20.dp)
        if (strongest) {
            SetupFilledButton(
                text = "Associer",
                onClick = onSelect,
                height = 44.dp,
                textStyle = BoucleType.buttonSmall,
                contentPadding = padding,
            )
        } else {
            SetupOutlinedButton(
                text = "Associer",
                onClick = onSelect,
                shape = RoundedCornerShape(14.dp),
                contentPadding = padding,
            )
        }
    }
}

/** « Signal fort · −48 dBm » : fort au-dessus de −60 dBm, moyen au-dessus de −75 dBm, faible sinon. */
private fun signalLabel(rssi: Int): String {
    val quality = when {
        rssi > -60 -> "fort"
        rssi > -75 -> "moyen"
        else -> "faible"
    }
    val value = if (rssi < 0) "−${abs(rssi)}" else rssi.toString()
    return "Signal $quality · $value dBm"
}

/** Avertissement sur la réinitialisation du Polar Loop. */
@Composable
private fun PairingWarning(modifier: Modifier = Modifier) {
    val palette = MetricPalette.Energy
    ExpressiveSurface(
        color = palette.container,
        radii = Radii.Tile,
        contentColor = palette.onContainer,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(BoucleIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Avant de commencer", style = BoucleType.buttonSmall)
                Text(
                    "Le Polar Loop n'accepte qu'un seul téléphone ou une seule app. S'il a déjà servi avec " +
                        "Polar Flow, il reste invisible : réinitialise-le d'abord, avec le bouton sous le " +
                        "bracelet pendant qu'il est sur son chargeur.",
                    style = BoucleType.small,
                )
            }
        }
    }
}
