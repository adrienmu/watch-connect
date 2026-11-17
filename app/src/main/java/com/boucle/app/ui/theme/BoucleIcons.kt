package com.boucle.app.ui.theme

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes au trait de la maquette (viewBox 24, trait arrondi). Elles se colorent avec la teinte
 * de [androidx.compose.material3.Icon] (par défaut la couleur du contenu).
 */
object BoucleIcons {
    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Sync = stroke(
        "Sync",
        "M20 12a8 8 0 0 1-14.3 4.9M4 12a8 8 0 0 1 14.3-4.9",
        "M18.5 3v4.2h-4.2M5.5 21v-4.2h4.2",
        width = 2.2f,
    )
    val ChevronLeft = stroke("ChevronLeft", "M15 5l-7 7 7 7")
    val ChevronRight = stroke("ChevronRight", "M9 5l7 7-7 7")
    val ArrowBack = stroke("ArrowBack", "M19 12H5M11 6l-6 6 6 6")
    val Moon = stroke("Moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    val Shield = stroke("Shield", "M12 21c-4.5-1.5-7-5-7-9.5V6l7-3 7 3v5.5c0 4.5-2.5 8-7 9.5z", "M9 12l2.2 2.2L15.5 10")
    val Bolt = stroke("Bolt", "M13 3L5 13.5h6L10 21l8-10.5h-6L13 3z")
    val Heart = stroke("Heart", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val Scale = stroke(
        "Scale",
        "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4z",
        "M8.5 9.5a5 5 0 0 1 7 0M12 9.5l1.5-1.5",
    )
    val Pulse = stroke("Pulse", "M3 12h4l3-7 4 14 3-7h4")
    val Today = stroke(
        "Today",
        "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0z",
        "M15.5 12a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z",
    )
    val Band = stroke(
        "Band",
        "M10 6h4a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3h-4a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3z",
        "M9 6V3h6v3M9 18v3h6v-3",
    )
    val Plus = stroke("Plus", "M12 5v14M5 12h14")
    val Link = stroke(
        "Link",
        "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val Info = stroke("Info", "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z", "M12 11v6M12 7.5v.5")
    val Warning = stroke("Warning", "M12 4l9 16H3L12 4z", "M12 10v5M12 17.5v.5")
    val Lock = stroke(
        "Lock",
        "M8 11h8a3 3 0 0 1 3 3v3a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-3a3 3 0 0 1 3-3z",
        "M8 11V8a4 4 0 0 1 8 0v3",
    )
    val Bluetooth = stroke("Bluetooth", "M7 7l10 10-5 4V3l5 4L7 17")
}
