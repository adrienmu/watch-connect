package com.boucle.app.data.export

import com.boucle.app.data.AppSettings
import com.boucle.app.data.RestoredSettings
import com.boucle.app.data.SavedDevice
import com.boucle.app.data.UserProfile
import com.boucle.app.data.db.ActivitySamplesEntity
import com.boucle.app.data.db.DailyActivityEntity
import com.boucle.app.data.db.DateSteps
import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HealthDao
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.NightlyRechargeEntity
import com.boucle.app.data.db.PpiBlockEntity
import com.boucle.app.data.db.SkinTemperatureEntity
import com.boucle.app.data.db.SleepEntity
import com.boucle.app.data.db.TrainingExerciseEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupImportTest {

    // --- Parsing et validation ---

    private fun readJson(text: String, sink: CollectingSink = CollectingSink()): BackupHeader =
        runBlocking { BackupReader.readJson(StringReader(text), sink) }

    private fun assertRefused(text: String, expected: String) {
        try {
            readJson(text)
            fail("sauvegarde acceptée : $text")
        } catch (e: BackupFormatException) {
            assertTrue("message : ${e.message}", e.message!!.contains(expected))
        }
    }

    @Test
    fun `version de format plus récente refusée avec un message clair`() {
        assertRefused("""{"formatVersion":2,"format":"boucle-export","dailyActivity":[]}""", "version plus récente")
    }

    @Test
    fun `version de format inconnue refusée`() {
        assertRefused("""{"formatVersion":0}""", "Format de sauvegarde inconnu")
        assertRefused("""{"formatVersion":"1"}""", "Format de sauvegarde inconnu")
        assertRefused("""{"formatVersion":1.5}""", "Format de sauvegarde inconnu")
    }

    @Test
    fun `version absente ou autre format refusés`() {
        assertRefused("""{"dailyActivity":[]}""", "pas une sauvegarde Boucle")
        assertRefused("""{}""", "pas une sauvegarde Boucle")
        assertRefused("""{"formatVersion":1,"format":"autre-chose"}""", "pas une sauvegarde Boucle")
        assertRefused("""[1,2]""", "pas une sauvegarde Boucle")
    }

    @Test
    fun `JSON tronqué refusé, à n'importe quel endroit`() {
        val text = """{"formatVersion":1,"format":"boucle-export","exportedAt":"2026-11-17T10:00:00Z",""" +
            """"sleep":[{"date":"2026-11-16","phases":[{"offsetSec":0,"state":"WAKE"}],"batteryRanOut":false}],""" +
            """"heartRate":[{"date":"2026-11-16","startTime":"08:00","trigger":"TIMED","bpm":[60,61.5,null]}]}"""
        readJson(text)
        for (cut in 1 until text.length) {
            try {
                readJson(text.substring(0, cut))
                fail("tronqué à $cut, accepté : ${text.substring(0, cut)}")
            } catch (e: BackupFormatException) {
                // attendu
            }
        }
    }

    @Test
    fun `contenu en trop ou syntaxe invalide refusés`() {
        assertRefused("""{"formatVersion":1} {}""", "endommagée")
        assertRefused("""{"formatVersion":1,}""", "endommagée")
        assertRefused("""{"formatVersion":01}""", "endommagée")
        assertRefused("""{"formatVersion":1,"sleep":[{"date":"2026-11-16","batteryRanOut":tru}]}""", "endommagée")
    }

    @Test
    fun `ligne invalide refusée avec sa position`() {
        assertRefused(
            """{"formatVersion":1,"dailyActivity":[{"date":"2026-11-16"},{"date":"16/11/2026"}]}""",
            "dailyActivity, élément 2 : champ « date »",
        )
        assertRefused("""{"formatVersion":1,"dailyActivity":[{"date":"2026-11-16","steps":12.5}]}""", "« steps »")
        assertRefused("""{"formatVersion":1,"heartRate":[{"date":"2026-11-16","trigger":"TIMED","bpm":[]}]}""", "« startTime »")
        assertRefused(
            """{"formatVersion":1,"trainingSessions":[{"id":"a","date":"2026-11-16","summaryProtoBase64":"%%%"}]}""",
            "« summaryProtoBase64 »",
        )
    }

    @Test
    fun `imbrication excessive refusée sans débordement de pile`() {
        fun nested(depth: Int) = """{"formatVersion":1,"profile":${"[".repeat(depth)}${"]".repeat(depth)}}"""
        readJson(nested(BackupReader.MAX_DEPTH))
        assertRefused(nested(BackupReader.MAX_DEPTH + 1), "imbriquées")
        assertRefused(nested(100_000), "imbriquées")
        assertRefused("""{"formatVersion":1,"dailyActivity":[${"{\"a\":".repeat(100_000)}1${"}".repeat(100_000)}]}""", "imbriquées")
    }

    @Test
    fun `champs et sections inconnus ignorés`() {
        val sink = CollectingSink()
        val header = readJson(
            """{"formatVersion":1,"nouveau":{"a":[1,{"b":null}]},"dailyActivity":[{"date":"2026-11-16","plus":true}]}""",
            sink,
        )
        assertEquals(1, header.formatVersion)
        assertEquals(listOf("2026-11-16"), sink.daily.map { it.date })
    }

    @Test
    fun `lecteur JSON - chaînes échappées et nombres gardés tels quels`() {
        val r = JsonPullReader(StringReader(""" ["a\"b\\c\ndé\/", -0.5e+3, 1.0, true, null] """))
        r.beginArray()
        assertEquals("a\"b\\c\ndé/", r.nextString())
        assertEquals("-0.5e+3", r.nextNumberText())
        assertEquals("1.0", r.nextNumberText())
        assertEquals(true, r.nextBoolean())
        r.nextNull()
        r.endArray()
        r.endDocument()
    }

    // --- Conversions des séries décodées en tableaux ---

    private fun seriesRoundTrip(csv: String): String {
        val out = StringWriter()
        DataExporter.numberSeries(JsonStream(out), csv)
        val items = BackupReader.readTree(JsonPullReader(StringReader(out.toString()))) as List<*>
        return BackupReader.joinSeries(items)
    }

    @Test
    fun `séries de nombres - aller-retour exact`() {
        listOf("12,0,7", "1.0,0.9,1.5E-4,36.53", "", "-3", "NaN,1", "1,,2").forEach {
            assertEquals(it, seriesRoundTrip(it))
        }
    }

    @Test
    fun `nombres recopiés tels quels, le reste en chaîne`() {
        val out = StringWriter()
        DataExporter.numberSeries(JsonStream(out), "1.0,NaN,1.5E-4")
        assertEquals("""[1.0,"NaN",1.5E-4]""", out.toString())
    }

    @Test
    fun `paires et classes d'activité - aller-retour exact`() {
        val pairs = listOf(
            mapOf("offsetSec" to JsonNumber("0"), "state" to "WAKE"),
            mapOf("offsetSec" to JsonNumber("600"), "state" to "REM"),
        )
        assertEquals("0:WAKE,600:REM", BackupReader.joinPairs(pairs, "offsetSec", "state"))
        assertEquals("", BackupReader.joinPairs(emptyList(), "offsetSec", "state"))
        val classes = listOf(
            mapOf("time" to "2026-11-17T08:00", "class" to "LIGHT", "factor" to JsonNumber("1.0")),
            mapOf("time" to "2026-11-17T08:30", "class" to null, "factor" to JsonNumber("0.5")),
        )
        assertEquals("2026-11-17T08:00|LIGHT|1.0;2026-11-17T08:30||0.5", BackupReader.joinActivityClasses(classes))
    }

    // --- Aller-retour complet export → import, avec une base en mémoire ---

    private val proto = byteArrayOf(0, 1, 2, -1, 127, -128)
    private val routePoints = RouteCodec.encode(
        listOf(
            RoutePoint(1_763_366_400_123L, 45.123457, 5.5, 210.0, 140),
            RoutePoint(1_763_366_401_123L, -45.2, -0.000001, null, null),
            RoutePoint(1_763_366_402_123L, 45.200001, 5.6, -3.5, 90),
        ),
    )

    private fun sampleDao() = FakeDao().apply {
        daily += DailyActivityEntity(
            "2026-11-16", 12034, 450, 120, 1650, 9123.5f, 1.0f, 0.875f, 3600, 1800, 900, 300, 28000, 60,
            "BALANCED", "RECOVERED",
        )
        daily += DailyActivityEntity("2026-11-17", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        samples += ActivitySamplesEntity(
            "2026-11-16", "2026-11-16T00:00", 60, "0,12,7", 30, "1.0,0.9,1.5E-4",
            "2026-11-16T08:00|LIGHT|1.0;2026-11-16T08:30||0.5",
        )
        samples += ActivitySamplesEntity("2026-11-16", "2026-11-16T12:00", null, "", null, "", "")
        hr += HrBlockEntity("2026-11-16", "08:00", "TIMED", "60,61,0")
        ppi += PpiBlockEntity("2026-11-16", "03:00", "AUTOMATIC", "1000,980", "10,999")
        sleep += SleepEntity(
            "2026-11-16", "2026-11-15T23:10", "2026-11-16T07:02", 480, "0:WAKE,600:REM", "0:1.0,5400:2.5",
            "SLEPT_WELL", false, 33.6f, -0.25f,
        )
        sleep += SleepEntity("2026-11-17", null, null, null, "", "", null, null, null, null)
        recharge += NightlyRechargeEntity(
            "2026-11-16", 1.25f, 4, 2, 3, 950, 45, 3500, 940, 30, 44, 8, 3400, 120, "tip \"sommeil\"", null, "exo",
        )
        skin += SkinTemperatureEntity("2026-11-16", "WRIST", "SKIN", "0:33.1,60000:33.25")
        sessions += TrainingSessionEntity(
            "/U/0/20261116/E/083000", "2026-11-16", "2026-11-16T08:30", 3600, 1L, "Course", 650, 10234.5f,
            150, 182, 85.5f, 12.0f, "0,60,120,30", 2, proto,
        )
        sessions += TrainingSessionEntity(
            "healthconnect:abc", "2026-11-16", null, null, null, null, null, null, null, null, null, null, "", 0, null,
        )
        exercises += TrainingExerciseEntity("/U/0/20261116/E/083000", 0, proto, byteArrayOf(9, 8))
        exercises += TrainingExerciseEntity("/U/0/orphelin", 1, null, null)
        routes += TrainingRouteEntity("/U/0/20261116/E/083000", "AVAILABLE", "BAND", null, routePoints)
        routes += TrainingRouteEntity("healthconnect:sans-seance", "CONSENT_REQUIRED", "HEALTH_CONNECT", "sans-seance", "")
        measurements += ExternalMeasurementEntity("hc-1", "WEIGHT", "2026-11-16T07:00:00Z", "2026-11-16", 72.35, "com.withings.wiscale2")
    }

    private val settings = AppSettings(
        device = SavedDevice("ABC123", "Polar Loop ABC123"),
        lastSyncedDate = "2026-11-16",
        lastSyncAt = "2026-11-17T08:00",
        profile = UserProfile(female = true, birthDate = LocalDate.of(1990, 5, 17), heightCm = 168.5f, weightKg = 61.2f, maxHr = null),
        lastHealthConnectSyncAt = "2026-11-17T08:05",
        lastHealthConnectSyncedDate = "2026-11-17",
    )

    private fun exportArchive(dao: FakeDao): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { DataExporter(dao).export(out, settings, Instant.parse("2026-11-17T10:00:00Z")) }
        return out.toByteArray()
    }

    @Test
    fun `aller-retour export puis import sans perte`() {
        val source = sampleDao()
        val sink = CollectingSink()
        val header = runBlocking { BackupReader.readArchive(ByteArrayInputStream(exportArchive(source)), sink) }

        assertEquals(DataExporter.FORMAT_VERSION, header.formatVersion)
        assertEquals("2026-11-17T10:00:00Z", header.exportedAt)
        assertEquals(
            BackupSettings(
                settings.profile, settings.device, settings.lastSyncedDate, settings.lastSyncAt,
                settings.lastHealthConnectSyncedDate, settings.lastHealthConnectSyncAt,
            ),
            header.settings,
        )
        assertEquals(source.daily, sink.daily)
        assertEquals(source.samples, sink.samples)
        assertEquals(source.hr, sink.hr)
        assertEquals(source.ppi, sink.ppi)
        assertEquals(source.sleep, sink.sleep)
        assertEquals(source.recharge, sink.recharge)
        assertEquals(source.skin, sink.skin)
        assertEquals(source.measurements, sink.measurements)
        assertEquals(source.routes.sortedBy { it.sessionPath }, sink.routes.sortedBy { it.sessionPath })
        assertEquals(source.sessions.map(::comparable).sortedBy { it.first.path }, sink.sessions.map(::comparable).sortedBy { it.first.path })
        assertEquals(
            source.exercises.map(::comparable).sortedBy { it.first },
            sink.exercises.map(::comparable).sortedBy { it.first },
        )
    }

    @Test
    fun `aller-retour export chiffré puis import`() {
        val key = com.boucle.app.data.backup.BackupKey.fromRaw(ByteArray(16) { 3 }, 600_000, ByteArray(32) { 5 })
        val plain = exportArchive(sampleDao())
        val out = ByteArrayOutputStream()
        val encrypted = com.boucle.app.data.backup.BackupCrypto.encryptingStream(out, key)
        runBlocking { DataExporter(sampleDao()).export(encrypted, settings, Instant.parse("2026-11-17T10:00:00Z")) }
        encrypted.finish()
        val file = out.toByteArray()
        assertTrue(com.boucle.app.data.backup.BackupCrypto.isEncrypted(ByteArrayInputStream(file)))

        val sink = CollectingSink()
        com.boucle.app.data.backup.BackupCrypto.decryptingStream(ByteArrayInputStream(file), key).use { input ->
            runBlocking { BackupReader.readArchive(input, sink) }
        }
        val reference = CollectingSink()
        runBlocking { BackupReader.readArchive(ByteArrayInputStream(plain), reference) }
        assertEquals(reference.daily, sink.daily)
        assertEquals(reference.sleep, sink.sleep)
        assertEquals(reference.measurements, sink.measurements)
        assertEquals(reference.routes, sink.routes)

        // Un fichier chiffré lu comme une archive en clair n'est pas pris pour une sauvegarde.
        try {
            runBlocking { BackupReader.readArchive(ByteArrayInputStream(file), CollectingSink()) }
            fail("fichier chiffré lu en clair")
        } catch (e: BackupFormatException) {
            assertEquals(BackupReader.NOT_A_BACKUP_MESSAGE, e.message)
        }
    }

    private fun importInto(dao: FakeDao, archive: ByteArray): ImportWriter {
        val writer = ImportWriter(dao)
        runBlocking {
            BackupReader.readArchive(ByteArrayInputStream(archive), writer)
            writer.flush()
        }
        return writer
    }

    @Test
    fun `réimporter l'export d'une base importée donne le même fichier`() {
        val first = exportArchive(sampleDao())
        val imported = FakeDao()
        val writer = importInto(imported, first)
        assertArrayEquals(jsonOf(first), jsonOf(exportArchive(imported)))
        // Base vide : tout ce qui a été relu a été ajouté.
        assertEquals(writer.counts(), writer.added())
    }

    @Test
    fun `réimporter la même sauvegarde n'ajoute rien`() {
        val archive = exportArchive(sampleDao())
        val dao = FakeDao()
        importInto(dao, archive)
        val second = importInto(dao, archive)
        assertEquals(ImportCounts(), second.added())
        assertEquals(second.counts(), ImportSummary(second.counts(), second.added(), null, RestoredSettings()).kept)
        assertArrayEquals(jsonOf(archive), jsonOf(exportArchive(dao)))
    }

    @Test
    fun `une ligne déjà présente n'est jamais remplacée par la sauvegarde`() {
        // Sauvegarde de la mi-journée : journée partielle, tracé pas encore autorisé, séance sans exercice.
        val backup = FakeDao().apply {
            daily += DailyActivityEntity("2026-11-17", 3000, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
            daily += DailyActivityEntity("2026-11-15", 8000, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
            sleep += SleepEntity("2026-11-17", null, null, null, "", "", null, null, null, null)
            samples += ActivitySamplesEntity("2026-11-17", "2026-11-17T00:00", 60, "1,2", null, "", "")
            skin += SkinTemperatureEntity("2026-11-17", null, null, "0:33.0")
            sessions += TrainingSessionEntity("s1", "2026-11-17", null, null, null, "Ancienne", null, null, null, null, null, null, "", 1, null)
            exercises += TrainingExerciseEntity("s1", 0, null, null)
            exercises += TrainingExerciseEntity("s1", 5, null, null)
            routes += TrainingRouteEntity("s1", "CONSENT_REQUIRED", "HEALTH_CONNECT", "hc", "")
            sessions += TrainingSessionEntity("s2", "2026-11-15", null, null, null, "Nouvelle", null, null, null, null, null, null, "", 1, null)
            exercises += TrainingExerciseEntity("s2", 0, null, null)
            routes += TrainingRouteEntity("s2", "AVAILABLE", "BAND", null, routePoints)
            // Exercice orphelin d'une séance présente sur le téléphone : laissé tel quel.
            exercises += TrainingExerciseEntity("s3", 0, null, null)
        }
        // Le téléphone a synchronisé la journée complète depuis.
        val fullDay = DailyActivityEntity(
            "2026-11-17", 12034, 450, 120, 1650, 9123.5f, 1.0f, 0.875f, 3600, 1800, 900, 300, 28000, 60, "BALANCED", "RECOVERED",
        )
        val fullNight = SleepEntity("2026-11-17", "2026-11-16T23:10", "2026-11-17T07:02", 480, "0:WAKE", "", null, false, null, null)
        val fullSamples = ActivitySamplesEntity("2026-11-17", "2026-11-17T00:00", 60, "1,2,3,4", null, "", "")
        val fullSkin = SkinTemperatureEntity("2026-11-17", "WRIST", "SKIN", "0:33.0,60000:33.2")
        val session = TrainingSessionEntity("s1", "2026-11-17", null, 3600, null, "Course", null, null, null, null, null, null, "", 2, null)
        val route = TrainingRouteEntity("s1", "AVAILABLE", "HEALTH_CONNECT", "hc", routePoints)
        val exercise = TrainingExerciseEntity("s1", 0, proto, proto)
        val s3 = TrainingSessionEntity("s3", "2026-11-16", null, null, null, null, null, null, null, null, null, null, "", 0, null)
        val phone = FakeDao().apply {
            daily += fullDay
            sleep += fullNight
            samples += fullSamples
            skin += fullSkin
            sessions += session
            sessions += s3
            exercises += exercise
            routes += route
        }

        val writer = importInto(phone, exportArchive(backup))

        // Lignes existantes conservées telles quelles.
        assertEquals(fullDay, phone.daily.single { it.date == "2026-11-17" })
        assertEquals(listOf(fullNight), phone.sleep)
        assertEquals(listOf(fullSamples), phone.samples)
        assertEquals(listOf(fullSkin), phone.skin)
        assertEquals("Course", phone.sessions.single { it.path == "s1" }.name)
        // Le tracé AVAILABLE n'est pas écrasé par le CONSENT_REQUIRED vide de la sauvegarde.
        assertEquals(route, phone.routes.single { it.sessionPath == "s1" })
        // Séance déjà présente : ses exercices ne sont pas touchés, même l'index absent du téléphone.
        assertEquals(listOf(exercise), phone.exercises.filter { it.sessionPath == "s1" })
        assertTrue(phone.exercises.none { it.sessionPath == "s3" })
        // Ce qui manquait est ajouté, avec tous ses enfants.
        assertEquals(8000, phone.daily.single { it.date == "2026-11-15" }.steps)
        assertEquals("Nouvelle", phone.sessions.single { it.path == "s2" }.name)
        assertEquals(listOf(0), phone.exercises.filter { it.sessionPath == "s2" }.map { it.exerciseIndex })
        assertEquals("AVAILABLE", phone.routes.single { it.sessionPath == "s2" }.status)

        assertEquals(
            ImportCounts(dailyActivity = 1, trainingSessions = 1, trainingExercises = 1, trainingRoutes = 1),
            writer.added(),
        )
        val summary = ImportSummary(writer.counts(), writer.added(), null, RestoredSettings())
        assertEquals(
            ImportCounts(
                dailyActivity = 1, activitySamples = 1, sleep = 1, skinTemperature = 1, trainingSessions = 1,
                trainingExercises = 3, trainingRoutes = 1,
            ),
            summary.kept,
        )
        assertEquals(
            "Sauvegarde importée. Ajoutés : 1 jour d'activité, 1 séance, 1 parcours GPS. " +
                "Déjà présents (conservés) : 1 jour d'activité, 1 nuit, 1 séance, 1 parcours GPS, " +
                "2 séries de mesures en continu.",
            summary.message(),
        )
    }

    @Test
    fun `archive tronquée ou autre fichier refusés`() {
        val archive = exportArchive(sampleDao())
        listOf(archive.size / 2, archive.size / 4, 40).forEach { cut ->
            try {
                runBlocking { BackupReader.readArchive(ByteArrayInputStream(archive.copyOf(cut)), CollectingSink()) }
                fail("archive tronquée à $cut acceptée")
            } catch (e: BackupFormatException) {
                // attendu
            }
        }
        try {
            runBlocking { BackupReader.readArchive(ByteArrayInputStream("pas un zip".toByteArray()), CollectingSink()) }
            fail("fichier quelconque accepté")
        } catch (e: BackupFormatException) {
            assertEquals(BackupReader.NOT_A_BACKUP_MESSAGE, e.message)
        }
        val gpxOnly = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("parcours/a.gpx")); it.write(1); it.closeEntry() }
        }.toByteArray()
        try {
            runBlocking { BackupReader.readArchive(ByteArrayInputStream(gpxOnly), CollectingSink()) }
            fail("ZIP sans JSON accepté")
        } catch (e: BackupFormatException) {
            assertEquals(BackupReader.NOT_A_BACKUP_MESSAGE, e.message)
        }
    }

    @Test
    fun `résumé lisible`() {
        val read = ImportCounts(dailyActivity = 12, sleep = 1, nightlyRecharge = 1, trainingSessions = 3, heartRate = 40)
        val summary = ImportSummary(read, read, null, RestoredSettings(profile = true))
        assertEquals(
            "Sauvegarde importée. Ajoutés : 12 jours d'activité, 1 nuit, 3 séances, 40 séries de mesures en continu. " +
                "Repris aussi : profil.",
            summary.message(),
        )
        assertEquals(
            "Sauvegarde importée. Rien d'ajouté. Déjà présents (conservés) : 12 jours d'activité, 1 nuit, 3 séances, " +
                "40 séries de mesures en continu.",
            ImportSummary(read, ImportCounts(), null, RestoredSettings()).message(),
        )
        assertEquals(
            "Sauvegarde importée : elle ne contenait aucune mesure.",
            ImportSummary(ImportCounts(), ImportCounts(), null, RestoredSettings()).message(),
        )
    }

    private fun comparable(s: TrainingSessionEntity) = s.copy(summaryProto = null) to s.summaryProto?.toList()
    private fun comparable(e: TrainingExerciseEntity) =
        "${e.sessionPath}#${e.exerciseIndex}" to listOf(e.baseProto?.toList(), e.samplesProto?.toList())

    private fun jsonOf(archive: ByteArray): ByteArray {
        val zip = java.util.zip.ZipInputStream(ByteArrayInputStream(archive))
        while (true) {
            val entry = zip.nextEntry ?: error("JSON absent")
            if (entry.name == DataExporter.JSON_FILE) return zip.readBytes()
        }
    }
}

