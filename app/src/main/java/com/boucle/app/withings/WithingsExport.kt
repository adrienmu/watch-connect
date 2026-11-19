package com.boucle.app.withings

import com.boucle.app.data.db.ExternalMeasurementEntity
import com.boucle.app.data.db.HrBlockEntity
import com.boucle.app.data.db.TrainingRouteEntity
import com.boucle.app.data.db.TrainingSessionEntity
import com.boucle.app.data.export.BackupSink
import com.boucle.app.domain.MeasurementType
import com.boucle.app.domain.RouteAnalysis
import com.boucle.app.domain.RouteCodec
import com.boucle.app.domain.RoutePoint
import com.boucle.app.domain.RouteSource
import com.boucle.app.domain.RouteStatus
import com.boucle.app.domain.Sport
import com.boucle.app.healthconnect.HcDayTotals
import com.boucle.app.healthconnect.HcSleepSession
import com.boucle.app.healthconnect.HcSleepStage
import com.boucle.app.healthconnect.HealthConnectGateway
import com.boucle.app.healthconnect.HealthConnectMapping
import java.io.Reader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** Ce qu'une lecture de l'archive a trouvé, pour le message montré à l'utilisateur. */
data class WithingsReport(
    /** Fichiers de l'archive reconnus et lus. */
    val files: List<String>,
    /** Premier et dernier jour couverts par les données lues ; `null` si l'archive est vide. */
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
)

/**
 * Lecture de l'archive « Télécharger mes données » de Withings (un ZIP de fichiers CSV) : tout l'historique de la
 * montre et de la balance, traduit dans les tables de l'app comme le fait [HealthConnectMapping] pour Health
 * Connect, qui ne remonte que quelques semaines en arrière.
 *
 *  - FC (raw_*_hr.csv) : moyenne par 5 minutes, en blocs d'une heure.
 *  - Pas (raw_*_steps.csv) : pas à la minute ; totaux du jour dans aggregates_*.csv (pas, distance, calories).
 *  - Sommeil (sleep.csv, raw_*_sleep-state.csv) : nuits avec leurs phases, FC, VFC et respiration nocturnes.
 *  - Séances (activities.csv) avec leur tracé GPS (raw_*_latitude.csv, raw_*_longitude.csv).
 *  - Balance (weight.csv), SpO2 et VFC : mesures ponctuelles.
 *
 * Les noms de fichiers et de colonnes varient selon l'âge de l'archive et les appareils : les fichiers sont reconnus
 * par motif, les colonnes par mots-clés, et ce qui n'est pas reconnu est ignoré. Les jours et les heures sont ceux du
 * fuseau enregistré avec chaque mesure. Pur Kotlin, testable sur la JVM.
 */
object WithingsExport {
    /** Préfixe des séances et des mesures venues de l'archive. */
    const val PREFIX = "withings:"

    /** Chemin de la séance commencée à cet instant : le même à chaque import, et retrouvable depuis Health Connect. */
    fun sessionPath(startEpochSec: Long) = "$PREFIX$startEpochSec"

    /** Identifiant d'une mesure de l'archive : le même à chaque import. */
    fun measurementId(type: MeasurementType, epochSec: Long) = "$PREFIX${type.name}:$epochSec"

    internal enum class Kind {
        WEIGHT, SLEEP, ACTIVITIES, DAILY_STEPS, DAILY_DISTANCE, DAILY_ACTIVE_CALORIES, DAILY_BMR_CALORIES,
        HR, STEPS, SLEEP_STATE, LATITUDE, LONGITUDE, ALTITUDE, SPO2, HRV, RESPIRATION,
    }

