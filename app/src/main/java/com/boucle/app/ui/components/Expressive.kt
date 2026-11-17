package com.boucle.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.boucle.app.ui.theme.BoucleColors
import com.boucle.app.ui.theme.BoucleIcons
import com.boucle.app.ui.theme.BoucleType
import kotlinx.coroutines.delay

/*
 * Briques partagées par tous les écrans : mêmes formes, mêmes mouvements Material 3 Expressive
 * (ressorts du MotionScheme.expressive du thème) partout.
 */

/** Rayons des quatre coins, pour pouvoir les animer (morphing au toucher). */
data class Radii(val topStart: Dp, val topEnd: Dp, val bottomEnd: Dp, val bottomStart: Dp) {
    constructor(all: Dp) : this(all, all, all, all)

    fun shape(scale: Float = 1f) = RoundedCornerShape(
        topStart = topStart * scale,
        topEnd = topEnd * scale,
        bottomEnd = bottomEnd * scale,
        bottomStart = bottomStart * scale,
    )

    companion object {
        val Hero = Radii(48.dp)
        val Card = Radii(32.dp)
        val CardSmall = Radii(28.dp)
        val Tile = Radii(24.dp)
        val Inner = Radii(16.dp)
        val PairStart = Radii(28.dp, 8.dp, 8.dp, 28.dp)
        val PairEnd = Radii(8.dp, 28.dp, 28.dp, 8.dp)
        val GridTopStart = Radii(32.dp, 8.dp, 8.dp, 8.dp)
        val GridTopEnd = Radii(8.dp, 32.dp, 8.dp, 8.dp)
        val GridBottomStart = Radii(8.dp, 8.dp, 8.dp, 32.dp)
        val GridBottomEnd = Radii(8.dp, 8.dp, 32.dp, 8.dp)
        val ListTop = Radii(24.dp, 24.dp, 6.dp, 6.dp)
        val ListBottom = Radii(6.dp, 6.dp, 24.dp, 24.dp)
    }
}

/**
 * Surface de la maquette. Cliquable, elle réagit au toucher comme Material 3 Expressive : elle se
 * contracte légèrement et ses coins se resserrent, puis revient avec un ressort.
 */
@Composable
fun ExpressiveSurface(
    color: Color,
    radii: Radii,
    modifier: Modifier = Modifier,
    contentColor: Color = BoucleColors.OnSurface,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spatial, label = "pressScale")
    val corner by animateFloatAsState(if (pressed) 0.6f else 1f, spatial, label = "pressCorner")
    val shape = radii.shape(corner)
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            role = Role.Button,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
    } else {
        Modifier
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(color, shape)
                .then(clickModifier)
                .padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/**
 * Entrée échelonnée : chaque bloc monte de 24 dp en apparaissant, avec le ressort spatial
 * Expressive, [index] × 50 ms après le précédent.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        delay(index * 50L)
        progress.animateTo(1f, spatial)
    }
    val offset = with(LocalDensity.current) { 24.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * offset
    }
}

/**
 * Valeur qui « compte » jusqu'à [target] à l'apparition puis suit ses changements avec un ressort.
 * Renvoie la valeur courante : à formater par l'appelant.
 */
@Composable
fun animatedValue(target: Float): Float {
    val value = remember { Animatable(0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(target) { value.animateTo(target, spec) }
    return value.value
}

/** Entier qui compte jusqu'à [value] (chiffres héros). */
@Composable
fun AnimatedNumber(
    value: Int,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    format: (Int) -> String = ::formatThousands,
) {
    val current = animatedValue(value.toFloat())
    Text(format(Math.round(current)), modifier, style = style, color = color)
}

/** 8412 → « 8 412 » (espace fine insécable comme séparateur de milliers). */
fun formatThousands(value: Int): String = "%,d".format(java.util.Locale.FRENCH, value).replace(' ', ' ').replace(' ', ' ')

/**
 * Barre de progression plate en deux segments séparés de 4 dp (la partie remplie, puis le reste),
 * qui se remplit avec un ressort.
 */
@Composable
fun SegmentedBar(
    fraction: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val animated = animatedValue(fraction.coerceIn(0f, 1f))
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (animated > 0.001f) {
            Box(
                Modifier
                    .weight(animated.coerceAtLeast(0.001f))
                    .height(height)
                    .background(color, RoundedCornerShape(percent = 50)),
            )
        }
        if (animated < 0.999f) {
            Box(
                Modifier
                    .weight((1f - animated).coerceAtLeast(0.001f))
                    .height(height)
                    .background(trackColor, RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/** Forme festonnée à 12 lobes de Material 3 Expressive (« cookie »). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun cookieShape(): Shape = MaterialShapes.Cookie12Sided.toShape()

/** Formes des pastilles d'icône de la maquette. */
enum class BadgeShape { Cookie, Square, Arch, Circle }

/** Icône posée sur une pastille de couleur (44 dp par défaut). */
@Composable
fun IconBadge(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    shape: BadgeShape = BadgeShape.Square,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    val resolved: Shape = when (shape) {
        BadgeShape.Cookie -> cookieShape()
        BadgeShape.Square -> RoundedCornerShape(14.dp)
        BadgeShape.Arch -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 27, bottomStartPercent = 27)
        BadgeShape.Circle -> RoundedCornerShape(percent = 50)
    }
    Box(
        modifier
            .size(size)
            .background(container, resolved),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(iconSize))
    }
}

/** En-tête d'onglet : titre de 40 extra-gras, action facultative à droite. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = BoucleType.screenTitle, color = BoucleColors.OnSurface)
            subtitle?.let { Text(it, style = BoucleType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = BoucleColors.OnSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** En-tête d'écran de détail : flèche de retour (44 dp) et titre de 26. */
@Composable
fun DetailHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, backLabel: String = "Retour") {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(BoucleIcons.ArrowBack, contentDescription = backLabel, tint = BoucleColors.OnSurface, modifier = Modifier.size(22.dp))
        }
        Text(title, style = BoucleType.detailTitle, color = BoucleColors.OnSurface)
    }
}

/** Petite tuile blanche « libellé / valeur / précision ». */
@Composable
fun StatTile(
    label: String,
    value: String,
    radii: Radii,
    modifier: Modifier = Modifier,
    detail: String? = null,
    valueColor: Color = BoucleColors.OnSurface,
    valueStyle: TextStyle = BoucleType.valueL,
) {
    ExpressiveSurface(
        color = BoucleColors.Surface,
        radii = radii,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant)
        Text(value, style = valueStyle, color = valueColor)
        detail?.let { Text(it, style = BoucleType.caption, color = BoucleColors.OnSurfaceVariant) }
    }
}

/** Pastille de couleur + libellé (légende de graphique). */
@Composable
fun LegendDot(label: String, color: Color, modifier: Modifier = Modifier, square: Boolean = true, textColor: Color = BoucleColors.OnSurfaceVariant, style: TextStyle = BoucleType.caption) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, if (square) RoundedCornerShape(3.dp) else RoundedCornerShape(percent = 50)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = style, color = textColor)
    }
}

/** Chevron de fin de ligne cliquable. */
@Composable
fun RowChevron() {
    Icon(BoucleIcons.ChevronRight, contentDescription = null, tint = BoucleColors.OnSurfaceVariant, modifier = Modifier.size(18.dp))
}
