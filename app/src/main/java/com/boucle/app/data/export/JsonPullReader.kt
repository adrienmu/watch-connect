package com.boucle.app.data.export

import java.io.Reader

/**
 * Lecture JSON en flux, symétrique de [JsonStream] : un élément à la fois, sans charger le fichier entier (les
 * séries de FC et d'intervalles pouls à pouls peuvent peser plusieurs dizaines de mégaoctets). Volontairement
 * minimal et testable hors Android, contrairement à `android.util.JsonReader`. Les nombres sont rendus tels
 * qu'écrits ([nextNumberText]) pour que l'import retrouve exactement le texte d'origine.
 *
 * Toute erreur de syntaxe, y compris un fichier tronqué, lève [BackupFormatException].
 */
internal class JsonPullReader(private val input: Reader, private val maxStringLength: Int = MAX_STRING_LENGTH) {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

    private enum class Scope { EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_OBJECT, DANGLING_NAME, NONEMPTY_OBJECT }

    private val stack = ArrayDeque<Scope>()
    private var started = false
    private var peeked: Token? = null
    private var peekedText: String? = null
    private var pushedBack = -1
    private val buffer = CharArray(8192)
    private var pos = 0
    private var limit = 0

    fun peek(): Token {
        peeked?.let { return it }
        val token = when (stack.lastOrNull()) {
            null -> if (!started) {
                started = true
                readValue(nextNonWhitespace())
            } else {
                var c: Int
                do c = read() while (c != -1 && isWhitespace(c))
                if (c == -1) Token.END_DOCUMENT else fail("contenu après la fin des données")
            }
            Scope.EMPTY_ARRAY -> {
                val c = nextNonWhitespace()
                if (c == ']'.code) {
                    Token.END_ARRAY
                } else {
                    replaceTop(Scope.NONEMPTY_ARRAY)
                    readValue(c)
                }
            }
            Scope.NONEMPTY_ARRAY -> when (val c = nextNonWhitespace()) {
                ']'.code -> Token.END_ARRAY
                ','.code -> readValue(nextNonWhitespace())
                else -> unexpected(c)
            }
            Scope.EMPTY_OBJECT -> when (val c = nextNonWhitespace()) {
                '}'.code -> Token.END_OBJECT
                '"'.code -> readName()
                else -> unexpected(c)
            }
            Scope.NONEMPTY_OBJECT -> when (val c = nextNonWhitespace()) {
                '}'.code -> Token.END_OBJECT
                ','.code -> {
                    val next = nextNonWhitespace()
                    if (next != '"'.code) unexpected(next)
                    readName()
                }
                else -> unexpected(c)
            }
            Scope.DANGLING_NAME -> {
                val c = nextNonWhitespace()
                if (c != ':'.code) unexpected(c)
                replaceTop(Scope.NONEMPTY_OBJECT)
                readValue(nextNonWhitespace())
            }
        }
        peeked = token
        return token
    }

    fun hasNext(): Boolean = peek().let { it != Token.END_OBJECT && it != Token.END_ARRAY && it != Token.END_DOCUMENT }

    fun beginObject() {
        consume(Token.BEGIN_OBJECT)
        stack.addLast(Scope.EMPTY_OBJECT)
    }

    fun endObject() {
        consume(Token.END_OBJECT)
        stack.removeLast()
    }

    fun beginArray() {
        consume(Token.BEGIN_ARRAY)
        stack.addLast(Scope.EMPTY_ARRAY)
    }

    fun endArray() {
        consume(Token.END_ARRAY)
        stack.removeLast()
    }

    fun nextName(): String = consumeText(Token.NAME)
    fun nextString(): String = consumeText(Token.STRING)

    /** Texte du nombre tel qu'il est écrit dans le fichier (ex. « 36.5 », « 1.0E-4 »). */
    fun nextNumberText(): String = consumeText(Token.NUMBER)

    fun nextBoolean(): Boolean = consumeText(Token.BOOLEAN) == "true"

    fun nextNull() = consume(Token.NULL)

    /** Vérifie que le document est terminé, sans contenu après la dernière accolade. */
    fun endDocument() = consume(Token.END_DOCUMENT)