    /** Rôle d'un fichier de l'archive d'après son nom ; `null` s'il n'est pas utilisé. */
    internal fun kind(entryName: String): Kind? {
        val base = entryName.substringAfterLast('/').lowercase()
        if (!base.endsWith(".csv")) return null
        val stem = base.removeSuffix(".csv").replace('-', '_')
        return when {
            stem == "weight" -> Kind.WEIGHT
            stem == "sleep" -> Kind.SLEEP
            stem == "activities" -> Kind.ACTIVITIES
            stem.startsWith("aggregates") -> when {
                "steps" in stem -> Kind.DAILY_STEPS
                "distance" in stem -> Kind.DAILY_DISTANCE
                "calories_earned" in stem -> Kind.DAILY_ACTIVE_CALORIES
                "calories_passive" in stem -> Kind.DAILY_BMR_CALORIES
                else -> null
            }
            stem.startsWith("raw") -> when {
                stem.endsWith("sleep_state") -> Kind.SLEEP_STATE
                stem.endsWith("_hr") -> Kind.HR
                stem.endsWith("_steps") -> Kind.STEPS
                stem.endsWith("latitude") -> Kind.LATITUDE
                stem.endsWith("longitude") -> Kind.LONGITUDE
                stem.endsWith("altitude") -> Kind.ALTITUDE
                "spo2" in stem -> Kind.SPO2
                "rmssd" in stem || stem.endsWith("_hrv") -> Kind.HRV
                "respiratory" in stem -> Kind.RESPIRATION
                else -> null
            }
            "spo2" in stem -> Kind.SPO2
            else -> null
        }
    }

    /** Vrai si ce nom de fichier est celui d'un fichier de l'archive Withings. */
    fun isWithingsFile(entryName: String): Boolean = kind(entryName) != null

