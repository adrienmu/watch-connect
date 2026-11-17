package com.boucle.app.data.export

import java.io.Writer

/**
 * Écriture JSON en flux, sans tout garder en mémoire : l'historique de FC et d'intervalles pouls à pouls peut
 * peser plusieurs dizaines de mégaoctets. Volontairement minimal (et testable hors Android, contrairement à
 * `android.util.JsonWriter`).
 */
internal class JsonStream(private val out: Writer) {
    /** Pour chaque objet ou tableau ouvert : vrai tant qu'aucun élément n'y a été écrit. */
    private val empty = ArrayDeque<Boolean>()
    private var afterName = false

    fun beginObject(): JsonStream = open('{')
    fun endObject(): JsonStream = close('}')
    fun beginArray(): JsonStream = open('[')
    fun endArray(): JsonStream = close(']')

    fun name(name: String): JsonStream {
        separate()
        writeString(name)
        out.write(':'.code)
        afterName = true
        return this
    }

    fun value(value: String?): JsonStream {
        separate()
        if (value == null) out.write("null") else writeString(value)
        return this
    }

    fun value(value: Boolean?): JsonStream {
        separate()
        out.write(value?.toString() ?: "null")
        return this
    }

    fun value(value: Number?): JsonStream {
        separate()
        out.write(numberToken(value) ?: "null")
        return this
    }

    /**
     * Nombre recopié tel qu'il est stocké (« 1.0 » reste « 1.0 »), pour que l'import retrouve exactement le même
     * texte. Ce qui n'est pas un nombre JSON valide (NaN, valeur vide…) est écrit comme une chaîne, sans perte.
     */
    fun rawNumber(text: String?): JsonStream {
        separate()
        when {
            text == null -> out.write("null")
            JsonPullReader.isJsonNumber(text) -> out.write(text)
            else -> writeString(text)
        }
        return this
    }

    fun field(name: String, value: String?): JsonStream = name(name).value(value)
    fun field(name: String, value: Boolean?): JsonStream = name(name).value(value)
    fun field(name: String, value: Number?): JsonStream = name(name).value(value)
    fun rawNumberField(name: String, text: String?): JsonStream = name(name).rawNumber(text)

    fun flush() = out.flush()

    private fun open(c: Char): JsonStream {
        separate()
        out.write(c.code)
        empty.addLast(true)
        return this
    }

    private fun close(c: Char): JsonStream {
        check(empty.isNotEmpty() && !afterName) { "JSON mal formé" }
        empty.removeLast()
        out.write(c.code)
        return this
    }

    /** Virgule avant chaque élément sauf le premier ; rien entre un nom et sa valeur. */
    private fun separate() {
        if (afterName) {
            afterName = false
            return
        }
        if (empty.isEmpty()) return
        if (!empty.last()) out.write(','.code)
        empty[empty.lastIndex] = false
    }

    private fun writeString(s: String) {
        out.write('"'.code)
        for (c in s) {
            when (c) {
                '"' -> out.write("\\\"")
                '\\' -> out.write("\\\\")
                '\n' -> out.write("\\n")
                '\r' -> out.write("\\r")
                '\t' -> out.write("\\t")
                else -> if (c < ' ') out.write("\\u%04x".format(c.code)) else out.write(c.code)
            }
        }
        out.write('"'.code)
    }

    private fun numberToken(n: Number?): String? = when (n) {
        null -> null
        is Double -> n.takeIf { it.isFinite() }?.let { if (it == Math.rint(it) && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        // Float.toString garde la valeur lisible (36.5 et non 36.5000000001 après conversion en Double).
        is Float -> n.takeIf { it.isFinite() }?.let { if (it == Math.rint(it.toDouble()).toFloat() && kotlin.math.abs(it) < 1e7f) it.toLong().toString() else it.toString() }
        else -> n.toString()
    }
}
