package com.boucle.app.data.backup

import com.boucle.app.data.export.BackupFormatException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Arrays
import java.util.Objects
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/*
 * Sauvegardes chiffrées par une phrase de passe, sans dépendance Android (testé sur la JVM).
 *
 * Format (version 1), décrit en détail dans docs/format-sauvegarde.md :
 *
 *   en-tête (60 octets, entiers big-endian)
 *     0  "BOUCLEENC"               9 octets, ASCII
 *     9  version                   1 octet = 1
 *    10  KDF                       1 octet = 1 (PBKDF2-HMAC-SHA256)
 *    11  itérations                4 octets
 *    15  sel                       16 octets
 *    31  chiffrement               1 octet = 1 (AES-256-GCM par segments)
 *    32  taille d'un segment clair 4 octets (65 536)
 *    36  préfixe de nonce          8 octets aléatoires, propres au fichier
 *    44  contrôle de clé           16 octets = HMAC-SHA256(clé, "BOUCLEENC v1 key check")[0..16)
 *   puis les segments : AES-256-GCM(clé, nonce = préfixe ‖ compteur sur 4 octets, AAD = en-tête ‖ dernier)
 *   où « dernier » vaut 1 pour le dernier segment et 0 sinon. Chaque segment chiffré fait la taille d'un segment
 *   clair + 16 octets d'étiquette, sauf le dernier (1 octet clair au moins, 0 seulement pour un contenu vide).
 *
 * Le chiffrement par segments borne la mémoire (le GCM d'Android garde tout le texte clair jusqu'à la vérification
 * de l'étiquette) ; le compteur dans le nonce interdit de réordonner les segments, l'en-tête dans l'AAD lie chaque
 * segment à ce fichier, et l'indicateur « dernier » détecte un fichier coupé à une frontière de segment.
 */

/** La phrase de passe ne correspond pas à cette sauvegarde (contrôle de clé différent). */
class WrongPassphraseException : BackupFormatException(BackupCrypto.WRONG_PASSPHRASE_MESSAGE)

/** Clé dérivée d'une phrase de passe, avec le sel et le nombre d'itérations qui l'ont produite. */
class BackupKey internal constructor(salt: ByteArray, val iterations: Int, keyBytes: ByteArray) : Closeable {
    private val saltBytes = salt.copyOf()
    private val bytes = keyBytes.copyOf()

    @Volatile
    private var destroyed = false

    val salt: ByteArray get() = saltBytes.copyOf()

    /** Copie des octets de la clé ; à effacer par l'appelant après usage. */
    fun encoded(): ByteArray {
        check(!destroyed) { "clé effacée" }
        return bytes.copyOf()
    }

    internal fun saltEquals(other: ByteArray) = MessageDigest.isEqual(saltBytes, other)

    /** Remplit la clé de zéros ; elle n'est plus utilisable ensuite. */
    override fun close() {
        destroyed = true
        bytes.fill(0)
    }

    companion object {
        /** Reconstruit une clé déjà dérivée (clé stockée de la sauvegarde automatique) ; [keyBytes] est copié. */
        fun fromRaw(salt: ByteArray, iterations: Int, keyBytes: ByteArray): BackupKey {
            require(salt.size == BackupCrypto.SALT_SIZE) { "sel de ${BackupCrypto.SALT_SIZE} octets attendu" }
            require(keyBytes.size == BackupCrypto.KEY_SIZE) { "clé de ${BackupCrypto.KEY_SIZE} octets attendue" }
            return BackupKey(salt, iterations, keyBytes)
        }
    }
}

/** En-tête d'un fichier chiffré, relu par [BackupCrypto.readHeader]. */
class EncryptedHeader internal constructor(
    internal val raw: ByteArray,
    val iterations: Int,
    internal val salt: ByteArray,
    val segmentSize: Int,
    internal val noncePrefix: ByteArray,
    internal val keyCheck: ByteArray,
)

object BackupCrypto {
    /** Premiers octets de tout fichier chiffré. */
    val MAGIC: ByteArray = "BOUCLEENC".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2_HMAC_SHA256 = 1
    const val CIPHER_AES256_GCM_SEGMENTS = 1

    /** Itérations PBKDF2 des nouvelles clés (recommandation OWASP 2023 pour PBKDF2-HMAC-SHA256). */
    const val ITERATIONS = 600_000
    const val SALT_SIZE = 16
    const val KEY_SIZE = 32
    const val SEGMENT_SIZE = 64 * 1024
    const val NONCE_PREFIX_SIZE = 8
    const val TAG_SIZE = 16
    const val KEY_CHECK_SIZE = 16
    const val HEADER_SIZE = 9 + 1 + 1 + 4 + SALT_SIZE + 1 + 4 + NONCE_PREFIX_SIZE + KEY_CHECK_SIZE