    /**
     * Lit toute l'archive et passe chaque ligne à [sink], les séances avant leur tracé.
     * @param zone fuseau des dates écrites sans décalage horaire (pesées).
     */
    suspend fun read(zip: ZipFile, zone: ZoneId, sink: BackupSink): WithingsReport {
        val files = zip.entries().asSequence().filter { !it.isDirectory }.mapNotNull { entry ->
            kind(entry.name)?.let { it to entry }
        }.toList()
        fun <T> each(kind: Kind, block: (Reader) -> T): List<T> =
            files.filter { it.first == kind }.map { (_, entry) -> zip.getInputStream(entry).bufferedReader().use(block) }
        fun series(kind: Kind, sink: SampleSink) = each(kind) { WithingsSeries.read(it, zone, sink) }

        val days = DayRange()

        // Séances et nuits d'abord : elles disent quelles mesures de FC garder à la seconde près.
        val workouts = each(Kind.ACTIVITIES) { readWorkouts(it, zone) }.flatten().distinctBy { it.start.epochSec }.sortedBy { it.start.epochSec }
        val nights = each(Kind.SLEEP) { readNights(it, zone) }.flatten()
        val windows = Windows(workouts.map { it.start.epochSec - HR_MARGIN_SEC to it.endEpochSec + HR_MARGIN_SEC })

        val hrDays = HashMap<Long, IntArray>()
        val workoutHr = TimeSeries()
        series(Kind.HR) { stamp, _, bpm ->
            if (bpm < MIN_BPM || bpm > MAX_BPM) return@series
            val local = stamp.localSec
            val day = hrDays.getOrPut(Math.floorDiv(local, DAY_SEC)) { IntArray(2 * HR_SLOTS_PER_DAY) }
            val slot = (Math.floorMod(local, DAY_SEC) / HR_SLOT_SEC).toInt()
            day[slot] += bpm.roundToInt()
            day[HR_SLOTS_PER_DAY + slot]++
            if (windows.contains(stamp.epochSec)) workoutHr.add(stamp, bpm)
        }
        workoutHr.sort()

        val stepDays = HashMap<Long, FloatArray>()
        series(Kind.STEPS) { stamp, duration, count ->
            if (count <= 0) return@series
            // Les pas d'une mesure sont répartis uniformément sur les minutes qu'elle couvre.
            val total = duration.coerceAtLeast(1)
            var t = stamp.localSec
            val end = t + total
            while (t < end) {
                val next = minOf((Math.floorDiv(t, 60L) + 1) * 60, end)
                val minutes = stepDays.getOrPut(Math.floorDiv(t, DAY_SEC)) { FloatArray(MINUTES_PER_DAY) }
                minutes[(Math.floorMod(t, DAY_SEC) / 60).toInt()] += (count * (next - t) / total).toFloat()
                t = next
            }
        }

        val stages = ArrayList<HcSleepStage>()
        series(Kind.SLEEP_STATE) { stamp, duration, state ->
            if (duration <= 0) return@series
            val start = Instant.ofEpochSecond(stamp.epochSec)
            stages += HcSleepStage(start, start.plusSeconds(duration.toLong()), sleepState(state.toInt()))
        }
        stages.sortBy { it.start }

        val latitude = TimeSeries().also { s -> series(Kind.LATITUDE) { stamp, _, v -> s.add(stamp, v) } }.apply { sort() }
        val longitude = TimeSeries().also { s -> series(Kind.LONGITUDE) { stamp, _, v -> s.add(stamp, v) } }.apply { sort() }
        val altitude = TimeSeries().also { s -> series(Kind.ALTITUDE) { stamp, _, v -> s.add(stamp, v) } }.apply { sort() }
        val spo2 = TimeSeries().also { s -> series(Kind.SPO2) { stamp, _, v -> s.add(stamp, if (v <= 1.0) v * 100 else v) } }.apply { sort() }
        val hrv = TimeSeries().also { s -> series(Kind.HRV) { stamp, _, v -> if (v > 0) s.add(stamp, v) } }.apply { sort() }
        val respiration = TimeSeries().also { s -> series(Kind.RESPIRATION) { stamp, _, v -> if (v > 0) s.add(stamp, v) } }.apply { sort() }

        // Totaux du jour.
        val totals = HashMap<Long, DoubleArray>()
        fun total(kind: Kind, column: Int) = series(kind) { stamp, _, value ->
            totals.getOrPut(Math.floorDiv(stamp.localSec, DAY_SEC)) { DoubleArray(4) { Double.NaN } }[column] = value
        }
        total(Kind.DAILY_STEPS, 0)
        total(Kind.DAILY_DISTANCE, 1)
        total(Kind.DAILY_ACTIVE_CALORIES, 2)
        total(Kind.DAILY_BMR_CALORIES, 3)
        // Sans total fourni, celui des pas à la minute.
        stepDays.forEach { (day, minutes) ->
            val row = totals.getOrPut(day) { DoubleArray(4) { Double.NaN } }
            if (row[0].isNaN()) row[0] = minutes.sum().toDouble()
        }
        HealthConnectMapping.dailyActivity(
            totals.toSortedMap().map { (day, row) ->
                fun at(i: Int) = row[i].takeIf { !it.isNaN() && it >= 0 }
                HcDayTotals(LocalDate.ofEpochDay(day), at(0)?.toLong(), at(1), at(2), at(3))
            },
        ).forEach {
            days.see(LocalDate.parse(it.date))
            sink.dailyActivity(it)
        }

        stepDays.toSortedMap().forEach { (day, minutes) ->
            val date = LocalDate.ofEpochDay(day)
            days.see(date)
            sink.activitySamples(HealthConnectMapping.activitySamplesOf(date, DoubleArray(MINUTES_PER_DAY) { minutes[it].toDouble() }))
        }

        hrDays.toSortedMap().forEach { (day, slots) ->
            val date = LocalDate.ofEpochDay(day)
            days.see(date)
            hrBlocks(date, slots).forEach { sink.hrBlock(it) }
        }

        // Nuits : celles de sleep.csv avec leurs phases ; sans ce fichier, les phases regroupées par nuit.
        val sessions = if (nights.isNotEmpty()) nights.map { it.session(stages) } else stageSessions(stages)
        val averageHrByEnd = nights.mapNotNull { n -> n.averageHr?.let { n.end.epochSec to it } }.toMap()
        HealthConnectMapping.sleeps(sessions, zone).sortedBy { it.date }.forEach { sleep ->
            days.see(LocalDate.parse(sleep.date))
            sink.sleep(sleep)
            val start = runCatching { ZonedDateTime.parse(sleep.startTime) }.getOrNull() ?: return@forEach
            val end = runCatching { ZonedDateTime.parse(sleep.endTime) }.getOrNull() ?: return@forEach
            val from = start.toEpochSecond()
            val to = end.toEpochSecond()
            val bpm = averageHrByEnd[to] ?: nightHr(hrDays, from, to, end.offset.totalSeconds)
            HealthConnectMapping.nightlyMeasure(
                date = sleep.date,
                meanRriMs = bpm?.takeIf { it > 0 }?.let { (60_000.0 / it).roundToInt() },
                meanRmssdMs = hrv.average(from, to)?.roundToInt(),
                meanRespirationIntervalMs = respiration.average(from, to)?.let { (60_000.0 / it).roundToInt() },
            )?.let { sink.nightlyRecharge(it) }
        }

        workouts.forEach { workout ->
            val session = workout.session(workoutHr)
            days.see(LocalDate.parse(session.date))
            sink.trainingSession(session)
            route(workout, latitude, longitude, altitude, workoutHr)?.let { sink.trainingRoute(it) }
        }

        each(Kind.WEIGHT) { readWeights(it, zone) }.flatten().forEach {
            days.see(LocalDate.parse(it.date))
            sink.externalMeasurement(it)
        }
        for (i in 0 until spo2.size) {
            if (spo2.value(i) in 50.0..100.0) sink.externalMeasurement(measurement(MeasurementType.SPO2, spo2.stamp(i), spo2.value(i)))
        }
        for (i in 0 until hrv.size) sink.externalMeasurement(measurement(MeasurementType.HRV_RMSSD, hrv.stamp(i), hrv.value(i)))

        return WithingsReport(files.map { it.second.name.substringAfterLast('/') }.sorted(), days.first, days.last)
    }

