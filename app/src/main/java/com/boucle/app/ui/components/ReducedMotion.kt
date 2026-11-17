package com.boucle.app.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Vrai quand le système demande de supprimer les animations (« Supprimer les animations » en accessibilité, ou
 * échelle d'animation à 0 dans les options pour les développeurs) : les animations décoratives sont alors sautées
 * et les valeurs affichées directement.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