    /** Longueur minimale d'une phrase de passe, en caractères. */
    const val MIN_PASSPHRASE_LENGTH = 10

    // Bornes acceptées à la lecture : un en-tête modifié ne doit pas bloquer l'app des heures ni l'épuiser en mémoire.
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val MIN_SEGMENT = 1024
    private const val MAX_SEGMENT = 4 * 1024 * 1024
    private const val MAX_SEGMENTS = 0xFFFF_FFFFL

    private val KEY_CHECK_LABEL = "BOUCLEENC v1 key check".toByteArray(Charsets.US_ASCII)
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val WRONG_PASSPHRASE_MESSAGE = "Phrase de passe incorrecte."
    const val CORRUPT_MESSAGE = "Fichier chiffré corrompu ou incomplet : il ne peut pas être déchiffré."
    const val NEWER_VERSION_MESSAGE =
        "Cette sauvegarde chiffrée vient d'une version plus récente de Boucle : mets l'app à jour pour l'importer."

    private val random: SecureRandom by lazy { SecureRandom() }

    /** Nouveau sel aléatoire. */
    fun newSalt(): ByteArray = ByteArray(SALT_SIZE).also { random.nextBytes(it) }

    /**
     * Dérive la clé de [passphrase] (PBKDF2-HMAC-SHA256 sur l'UTF-8 de la phrase normalisée NFC). Lent exprès
     * (environ une seconde) : à appeler hors du fil principal. [passphrase] n'est pas modifié ; l'appelant l'efface.
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): BackupKey {
        require(salt.size == SALT_SIZE) { "sel de $SALT_SIZE octets attendu" }
        val normalized = normalize(passphrase)
        val spec = PBEKeySpec(normalized, salt, iterations, KEY_SIZE * 8)
        try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return BackupKey(salt, iterations, encoded)
            } finally {
                encoded.fill(0)
            }
        } finally {
            spec.clearPassword()
            if (normalized !== passphrase) normalized.fill('\u0000')
        }
    }

    /**
     * Même texte, même clé, quel que soit le clavier : « é » saisi d'un bloc ou en « e » + accent. La phrase n'est
     * convertie en String (non effaçable) que si elle n'est pas déjà normalisée, ce qui est rare.
     */
    private fun normalize(passphrase: CharArray): CharArray {
        if (Normalizer.isNormalized(CharBuffer.wrap(passphrase), Normalizer.Form.NFC)) return passphrase
        return Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
    }

