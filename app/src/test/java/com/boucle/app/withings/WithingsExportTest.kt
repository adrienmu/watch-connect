package com.boucle.app.withings

import com.boucle.app.data.db.ActivityEditEntity
import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingExerciseEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.data.export.BackupSink
import com.boucle.app.data.export.ImportCounts
import com.boucle.app.domain.MeasurementType
import com.boucle.app.domain.RouteCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class WithingsExportTest {
    private val paris = ZoneId.of("Europe/Paris")

    @Test
    fun `csv keeps bracket lists and quoted json in one field`() {
        val csv = CsvReader(StringReader("﻿start,duration,value\r\n2024-03-01T10:00:00+01:00,[60,60],[100,120]\n\na,\"{\"\"x\"\":1,\"\"y\"\":[1,2]}\",\"line\nbreak\"\n"))
        assertEquals(listOf("start", "duration", "value"), csv.next())
        assertEquals(listOf("2024-03-01T10:00:00+01:00", "[60,60]", "[100,120]"), csv.next())
        assertEquals(listOf("a", "{\"x\":1,\"y\":[1,2]}", "line\nbreak"), csv.next())
        assertNull(csv.next())
    }

    @Test
    fun `timestamps keep their own offset`() {
        val winter = WithingsTime.parse("2024-03-01T23:30:00+01:00", paris)!!
        assertEquals(3600, winter.offsetSec)
        assertEquals(winter, WithingsTime.parse("2024-03-01T23:30:00+0100", paris))
        // Sans décalage : heure locale du fuseau donné (heure d'été).
        assertEquals(7200, WithingsTime.parse("2024-07-01 07:10:00", paris)!!.offsetSec)
        assertEquals(winter.epochSec, WithingsTime.parse(winter.epochSec.toString(), paris)!!.epochSec)
        assertNull(WithingsTime.parse("pas une date", paris))
    }

    @Test
    fun `raw series expands durations`() {
        val seen = ArrayList<Triple<Long, Int, Double>>()
        val count = WithingsSeries.read(
            StringReader("start,duration,value\n2024-03-01T10:00:00+01:00,\"[60,30]\",\"[100,x,7]\"\nillisible,[1],[2]\n"),
            paris,
        ) { stamp, duration, value -> seen += Triple(stamp.epochSec, duration, value) }
        val start = WithingsTime.parse("2024-03-01T10:00:00+01:00", paris)!!.epochSec
        assertEquals(2, count)
        assertEquals(listOf(Triple(start, 60, 100.0), Triple(start + 90, 0, 7.0)), seen)
    }

    @Test
    fun `files are recognised by name`() {
        assertEquals(WithingsExport.Kind.HR, WithingsExport.kind("export/raw_hr_hr.csv"))
        assertEquals(WithingsExport.Kind.HR, WithingsExport.kind("raw_tracker_hr.csv"))
        assertEquals(WithingsExport.Kind.SLEEP_STATE, WithingsExport.kind("raw_tracker_sleep-state.csv"))
        assertEquals(WithingsExport.Kind.DAILY_ACTIVE_CALORIES, WithingsExport.kind("aggregates_calories_earned.csv"))
        assertNull(WithingsExport.kind("raw_tracker_elevation.csv"))
        assertNull(WithingsExport.kind("donnees.json"))
        assertTrue(WithingsExport.isWithingsFile("weight.csv"))
        assertFalse(WithingsExport.isWithingsFile("boucle.json"))
    }

    @Test
    fun `whole archive becomes app rows`() = runBlocking {
        val sink = Collecting()
        val report = archive(fullArchive()).use { WithingsExport.read(it, paris, sink) }

        assertEquals(LocalDate.parse("2024-03-01"), report.firstDay)
        assertEquals(LocalDate.parse("2024-03-02"), report.lastDay)
        assertTrue("raw_hr_hr.csv" in report.files)

        // Totaux du jour : pas, distance, calories actives et de repos.
        val day = sink.daily.single { it.date == "2024-03-01" }
        assertEquals(8000, day.steps)
        assertEquals(6100f, day.distanceMeters)
        assertEquals(420, day.activityCalories)
        assertEquals(1700, day.bmrCalories)

        // Pas à la minute : 100 à 10:00, 120 à 10:01, à l'heure locale.
        val steps = sink.samples.single().stepSamples.split(",")
        assertEquals("2024-03-01", sink.samples.single().date)
        assertEquals(1440, steps.size)
        assertEquals("100", steps[600])
        assertEquals("120", steps[601])
        assertEquals("0", steps[602])

        // FC : moyenne par 5 minutes, bloc de l'heure locale.
        val block = sink.hr.single { it.date == "2024-03-01" && it.startTime == "18:00" }
        assertEquals("150,160,0,0,0,0,0,0,0,0,0,0", block.samples)

        // Nuit rangée au jour du réveil, avec ses phases détaillées.
        val night = sink.sleep.single()
        assertEquals("2024-03-02", night.date)
        assertEquals("0:NONREM12,3600:NONREM3,7200:REM,10800:WAKE,12600:NONREM12", night.phases)
        val recharge = sink.recharge.single()
        assertEquals("2024-03-02", recharge.date)
        // 50 bpm de moyenne d'après sleep.csv → 1 200 ms entre deux battements.
        assertEquals(1200, recharge.meanRriMs)

        // Séance : type, données JSON, FC de la colonne Data.
        val run = sink.sessions.single()
        assertEquals("Course à pied", run.name)
        assertEquals(1L, run.sportId)
        assertEquals("2024-03-01T18:00", run.startTime)
        assertEquals(600, run.durationSec)
        assertEquals(2000.4f, run.distanceMeters)
        assertEquals(155, run.averageHr)
        assertEquals(172, run.maxHr)
        assertTrue(run.path.startsWith("withings:"))

        // Tracé : les points pendant la séance, avec la FC mesurée au même moment.
        val route = sink.routes.single()
        assertEquals(run.path, route.sessionPath)
        assertEquals("WITHINGS", route.source)
        val points = RouteCodec.decode(route.points)
        assertEquals(3, points.size)
        assertEquals(48.85, points.first().latitude, 1e-6)
        assertEquals(2.35, points.first().longitude, 1e-6)
        assertEquals(150, points.first().heartRate)

        // Pesée : masse grasse en %, masse maigre = poids − graisse.
        fun measure(type: MeasurementType) = sink.measurements.single { it.type == type.name }
        assertEquals(80.0, measure(MeasurementType.WEIGHT).value, 1e-9)
        assertEquals(20.0, measure(MeasurementType.BODY_FAT).value, 1e-9)
        assertEquals(64.0, measure(MeasurementType.LEAN_MASS).value, 1e-9)
        assertEquals(3.2, measure(MeasurementType.BONE_MASS).value, 1e-9)
        assertEquals("2024-03-02", measure(MeasurementType.WEIGHT).date)
        assertEquals("2024-03-02T06:10:00Z", measure(MeasurementType.WEIGHT).time)
        assertEquals(97.0, measure(MeasurementType.SPO2).value, 1e-9)
    }

    @Test
    fun `night without detailed stages keeps phase totals`() = runBlocking {
        val sink = Collecting()
        archive(mapOf("sleep.csv" to SLEEP)).use { WithingsExport.read(it, paris, sink) }
        // Léger 4 h, profond 2 h, paradoxal 1 h 30, éveil 30 min, bout à bout.
        assertEquals("0:NONREM12,14400:NONREM3,21600:REM,27000:WAKE", sink.sleep.single().phases)
    }

    @Test
    fun `reading twice gives the same keys`() = runBlocking {
        val first = Collecting()
        val second = Collecting()
        archive(fullArchive()).use { WithingsExport.read(it, paris, first) }
        archive(fullArchive()).use { WithingsExport.read(it, paris, second) }
        assertEquals(first.sessions.map { it.path }, second.sessions.map { it.path })
        assertEquals(first.measurements.map { it.id }, second.measurements.map { it.id })
    }

    @Test
    fun `merge filter drops what the phone already has under another key`() = runBlocking {
        val all = Collecting()
        archive(fullArchive()).use { WithingsExport.read(it, paris, all) }
        val run = all.sessions.single()
        val weight = all.measurements.single { it.type == MeasurementType.WEIGHT.name }

        val kept = Collecting()
        val filter = WithingsMergeFilter(
            out = kept,
            hrDates = setOf("2024-03-01"),
            activityDates = setOf("2024-03-01"),
            // La même séance lue dans Health Connect (départ à 30 s près) et la même pesée.
            sessions = listOf(run.copy(path = "healthconnect:abc", startTime = "2024-03-01T18:00:30")),
            measurements = listOf(weight.copy(id = "hc-1")),
        )
        archive(fullArchive()).use { WithingsExport.read(it, paris, filter) }

        assertTrue(kept.hr.none { it.date == "2024-03-01" })
        assertTrue(kept.hr.any { it.date == "2024-03-02" })
        assertTrue(kept.samples.isEmpty())
        assertTrue(kept.sessions.isEmpty())
        assertTrue(kept.routes.isEmpty())
        assertTrue(kept.measurements.none { it.type == MeasurementType.WEIGHT.name })
        assertTrue(kept.measurements.any { it.type == MeasurementType.BODY_FAT.name })
        // Les jours et les nuits passent : « n'ajouter que ce qui manque » s'en charge par leur date.
        assertEquals(all.daily, kept.daily)
        assertEquals(all.sleep, kept.sleep)
    }

    @Test
    fun `summary says what was added and what was already there`() {
        val report = WithingsReport(listOf("weight.csv"), LocalDate.parse("2019-03-12"), LocalDate.parse("2026-11-18"))
        val summary = WithingsImportSummary(
            report,
            read = ImportCounts(dailyActivity = 10, sleep = 8, trainingSessions = 1),
            added = ImportCounts(dailyActivity = 7, sleep = 8),
        )
        assertEquals(
            "Historique Withings importé, du 12/03/2019 au 18/11/2026. Ajoutés : 7 jours d'activité, 8 nuits. " +
                "Déjà présents (conservés) : 3 jours d'activité, 1 séance.",
            summary.message(),
        )
        assertEquals(
            "Historique Withings importé. Rien d'ajouté : tout était déjà là.",
            WithingsImportSummary(WithingsReport(listOf("weight.csv"), null, null), ImportCounts(sleep = 2), ImportCounts()).message(),
        )
    }

    private fun fullArchive() = mapOf(
        "export/weight.csv" to "Date,\"Weight (kg)\",\"Fat mass (kg)\",\"Bone mass (kg)\",\"Muscle mass (kg)\",\"Hydration (kg)\",Comments\n" +
            "2024-03-02 07:10:00,80.0,16.0,3.2,60.0,45.0,\n",
        "export/aggregates_steps.csv" to "date,value\n2024-03-01,8000\n",
        "export/aggregates_distance.csv" to "date,value\n2024-03-01,6100\n",
        "export/aggregates_calories_earned.csv" to "date,value\n2024-03-01,420\n",
        "export/aggregates_calories_passive.csv" to "date,value\n2024-03-01,1700\n",
        "export/raw_tracker_steps.csv" to "start,duration,value\n2024-03-01T10:00:00+01:00,[60,60],[100,120]\n",
        "export/raw_hr_hr.csv" to "start,duration,value\n" +
            "2024-03-01T18:00:00+01:00,[1,1,1],[150,150,150]\n" +
            "2024-03-01T18:05:00+01:00,[60],[160]\n" +
            "2024-03-02T02:00:00+01:00,[600],[52]\n",
        "export/raw_tracker_sleep-state.csv" to "start,duration,value\n" +
            "2024-03-01T23:00:00+01:00,[3600,3600,3600,1800,16200],[1,2,3,0,1]\n",
        "export/sleep.csv" to SLEEP,
        "export/activities.csv" to "from,to,from (manual),to (manual),Timezone,Activity type,Data,GPS,Modified\n" +
            "2024-03-01T18:00:00+01:00,2024-03-01T18:10:00+01:00,,,Europe/Paris,Running," +
            "\"{\"\"calories\"\":180,\"\"hr_average\"\":155,\"\"hr_max\"\":172,\"\"distance\"\":2000.4,\"\"steps\"\":1900}\",,\n",
        "export/raw_location_latitude.csv" to "start,duration,value\n2024-03-01T18:00:00+01:00,[1,1,1],[48.85,48.8501,48.8502]\n" +
            "2024-03-01T20:00:00+01:00,[1],[48.9]\n",
        "export/raw_location_longitude.csv" to "start,duration,value\n2024-03-01T18:00:00+01:00,[1,1,1],[2.35,2.3501,2.3502]\n" +
            "2024-03-01T20:00:00+01:00,[1],[2.4]\n",
        "export/raw_spo2_auto_spo2.csv" to "start,duration,value\n2024-03-02T03:00:00+01:00,[30],[97]\n",
        "export/signal.csv" to "inconnu\n1\n",
    )

    private fun archive(files: Map<String, String>): ZipFile {
        val file = File.createTempFile("withings", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ZipFile(file)
    }

    private companion object {
        const val SLEEP = "from,to,light (s),deep (s),rem (s),awake (s),wake up,Duration to sleep (s),Duration to wake up (s)," +
            "Snoring (s),Snoring episodes,Average heart rate,Heart rate (min),Heart rate (max),Night events\n" +
            "2024-03-01T23:00:00+01:00,2024-03-02T07:00:00+01:00,14400,7200,5400,1800,1,300,120,0,0,50,44,71,\n"
    }
}

private class Collecting : BackupSink {
    val daily = mutableListOf<DailyActivityEntity>()
    val samples = mutableListOf<ActivitySamplesEntity>()
    val hr = mutableListOf<HrBlockEntity>()
    val sleep = mutableListOf<SleepEntity>()
    val recharge = mutableListOf<NightlyRechargeEntity>()
    val sessions = mutableListOf<TrainingSessionEntity>()
    val routes = mutableListOf<TrainingRouteEntity>()
    val measurements = mutableListOf<ExternalMeasurementEntity>()

    override suspend fun dailyActivity(row: DailyActivityEntity) { daily += row }
    override suspend fun activitySamples(row: ActivitySamplesEntity) { samples += row }
    override suspend fun hrBlock(row: HrBlockEntity) { hr += row }
    override suspend fun ppiBlock(row: PpiBlockEntity) = Unit
    override suspend fun sleep(row: SleepEntity) { sleep += row }
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) { recharge += row }
    override suspend fun skinTemperature(row: SkinTemperatureEntity) = Unit
    override suspend fun trainingSession(row: TrainingSessionEntity) { sessions += row }
    override suspend fun trainingExercise(row: TrainingExerciseEntity) = Unit
    override suspend fun trainingRoute(row: TrainingRouteEntity) { routes += row }
    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) { measurements += row }
    override suspend fun activityEdit(row: ActivityEditEntity) = Unit
}