    /** Passe la valeur suivante, quelle qu'elle soit (champ inconnu d'une version compatible). */
    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                Token.BEGIN_OBJECT -> { beginObject(); depth++ }
                Token.BEGIN_ARRAY -> { beginArray(); depth++ }
                Token.END_OBJECT -> { endObject(); depth-- }
                Token.END_ARRAY -> { endArray(); depth-- }
                Token.END_DOCUMENT -> fail("fin des données inattendue")
                else -> { peeked = null; peekedText = null }
            }
        } while (depth > 0)
    }

    private fun consume(expected: Token) {
        val token = peek()
        if (token != expected) fail("${describe(expected)} attendu, ${describe(token)} trouvé")
        peeked = null
        peekedText = null
    }

    private fun consumeText(expected: Token): String {
        val text = peek().let { peekedText }
        consume(expected)
        return text!!
    }

    private fun replaceTop(scope: Scope) {
        stack[stack.lastIndex] = scope
    }

    private fun readName(): Token {
        peekedText = readString()
        replaceTop(Scope.DANGLING_NAME)
        return Token.NAME
    }

    private fun readValue(c: Int): Token = when {
        c == '{'.code -> Token.BEGIN_OBJECT
        c == '['.code -> Token.BEGIN_ARRAY
        c == '"'.code -> { peekedText = readString(); Token.STRING }
        c == 't'.code -> { expectLiteral("rue"); peekedText = "true"; Token.BOOLEAN }
        c == 'f'.code -> { expectLiteral("alse"); peekedText = "false"; Token.BOOLEAN }
        c == 'n'.code -> { expectLiteral("ull"); Token.NULL }
        c == '-'.code || c in '0'.code..'9'.code -> { peekedText = readNumber(c); Token.NUMBER }
        else -> unexpected(c)
    }

    private fun expectLiteral(rest: String) {
        for (expected in rest) {
            val c = read()
            if (c == -1) truncated()
            if (c != expected.code) unexpected(c)
        }
    }

    private fun readNumber(first: Int): String {
        val sb = StringBuilder().append(first.toChar())
        while (true) {
            val c = read()
            if (c == -1) break
            val ch = c.toChar()
            if (ch in '0'..'9' || ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                sb.append(ch)
                if (sb.length > 64) fail("nombre trop long")
            } else {
                pushedBack = c
                break
            }
        }
        val text = sb.toString()
        if (!isJsonNumber(text)) fail("nombre invalide « $text »")
        return text
    }

    private fun readString(): String {
        val sb = StringBuilder()
        while (true) {
            val c = read()
            if (c == -1) truncated()
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> {
                    val e = read()
                    if (e == -1) truncated()
                    when (e.toChar()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            var code = 0
                            repeat(4) {
                                val h = read()
                                if (h == -1) truncated()
                                val digit = Character.digit(h, 16)
                                if (digit < 0) fail("séquence \\u invalide")
                                code = code * 16 + digit
                            }
                            sb.append(code.toChar())
                        }
                        else -> fail("séquence d'échappement invalide")
                    }
                }
                else -> {
                    if (c < 0x20) fail("caractère de contrôle dans une chaîne")
                    sb.append(c.toChar())
                }
            }
            if (sb.length > maxStringLength) fail("valeur trop longue")
        }
    }

    private fun nextNonWhitespace(): Int {
        while (true) {
            val c = read()
            if (c == -1) truncated()
            if (!isWhitespace(c)) return c
        }
    }

    private fun isWhitespace(c: Int) = c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code

    private fun read(): Int {
        if (pushedBack != -1) {
            val c = pushedBack
            pushedBack = -1
            return c
        }
        if (pos >= limit) {
            limit = input.read(buffer, 0, buffer.size)
            pos = 0
            if (limit <= 0) {
                limit = 0
                return -1
            }
        }
        return buffer[pos++].code
    }

    private fun unexpected(c: Int): Nothing = fail("caractère inattendu « ${c.toChar()} »")

    private fun truncated(): Nothing = throw BackupFormatException(TRUNCATED_MESSAGE)

    private fun fail(detail: String): Nothing = throw BackupFormatException("$CORRUPT_MESSAGE ($detail).")

    private fun describe(token: Token) = when (token) {
        Token.BEGIN_OBJECT -> "objet"
        Token.END_OBJECT -> "fin d'objet"
        Token.BEGIN_ARRAY -> "liste"
        Token.END_ARRAY -> "fin de liste"
        Token.NAME -> "nom de champ"
        Token.STRING -> "texte"
        Token.NUMBER -> "nombre"
        Token.BOOLEAN -> "booléen"
        Token.NULL -> "null"
        Token.END_DOCUMENT -> "fin du fichier"
    }

    companion object {
        /** Assez pour les messages protobuf bruts des séances (encodés en base64), pas assez pour saturer la mémoire. */
        const val MAX_STRING_LENGTH = 32 * 1024 * 1024

        const val TRUNCATED_MESSAGE = "La sauvegarde est incomplète : le fichier a été coupé avant la fin."
        const val CORRUPT_MESSAGE = "La sauvegarde est endommagée"

        private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

        /** Vrai si [text] est un nombre JSON valide, à écrire tel quel. */
        fun isJsonNumber(text: String): Boolean = NUMBER.matches(text)
    }
}

/** Sauvegarde illisible ou refusée ; le message, en français, est montré tel quel à l'utilisateur. */
class BackupFormatException(message: String) : java.io.IOException(message)