/** Garde chaque ligne relue. */
private open class CollectingSink : BackupSink {
    val daily = mutableListOf<DailyActivityEntity>()
    val samples = mutableListOf<ActivitySamplesEntity>()
    val hr = mutableListOf<HrBlockEntity>()
    val ppi = mutableListOf<PpiBlockEntity>()
    val sleep = mutableListOf<SleepEntity>()
    val recharge = mutableListOf<NightlyRechargeEntity>()
    val skin = mutableListOf<SkinTemperatureEntity>()
    val sessions = mutableListOf<TrainingSessionEntity>()
    val exercises = mutableListOf<TrainingExerciseEntity>()
    val routes = mutableListOf<TrainingRouteEntity>()
    val measurements = mutableListOf<ExternalMeasurementEntity>()

    override suspend fun dailyActivity(row: DailyActivityEntity) { daily += row }
    override suspend fun activitySamples(row: ActivitySamplesEntity) { samples += row }
    override suspend fun hrBlock(row: HrBlockEntity) { hr += row }
    override suspend fun ppiBlock(row: PpiBlockEntity) { ppi += row }
    override suspend fun sleep(row: SleepEntity) { sleep += row }
    override suspend fun nightlyRecharge(row: NightlyRechargeEntity) { recharge += row }
    override suspend fun skinTemperature(row: SkinTemperatureEntity) { skin += row }
    override suspend fun trainingSession(row: TrainingSessionEntity) { sessions += row }
    override suspend fun trainingExercise(row: TrainingExerciseEntity) { exercises += row }
    override suspend fun trainingRoute(row: TrainingRouteEntity) { routes += row }
    override suspend fun externalMeasurement(row: ExternalMeasurementEntity) { measurements += row }
}

