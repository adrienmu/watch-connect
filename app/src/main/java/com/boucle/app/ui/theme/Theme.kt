package com.boucle.app.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.boucle.app.R

/*
 * Jetons de la maquette officielle (canevas Claude Design « Boucle — écrans de l'app »).
 * 1 px de la maquette = 1 dp, 1 px de police = 1 sp. Les couleurs sont fixes (pas de couleurs
 * dynamiques) pour rester identiques à la maquette.
 */

/** Couleurs de base de l'app. */
object BoucleColors {
    val Background = Color(0xFFF2F5F1)
    val Surface = Color(0xFFFFFFFF)
    val OnSurface = Color(0xFF16201B)
    val OnSurfaceVariant = Color(0xFF55635B)
    val Disabled = Color(0xFF9AA59E)
    val Outline = Color(0xFF7B8A81)
    val Divider = Color(0xFFE3E9E4)
    val Track = Color(0xFFE7EDE8)
    val Error = Color(0xFFA3261F)

    val Primary = Color(0xFF0B5D4B)
    val PrimaryDark = Color(0xFF083F33)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFCFE9DF)
    val OnPrimaryContainer = Color(0xFF0A3D31)
}

/** Une famille de couleurs par métrique : fond de carte, texte sur ce fond, accent. */
data class MetricColors(val container: Color, val onContainer: Color, val accent: Color)

object MetricPalette {
    /** Activité, récupération : vert. */
    val Activity = MetricColors(Color(0xFFCFE9DF), Color(0xFF0A3D31), Color(0xFF0B5D4B))
    val Sleep = MetricColors(Color(0xFFE2E3F8), Color(0xFF1E2166), Color(0xFF3A3F9E))
    val Energy = MetricColors(Color(0xFFFBE6D2), Color(0xFF5C2B04), Color(0xFFB4560F))
    val Heart = MetricColors(Color(0xFFF9E0E5), Color(0xFF63111F), Color(0xFFA82C44))
    /** Corps et VO2max : bleu. */
    val Body = MetricColors(Color(0xFFD8EDF4), Color(0xFF083A4C), Color(0xFF0F5F7A))
    /** Bien-être et stress : prune. */
    val Wellbeing = MetricColors(Color(0xFFF1E1F2), Color(0xFF43154A), Color(0xFF7B3486))
}

/** Couleurs propres à certains graphiques. */
object ChartColors {
    // Zones de stress, de la plus claire (repos) à la plus foncée (élevé), et piste des barres du bien-être.
    val StressRest = Color(0xFFE3C8E8)
    val StressLow = Color(0xFFC79AD0)
    val StressMedium = Color(0xFF8F4A9B)
    val StressHigh = Color(0xFF43154A)
    val WellbeingTrack = Color(0xFFE9D3EC)

    // Anneaux de l'écran Aujourd'hui (bloc vert plein).
    val RingTrack = Color(0xFF1F6E5C)
    val RingSteps = Color(0xFFA9F0D6)
    val RingCalories = Color(0xFFFFB877)
    val RingGoal = Color(0xFFC9CDFF)

    // Tuile Pas (bloc vert plein) : jour sous l'objectif, encart sombre, texte secondaire.
    val StepsBelowGoal = Color(0xFF5DB59B)
    val StepsInset = Color(0xFF083F33)
    val StepsMuted = Color(0xFFB9D6CC)

    // Stades de sommeil.
    val SleepDeep = Color(0xFF23277A)
    val SleepLight = Color(0xFF5158C4)
    val SleepRem = Color(0xFF9BA1EC)
    val SleepWake = Color(0xFFC4621A)

    // Intensité d'activité.
    val Sedentary = Color(0xFFC9D3CC)
    val Light = Color(0xFF7DBCA8)
    val Moderate = Color(0xFF1E8A6F)
    val Vigorous = Color(0xFF063D31)

    // Composition corporelle.
    val Fat = Color(0xFFE3A21A)
    val Lean = Color(0xFF0F5F7A)

    val EnergyDrain = Color(0xFF8F4208)
}

/**
 * Couleurs du détail d'activité avec tracé GPS (rangée « Détail d'activité : tracé GPS » de la maquette), qui a sa
 * propre palette indigo.
 */
