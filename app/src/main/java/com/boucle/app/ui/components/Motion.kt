package com.boucle.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Continuité entre écrans (transformation de conteneur) et retours haptiques, partagés par tous
 * les écrans.
 */

/** Portée des éléments partagés, fournie par la navigation. `null` hors navigation (aperçus). */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Portée d'animation de l'écran affiché, fournie par la navigation. */
val LocalScreenAnimationScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Clés des blocs qui se transforment l'un en l'autre d'un écran à l'autre : la tuile qu'on touche
 * grandit jusqu'à devenir le bloc héros de l'écran ouvert, et revient au retour.
 */
object SharedKeys {
    const val SLEEP = "metric-sleep"
    const val RECOVERY = "metric-recovery"
    const val ENERGY = "metric-energy"
    const val HEART = "metric-heart"
    const val ACTIVITY = "metric-activity"
    const val BODY = "metric-body"
    const val VO2MAX = "metric-vo2max"
    const val WELLBEING = "metric-wellbeing"
}

/**
 * Bloc relié à son homologue sur un autre écran par [key] (transformation de conteneur de
 * Material 3 Expressive : position, taille et coins suivent le ressort spatial du thème, les
 * contenus se fondent l'un dans l'autre). Les coins pendant la transition suivent [radii].
 *
 * Quand le bloc n'a pas d'homologue à l'écran (arrivée par la barre d'onglets, par exemple), il
 * fait l'entrée échelonnée habituelle, comme [staggeredEntrance] avec [entranceIndex] ; quand il
 * en a un, c'est la transformation qui l'amène et l'entrée échelonnée est sautée. [entrance] à
 * `false` quand un parent anime déjà l'entrée du bloc.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedContainer(key: String, radii: Radii, entranceIndex: Int = 0, entrance: Boolean = true): Modifier {
    val sharedScope = LocalSharedTransitionScope.current
    val animationScope = LocalScreenAnimationScope.current
    if (sharedScope == null || animationScope == null) return if (entrance) staggeredEntrance(entranceIndex) else this

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Rect>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val entranceSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    val offset = with(LocalDensity.current) { 24.dp.toPx() }
    val progress = remember { Animatable(if (entrance) 0f else 1f) }

    return with(sharedScope) {
        val state = rememberSharedContentState(key)
        LaunchedEffect(state.isMatchFound) {
            if (state.isMatchFound) {
                progress.snapTo(1f)
            } else if (progress.value < 1f) {
                delay(entranceIndex * 50L)
                progress.animateTo(1f, entranceSpec)
            }
        }
        this@sharedContainer
            .sharedBounds(
                sharedContentState = state,
                animatedVisibilityScope = animationScope,
                enter = fadeIn(effects),
                exit = fadeOut(effects),
                boundsTransform = BoundsTransform { _, _ -> spatial },
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(radii.shape()),
            )
            .graphicsLayer {
                if (!state.isMatchFound) {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    translationY = (1f - p) * offset
                }
            }
    }
}

/** Retours haptiques de l'app, nommés d'après leur usage. */
class Haptics internal constructor(private val perform: (HapticFeedbackType) -> Unit) {
    /** Toucher d'une carte ou d'un bouton. */
    fun tap() = perform(HapticFeedbackType.ContextClick)

    /** Choix dans un groupe (jour, onglet, sexe…). */
    fun select() = perform(HapticFeedbackType.SegmentTick)

    /** Cran d'un curseur. */
    fun tick() = perform(HapticFeedbackType.SegmentFrequentTick)

    /** Seuil atteint (tirer pour synchroniser). */
    fun threshold() = perform(HapticFeedbackType.GestureThresholdActivate)

    /** Action réussie (synchro terminée, bracelet associé, profil envoyé). */
    fun success() = perform(HapticFeedbackType.Confirm)

    /** Échec d'une action. */
    fun failure() = perform(HapticFeedbackType.Reject)

    /** Fin d'un geste (retour validé). */
    fun gestureEnd() = perform(HapticFeedbackType.GestureEnd)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics { feedback.performHapticFeedback(it) } }
}