/** Base en mémoire : seules les requêtes de l'export sont implémentées. */
private class FakeDao : CollectingSink(), HealthDao {
    /** Comme `INSERT OR IGNORE` : ajoute les lignes dont la clé est absente, -1 pour les autres. */
    private fun <T> insertIgnore(table: MutableList<T>, items: List<T>, key: (T) -> Any): List<Long> = items.map { item ->
        if (table.any { key(it) == key(item) }) {
            -1L
        } else {
            table += item
            table.size.toLong()
        }
    }

    override suspend fun insertDailyActivityIfAbsent(items: List<DailyActivityEntity>) = insertIgnore(daily, items) { it.date }
    override suspend fun insertActivitySamplesIfAbsent(items: List<ActivitySamplesEntity>) =
        insertIgnore(samples, items) { it.date to it.startTime }
    override suspend fun insertHrBlocksIfAbsent(items: List<HrBlockEntity>) = insertIgnore(hr, items) { it.date to it.startTime }
    override suspend fun insertPpiBlocksIfAbsent(items: List<PpiBlockEntity>) = insertIgnore(ppi, items) { it.date to it.startTime }
    override suspend fun insertSleepIfAbsent(items: List<SleepEntity>) = insertIgnore(sleep, items) { it.date }
    override suspend fun insertNightlyRechargeIfAbsent(items: List<NightlyRechargeEntity>) = insertIgnore(recharge, items) { it.date }
    override suspend fun insertSkinTemperatureIfAbsent(items: List<SkinTemperatureEntity>) = insertIgnore(skin, items) { it.date }
    override suspend fun insertTrainingSessionIfAbsent(item: TrainingSessionEntity) = insertIgnore(sessions, listOf(item)) { it.path }.single()
    override suspend fun insertTrainingExercisesIfAbsent(items: List<TrainingExerciseEntity>) =
        insertIgnore(exercises, items) { it.sessionPath to it.exerciseIndex }
    override suspend fun insertTrainingRoutesIfAbsent(items: List<TrainingRouteEntity>) = insertIgnore(routes, items) { it.sessionPath }
    override suspend fun insertExternalMeasurementsIfAbsent(items: List<ExternalMeasurementEntity>) =
        insertIgnore(measurements, items) { it.id }
    override suspend fun hasTrainingSession(path: String) = sessions.any { it.path == path }