    // --- Fréquence cardiaque ---

    /** Blocs d'une heure d'un jour, 1 valeur par 5 minutes (0 = pas de mesure), comme [HealthConnectMapping.hrBlocks]. */
    private fun hrBlocks(date: LocalDate, slots: IntArray): List<HrBlockEntity> = (0 until 24).mapNotNull { hour ->
        val range = hour * HR_SLOTS_PER_HOUR until (hour + 1) * HR_SLOTS_PER_HOUR
        if (range.none { slots[HR_SLOTS_PER_DAY + it] > 0 }) return@mapNotNull null
        val values = range.joinToString(",") { i ->
            val count = slots[HR_SLOTS_PER_DAY + i]
            if (count > 0) (slots[i].toDouble() / count).roundToInt().toString() else "0"
        }
        HrBlockEntity(date.toString(), "%02d:00".format(hour), HealthConnectMapping.HR_TRIGGER, values)
    }

    /** FC moyenne entre deux instants, d'après les moyennes par 5 minutes ; `null` avec moins de 3 créneaux mesurés. */
    private fun nightHr(hrDays: Map<Long, IntArray>, from: Long, to: Long, offsetSec: Int): Double? {
        var sum = 0.0
        var count = 0
        var t = from + offsetSec
        val end = to + offsetSec
        while (t <= end) {
            val slots = hrDays[Math.floorDiv(t, DAY_SEC)]
            val slot = (Math.floorMod(t, DAY_SEC) / HR_SLOT_SEC).toInt()
            if (slots != null && slots[HR_SLOTS_PER_DAY + slot] > 0) {
                sum += slots[slot].toDouble() / slots[HR_SLOTS_PER_DAY + slot]
                count++
            }
            t += HR_SLOT_SEC
        }
        return if (count >= MIN_NIGHT_HR_SLOTS) sum / count else null
    }

    // --- Sommeil ---

    /** État de sommeil Withings (0 éveil, 1 léger, 2 profond, 3 paradoxal) → vocabulaire du bracelet. */
    internal fun sleepState(state: Int): String = when (state) {
        0 -> "WAKE"
        2 -> "NONREM3"
        3 -> "REM"
        else -> "NONREM12"
    }

    /** Une ligne de sleep.csv. Les durées par phase ne servent que si la nuit n'a pas de phases détaillées. */
    internal class Night(
        val start: Stamp,
        val end: Stamp,
        val lightSec: Int,
        val deepSec: Int,
        val remSec: Int,
        val awakeSec: Int,
        val averageHr: Double?,
    ) {
        fun session(stages: List<HcSleepStage>): HcSleepSession {
            val from = Instant.ofEpochSecond(start.epochSec)
            val to = Instant.ofEpochSecond(end.epochSec)
            // Phases qui commencent pendant la nuit, coupées à son réveil.
            var i = stages.binarySearchBy(from) { it.start }.let { if (it < 0) -it - 1 else it }
            val own = ArrayList<HcSleepStage>()
            while (i < stages.size && stages[i].start < to) {
                val stage = stages[i++]
                own += if (stage.end > to) stage.copy(end = to) else stage
            }
            return HcSleepSession(from, to, ZoneOffset.ofTotalSeconds(end.offsetSec), own.ifEmpty { summaryStages(from) })
        }

        /**
         * Sans phases détaillées : les durées de chaque phase mises bout à bout (léger, profond, paradoxal, éveil).
         * Les totaux sont justes, l'ordre des phases dans la nuit ne l'est pas.
         */
        private fun summaryStages(from: Instant): List<HcSleepStage> {
            var t = from
            return listOf("NONREM12" to lightSec, "NONREM3" to deepSec, "REM" to remSec, "WAKE" to awakeSec)
                .filter { it.second > 0 }
                .map { (state, seconds) -> HcSleepStage(t, t.plusSeconds(seconds.toLong()), state).also { t = it.end } }
        }
    }

