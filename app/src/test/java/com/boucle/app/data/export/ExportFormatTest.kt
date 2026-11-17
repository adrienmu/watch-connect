package com.boucle.app.data.export

import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class ExportFormatTest {

    private fun json(block: (JsonStream) -> Unit): String {
        val out = StringWriter()
        block(JsonStream(out))
        return out.toString()
    }

    @Test
    fun `objets, tableaux et séparateurs`() {
        val text = json {
            it.beginObject()
                .field("a", 1)
                .field("b", "x")
                .field("c", null as String?)
                .name("d").beginArray().value(1).value(2.5).beginObject().endObject().endArray()
                .name("e").beginArray().endArray()
                .field("f", true)
                .endObject()
        }
        assertEquals("""{"a":1,"b":"x","c":null,"d":[1,2.5,{}],"e":[],"f":true}""", text)
    }

    @Test
    fun `chaînes échappées`() {
        val text = json { it.value("a\"b\\c\nd\u0001é") }
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001é\"", text)
    }

    @Test
    fun `nombres lisibles et non finis en null`() {
        val text = json {
            it.beginArray().value(36.5f).value(70f).value(12.0).value(Double.NaN).value(Float.POSITIVE_INFINITY).endArray()
        }
        assertEquals("[36.5,70,12,null,null]", text)
    }

    @Test
    fun `séries de nombres`() {
        assertEquals("[12,0,7.5,null]", json { DataExporter.numberSeries(it, "12,0,7.5,x") })
        assertEquals("[]", json { DataExporter.numberSeries(it, "") })
    }

    @Test
    fun `paires`() {
        assertEquals(listOf("0" to "WAKE", "600" to "REM"), DataExporter.pairs("0:WAKE,600:REM"))
        assertEquals(emptyList<Pair<String, String>>(), DataExporter.pairs(""))
    }

    @Test
    fun `noms de fichiers GPX uniques, seulement pour les tracés lisibles`() {
        fun session(path: String, start: String?) = TrainingSessionEntity(
            path, "2026-11-17", start, null, null, null, null, null, null, null, null, null, "", 0, null,
        )
        fun route(path: String, status: String = "AVAILABLE", points: String = "1:45.0:5.0::") =
            TrainingRouteEntity(path, status, "BAND", null, points)
        val sessions = listOf(
            session("a", "2026-11-17T08:30"),
            session("b", "2026-11-17T08:30"),
            session("c", null),
            session("d", "2026-11-17T18:00"),
            session("e", "2026-11-17T19:00"),
        )
        val routes = listOf(route("a"), route("b"), route("c"), route("d", status = "CONSENT_REQUIRED", points = ""))
            .associateBy { it.sessionPath }
        val names = DataExporter.gpxFileNames(sessions, routes)
        assertEquals(
            mapOf(
                "a" to "parcours/2026-11-17_08h30.gpx",
                "b" to "parcours/2026-11-17_08h30-2.gpx",
                "c" to "parcours/2026-11-17.gpx",
            ),
            names,
        )
    }

    @Test
    fun `GPX avec altitude, heure UTC et FC`() {
        val out = StringWriter()
        Gpx.write(
            out,
            "Course <matin> & co",
            null,
            listOf(RoutePoint(0L, 45.1234567, 5.5, 210.04, 140), RoutePoint(1000L, 45.2, 5.6)),
        )
        val gpx = out.toString()
        assertTrue(gpx.contains("<name>Course &lt;matin&gt; &amp; co</name>"))
        assertTrue(
            gpx.contains(
                "<trkpt lat=\"45.123457\" lon=\"5.500000\"><ele>210.0</ele><time>1970-01-01T00:00:00Z</time>" +
                    "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>140</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>",
            ),
        )
        assertTrue(gpx.contains("<trkpt lat=\"45.200000\" lon=\"5.600000\"><time>1970-01-01T00:00:01Z</time></trkpt>"))
        assertFalse(gpx.contains("<type>"))
    }
}
