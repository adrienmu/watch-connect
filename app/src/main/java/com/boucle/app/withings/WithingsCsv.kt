package com.boucle.app.withings

import java.io.Reader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/*
 * Lecture des fichiers CSV de l'archive d'export Withings. Pur Kotlin, testable sur la JVM.
 *
 * Deux formes de fichiers :
 *  - les tableaux classiques (weight.csv, sleep.csv, activities.csv, aggregates_*.csv), une mesure par ligne ;
 *  - les séries brutes (raw_*.csv), « start,duration,value » : un instant de départ, puis une liste de durées en
 *    secondes et la liste des valeurs correspondantes (« [60,60],[72,75] » : 72 pendant 60 s, puis 75 pendant 60 s).
 */

/**
 * Lecteur CSV en flux, une ligne à la fois. Une virgule ne sépare pas les champs entre guillemets ni entre crochets :
 * les listes des séries brutes ne sont pas toujours entre guillemets. Un champ entre guillemets peut contenir des
 * retours à la ligne et des guillemets doublés (le JSON de activities.csv).
 */
internal class CsvReader(source: Reader) {
    // Un guillemet doublé se reconnaît en lisant un caractère d'avance, puis en revenant en arrière.
    private val reader: Reader = if (source.markSupported()) source else java.io.BufferedReader(source)
    private var first = true

    /** Champs de la ligne suivante, sans les guillemets ; `null` à la fin du fichier. Les lignes vides sont sautées. */
    fun next(): List<String>? {
        while (true) {
            val record = readRecord() ?: return null
            if (record.size > 1 || record[0].isNotBlank()) return record
        }
    }

    private fun readRecord(): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var depth = 0
        var any = false
        while (true) {
            var c = reader.read()
            if (first) {
                first = false
                // Marque d'ordre des octets en tête de fichier.
                if (c == BOM) c = reader.read()
            }
            if (c < 0) {
                if (!any) return null
                fields.add(field.toString())
                return fields
            }
            any = true
            val ch = c.toChar()
            when {
                inQuotes -> if (ch == '"') {
                    reader.mark(1)
                    if (reader.read() == '"'.code) field.append('"') else {
                        reader.reset()
                        inQuotes = false
                    }
                } else {
                    field.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == '\n' -> {
                    fields.add(field.toString())
                    return fields
                }
                ch == '\r' -> Unit
                ch == ',' && depth == 0 -> {
                    fields.add(field.toString())
                    field.setLength(0)
                }
                else -> {
                    if (ch == '[') depth++ else if (ch == ']' && depth > 0) depth--
                    field.append(ch)
                }
            }
        }
    }

    private companion object {
        const val BOM = 0xFEFF
    }
}

/** Instant d'une mesure, avec le décalage horaire dans lequel elle a été enregistrée. */
internal data class Stamp(val epochSec: Long, val offsetSec: Int) {
    /** Secondes « murales » : l'heure locale lue comme si elle était UTC, pour trouver le jour et la minute. */
    val localSec: Long get() = epochSec + offsetSec
}

internal object WithingsTime {
    private val COMPACT_OFFSET = Regex("[+-]\\d{4}$")

    /**
     * Lit les formats de date de l'archive : ISO avec décalage (« 2024-03-01T23:12:00+01:00 »), date et heure
     * locales (« 2024-03-01 07:12:33 », dans [zone]), jour seul, ou secondes depuis l'époque Unix.
     */
    fun parse(text: String, zone: ZoneId): Stamp? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) {
            val epoch = t.toLongOrNull() ?: return null
            return Stamp(epoch, zone.rules.getOffset(java.time.Instant.ofEpochSecond(epoch)).totalSeconds)
        }
        val iso = t.replace(' ', 'T').let { if (COMPACT_OFFSET.containsMatchIn(it)) it.dropLast(2) + ":" + it.takeLast(2) else it }
        runCatching { OffsetDateTime.parse(iso) }.getOrNull()?.let { return Stamp(it.toEpochSecond(), it.offset.totalSeconds) }
        val local = runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { LocalDate.parse(iso).atStartOfDay() }.getOrNull()
            ?: return null
        val zoned = local.atZone(zone)
        return Stamp(zoned.toEpochSecond(), zoned.offset.totalSeconds)
    }
}

/** Reçoit chaque valeur d'une série : son instant, sa durée (0 si inconnue) et sa valeur. */
internal fun interface SampleSink {
    fun accept(stamp: Stamp, durationSec: Int, value: Double)
}

internal object WithingsSeries {
    private val TIME_HEADERS = listOf("start", "date", "from", "time")

    /**
     * Lit une série, brute (« start,duration,value ») ou simple (une date et une valeur par ligne), et passe chaque
     * valeur numérique à [sink]. Les lignes illisibles sont sautées.
     * @return le nombre de valeurs lues.
     */
    fun read(reader: Reader, zone: ZoneId, sink: SampleSink): Int {
        val csv = CsvReader(reader)
        val header = csv.next()?.map { it.trim().lowercase() } ?: return 0
        val timeCol = TIME_HEADERS.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }
            ?: header.indexOfFirst { h -> TIME_HEADERS.any { it in h } }.takeIf { it >= 0 }
            ?: return 0
        val durationCol = header.indexOf("duration")
        val valueCol = header.indexOf("value").takeIf { it >= 0 }
            ?: header.indices.lastOrNull { it != timeCol && it != durationCol }
            ?: return 0
        var count = 0
        while (true) {
            val row = csv.next() ?: break
            val start = row.getOrNull(timeCol)?.let { WithingsTime.parse(it, zone) } ?: continue
            val values = numbers(row.getOrNull(valueCol) ?: continue)
            val durations = row.getOrNull(durationCol)?.let(::numbers) ?: DoubleArray(0)
            var elapsed = 0L
            for (i in values.indices) {
                val duration = durations.getOrNull(i)?.takeIf { !it.isNaN() && it > 0 }?.toInt() ?: 0
                if (!values[i].isNaN()) {
                    sink.accept(Stamp(start.epochSec + elapsed, start.offsetSec), duration, values[i])
                    count++
                }
                elapsed += duration
            }
        }
        return count
    }

    /** « [60, 60] », « 72 » ou « "[1.5,2]" » → nombres ; `NaN` pour un élément illisible. */
    fun numbers(text: String): DoubleArray {
        val inner = text.trim().removePrefix("[").removeSuffix("]")
        if (inner.isBlank()) return DoubleArray(0)
        val parts = inner.split(',')
        return DoubleArray(parts.size) { parts[it].trim().toDoubleOrNull() ?: Double.NaN }
    }
}

/** Colonnes d'un tableau, retrouvées par mots-clés dans l'en-tête (les intitulés varient d'une archive à l'autre). */
internal class CsvTable(reader: Reader) {
    private val csv = CsvReader(reader)
    private val header: List<String> = csv.next()?.map { it.trim().lowercase() } ?: emptyList()

    /** Première colonne dont l'intitulé est exactement l'un des [names], sinon en contient un. */
    fun column(vararg names: String): Int =
        names.firstNotNullOfOrNull { name -> header.indexOf(name).takeIf { it >= 0 } }
            ?: names.firstNotNullOfOrNull { name -> header.indexOfFirst { name in it }.takeIf { it >= 0 } }
            ?: -1

    fun next(): List<String>? = csv.next()
}

/** Champ numérique d'un petit objet JSON à plat (colonne « Data » de activities.csv). */
internal fun jsonNumber(json: String, key: String): Double? =
    Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()
