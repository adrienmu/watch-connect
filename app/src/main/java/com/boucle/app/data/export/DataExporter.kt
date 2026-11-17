package com.boucle.app.data.export

import com.boucle.app.data.AppSettings
import com.boucle.app.data.db.HealthDao
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.RouteStatus
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Export de toutes les données gardées par l'app (droit à la portabilité, article 20 du RGPD) : une archive ZIP
 * avec un fichier JSON structuré qui reprend chaque table, et un fichier GPX par tracé GPS, directement
 * importable dans les applications de sport et de cartographie.
 */
class DataExporter(private val dao: HealthDao) {

    /** Écrit l'archive dans [output], sans le fermer. */
    suspend fun export(output: OutputStream, settings: AppSettings, exportedAt: Instant = Instant.now()) {
        val zip = ZipOutputStream(output)
        val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))

        val routes = dao.allTrainingRoutes().associateBy { it.sessionPath }
        val sessions = dao.allTrainingSessions()
        val gpxFiles = gpxFileNames(sessions, routes)

        zip.putNextEntry(ZipEntry(README_FILE))
        writer.write(README)
        writer.flush()
        zip.closeEntry()

        zip.putNextEntry(ZipEntry(JSON_FILE))
        val json = JsonStream(writer)
        json.beginObject()
        json.field("format", FORMAT_NAME).field("formatVersion", FORMAT_VERSION).field("exportedAt", exportedAt.toString())
        writeSettings(json, settings)
        writeDailyActivity(json)
        writeActivitySamples(json)
        writeHeartRate(json)
        writePpi(json)
        writeSleep(json)
        writeNightlyRecharge(json)
        writeSkinTemperature(json)
        writeTrainingSessions(json, sessions, routes, gpxFiles)
        writeExternalMeasurements(json)
        json.endObject()
        json.flush()
        zip.closeEntry()

        sessions.forEach { session ->
            val file = gpxFiles[session.path] ?: return@forEach
            val points = routes[session.path]?.let { RouteCodec.decode(it.points) }.orEmpty()
            zip.putNextEntry(ZipEntry(file))
            Gpx.write(writer, session.name ?: "Séance du ${session.date}", null, points)
            writer.flush()
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    private fun writeSettings(json: JsonStream, settings: AppSettings) {
        json.name("profile")
        val profile = settings.profile
        if (profile == null) {
            json.value(null as String?)
        } else {
            json.beginObject()
                .field("sex", if (profile.female) "FEMALE" else "MALE")
                .field("birthDate", profile.birthDate.toString())
                .field("heightCm", profile.heightCm)
                .field("weightKg", profile.weightKg)
                .field("maxHr", profile.maxHr)
                .endObject()
        }
        json.name("device")
        val device = settings.device
        if (device == null) json.value(null as String?) else json.beginObject().field("id", device.id).field("name", device.name).endObject()
        json.name("sync").beginObject()
            .field("lastSyncedDate", settings.lastSyncedDate)
            .field("lastSyncAt", settings.lastSyncAt)
            .field("lastHealthConnectSyncedDate", settings.lastHealthConnectSyncedDate)
            .field("lastHealthConnectSyncAt", settings.lastHealthConnectSyncAt)
            .endObject()
    }

    private suspend fun writeDailyActivity(json: JsonStream) {
        json.name("dailyActivity").beginArray()
        dao.allDailyActivity().forEach {
            json.beginObject()
                .field("date", it.date)
                .field("steps", it.steps)
                .field("activityCalories", it.activityCalories)
                .field("trainingCalories", it.trainingCalories)
                .field("bmrCalories", it.bmrCalories)
                .field("distanceMeters", it.distanceMeters)
                .field("activityGoal", it.activityGoal)
                .field("achievedActivity", it.achievedActivity)
                .field("sedentarySec", it.sedentarySec)
                .field("lightSec", it.lightSec)
                .field("moderateSec", it.moderateSec)
                .field("vigorousSec", it.vigorousSec)
                .field("sleepSec", it.sleepSec)
                .field("nonWearSec", it.nonWearSec)
                .field("dailyBalance", it.dailyBalance)
                .field("readiness", it.readiness)
                .endObject()
        }
        json.endArray()
    }

    private suspend fun writeActivitySamples(json: JsonStream) {
        json.name("activitySamples").beginArray()
        dao.activitySampleDates().forEach { date ->
            dao.activitySamplesOn(date).forEach {
                json.beginObject()
                    .field("date", it.date)
                    .field("startTime", it.startTime)
                    .field("stepIntervalSec", it.stepIntervalSec)
                json.name("steps")
                numberSeries(json, it.stepSamples)
                json.field("metIntervalSec", it.metIntervalSec)
                json.name("met")
                numberSeries(json, it.metSamples)
                json.name("activityClasses").beginArray()
                splitNonEmpty(it.activityInfo, ';').forEach { item ->
                    val f = item.split('|')
                    json.beginObject()
                        .field("time", f.getOrNull(0))
                        .field("class", f.getOrNull(1)?.ifEmpty { null })
                        .field("factor", f.getOrNull(2)?.let(::parseNumber))
                        .endObject()
                }
                json.endArray()
                json.endObject()
            }
        }
        json.endArray()
    }

    private suspend fun writeHeartRate(json: JsonStream) {
        json.name("heartRate").beginArray()
        dao.hrBlockDates().forEach { date ->
            dao.hrBlocksOn(date).forEach {
                json.beginObject().field("date", it.date).field("startTime", it.startTime).field("trigger", it.trigger)
                json.name("bpm")
                numberSeries(json, it.samples)
                json.endObject()
            }
        }
        json.endArray()
    }

    private suspend fun writePpi(json: JsonStream) {
        json.name("ppi").beginArray()
        dao.ppiBlockDates().forEach { date ->
            dao.ppiBlocksOn(date).forEach {
                json.beginObject().field("date", it.date).field("startTime", it.startTime).field("trigger", it.trigger)
                json.name("ppiMs")
                numberSeries(json, it.ppiMs)
                json.name("errorEstimateMs")
                numberSeries(json, it.errorEstimateMs)
                json.endObject()
            }
        }
        json.endArray()
    }

    private suspend fun writeSleep(json: JsonStream) {
        json.name("sleep").beginArray()
        dao.allSleep().forEach {
            json.beginObject()
                .field("date", it.date)
                .field("startTime", it.startTime)
                .field("endTime", it.endTime)
                .field("goalMinutes", it.goalMinutes)
            json.name("phases").beginArray()
            pairs(it.phases).forEach { (offset, state) ->
                json.beginObject().field("offsetSec", parseNumber(offset)).field("state", state).endObject()
            }
            json.endArray()
            json.name("cycles").beginArray()
            pairs(it.cycles).forEach { (offset, depth) ->
                json.beginObject().field("offsetSec", parseNumber(offset)).field("depth", parseNumber(depth)).endObject()
            }
            json.endArray()
            json.field("userRating", it.userRating)
                .field("batteryRanOut", it.batteryRanOut)
                .field("skinTemperatureC", it.skinTemperatureC)
                .field("skinTemperatureDeviationC", it.skinTemperatureDeviationC)
                .endObject()
        }
        json.endArray()
    }

    private suspend fun writeNightlyRecharge(json: JsonStream) {
        json.name("nightlyRecharge").beginArray()
        dao.allNightlyRecharge().forEach {
            json.beginObject()
                .field("date", it.date)
                .field("ansStatus", it.ansStatus)
                .field("recoveryIndicator", it.recoveryIndicator)
                .field("recoveryIndicatorSubLevel", it.recoveryIndicatorSubLevel)
                .field("ansRate", it.ansRate)
                .field("meanRriMs", it.meanRriMs)
                .field("meanRmssdMs", it.meanRmssdMs)
                .field("meanRespirationIntervalMs", it.meanRespirationIntervalMs)
                .field("baselineRriMs", it.baselineRriMs)
                .field("baselineRriSd", it.baselineRriSd)
                .field("baselineRmssdMs", it.baselineRmssdMs)
                .field("baselineRmssdSd", it.baselineRmssdSd)
                .field("baselineRespirationIntervalMs", it.baselineRespirationIntervalMs)
                .field("baselineRespirationIntervalSd", it.baselineRespirationIntervalSd)
                .field("sleepTip", it.sleepTip)
                .field("vitalityTip", it.vitalityTip)
                .field("exerciseTip", it.exerciseTip)
                .endObject()
        }
        json.endArray()
    }

    private suspend fun writeSkinTemperature(json: JsonStream) {
        json.name("skinTemperature").beginArray()
        dao.allSkinTemperature().forEach {
            json.beginObject()
                .field("date", it.date)
                .field("sensorLocation", it.sensorLocation)
                .field("measurementType", it.measurementType)
            json.name("samples").beginArray()
            pairs(it.samples).forEach { (delta, celsius) ->
                json.beginObject().field("offsetMs", parseNumber(delta)).field("celsius", parseNumber(celsius)).endObject()
            }
            json.endArray()
            json.endObject()
        }
        json.endArray()
    }

    private suspend fun writeTrainingSessions(
        json: JsonStream,
        sessions: List<TrainingSessionEntity>,
        routes: Map<String, TrainingRouteEntity>,
        gpxFiles: Map<String, String>,
    ) {
        json.name("trainingSessions").beginArray()
        sessions.forEach { s ->
            json.beginObject()
                .field("id", s.path)
                .field("date", s.date)
                .field("startTime", s.startTime)
                .field("durationSec", s.durationSec)
                .field("sportId", s.sportId)
                .field("name", s.name)
                .field("calories", s.calories)
                .field("distanceMeters", s.distanceMeters)
                .field("averageHr", s.averageHr)
                .field("maxHr", s.maxHr)
                .field("cardioLoad", s.cardioLoad)
                .field("muscleLoad", s.muscleLoad)
            json.name("hrZoneSec")
            numberSeries(json, s.hrZoneSec)
            json.field("summaryProtoBase64", s.summaryProto?.let(::base64))
            json.name("exercises").beginArray()
            dao.trainingExercisesOf(s.path).forEach { e ->
                json.beginObject()
                    .field("index", e.exerciseIndex)
                    .field("summaryProtoBase64", e.baseProto?.let(::base64))
                    .field("samplesProtoBase64", e.samplesProto?.let(::base64))
                    .endObject()
            }
            json.endArray()
            json.name("route")
            val route = routes[s.path]
            if (route == null) {
                json.value(null as String?)
            } else {
                json.beginObject()
                    .field("status", route.status)
                    .field("source", route.source)
                    .field("healthConnectId", route.healthConnectId)
                    .field("gpxFile", gpxFiles[s.path])
                json.name("points").beginArray()
                RouteCodec.decode(route.points).forEach { writePoint(json, it) }
                json.endArray()
                json.endObject()
            }
            json.endObject()
        }
        json.endArray()
    }

    private fun writePoint(json: JsonStream, p: RoutePoint) {
        json.beginObject()
            .field("time", Instant.ofEpochMilli(p.timeMs).toString())
            .field("latitude", p.latitude)
            .field("longitude", p.longitude)
            .field("altitudeMeters", p.altitude)
            .field("heartRate", p.heartRate)
            .endObject()
    }

    private suspend fun writeExternalMeasurements(json: JsonStream) {
        json.name("externalMeasurements").beginArray()
        dao.allExternalMeasurements().forEach {
            json.beginObject()
                .field("id", it.id)
                .field("type", it.type)
                .field("time", it.time)
                .field("date", it.date)
                .field("value", it.value)
                .field("sourceApp", it.source)
                .endObject()
        }
        json.endArray()
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    internal companion object {
        const val FORMAT_NAME = "boucle-export"
        const val FORMAT_VERSION = 1
        const val JSON_FILE = "boucle-donnees.json"
        const val README_FILE = "LISEZMOI.txt"

        /** Nom de fichier proposé pour l'archive. */
        fun archiveName(date: java.time.LocalDate) = "boucle-export-$date.zip"

        /** Un fichier GPX par séance dont le tracé est lisible, nommé d'après le jour et l'heure de départ. */
        fun gpxFileNames(sessions: List<TrainingSessionEntity>, routes: Map<String, TrainingRouteEntity>): Map<String, String> {
            val used = HashSet<String>()
            return buildMap {
                sessions.forEach { s ->
                    val route = routes[s.path] ?: return@forEach
                    if (route.status != RouteStatus.AVAILABLE.name || route.points.isBlank()) return@forEach
                    val time = s.startTime?.substringAfter('T', "")?.take(5)?.replace(":", "h")?.takeIf { it.isNotEmpty() }
                    val base = listOfNotNull(s.date, time).joinToString("_")
                    var name = "parcours/$base.gpx"
                    var n = 2
                    while (!used.add(name)) name = "parcours/$base-${n++}.gpx"
                    put(s.path, name)
                }
            }
        }

        /** « 12,0,7.5 » → nombres ; une valeur illisible devient `null` pour garder les positions. */
        fun numberSeries(json: JsonStream, csv: String) {
            json.beginArray()
            splitNonEmpty(csv, ',').forEach { json.value(parseNumber(it)) }
            json.endArray()
        }

        /** « a:b,c:d » → paires (a, b), (c, d). */
        fun pairs(csv: String): List<Pair<String, String>> = splitNonEmpty(csv, ',').map {
            it.substringBefore(':') to it.substringAfter(':', "")
        }

        fun parseNumber(s: String): Number? {
            val t = s.trim()
            return t.toLongOrNull() ?: t.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        private fun splitNonEmpty(s: String, separator: Char): List<String> =
            if (s.isBlank()) emptyList() else s.split(separator)

        val README = """
            Export des données de Boucle
            ============================

            Cette archive contient toutes les données que l'application Boucle gardait sur le téléphone au
            moment de l'export.

            - $JSON_FILE : toutes les données, au format JSON (UTF-8). Chaque section reprend une catégorie :
                profile, device, sync              profil, bracelet associé, dates des synchronisations
                dailyActivity                      résumé d'activité par jour
                activitySamples                    pas à la minute (steps), MET (met) et classes d'activité
                heartRate                          fréquence cardiaque en continu (bpm)
                ppi                                intervalles pouls à pouls (ms) et erreur estimée (ms ;
                                                   999 = battement écarté par le bracelet)
                sleep                              nuits, phases et cycles de sommeil
                nightlyRecharge                    récupération nocturne (Nightly Recharge)
                skinTemperature                    température de la peau pendant la nuit
                trainingSessions                   séances d'entraînement, avec leur tracé GPS éventuel
                                                   (les champs *ProtoBase64 sont les messages bruts du
                                                   bracelet, au format protobuf Polar, encodés en base64)
                externalMeasurements               mesures lues dans Health Connect (poids, SpO2…)
              Les unités sont dans le nom des champs (Sec = secondes, Ms = millisecondes, Meters = mètres,
              C ou celsius = degrés Celsius). Les dates sont au format ISO 8601 ; les heures sans fuseau
              sont en heure locale du téléphone, celles qui finissent par Z en temps universel (UTC).

            - parcours/*.gpx : un fichier GPX 1.1 par séance avec un tracé GPS, importable dans la plupart
              des applications de sport et de cartographie. La fréquence cardiaque de chaque point suit
              l'extension Garmin TrackPointExtension.

            Attention : ces fichiers contiennent des données de santé. Une fois exportés, ils ne sont plus
            protégés par l'espace privé de l'application ; à vous de les conserver en lieu sûr.
        """.trimIndent() + "\n"
    }
}
