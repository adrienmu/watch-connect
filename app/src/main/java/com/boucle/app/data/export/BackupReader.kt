package com.boucle.app.data.export

import com.boucle.app.data.SavedDevice
import com.boucle.app.data.UserProfile
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
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Réglages contenus dans une sauvegarde : profil, bracelet associé et dates des synchronisations. */
data class BackupSettings(
    val profile: UserProfile? = null,
    val device: SavedDevice? = null,
    val lastSyncedDate: String? = null,
    val lastSyncAt: String? = null,
    val lastHealthConnectSyncedDate: String? = null,
    val lastHealthConnectSyncAt: String? = null,
)

/** En-tête et réglages d'une sauvegarde relue jusqu'au bout. */
data class BackupHeader(val formatVersion: Int, val exportedAt: String?, val settings: BackupSettings)

/** Reçoit chaque ligne relue, dans l'ordre du fichier (une séance avant ses exercices et son tracé). */
interface BackupSink {
    suspend fun dailyActivity(row: DailyActivityEntity)
    suspend fun activitySamples(row: ActivitySamplesEntity)
    suspend fun hrBlock(row: HrBlockEntity)
    suspend fun ppiBlock(row: PpiBlockEntity)
    suspend fun sleep(row: SleepEntity)
    suspend fun nightlyRecharge(row: NightlyRechargeEntity)
    suspend fun skinTemperature(row: SkinTemperatureEntity)
    suspend fun trainingSession(row: TrainingSessionEntity)
    suspend fun trainingExercise(row: TrainingExerciseEntity)
    suspend fun trainingRoute(row: TrainingRouteEntity)
    suspend fun externalMeasurement(row: ExternalMeasurementEntity)
}

/**
 * Relecture d'une archive écrite par [DataExporter], en flux : une ligne à la fois est gardée en mémoire. Chaque
 * ligne est validée et convertie en entité Room avant d'être passée au [BackupSink] ; la moindre erreur lève
 * [BackupFormatException] avec un message en français, prêt à afficher.
 */
object BackupReader {

    const val NOT_A_BACKUP_MESSAGE =
        "Ce fichier n'est pas une sauvegarde Boucle : choisis l'archive .zip créée par « Exporter mes données »."