object RouteColors {
    val Background = Color(0xFFFBF8FF)
    val Container = Color(0xFFF4F2FA)
    val ContainerHigh = Color(0xFFE8E7EF)
    val OnSurface = Color(0xFF1B1B21)
    val OnSurfaceVariant = Color(0xFF46464F)
    val Outline = Color(0xFFC7C5D0)
    val Primary = Color(0xFF4355B9)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFDEE0FF)
    val OnPrimaryContainer = Color(0xFF00105C)
    val SecondaryContainer = Color(0xFFE0E0F9)
    val OnSecondaryContainer = Color(0xFF191A2C)
    val TertiaryContainer = Color(0xFFFFDCBE)
    val OnTertiaryContainer = Color(0xFF2C1600)

    /** Fond de carte avant l'arrivée des tuiles. */
    val MapGround = Color(0xFFECEEE6)
    val Start = Color(0xFF2E7D4F)
    val Finish = Color(0xFF1B1B21)

    /** Dégradés du tracé, du minimum au maximum : allure (lente → rapide), FC, altitude. */
    val PaceGradient = listOf(Color(0xFFF0A050), Color(0xFF9A6BC9), Color(0xFF2A3A9E))
    val HeartGradient = listOf(Color(0xFFFFC48A), Color(0xFFF08A4B), Color(0xFFB3261E))
    val AltitudeGradient = listOf(Color(0xFFA8D5A2), Color(0xFF5E9F63), Color(0xFF1E5A2C))

    val FastestSplit = Color(0xFF8B5000)
    val PaceAltitudeFill = Color(0xFFDCE8D6)
    val HeartLine = Color(0xFFB3261E)
    val HeartFill = Color(0xB3FFDCBE)
    val AltitudeFill = Color(0xFFA8D5A2)
    val AltitudeLine = Color(0xFF1E5A2C)
    val Climb = Color(0xFF2E6B3A)
    val Descent = Color(0xFF8A93B8)

    /** Zones cardiaques 1 à 5 : fond de la pastille et chiffre. */
    val Zones = listOf(
        Color(0xFFFFE8D4) to Color(0xFF2C1600),
        Color(0xFFFFC48A) to Color(0xFF2C1600),
        Color(0xFFF08A4B) to Color(0xFF2C1600),
        Color(0xFFC4502A) to Color(0xFFFFFFFF),
        Color(0xFF8C1D18) to Color(0xFFFFFFFF),
    )
}

/** Graisses utilisées par les styles de la maquette. */
private val FONT_WEIGHTS = listOf(400, 500, 600, 700, 800)

/**
 * Google Sans Flex (police variable, licence OFL : `app/licences/GoogleSansFlex-OFL.txt`), avec l'axe d'arrondi
 * « ROND » au maximum comme dans la maquette.
 */
@OptIn(ExperimentalTextApi::class)
private val Sans = FontFamily(
    FONT_WEIGHTS.map { weight ->
        Font(
            resId = R.font.google_sans_flex,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight),
                FontVariation.Setting("ROND", 100f),
            ),
        )
    },
)

private fun style(size: Int, weight: Int, lineHeight: Float? = null, letterSpacing: TextUnit = TextUnit.Unspecified) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = lineHeight?.em ?: TextUnit.Unspecified,
    letterSpacing = letterSpacing,
)

/**
 * Styles de texte de la maquette (typographie accentuée de Material 3 Expressive : très grands
 * chiffres très gras, titres d'onglet extra-gras). Utiliser ces styles plutôt que des tailles libres.
 */
object BoucleType {
    /** Chiffre héros de Récupération (120). */
    val heroXL = style(120, 800, 0.82f)
    /** Chiffre héros des écrans de détail (96). */
    val heroL = style(96, 800, 0.85f)
    /** Chiffre héros de Forme (88). */
    val heroM = style(88, 800, 0.85f)
    /** Score de sommeil dans la forme festonnée (64). */
    val heroS = style(64, 800, 1f)
    /** Chiffre héros de l'écran Aujourd'hui (56). */
    val heroXS = style(56, 800, 0.9f)
    /** Valeur des tuiles de l'écran Aujourd'hui (48). */
    val tileValue = style(48, 800, 1f)
    /** Titre d'écran de bienvenue (48). */
    val welcomeTitle = style(48, 800, 1f, (-1.5).sp)
    /** « Bon » à côté du score de sommeil (44). */
    val scoreLabel = style(44, 800, 1f)
    /** Titre d'onglet (40). */
    val screenTitle = style(40, 800, 1f, (-1.2).sp)
    /** Valeur moyenne, ex. SpO2 (40). */
    val valueXL = style(40, 800)
    /** Titre « Recherche en cours… » (30). */
    val statusTitle = style(30, 800, letterSpacing = (-0.6).sp)
    /** Titre d'écran de détail (26) et valeurs des petites tuiles (26). */
    val detailTitle = style(26, 700)
    val valueL = style(26, 700)
    /** Nom de l'appareil, batterie (26, extra-gras). */
    val valueLBold = style(26, 800)
    /** Valeurs des lignes (24). */
    val valueM = style(24, 700)
    /** Valeur forte du formulaire (24). */
    val valueMBold = style(24, 800)
    /** Titre de section (20). */
    val sectionTitle = style(20, 700)
    /** Valeur compacte (17), ex. détails d'une séance. */
    val valueS = style(17, 700)
    /** Pastille « Bonne » de Récupération (18). */
    val pillLarge = style(18, 800)
    /** Bouton principal agrandi (18) et bouton de bienvenue (20). */
    val buttonLarge = style(18, 700)
    val buttonXL = style(20, 700)
    val button = style(15, 600)
    val buttonSmall = style(14, 600)
    val bodyLarge = style(16, 400, 1.45f)
    val body = style(14, 400, 1.4f)
    val bodyStrong = style(15, 600)
    val rowTitle = style(16, 700)
    val small = style(13, 400, 1.45f)
    val smallStrong = style(13, 600)
    val caption = style(12, 400)
    val captionStrong = style(12, 600)
    val navLabel = style(12, 500)
    val navLabelSelected = style(12, 700)
}