    internal fun readNights(reader: Reader, zone: ZoneId): List<Night> {
        val table = CsvTable(reader)
        val from = table.column("from", "start")
        val to = table.column("to", "end")
        val light = table.column("light")
        val deep = table.column("deep")
        val rem = table.column("rem")
        val awake = table.column("awake")
        val hr = table.column("average heart rate", "hr average")
        if (from < 0 || to < 0) return emptyList()
        val out = ArrayList<Night>()
        while (true) {
            val row = table.next() ?: break
            val start = row.getOrNull(from)?.let { WithingsTime.parse(it, zone) } ?: continue
            val end = row.getOrNull(to)?.let { WithingsTime.parse(it, zone) } ?: continue
            if (end.epochSec <= start.epochSec) continue
            fun seconds(column: Int) = row.getOrNull(column)?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            out += Night(start, end, seconds(light), seconds(deep), seconds(rem), seconds(awake), row.getOrNull(hr)?.trim()?.toDoubleOrNull()?.takeIf { it > 0 })
        }
        return out
    }

    /** Sans sleep.csv : une nuit par suite de phases sans trou de plus d'une heure. */
    private fun stageSessions(stages: List<HcSleepStage>): List<HcSleepSession> {
        val out = ArrayList<HcSleepSession>()
        var current = ArrayList<HcSleepStage>()
        fun close() {
            if (current.isNotEmpty()) out += HcSleepSession(current.first().start, current.maxOf { it.end }, null, current)
            current = ArrayList()
        }
        stages.forEach { stage ->
            if (current.isNotEmpty() && stage.start > current.last().end.plusSeconds(MAX_STAGE_GAP_SEC)) close()
            current += stage
        }
        close()
        return out
    }

    // --- Séances ---

    internal class Workout(val start: Stamp, val endEpochSec: Long, val zone: ZoneId, val type: String, val data: String) {
        fun session(hr: TimeSeries): TrainingSessionEntity {
            val local: LocalDateTime = Instant.ofEpochSecond(start.epochSec).atZone(zone).toLocalDateTime()
            val (name, sport) = sport(type)
            fun number(vararg keys: String) = keys.firstNotNullOfOrNull { jsonNumber(data, it) }?.takeIf { it > 0 }
            return TrainingSessionEntity(
                path = sessionPath(start.epochSec),
                date = local.toLocalDate().toString(),
                startTime = local.toString(),
                durationSec = (endEpochSec - start.epochSec).toInt(),
                sportId = sport?.sportId,
                name = name,
                calories = number("calories", "manual_calories")?.roundToInt(),
                distanceMeters = number("distance", "manual_distance")?.toFloat(),
                averageHr = (number("hr_average") ?: hr.average(start.epochSec, endEpochSec))?.roundToInt(),
                maxHr = (number("hr_max") ?: hr.max(start.epochSec, endEpochSec))?.roundToInt(),
                cardioLoad = null,
                muscleLoad = null,
                hrZoneSec = "",
                exerciseCount = 0,
                summaryProto = null,
            )
        }
    }

    /** Nom affiché et sport reconnu par l'app, d'après le type d'activité Withings (« Running », « Walking »…). */
    internal fun sport(type: String): Pair<String, Sport?> {
        val t = type.lowercase()
        return when {
            "run" in t || "course" in t -> "Course à pied" to Sport.RUN
            "hik" in t || "rando" in t -> "Randonnée" to Sport.WALK
            "walk" in t || "marche" in t -> "Marche" to Sport.WALK
            "cycl" in t || "bik" in t || "vélo" in t -> "Vélo" to Sport.BIKE
            "swim" in t || "natation" in t -> "Natation" to null
            "yoga" in t -> "Yoga" to null
            "weight" in t || "strength" in t || "lift" in t -> "Renforcement" to null
            t.isBlank() || t == "other" -> "Entraînement" to null
            else -> type.trim().replace('_', ' ').replaceFirstChar { it.uppercase() } to null
        }
    }