    override suspend fun upsertDailyActivity(items: List<DailyActivityEntity>) { daily += items }
    override suspend fun upsertActivitySamples(items: List<ActivitySamplesEntity>) { samples += items }
    override suspend fun upsertHrBlocks(items: List<HrBlockEntity>) { hr += items }
    override suspend fun upsertPpiBlocks(items: List<PpiBlockEntity>) { ppi += items }
    override suspend fun upsertSleep(items: List<SleepEntity>) { sleep += items }
    override suspend fun upsertNightlyRecharge(items: List<NightlyRechargeEntity>) { recharge += items }
    override suspend fun upsertSkinTemperature(items: List<SkinTemperatureEntity>) { skin += items }
    override suspend fun upsertTrainingSessions(items: List<TrainingSessionEntity>) { sessions += items }
    override suspend fun upsertTrainingExercises(items: List<TrainingExerciseEntity>) { exercises += items }
    override suspend fun upsertExternalMeasurements(items: List<ExternalMeasurementEntity>) { measurements += items }
    override suspend fun upsertTrainingRoutes(items: List<TrainingRouteEntity>) { routes += items }

    override suspend fun hasAnyData() = listOf(daily, samples, hr, ppi, sleep, recharge, skin, sessions, exercises, measurements, routes)
        .any { it.isNotEmpty() }
    override suspend fun allDailyActivity() = daily.sortedBy { it.date }
    override suspend fun allSleep() = sleep.sortedBy { it.date }
    override suspend fun allNightlyRecharge() = recharge.sortedBy { it.date }
    override suspend fun allSkinTemperature() = skin.sortedBy { it.date }
    override suspend fun allExternalMeasurements() = measurements.sortedBy { it.time }
    override suspend fun allTrainingSessions() = sessions.sortedWith(compareBy({ it.date }, { it.startTime }))
    override suspend fun allTrainingRoutes() = routes.toList()
    override suspend fun trainingExercisesOf(path: String) = exercises.filter { it.sessionPath == path }.sortedBy { it.exerciseIndex }
    override suspend fun unlinkedTrainingExercises(): List<TrainingExerciseEntity> {
        val paths = sessions.map { it.path }.toSet()
        return exercises.filter { it.sessionPath !in paths }.sortedWith(compareBy({ it.sessionPath }, { it.exerciseIndex }))
    }
    override suspend fun activitySampleDates() = samples.map { it.date }.distinct().sorted()
    override suspend fun activitySamplesOn(date: String) = samples.filter { it.date == date }.sortedBy { it.startTime }
    override suspend fun hrBlockDates() = hr.map { it.date }.distinct().sorted()
    override suspend fun hrBlocksOn(date: String) = hr.filter { it.date == date }.sortedBy { it.startTime }
    override suspend fun ppiBlockDates() = ppi.map { it.date }.distinct().sorted()
    override suspend fun ppiBlocksOn(date: String) = ppi.filter { it.date == date }.sortedBy { it.startTime }

