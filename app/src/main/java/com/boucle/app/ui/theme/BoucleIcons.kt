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
    private fun stroke(name: String, vararg paths: String, width: Float = 2f, filled: List<String> = emptyList()): ImageVector =
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
            filled.forEach { data -> addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black)) }
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

    // Détail d'activité et carte du tracé GPS.
    val Share = stroke("Share", "M4 13v6a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-6M16 7l-4-4-4 4M12 3v12")
    val More = stroke("More", "M12 5h.01M12 12h.01M12 19h.01", width = 4f)
    val Expand = stroke("Expand", "M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7")
    val Close = stroke("Close", "M6 6l12 12M18 6L6 18", width = 2.2f)
    val Layers = stroke("Layers", "M12 3l9 5-9 5-9-5 9-5z", "M3 13l9 5 9-5")
    val Play = stroke("Play", filled = listOf("M8 5v14l11-7z"))
    val Recenter = stroke(
        "Recenter",
        "M19 12a7 7 0 1 1-14 0a7 7 0 1 1 14 0z",
        "M12 2v3M12 19v3M2 12h3M19 12h3",
        filled = listOf("M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0z"),
    )
    val Pin = stroke("Pin", "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z", "M14 10a2 2 0 1 1-4 0a2 2 0 1 1 4 0z")
    val Check = stroke("Check", "M5 12l5 5 9-10", width = 2.4f)
    val ArrowForward = stroke("ArrowForward", "M5 12h14M13 6l6 6-6 6")
    val Run = stroke("Run", "M17 4a2 2 0 1 1-4 0a2 2 0 1 1 4 0z", "M8 21l3-6 3 2v5M6 11l3-3h4l2 4 3 1M11 15l1-6")
    val Bike = stroke(
        "Bike",
        "M9 17a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z",
        "M22 17a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0z",
        "M5.5 17l3.5-7h6l3.5 7M9 10l3 7 3-7M13 6h3",
    )
    val Timer = stroke("Timer", "M20 13a8 8 0 1 1-16 0a8 8 0 1 1 16 0z", "M12 9v4l2.5 2M9 2h6")
    val Gauge = stroke("Gauge", "M5 18a8 8 0 1 1 14 0M12 14l4-5")
    val Mountain = stroke("Mountain", "M3 19l6-10 4 6 2-3 6 7z")
    // Historique des pas et objectifs.
    val Calendar = stroke(
        "Calendar",
        "M6 5h12a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M4 10h16M8 3v4M16 3v4",
    )
    val Edit = stroke("Edit", "M4 20l1-4L16.5 4.5a2.1 2.1 0 0 1 3 3L8 19l-4 1z", "M14.5 6.5l3 3")
    val Minus = stroke("Minus", "M5 12h14")
    /** Deux empreintes pleines. */
    val Steps = stroke(
        "Steps",
        filled = listOf("M8.5 4a3 5 0 1 1 0 10a3 5 0 1 1 0-10z", "M15.5 10a3 5 0 1 1 0 10a3 5 0 1 1 0-10z"),
    )
    /** Flamme pleine (série de jours à l'objectif). */
    val Flame = stroke(
        "Flame",
        filled = listOf("M12 3c1 3.5 5 5.5 5 10a5 5 0 0 1-10 0c0-1.8.8-3 1.8-4 .3 1.5 1 2.3 2 2.6C10.2 9.5 10.5 6 12 3z"),
    )
    /** Synchronisation Health Connect (deux flèches en cercle). */
    val Refresh = stroke("Refresh", "M4 12a8 8 0 0 1 14-5.3L20 9M20 12a8 8 0 0 1-14 5.3L4 15M20 4v5h-5M4 20v-5h5")
}