    /** Vrai si [prefix] (les premiers octets d'un fichier) commence par [MAGIC]. */
    fun hasMagic(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    /** Lit les premiers octets de [input] (sans le fermer) : vrai pour un fichier chiffré par Boucle. */
    fun isEncrypted(input: InputStream): Boolean {
        val prefix = ByteArray(MAGIC.size)
        return readFully(input, prefix, 0, prefix.size) == prefix.size && hasMagic(prefix)
    }

    /**
     * Flux qui chiffre ce qu'on y écrit vers [out] avec [key]. L'en-tête est écrit tout de suite. [EncryptingOutputStream.finish]
     * écrit le dernier segment sans fermer [out] ; `close()` termine puis ferme [out]. Sans l'un ou l'autre, le
     * fichier est incomplet et sera refusé au déchiffrement.
     */
    fun encryptingStream(
        out: OutputStream,
        key: BackupKey,
        segmentSize: Int = SEGMENT_SIZE,
        random: SecureRandom = this.random,
    ): EncryptingOutputStream {
        require(segmentSize in MIN_SEGMENT..MAX_SEGMENT) { "taille de segment hors bornes" }
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also { random.nextBytes(it) }
        val header = buildHeader(key, segmentSize, prefix)
        return EncryptingOutputStream(out, keySpec(key), header, prefix, segmentSize)
    }

    /**
     * Lit et vérifie l'en-tête de [input] (sans le fermer). Lève [BackupFormatException] si ce n'est pas un fichier
     * chiffré par Boucle ou s'il est d'une version inconnue.
     */
    fun readHeader(input: InputStream): EncryptedHeader {
        val raw = ByteArray(HEADER_SIZE)
        val n = readFully(input, raw, 0, raw.size)
        if (n < MAGIC.size || !hasMagic(raw)) throw BackupFormatException("Ce fichier n'est pas une sauvegarde chiffrée de Boucle.")
        if (n < HEADER_SIZE) throw BackupFormatException(CORRUPT_MESSAGE)
        val b = ByteBuffer.wrap(raw)
        b.position(MAGIC.size)
        val version = b.get().toInt() and 0xFF
        if (version != VERSION) throw BackupFormatException(if (version > VERSION) NEWER_VERSION_MESSAGE else CORRUPT_MESSAGE)
        val kdf = b.get().toInt() and 0xFF
        val iterations = b.int
        val salt = ByteArray(SALT_SIZE).also { b.get(it) }
        val cipher = b.get().toInt() and 0xFF
        val segmentSize = b.int
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also { b.get(it) }
        val keyCheck = ByteArray(KEY_CHECK_SIZE).also { b.get(it) }
        if (kdf != KDF_PBKDF2_HMAC_SHA256 || cipher != CIPHER_AES256_GCM_SEGMENTS ||
            iterations !in MIN_ITERATIONS..MAX_ITERATIONS || segmentSize !in MIN_SEGMENT..MAX_SEGMENT
        ) {
            throw BackupFormatException(CORRUPT_MESSAGE)
        }
        return EncryptedHeader(raw, iterations, salt, segmentSize, prefix, keyCheck)
    }

    /** Dérive la clé de [passphrase] avec le sel et les itérations de [header] (une seule fois par import). */
    fun deriveKey(passphrase: CharArray, header: EncryptedHeader): BackupKey =
        deriveKey(passphrase, header.salt.copyOf(), header.iterations)

    /** Vrai si [key] est celle de [header] (même sel, mêmes itérations et contrôle de clé identique). */
    fun matches(key: BackupKey, header: EncryptedHeader): Boolean =
        key.iterations == header.iterations && key.saltEquals(header.salt) &&
            MessageDigest.isEqual(keyCheck(key), header.keyCheck)

    /**
     * Flux déchiffré de [input] avec une clé déjà dérivée. Lève [WrongPassphraseException] si la clé ne correspond
     * pas, puis, à la lecture, [BackupFormatException] ([CORRUPT_MESSAGE]) dès qu'un segment est modifié, déplacé ou
     * manquant. Les octets rendus avant l'erreur viennent de segments authentifiés, mais le fichier n'est valide
     * que lu jusqu'au bout sans erreur. Fermer ce flux ferme [input].
     */
    fun decryptingStream(input: InputStream, key: BackupKey): InputStream {
        val header = readHeader(input)
        if (!matches(key, header)) throw WrongPassphraseException()
        return DecryptingInputStream(input, keySpec(key), header)
    }

    /** Comme [decryptingStream] avec une clé, en la dérivant de [passphrase] (lent : une dérivation par appel). */
    fun decryptingStream(input: InputStream, passphrase: CharArray): InputStream {
        val header = readHeader(input)
        deriveKey(passphrase, header).use { key ->
            if (!matches(key, header)) throw WrongPassphraseException()
            return DecryptingInputStream(input, keySpec(key), header)
        }
    }

    private fun buildHeader(key: BackupKey, segmentSize: Int, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC)
            .put(VERSION.toByte())
            .put(KDF_PBKDF2_HMAC_SHA256.toByte())
            .putInt(key.iterations)
            .put(key.salt)
            .put(CIPHER_AES256_GCM_SEGMENTS.toByte())
            .putInt(segmentSize)
            .put(prefix)
            .put(keyCheck(key))
            .array()

    private fun keyCheck(key: BackupKey): ByteArray {
        val raw = key.encoded()
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(raw, "HmacSHA256"))
            return mac.doFinal(KEY_CHECK_LABEL).copyOf(KEY_CHECK_SIZE)
        } finally {
            raw.fill(0)
        }
    }

    // SecretKeySpec garde sa propre copie, qu'on ne peut pas effacer (destroy() n'est pas implémenté) : limite connue.
    private fun keySpec(key: BackupKey): SecretKeySpec {
        val raw = key.encoded()
        try {
            return SecretKeySpec(raw, "AES")
        } finally {
            raw.fill(0)
        }
    }

    internal fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_PREFIX_SIZE + 4).put(prefix).putInt(counter.toInt()).array()

    internal fun aad(header: ByteArray, last: Boolean): ByteArray =
        header.copyOf(header.size + 1).also { it[header.size] = if (last) 1 else 0 }

    internal fun newCipher(): Cipher = Cipher.getInstance(TRANSFORMATION)

    internal fun checkCounter(counter: Long) {
        if (counter > MAX_SEGMENTS) throw IOException("fichier trop volumineux pour ce format")
    }

    /** Lit jusqu'à [len] octets ; moins seulement à la fin du flux. */
    internal fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