    private fun unused(): Nothing = throw UnsupportedOperationException()
    override fun trainingRoute(path: String): Flow<TrainingRouteEntity?> = unused()
    override suspend fun trainingRouteNow(path: String): TrainingRouteEntity? = unused()
    override fun trainingSession(path: String): Flow<TrainingSessionEntity?> = unused()
    override fun externalMeasurementsRange(from: String, to: String): Flow<List<ExternalMeasurementEntity>> = unused()
    override fun externalMeasurementsOfType(type: String, from: String, to: String): Flow<List<ExternalMeasurementEntity>> = unused()
    override fun dailyActivity(date: String): Flow<DailyActivityEntity?> = unused()
    override fun dailyActivityRange(from: String, to: String): Flow<List<DailyActivityEntity>> = unused()
    override fun dailySteps(): Flow<List<DateSteps>> = unused()
    override fun activitySamples(date: String): Flow<List<ActivitySamplesEntity>> = unused()
    override fun hrBlocks(date: String): Flow<List<HrBlockEntity>> = unused()
    override fun ppiBlocks(date: String): Flow<List<PpiBlockEntity>> = unused()
    override fun sleep(date: String): Flow<SleepEntity?> = unused()
    override fun activitySamplesRange(from: String, to: String): Flow<List<ActivitySamplesEntity>> = unused()
    override fun hrBlocksRange(from: String, to: String): Flow<List<HrBlockEntity>> = unused()
    override fun ppiBlocksRange(from: String, to: String): Flow<List<PpiBlockEntity>> = unused()
    override fun sleepRange(from: String, to: String): Flow<List<SleepEntity>> = unused()
    override fun nightlyRechargeRange(from: String, to: String): Flow<List<NightlyRechargeEntity>> = unused()
    override fun nightlyRecharge(date: String): Flow<NightlyRechargeEntity?> = unused()
    override fun skinTemperature(date: String): Flow<SkinTemperatureEntity?> = unused()
    override fun trainingSessions(date: String): Flow<List<TrainingSessionEntity>> = unused()
    override fun trainingSessionsRange(from: String, to: String): Flow<List<TrainingSessionEntity>> = unused()
}