    /** Lit l'archive ZIP [input] (sans la fermer) : seul le fichier JSON est relu, les GPX sont ignorés. */
    suspend fun readArchive(input: InputStream, sink: BackupSink): BackupHeader {
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: throw BackupFormatException(NOT_A_BACKUP_MESSAGE)
                if (!entry.isDirectory && entry.name == DataExporter.JSON_FILE) {
                    return readJson(BufferedReader(InputStreamReader(zip, Charsets.UTF_8)), sink)
                }
            }
        } catch (e: ZipException) {
            throw BackupFormatException(NOT_A_BACKUP_MESSAGE)
        } catch (e: EOFException) {
            throw BackupFormatException(JsonPullReader.TRUNCATED_MESSAGE)
        }
    }

    /** Lit le document JSON [reader] jusqu'au bout et vérifie qu'il ne contient rien après. */
    suspend fun readJson(reader: Reader, sink: BackupSink): BackupHeader {
        val r = JsonPullReader(reader)
        if (r.peek() != JsonPullReader.Token.BEGIN_OBJECT) throw BackupFormatException(NOT_A_BACKUP_MESSAGE)
        r.beginObject()
        var version: Int? = null
        var exportedAt: String? = null
        var settings = BackupSettings()
        fun requireVersion() {
            if (version == null) throw BackupFormatException("$NOT_A_BACKUP_MESSAGE (version du format absente)")
        }
        while (r.hasNext()) {
            when (val name = r.nextName()) {
                "formatVersion" -> version = checkVersion(readTree(r))
                "format" -> {
                    val format = readTree(r)
                    if (format != DataExporter.FORMAT_NAME) throw BackupFormatException(NOT_A_BACKUP_MESSAGE)
                }
                "exportedAt" -> exportedAt = readTree(r) as? String
                "profile" -> {
                    requireVersion()
                    settings = settings.copy(profile = (readTree(r) as? Map<*, *>)?.let { profile(Fields(it, name, 0)) })
                }
                "device" -> {
                    requireVersion()
                    settings = settings.copy(device = (readTree(r) as? Map<*, *>)?.let { device(Fields(it, name, 0)) })
                }
                "sync" -> {
                    requireVersion()
                    (readTree(r) as? Map<*, *>)?.let { Fields(it, name, 0) }?.let {
                        settings = settings.copy(
                            lastSyncedDate = it.dateOrNull("lastSyncedDate")?.toString(),
                            lastSyncAt = it.stringOrNull("lastSyncAt"),
                            lastHealthConnectSyncedDate = it.dateOrNull("lastHealthConnectSyncedDate")?.toString(),
                            lastHealthConnectSyncAt = it.stringOrNull("lastHealthConnectSyncAt"),
                        )
                    }
                }
                in SECTIONS -> {
                    requireVersion()
                    readSection(r, name, sink)
                }
                // Champ ajouté par une version compatible : ignoré.
                else -> r.skipValue()
            }
        }
        r.endObject()
        r.endDocument()
        requireVersion()
        return BackupHeader(version!!, exportedAt, settings)
    }

    private val SECTIONS = setOf(
        "dailyActivity", "activitySamples", "heartRate", "ppi", "sleep", "nightlyRecharge", "skinTemperature",
        "trainingSessions", "externalMeasurements", "unlinkedRoutes", "unlinkedExercises",
    )

    private fun checkVersion(value: Any?): Int {
        val text = (value as? JsonNumber)?.text
        val version = text?.toIntOrNull()
            ?: throw BackupFormatException("Format de sauvegarde inconnu (${text ?: value}) : ce fichier ne peut pas être importé.")
        if (version > DataExporter.FORMAT_VERSION) {
            throw BackupFormatException(
                "Cette sauvegarde vient d'une version plus récente de Boucle (format $version). " +
                    "Mets l'application à jour pour l'importer.",
            )
        }
        if (version < 1) throw BackupFormatException("Format de sauvegarde inconnu ($version) : ce fichier ne peut pas être importé.")
        return version
    }

    private suspend fun readSection(r: JsonPullReader, section: String, sink: BackupSink) {
        if (r.peek() == JsonPullReader.Token.NULL) {
            r.nextNull()
            return
        }
        if (r.peek() != JsonPullReader.Token.BEGIN_ARRAY) throw corrupt("$section : liste attendue")
        r.beginArray()
        var index = 0
        while (r.hasNext()) {
            val map = readTree(r) as? Map<*, *> ?: throw corrupt("$section, élément ${index + 1} : objet attendu")
            val f = Fields(map, section, index)
            when (section) {
                "dailyActivity" -> sink.dailyActivity(dailyActivity(f))
                "activitySamples" -> sink.activitySamples(activitySamples(f))
                "heartRate" -> sink.hrBlock(HrBlockEntity(f.date("date"), f.string("startTime"), f.string("trigger"), f.series("bpm")))
                "ppi" -> sink.ppiBlock(
                    PpiBlockEntity(f.date("date"), f.string("startTime"), f.string("trigger"), f.series("ppiMs"), f.series("errorEstimateMs")),
                )
                "sleep" -> sink.sleep(sleep(f))
                "nightlyRecharge" -> sink.nightlyRecharge(nightlyRecharge(f))
                "skinTemperature" -> sink.skinTemperature(
                    SkinTemperatureEntity(
                        date = f.date("date"),
                        sensorLocation = f.stringOrNull("sensorLocation"),
                        measurementType = f.stringOrNull("measurementType"),
                        samples = joinPairs(f.objects("samples"), "offsetMs", "celsius"),
                    ),
                )
                "trainingSessions" -> {
                    val session = trainingSession(f)
                    sink.trainingSession(session)
                    f.objects("exercises").forEachIndexed { i, e ->
                        sink.trainingExercise(exercise(Fields(e, "$section/exercises", i), session.path))
                    }
                    f.objectOrNull("route")?.let { sink.trainingRoute(route(Fields(it, "$section/route", index), session.path)) }
                }
                "externalMeasurements" -> sink.externalMeasurement(
                    ExternalMeasurementEntity(
                        id = f.string("id"),
                        type = f.string("type"),
                        time = f.string("time"),
                        date = f.date("date"),
                        value = f.double("value"),
                        source = f.string("sourceApp"),
                    ),
                )
                "unlinkedRoutes" -> sink.trainingRoute(route(f, f.string("sessionId")))
                "unlinkedExercises" -> sink.trainingExercise(exercise(f, f.string("sessionId")))
            }
            index++
        }
        r.endArray()
    }

    private fun profile(f: Fields) = UserProfile(
        female = when (val sex = f.string("sex")) {
            "FEMALE" -> true
            "MALE" -> false
            else -> throw f.invalid("sex", sex)
        },
        birthDate = LocalDate.parse(f.date("birthDate")),
        heightCm = f.float("heightCm"),
        weightKg = f.float("weightKg"),
        maxHr = f.intOrNull("maxHr"),
    )

    private fun device(f: Fields) = SavedDevice(f.string("id"), f.stringOrNull("name") ?: f.string("id"))

    private fun dailyActivity(f: Fields) = DailyActivityEntity(
        date = f.date("date"),
        steps = f.intOrNull("steps"),
        activityCalories = f.intOrNull("activityCalories"),
        trainingCalories = f.intOrNull("trainingCalories"),
        bmrCalories = f.intOrNull("bmrCalories"),
        distanceMeters = f.floatOrNull("distanceMeters"),
        activityGoal = f.floatOrNull("activityGoal"),
        achievedActivity = f.floatOrNull("achievedActivity"),
        sedentarySec = f.intOrNull("sedentarySec"),
        lightSec = f.intOrNull("lightSec"),
        moderateSec = f.intOrNull("moderateSec"),
        vigorousSec = f.intOrNull("vigorousSec"),
        sleepSec = f.intOrNull("sleepSec"),
        nonWearSec = f.intOrNull("nonWearSec"),
        dailyBalance = f.stringOrNull("dailyBalance"),
        readiness = f.stringOrNull("readiness"),
    )

    private fun activitySamples(f: Fields) = ActivitySamplesEntity(
        date = f.date("date"),
        startTime = f.string("startTime"),
        stepIntervalSec = f.intOrNull("stepIntervalSec"),
        stepSamples = f.series("steps"),
        metIntervalSec = f.intOrNull("metIntervalSec"),
        metSamples = f.series("met"),
        activityInfo = joinActivityClasses(f.objects("activityClasses")),
    )

    private fun sleep(f: Fields) = SleepEntity(
        date = f.date("date"),
        startTime = f.stringOrNull("startTime"),
        endTime = f.stringOrNull("endTime"),
        goalMinutes = f.intOrNull("goalMinutes"),
        phases = joinPairs(f.objects("phases"), "offsetSec", "state"),
        cycles = joinPairs(f.objects("cycles"), "offsetSec", "depth"),
        userRating = f.stringOrNull("userRating"),
        batteryRanOut = f.booleanOrNull("batteryRanOut"),
        skinTemperatureC = f.floatOrNull("skinTemperatureC"),
        skinTemperatureDeviationC = f.floatOrNull("skinTemperatureDeviationC"),
    )

    private fun nightlyRecharge(f: Fields) = NightlyRechargeEntity(
        date = f.date("date"),
        ansStatus = f.floatOrNull("ansStatus"),
        recoveryIndicator = f.intOrNull("recoveryIndicator"),
        recoveryIndicatorSubLevel = f.intOrNull("recoveryIndicatorSubLevel"),
        ansRate = f.intOrNull("ansRate"),
        meanRriMs = f.intOrNull("meanRriMs"),
        meanRmssdMs = f.intOrNull("meanRmssdMs"),
        meanRespirationIntervalMs = f.intOrNull("meanRespirationIntervalMs"),
        baselineRriMs = f.intOrNull("baselineRriMs"),
        baselineRriSd = f.intOrNull("baselineRriSd"),
        baselineRmssdMs = f.intOrNull("baselineRmssdMs"),
        baselineRmssdSd = f.intOrNull("baselineRmssdSd"),
        baselineRespirationIntervalMs = f.intOrNull("baselineRespirationIntervalMs"),
        baselineRespirationIntervalSd = f.intOrNull("baselineRespirationIntervalSd"),
        sleepTip = f.stringOrNull("sleepTip"),
        vitalityTip = f.stringOrNull("vitalityTip"),
        exerciseTip = f.stringOrNull("exerciseTip"),
    )

    private fun trainingSession(f: Fields) = TrainingSessionEntity(
        path = f.string("id"),
        date = f.date("date"),
        startTime = f.stringOrNull("startTime"),
        durationSec = f.intOrNull("durationSec"),
        sportId = f.longOrNull("sportId"),
        name = f.stringOrNull("name"),
        calories = f.intOrNull("calories"),
        distanceMeters = f.floatOrNull("distanceMeters"),
        averageHr = f.intOrNull("averageHr"),
        maxHr = f.intOrNull("maxHr"),
        cardioLoad = f.floatOrNull("cardioLoad"),
        muscleLoad = f.floatOrNull("muscleLoad"),
        hrZoneSec = f.series("hrZoneSec"),
        // Absent des tout premiers exports : le nombre d'exercices exportés le remplace.
        exerciseCount = f.intOrNull("exerciseCount") ?: f.objects("exercises").size,
        summaryProto = f.base64OrNull("summaryProtoBase64"),
    )

    private fun exercise(f: Fields, sessionPath: String) = TrainingExerciseEntity(
        sessionPath = sessionPath,
        exerciseIndex = f.int("index"),
        baseProto = f.base64OrNull("summaryProtoBase64"),
        samplesProto = f.base64OrNull("samplesProtoBase64"),
    )

    private fun route(f: Fields, sessionPath: String) = TrainingRouteEntity(
        sessionPath = sessionPath,
        status = f.string("status"),
        source = f.string("source"),
        healthConnectId = f.stringOrNull("healthConnectId"),
        points = encodeRoutePoints(f.objects("points").mapIndexed { i, p -> routePoint(Fields(p, "${f.where}/points", i)) }),
    )

    private fun routePoint(f: Fields): RoutePoint = RoutePoint(
        timeMs = f.instantMs("time"),
        latitude = f.double("latitude"),
        longitude = f.double("longitude"),
        altitude = f.doubleOrNull("altitudeMeters"),
        heartRate = f.intOrNull("heartRate"),
    )

    // Conversions inverses de celles de DataExporter : tableaux décodés → colonnes texte de la base.

    /** Inverse de [DataExporter.numberSeries] : [12, 0, 7.5] → « 12,0,7.5 », chaque nombre recopié tel quel. */
    internal fun joinSeries(items: List<Any?>, where: String = "série"): String =
        items.joinToString(",") { scalarText(it) ?: if (it == null) "" else throw corrupt("$where : nombre attendu") }

    /** Inverse de [DataExporter.pairs] : [{a:0, b:"WAKE"}] → « 0:WAKE ». */
    internal fun joinPairs(items: List<Map<*, *>>, first: String, second: String, where: String = "paires"): String =
        items.joinToString(",") { item ->
            val a = scalarText(item[first]) ?: throw corrupt("$where : champ « $first » manquant")
            val b = scalarText(item[second]) ?: throw corrupt("$where : champ « $second » manquant")
            "$a:$b"
        }

    /** Inverse des classes d'activité exportées : [{time, class, factor}] → « time|classe|facteur;… ». */
    internal fun joinActivityClasses(items: List<Map<*, *>>): String = items.joinToString(";") { item ->
        val time = scalarText(item["time"]) ?: throw corrupt("activityClasses : champ « time » manquant")
        "$time|${scalarText(item["class"]).orEmpty()}|${scalarText(item["factor"]).orEmpty()}"
    }

    /** Points d'un tracé, encodés comme dans la base ([RouteCodec]). */
    internal fun encodeRoutePoints(points: List<RoutePoint>): String = RouteCodec.encode(points)

    private fun scalarText(value: Any?): String? = when (value) {
        is JsonNumber -> value.text
        is String -> value
        is Boolean -> value.toString()
        else -> null
    }

    private fun corrupt(detail: String) = BackupFormatException("${JsonPullReader.CORRUPT_MESSAGE} ($detail).")

    /**
     * Valeur suivante sous forme d'arbre (objets, listes, chaînes, [JsonNumber], booléens, null) : réservé aux
     * petits morceaux du document (une ligne, un réglage), jamais à une section entière.
     */
    internal fun readTree(r: JsonPullReader): Any? = when (r.peek()) {
        JsonPullReader.Token.BEGIN_OBJECT -> {
            r.beginObject()
            val map = LinkedHashMap<String, Any?>()
            while (r.hasNext()) {
                val key = r.nextName()
                map[key] = readTree(r)
            }
            r.endObject()
            map
        }
        JsonPullReader.Token.BEGIN_ARRAY -> {
            r.beginArray()
            val list = ArrayList<Any?>()
            while (r.hasNext()) list.add(readTree(r))
            r.endArray()
            list
        }
        JsonPullReader.Token.STRING -> r.nextString()
        JsonPullReader.Token.NUMBER -> JsonNumber(r.nextNumberText())
        JsonPullReader.Token.BOOLEAN -> r.nextBoolean()
        JsonPullReader.Token.NULL -> r.nextNull().let { null }
        else -> throw corrupt("valeur attendue")
    }

    /** Champs d'un objet du fichier, avec des messages d'erreur qui disent où se trouve le problème. */
    internal class Fields(private val map: Map<*, *>, section: String, index: Int) {
        val where = "$section, élément ${index + 1}"

        fun invalid(name: String, value: Any? = null) = corrupt(
            "$where : champ « $name » " + if (value == null) "manquant ou invalide" else "invalide (${(value as? JsonNumber)?.text ?: value})",
        )

        private fun number(name: String): String? = when (val v = map[name]) {
            null -> null
            is JsonNumber -> v.text
            else -> throw invalid(name, v)
        }

        fun stringOrNull(name: String): String? = when (val v = map[name]) {
            null -> null
            is String -> v
            else -> throw invalid(name, v)
        }

        fun string(name: String): String = stringOrNull(name) ?: throw invalid(name)

        fun date(name: String): String {
            val text = string(name)
            runCatching { LocalDate.parse(text) }.getOrNull() ?: throw invalid(name, text)
            return text
        }

        fun dateOrNull(name: String): LocalDate? = stringOrNull(name)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull() ?: throw invalid(name, it)
        }

        fun intOrNull(name: String): Int? = number(name)?.let { it.toIntOrNull() ?: throw invalid(name, it) }
        fun int(name: String): Int = intOrNull(name) ?: throw invalid(name)
        fun longOrNull(name: String): Long? = number(name)?.let { it.toLongOrNull() ?: throw invalid(name, it) }

        fun floatOrNull(name: String): Float? = number(name)?.let {
            it.toFloatOrNull()?.takeIf { f -> f.isFinite() } ?: throw invalid(name, it)
        }
        fun float(name: String): Float = floatOrNull(name) ?: throw invalid(name)

        fun doubleOrNull(name: String): Double? = number(name)?.let {
            it.toDoubleOrNull()?.takeIf { d -> d.isFinite() } ?: throw invalid(name, it)
        }
        fun double(name: String): Double = doubleOrNull(name) ?: throw invalid(name)

        fun booleanOrNull(name: String): Boolean? = when (val v = map[name]) {
            null -> null
            is Boolean -> v
            else -> throw invalid(name, v)
        }

        fun instantMs(name: String): Long {
            val text = string(name)
            return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() ?: throw invalid(name, text)
        }

        fun base64OrNull(name: String): ByteArray? = stringOrNull(name)?.let {
            runCatching { Base64.getDecoder().decode(it) }.getOrNull() ?: throw invalid(name)
        }

        /** Série de nombres → colonne texte ; absente ou null = série vide. */
        fun series(name: String): String = when (val v = map[name]) {
            null -> ""
            is List<*> -> joinSeries(v, "$where, « $name »")
            else -> throw invalid(name, v)
        }

        fun objects(name: String): List<Map<*, *>> = when (val v = map[name]) {
            null -> emptyList()
            is List<*> -> v.map { it as? Map<*, *> ?: throw invalid(name) }
            else -> throw invalid(name, v)
        }

        fun objectOrNull(name: String): Map<*, *>? = when (val v = map[name]) {
            null -> null
            is Map<*, *> -> v
            else -> throw invalid(name, v)
        }
    }
}

/** Nombre JSON gardé sous sa forme écrite, pour le recopier sans arrondi ni reformatage. */
@JvmInline
internal value class JsonNumber(val text: String)