/** Flux chiffrant de [BackupCrypto.encryptingStream] : rien n'est écrit en clair, seul un segment est en mémoire. */
class EncryptingOutputStream internal constructor(
    private val out: OutputStream,
    private val key: SecretKeySpec,
    private val header: ByteArray,
    private val prefix: ByteArray,
    private val segmentSize: Int,
) : OutputStream() {
    private val cipher = BackupCrypto.newCipher()
    private val buffer = ByteArray(segmentSize)
    private val sealed = ByteArray(segmentSize + BackupCrypto.TAG_SIZE)
    private var length = 0
    private var counter = 0L
    private var finished = false

    init {
        out.write(header)
    }

    override fun write(b: Int) {
        ensureOpen()
        // Un segment plein n'est écrit qu'à l'arrivée de l'octet suivant : on sait alors qu'il n'est pas le dernier.
        if (length == segmentSize) seal(last = false)
        buffer[length++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        Objects.checkFromIndexSize(off, len, b.size)
        var o = off
        var remaining = len
        while (remaining > 0) {
            if (length == segmentSize) seal(last = false)
            val n = minOf(remaining, segmentSize - length)
            System.arraycopy(b, o, buffer, length, n)
            length += n
            o += n
            remaining -= n
        }
    }

    /** Ne vide que les segments déjà chiffrés : le segment en cours attend d'être plein ou [finish]. */
    override fun flush() {
        out.flush()
    }

    /** Écrit le dernier segment et vide [out] sans le fermer. Sans effet la deuxième fois. */
    fun finish() {
        if (finished) return
        seal(last = true)
        finished = true
        buffer.fill(0)
        out.flush()
    }

    override fun close() {
        try {
            finish()
        } finally {
            out.close()
        }
    }

    private fun ensureOpen() {
        if (finished) throw IOException("flux chiffré déjà terminé")
    }

    private fun seal(last: Boolean) {
        BackupCrypto.checkCounter(counter)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.nonce(prefix, counter)))
            cipher.updateAAD(BackupCrypto.aad(header, last))
            val n = cipher.doFinal(buffer, 0, length, sealed, 0)
            out.write(sealed, 0, n)
        } catch (e: GeneralSecurityException) {
            throw IOException("chiffrement impossible", e)
        }
        counter++
        length = 0
    }
}

/** Flux déchiffrant : lit un segment chiffré d'avance pour savoir s'il est le dernier. */
private class DecryptingInputStream(
    private val input: InputStream,
    private val key: SecretKeySpec,
    private val header: EncryptedHeader,
) : InputStream() {
    private val cipher = BackupCrypto.newCipher()
    private val sealedSize = header.segmentSize + BackupCrypto.TAG_SIZE
    private val sealed = ByteArray(sealedSize)
    private val plain = ByteArray(header.segmentSize)
    private var position = 0
    private var limit = 0
    private var counter = 0L
    private var lookahead = -1
    private var done = false
    private var closed = false

    override fun read(): Int {
        if (!fill()) return -1
        return plain[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        Objects.checkFromIndexSize(off, len, b.size)
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, limit - position)
        System.arraycopy(plain, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = limit - position

    override fun close() {
        if (closed) return
        closed = true
        plain.fill(0)
        input.close()
    }

    /** Vrai s'il reste des octets à rendre, en déchiffrant le segment suivant au besoin. */
    private fun fill(): Boolean {
        if (closed) throw IOException("flux fermé")
        while (position == limit) {
            if (done) return false
            openNextSegment()
        }
        return true
    }

    private fun openNextSegment() {
        var n = 0
        if (lookahead >= 0) {
            sealed[0] = lookahead.toByte()
            lookahead = -1
            n = 1
        }
        n += BackupCrypto.readFully(input, sealed, n, sealedSize - n)
        val last = if (n < sealedSize) {
            true
        } else {
            val next = input.read()
            if (next < 0) true else {
                lookahead = next
                false
            }
        }
        // Un segment n'est jamais plus court que son étiquette ; vide seulement pour un contenu vide.
        if (n < BackupCrypto.TAG_SIZE || (n == BackupCrypto.TAG_SIZE && counter > 0)) throw corrupt()
        BackupCrypto.checkCounter(counter)
        try {
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(BackupCrypto.TAG_SIZE * 8, BackupCrypto.nonce(header.noncePrefix, counter)),
            )
            cipher.updateAAD(BackupCrypto.aad(header.raw, last))
            limit = cipher.doFinal(sealed, 0, n, plain, 0)
        } catch (e: AEADBadTagException) {
            throw corrupt()
        } catch (e: GeneralSecurityException) {
            throw corrupt()
        }
        position = 0
        counter++
        if (last) done = true
    }

    private fun corrupt() = BackupFormatException(BackupCrypto.CORRUPT_MESSAGE)
}

/** Efface une saisie de phrase de passe. */
fun CharArray.wipe() = Arrays.fill(this, '\u0000')