/** Rayons de la maquette. */
object BoucleShapes {
    /** Bloc héros d'un écran. */
    val hero = RoundedCornerShape(48.dp)
    /** Carte de section blanche. */
    val card = RoundedCornerShape(32.dp)
    val cardSmall = RoundedCornerShape(28.dp)
    val tile = RoundedCornerShape(24.dp)
    val inner = RoundedCornerShape(16.dp)
    val iconSquare = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(percent = 50)

    /** Paire de tuiles côte à côte : grands coins à l'extérieur, petits à l'intérieur. */
    val pairStart = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp)
    val pairEnd = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp)

    /** Grille 2 × 2 de l'écran Aujourd'hui : le grand coin pointe vers l'extérieur. */
    val gridTopStart = RoundedCornerShape(topStart = 32.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
    val gridTopEnd = RoundedCornerShape(topStart = 8.dp, topEnd = 32.dp, bottomEnd = 8.dp, bottomStart = 8.dp)
    val gridBottomStart = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 32.dp)
    val gridBottomEnd = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomEnd = 32.dp, bottomStart = 8.dp)

    /** Liste groupée de deux lignes. */
    val listTop = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 6.dp, bottomStart = 6.dp)
    val listBottom = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
}

private val colorScheme = lightColorScheme(
    primary = BoucleColors.Primary,
    onPrimary = BoucleColors.OnPrimary,
    primaryContainer = BoucleColors.PrimaryContainer,
    onPrimaryContainer = BoucleColors.OnPrimaryContainer,
    secondary = BoucleColors.Primary,
    onSecondary = BoucleColors.OnPrimary,
    secondaryContainer = BoucleColors.PrimaryContainer,
    onSecondaryContainer = BoucleColors.OnPrimaryContainer,
    background = BoucleColors.Background,
    onBackground = BoucleColors.OnSurface,
    surface = BoucleColors.Background,
    onSurface = BoucleColors.OnSurface,
    surfaceVariant = BoucleColors.Track,
    onSurfaceVariant = BoucleColors.OnSurfaceVariant,
    surfaceContainerLowest = BoucleColors.Surface,
    surfaceContainerLow = BoucleColors.Surface,
    surfaceContainer = BoucleColors.Surface,
    surfaceContainerHigh = BoucleColors.Surface,
    surfaceContainerHighest = BoucleColors.Divider,
    outline = BoucleColors.Outline,
    outlineVariant = BoucleColors.Divider,
    error = BoucleColors.Error,
)

private val typography = Typography().let { base ->
    base.copy(
        displayLarge = BoucleType.heroL,
        displayMedium = BoucleType.heroXS,
        displaySmall = BoucleType.tileValue,
        headlineLarge = BoucleType.screenTitle,
        headlineMedium = BoucleType.valueXL,
        headlineSmall = BoucleType.detailTitle,
        titleLarge = BoucleType.sectionTitle,
        titleMedium = BoucleType.rowTitle,
        titleSmall = BoucleType.bodyStrong,
        bodyLarge = BoucleType.bodyLarge,
        bodyMedium = BoucleType.body,
        bodySmall = BoucleType.small,
        labelLarge = BoucleType.buttonSmall,
        labelMedium = BoucleType.captionStrong,
        labelSmall = BoucleType.caption,
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoucleTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = shapes,
        typography = typography,
        content = content,
    )
}