    internal fun readWorkouts(reader: Reader, zone: ZoneId): List<Workout> {
        val table = CsvTable(reader)
        val from = table.column("from", "start")
        val to = table.column("to", "end")
        val timezone = table.column("timezone")
        val type = table.column("activity type", "type")
        val data = table.column("data")
        if (from < 0 || to < 0) return emptyList()
        val out = ArrayList<Workout>()
        while (true) {
            val row = table.next() ?: break
            val rowZone = row.getOrNull(timezone)?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            val start = row.getOrNull(from)?.let { WithingsTime.parse(it, rowZone ?: zone) } ?: continue
            val end = row.getOrNull(to)?.let { WithingsTime.parse(it, rowZone ?: zone) } ?: continue
            if (end.epochSec <= start.epochSec) continue
            out += Workout(start, end.epochSec, rowZone ?: ZoneOffset.ofTotalSeconds(start.offsetSec), row.getOrNull(type).orEmpty(), row.getOrNull(data).orEmpty())
        }
        return out
    }

    /** Tracé d'une séance : les points GPS enregistrés pendant qu'elle durait, avec la FC du même moment. */
    private fun route(workout: Workout, latitude: TimeSeries, longitude: TimeSeries, altitude: TimeSeries, hr: TimeSeries): TrainingRouteEntity? {
        val from = workout.start.epochSec
        val to = workout.endEpochSec
        var i = latitude.lowerBound(from)
        if (i >= latitude.size || latitude.time(i) > to) return null
        val heart = hr.pairsMs(from - HR_MARGIN_SEC, to + HR_MARGIN_SEC)
        val points = ArrayList<RoutePoint>()
        while (i < latitude.size && latitude.time(i) <= to) {
            val time = latitude.time(i)
            val lat = latitude.value(i++)
            val lon = longitude.at(time) ?: continue
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180 || (lat == 0.0 && lon == 0.0)) continue
            if (points.isNotEmpty() && points.last().timeMs == time * 1000) continue
            points += RoutePoint(time * 1000, lat, lon, altitude.at(time), RouteAnalysis.heartRateAt(time * 1000, heart))
        }
        if (points.size < RouteAnalysis.MIN_POINTS) return null
        return TrainingRouteEntity(
            sessionPath = sessionPath(from),
            status = RouteStatus.AVAILABLE.name,
            source = RouteSource.WITHINGS.name,
            healthConnectId = null,
            points = RouteCodec.encode(points),
        )
    }

    // --- Mesures ponctuelles ---

    private fun measurement(type: MeasurementType, stamp: Stamp, value: Double) = ExternalMeasurementEntity(
        id = measurementId(type, stamp.epochSec),
        type = type.name,
        time = Instant.ofEpochSecond(stamp.epochSec).toString(),
        date = LocalDate.ofEpochDay(Math.floorDiv(stamp.localSec, DAY_SEC)).toString(),
        value = value,
        source = HealthConnectGateway.ACTIVITY_SOURCE,
    )

    /** Pesées : poids, masse grasse (en % du poids), masse maigre (poids − graisse), masse osseuse et eau, en kg. */
    internal fun readWeights(reader: Reader, zone: ZoneId): List<ExternalMeasurementEntity> {
        val table = CsvTable(reader)
        val date = table.column("date")
        val weight = table.column("weight", "poids")
        val fat = table.column("fat mass", "masse grasse")
        val bone = table.column("bone mass", "masse osseuse")
        val water = table.column("hydration", "hydratation")
        if (date < 0 || weight < 0) return emptyList()
        val out = ArrayList<ExternalMeasurementEntity>()
        while (true) {
            val row = table.next() ?: break
            val stamp = row.getOrNull(date)?.let { WithingsTime.parse(it, zone) } ?: continue
            fun kg(column: Int) = row.getOrNull(column)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }
            val kilograms = kg(weight) ?: continue
            out += measurement(MeasurementType.WEIGHT, stamp, kilograms)
            kg(fat)?.takeIf { it < kilograms }?.let {
                out += measurement(MeasurementType.BODY_FAT, stamp, it / kilograms * 100)
                out += measurement(MeasurementType.LEAN_MASS, stamp, kilograms - it)
            }
            kg(bone)?.let { out += measurement(MeasurementType.BONE_MASS, stamp, it) }
            kg(water)?.let { out += measurement(MeasurementType.WATER_MASS, stamp, it) }
        }
        return out
    }

    private const val DAY_SEC = 86_400L
    private const val MINUTES_PER_DAY = 24 * 60
    private const val HR_SLOT_SEC = 300L
    private const val HR_SLOTS_PER_HOUR = 12
    private const val HR_SLOTS_PER_DAY = 24 * HR_SLOTS_PER_HOUR
    private const val MIN_NIGHT_HR_SLOTS = 3
    private const val MIN_BPM = 25.0
    private const val MAX_BPM = 250.0
    /** Marge autour d'une séance pour retrouver la FC de ses premiers et derniers points. */
    private const val HR_MARGIN_SEC = 60L
    private const val MAX_STAGE_GAP_SEC = 3_600L
}

