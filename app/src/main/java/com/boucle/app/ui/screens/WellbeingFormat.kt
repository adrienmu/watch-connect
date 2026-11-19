package com.boucle.app.ui.screens

import androidx.compose.ui.graphics.Color
import com.boucle.app.domain.StressZone
import com.boucle.app.domain.WellbeingLevel
import com.boucle.app.domain.WellbeingScore
import com.boucle.app.ui.theme.ChartColors

/** Libellés et couleurs du bien-être, partagés par la tuile d'Aujourd'hui et l'écran Bien-être. */
internal val WellbeingLevel.label: String
    get() = when (this) {
        WellbeingLevel.WATCH -> "À surveiller"
        WellbeingLevel.FRAGILE -> "Fragile"
        WellbeingLevel.BALANCED -> "Équilibré"
        WellbeingLevel.SERENE -> "Serein"
    }

internal val StressZone.label: String
    get() = when (this) {
        StressZone.REST -> "Repos"
        StressZone.LOW -> "Faible"
        StressZone.MEDIUM -> "Moyen"
        StressZone.HIGH -> "Élevé"
    }

/** « Au calme », « Stress faible »… pour une phrase sur la journée. */
internal val StressZone.phrase: String
    get() = when (this) {
        StressZone.REST -> "Au calme"
        StressZone.LOW -> "Stress faible"
        StressZone.MEDIUM -> "Stress moyen"
        StressZone.HIGH -> "Stress élevé"
    }

/** Du plus clair (repos) au plus foncé (élevé) : les zones se distinguent aussi sans la teinte. */
internal val StressZone.color: Color
    get() = when (this) {
        StressZone.REST -> ChartColors.StressRest
        StressZone.LOW -> ChartColors.StressLow
        StressZone.MEDIUM -> ChartColors.StressMedium
        StressZone.HIGH -> ChartColors.StressHigh
    }

/** Position de la VFC nocturne moyenne face à sa référence ; `null` sans les deux mesures. */
internal val WellbeingScore.hrvPosition: String?
    get() {
        val rmssd = rmssdMs ?: return null
        val base = rmssdBaselineMs ?: return null
        return when {
            rmssd > base * 1.05 -> "au-dessus de ta normale"
            rmssd < base * 0.95 -> "sous ta normale"
            else -> "dans ta normale"
        }
    }
