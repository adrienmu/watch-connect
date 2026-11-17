package com.boucle.app.data.export

import com.boucle.app.domain.RoutePoint
import java.io.Writer
import java.time.Instant
import java.util.Locale

/**
 * Tracé au format GPX 1.1, lu par la plupart des applications de sport et de cartographie. La FC de chaque
 * point passe par l'extension Garmin TrackPointExtension, la plus répandue pour cette donnée.
 */
internal object Gpx {
    fun write(out: Writer, name: String, type: String?, points: List<RoutePoint>) {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.write(
            "<gpx version=\"1.1\" creator=\"Boucle\" xmlns=\"http://www.topografix.com/GPX/1/1\" " +
                "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n",
        )
        points.firstOrNull()?.let {
            out.write("  <metadata><name>${escape(name)}</name><time>${Instant.ofEpochMilli(it.timeMs)}</time></metadata>\n")
        }
        out.write("  <trk>\n    <name>${escape(name)}</name>\n")
        type?.let { out.write("    <type>${escape(it)}</type>\n") }
        out.write("    <trkseg>\n")
        points.forEach { p ->
            out.write("      <trkpt lat=\"${coordinate(p.latitude)}\" lon=\"${coordinate(p.longitude)}\">")
            p.altitude?.let { out.write("<ele>${String.format(Locale.ROOT, "%.1f", it)}</ele>") }
            out.write("<time>${Instant.ofEpochMilli(p.timeMs)}</time>")
            p.heartRate?.let {
                out.write("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$it</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            }
            out.write("</trkpt>\n")
        }
        out.write("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.6f", value)

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c >= ' ' || c == '\t' || c == '\n') append(c)
            }
        }
    }
}
