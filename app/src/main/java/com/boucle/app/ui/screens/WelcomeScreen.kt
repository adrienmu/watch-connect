package com.boucle.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.components.BadgeShape
import com.boucle.app.ui.components.IconBadge
import com.boucle.app.ui.components.staggeredEntrance
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import com.boucle.app.ui.theme.ChartColors
import com.boucle.app.ui.theme.MetricColors
import com.boucle.app.ui.theme.MetricPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Anneau de l'illustration : rayon et remplissage dans le repère 150 × 150 de la maquette. */
private class WelcomeRing(val radius: Float, val fraction: Float, val track: Color, val arc: Color)

private val welcomeRings = listOf(
    WelcomeRing(62f, 280f / 389.6f, MetricPalette.Activity.container, BoucleColors.Primary),
    // Orange de la maquette (identique à la couleur « éveillé » des stades de sommeil).
    WelcomeRing(48f, 180f / 301.6f, MetricPalette.Energy.container, ChartColors.SleepWake),
    // Indigo propre à cette illustration.
    WelcomeRing(34f, 150f / 213.6f, MetricPalette.Sleep.container, Color(0xFF6A72D6)),
)

/**
 * Première ouverture, plein écran sans barres : les trois anneaux se tracent avec les ressorts du
 * thème, puis le titre, le texte, la liste et les boutons arrivent à tour de rôle.
 */
@Composable
fun WelcomeScreen(
    onAllowBluetooth: () -> Unit,
    onTryDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .background(BoucleColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                WelcomeRings(Modifier.align(Alignment.CenterHorizontally))
                Column(Modifier.staggeredEntrance(3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Bienvenue dans Boucle", style = BoucleType.welcomeTitle, color = BoucleColors.OnSurface)
                    Text(
                        "Ton sommeil, ta récupération et ton activité, lus directement sur ton bracelet.",
                        style = BoucleType.bodyLarge,
                        color = BoucleColors.OnSurfaceVariant,
                    )
                }
                Column(Modifier.staggeredEntrance(4), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WelcomeFeature(
                        icon = BoucleIcons.Bluetooth,
                        colors = MetricPalette.Activity,
                        shape = BadgeShape.Cookie,
                        title = "Autorisation Bluetooth",
                        text = "L'app en a besoin pour parler au bracelet.",
                    )
                    WelcomeFeature(
                        icon = BoucleIcons.Lock,
                        colors = MetricPalette.Sleep,
                        shape = BadgeShape.Square,
                        title = "Tes données restent chez toi",
                        text = "Aucune donnée ne quitte le téléphone.",
                    )
                    WelcomeFeature(
                        icon = BoucleIcons.Link,
                        colors = MetricPalette.Body,
                        shape = BadgeShape.Arch,
                        title = "Balance et montre en option",
                        text = "Via Health Connect, quand tu le souhaites.",
                    )
                }
            }
            Column(
                Modifier
                    .padding(top = 28.dp)
                    .staggeredEntrance(5),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SetupFilledButton(
                    text = "Autoriser le Bluetooth",
                    onClick = onAllowBluetooth,
                    modifier = Modifier.fillMaxWidth(),
                    height = 72.dp,
                    textStyle = BoucleType.buttonXL,
                )
                SetupOutlinedButton(
                    text = "Essayer avec la démo",
                    onClick = onTryDemo,
                    modifier = Modifier.fillMaxWidth(),
                    height = 56.dp,
                    textStyle = BoucleType.bodyStrong.copy(fontSize = BoucleType.bodyLarge.fontSize),
                    shape = RoundedCornerShape(20.dp),
                )
                Text(
                    "La démo charge 14 jours de données fictives, sans bracelet.",
                    style = BoucleType.caption,
                    color = BoucleColors.OnSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Les trois anneaux concentriques (132 dp) : chaque arc se trace avec un ressort, l'un après l'autre. */
@Composable
private fun WelcomeRings(modifier: Modifier = Modifier) {
    val progress = remember { welcomeRings.map { Animatable(0f) } }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        progress.forEachIndexed { index, animatable ->
            launch {
                delay(index * 90L)
                animatable.animateTo(1f, spatial)
            }
        }
    }
    Canvas(modifier.size(132.dp)) {
        val scale = size.width / 150f
        val stroke = 11f * scale
        welcomeRings.forEachIndexed { index, ring ->
            val p = progress[index].value
            val radius = ring.radius * scale
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawCircle(ring.track, radius, alpha = p.coerceIn(0f, 1f), style = Stroke(stroke))
            drawArc(
                color = ring.arc,
                startAngle = -90f,
                sweepAngle = 360f * ring.fraction * p.coerceAtLeast(0f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Ligne de la liste : pastille d'icône et deux lignes de texte. */
@Composable
private fun WelcomeFeature(icon: ImageVector, colors: MetricColors, shape: BadgeShape, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        IconBadge(icon = icon, container = colors.container, content = colors.onContainer, shape = shape)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = BoucleType.bodyStrong, color = BoucleColors.OnSurface)
            Text(text, style = BoucleType.small, color = BoucleColors.OnSurfaceVariant)
        }
    }
}