/** Premier et dernier jour vus. */
private class DayRange {
    var first: LocalDate? = null
    var last: LocalDate? = null

    fun see(day: LocalDate) {
        if (first.let { it == null || day < it }) first = day
        if (last.let { it == null || day > it }) last = day
    }
}

/** Intervalles de temps fusionnés, pour savoir vite si un instant tombe dans l'un d'eux. */
internal class Windows(intervals: List<Pair<Long, Long>>) {
    private val starts: LongArray
    private val ends: LongArray

    init {
        val merged = ArrayList<LongArray>()
        intervals.sortedBy { it.first }.forEach { (from, to) ->
            val last = merged.lastOrNull()
            if (last != null && from <= last[1]) last[1] = maxOf(last[1], to) else merged += longArrayOf(from, to)
        }
        starts = LongArray(merged.size) { merged[it][0] }
        ends = LongArray(merged.size) { merged[it][1] }
    }

    fun contains(t: Long): Boolean {
        val i = starts.binarySearch(t).let { if (it < 0) -it - 2 else it }
        return i >= 0 && t <= ends[i]
    }
}

/**
 * Série de mesures horodatées dans des tableaux primitifs : les séries à la seconde (GPS, FC des séances) comptent
 * des centaines de milliers de points. [sort] avant toute recherche.
 */
internal class TimeSeries {
    private var times = LongArray(256)
    private var offsets = IntArray(256)
    private var values = DoubleArray(256)
    private var sorted = true
    var size = 0
        private set

    fun add(stamp: Stamp, value: Double) {
        if (size == times.size) {
            times = times.copyOf(size * 2)
            offsets = offsets.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        if (size > 0 && stamp.epochSec < times[size - 1]) sorted = false
        times[size] = stamp.epochSec
        offsets[size] = stamp.offsetSec
        values[size] = value
        size++
    }

    fun sort() {
        if (sorted) return
        val order = (0 until size).sortedBy { times[it] }
        val sortedTimes = LongArray(size) { times[order[it]] }
        val sortedOffsets = IntArray(size) { offsets[order[it]] }
        val sortedValues = DoubleArray(size) { values[order[it]] }
        times = sortedTimes
        offsets = sortedOffsets
        values = sortedValues
        sorted = true
    }

    fun time(i: Int) = times[i]
    fun value(i: Int) = values[i]
    fun stamp(i: Int) = Stamp(times[i], offsets[i])

    /** Indice de la première mesure à partir de [t]. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Valeur mesurée exactement à [t]. */
    fun at(t: Long): Double? = lowerBound(t).takeIf { it < size && times[it] == t }?.let { values[it] }

    fun average(from: Long, to: Long): Double? {
        var sum = 0.0
        var count = 0
        var i = lowerBound(from)
        while (i < size && times[i] <= to) {
            sum += values[i++]
            count++
        }
        return if (count > 0) sum / count else null
    }

    fun max(from: Long, to: Long): Double? {
        var max: Double? = null
        var i = lowerBound(from)
        while (i < size && times[i] <= to) {
            val v = values[i++]
            if (max == null || v > max) max = v
        }
        return max
    }

    /** Mesures entre deux instants, en « millisecondes → valeur arrondie », pour [RouteAnalysis.heartRateAt]. */
    fun pairsMs(from: Long, to: Long): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var i = lowerBound(from)
        while (i < size && times[i] <= to) {
            out += times[i] * 1000 to values[i].roundToInt()
            i++
        }
        return out
    }
}
