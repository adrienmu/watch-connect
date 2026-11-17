package com.boucle.app.data.backup

import com.boucle.app.data.export.BackupFormatException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random

class EncryptedStreamsTest {

    private val seg = BackupCrypto.SEGMENT_SIZE
    private val sealedSeg = seg + BackupCrypto.TAG_SIZE
    private val header = BackupCrypto.HEADER_SIZE

    /** Clé fixe sans dérivation (rapide) ; la dérivation elle-même est testée à part. */
    private val key = BackupKey.fromRaw(ByteArray(16) { it.toByte() }, BackupCrypto.ITERATIONS, ByteArray(32) { (it * 7).toByte() })

    private fun data(size: Int, seed: Long = size.toLong()) = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun encrypt(plain: ByteArray, k: BackupKey = key, chunk: Int = 8191): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = BackupCrypto.encryptingStream(out, k)
        var off = 0
        while (off < plain.size) {
            val n = minOf(chunk, plain.size - off)
            enc.write(plain, off, n)
            off += n
        }
        enc.finish()
        return out.toByteArray()
    }

    private fun decrypt(file: ByteArray, k: BackupKey = key): ByteArray =
        BackupCrypto.decryptingStream(ByteArrayInputStream(file), k).use { it.readBytes() }

    private fun assertCorrupt(file: ByteArray, k: BackupKey = key) {
        try {
            decrypt(file, k)
            fail("fichier modifié accepté")
        } catch (e: WrongPassphraseException) {
            fail("signalé comme mauvaise phrase de passe au lieu de fichier corrompu")
        } catch (e: BackupFormatException) {
            assertEquals(BackupCrypto.CORRUPT_MESSAGE, e.message)
        }
    }

    private fun expectedSize(plain: Int): Int {
        val segments = if (plain == 0) 1 else (plain + seg - 1) / seg
        return header + plain + segments * BackupCrypto.TAG_SIZE
    }

    @Test
    fun `aller-retour sur plusieurs tailles`() {
        for (size in listOf(0, 1, seg - 1, seg, seg + 1, 2 * seg, 3 * 1024 * 1024 + 17)) {
            val plain = data(size)
            val file = encrypt(plain)
            assertEquals("taille du fichier pour $size octets", expectedSize(size), file.size)
            assertArrayEquals("contenu pour $size octets", plain, decrypt(file))
        }
    }

    @Test
    fun `écriture octet par octet et lecture octet par octet`() {
        val plain = data(seg + 3)
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, key).use { enc -> plain.forEach { enc.write(it.toInt()) } }
        val read = ByteArrayOutputStream()
        BackupCrypto.decryptingStream(ByteArrayInputStream(out.toByteArray()), key).use { input ->
            while (true) {
                val b = input.read()
                if (b < 0) break
                read.write(b)
            }
        }
        assertArrayEquals(plain, read.toByteArray())
    }

    @Test
    fun `flux source lu par petits morceaux`() {
        val plain = data(2 * seg + 5)
        val file = encrypt(plain)
        val trickle = object : InputStream() {
            val inner = ByteArrayInputStream(file)
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, 7))
        }
        assertArrayEquals(plain, BackupCrypto.decryptingStream(trickle, key).use { it.readBytes() })
    }

    @Test
    fun `deux chiffrements du même contenu diffèrent`() {
        val plain = data(1000)
        val a = encrypt(plain)
        val b = encrypt(plain)
        assertFalse(a.contentEquals(b))
        // Le préfixe de nonce (octets 36 à 43) est tiré au hasard pour chaque fichier.
        assertFalse(a.copyOfRange(36, 44).contentEquals(b.copyOfRange(36, 44)))
        assertArrayEquals(plain, decrypt(a))
        assertArrayEquals(plain, decrypt(b))
    }

    @Test
    fun `mauvaise phrase de passe signalée comme telle`() {
        val salt = BackupCrypto.newSalt()
        val good = "cheval agrafe correcte".toCharArray()
        val file = BackupCrypto.deriveKey(good, salt).use { encrypt(data(5000), it) }

        val parsed = BackupCrypto.readHeader(ByteArrayInputStream(file))
        val wrong = BackupCrypto.deriveKey("cheval agrafe incorrecte".toCharArray(), parsed)
        assertFalse(BackupCrypto.matches(wrong, parsed))
        try {
            decrypt(file, wrong)
            fail("mauvaise phrase de passe acceptée")
        } catch (e: WrongPassphraseException) {
            assertEquals("Phrase de passe incorrecte.", e.message)
        }
        // La bonne phrase de passe, rederivée depuis l'en-tête, déchiffre.
        val again = BackupCrypto.deriveKey(good, parsed)
        assertTrue(BackupCrypto.matches(again, parsed))
        assertEquals(5000, decrypt(file, again).size)
        // Variante qui dérive elle-même la clé.
        assertEquals(5000, BackupCrypto.decryptingStream(ByteArrayInputStream(file), good).use { it.readBytes() }.size)
    }

    @Test
    fun `phrase de passe normalisée NFC`() {
        val salt = BackupCrypto.newSalt()
        val composed = BackupCrypto.deriveKey("éléphant rose 42".toCharArray(), salt)
        val decomposed = BackupCrypto.deriveKey("éléphant rose 42".toCharArray(), salt)
        assertArrayEquals(composed.encoded(), decomposed.encoded())
    }

    @Test
    fun `dérivation PBKDF2 - temps mesuré`() {
        val start = System.nanoTime()
        val k = BackupCrypto.deriveKey("une phrase de passe de test".toCharArray())
        val ms = (System.nanoTime() - start) / 1_000_000
        println("PBKDF2-HMAC-SHA256, ${BackupCrypto.ITERATIONS} itérations sur la JVM : $ms ms")
        assertEquals(BackupCrypto.ITERATIONS, k.iterations)
        assertEquals(BackupCrypto.KEY_SIZE, k.encoded().size)
        k.close()
        try {
            k.encoded()
            fail("clé effacée encore lisible")
        } catch (e: IllegalStateException) {
            // attendu
        }
    }

    @Test
    fun `troncature à une frontière de segment détectée`() {
        val file = encrypt(data(3 * seg))
        assertEquals(header + 3 * sealedSeg, file.size)
        assertCorrupt(file.copyOf(header + sealedSeg))
        assertCorrupt(file.copyOf(header + 2 * sealedSeg))
        // Fichier réduit à son en-tête.
        assertCorrupt(file.copyOf(header))
    }

    @Test
    fun `troncature n'importe où détectée`() {
        val file = encrypt(data(2 * seg + 100))
        for (cut in listOf(header + 1, header + 15, header + 16, header + sealedSeg - 1, header + sealedSeg + 1, file.size - 1)) {
            assertCorrupt(file.copyOf(cut))
        }
        // Contenu vide : seul l'unique segment final (l'étiquette) peut être coupé.
        val empty = encrypt(ByteArray(0))
        assertCorrupt(empty.copyOf(empty.size - 1))
    }

    @Test
    fun `octets ajoutés après le dernier segment détectés`() {
        val file = encrypt(data(seg))
        assertCorrupt(file + byteArrayOf(0))
        assertCorrupt(file + file.copyOfRange(header, file.size))
    }

    @Test
    fun `segment modifié détecté`() {
        val file = encrypt(data(2 * seg + 10))
        for (pos in listOf(header, header + 100, header + sealedSeg - 1, header + sealedSeg + 5, file.size - 1)) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 1).toByte()
            assertCorrupt(copy)
        }
    }

    @Test
    fun `segments permutés détectés`() {
        val file = encrypt(data(3 * seg))
        val s0 = file.copyOfRange(header, header + sealedSeg)
        val s1 = file.copyOfRange(header + sealedSeg, header + 2 * sealedSeg)
        val swapped = file.copyOfRange(0, header) + s1 + s0 + file.copyOfRange(header + 2 * sealedSeg, file.size)
        assertEquals(file.size, swapped.size)
        assertCorrupt(swapped)
        // Segment d'un autre fichier (même clé) : refusé, l'en-tête est dans l'AAD et le préfixe de nonce diffère.
        val other = encrypt(data(3 * seg, seed = 99))
        val mixed = file.copyOfRange(0, header + sealedSeg) + other.copyOfRange(header + sealedSeg, other.size)
        assertCorrupt(mixed)
    }

    @Test
    fun `en-tête modifié détecté`() {
        val file = encrypt(data(seg + 1))
        // Préfixe de nonce (36..43) : clé correcte, segments refusés.
        for (pos in 36 until 44) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 0x80).toByte()
            assertCorrupt(copy)
        }
        // Taille de segment (32..35) : segments refusés ou taille hors bornes.
        for (pos in 32 until 36) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 1).toByte()
            assertCorrupt(copy)
        }
        // Type de KDF ou de chiffrement inconnus.
        for (pos in listOf(10, 31)) {
            val copy = file.copyOf()
            copy[pos] = 7
            assertCorrupt(copy)
        }
        // Sel, itérations ou contrôle de clé modifiés : la clé ne correspond plus (comme une autre phrase de passe).
        for (pos in listOf(13, 20, 50)) {
            val copy = file.copyOf()
            copy[pos] = (copy[pos].toInt() xor 1).toByte()
            try {
                decrypt(copy)
                fail("en-tête modifié accepté (octet $pos)")
            } catch (e: BackupFormatException) {
                // WrongPassphraseException ou fichier corrompu : jamais de contenu rendu.
            }
        }
    }

    @Test
    fun `version plus récente refusée avec un message clair`() {
        val file = encrypt(data(10))
        file[9] = 2
        try {
            decrypt(file)
            fail("version inconnue acceptée")
        } catch (e: BackupFormatException) {
            assertEquals(BackupCrypto.NEWER_VERSION_MESSAGE, e.message)
        }
    }

    @Test
    fun `détection du magic`() {
        val file = encrypt(data(10))
        assertTrue(BackupCrypto.isEncrypted(ByteArrayInputStream(file)))
        assertEquals("BOUCLEENC", String(file.copyOf(9), Charsets.US_ASCII))
        assertEquals(1, file[9].toInt())
        assertFalse(BackupCrypto.isEncrypted(ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0, 0, 0, 0, 0))))
        assertFalse(BackupCrypto.isEncrypted(ByteArrayInputStream("BOUCLE".toByteArray())))
        assertFalse(BackupCrypto.isEncrypted(ByteArrayInputStream(ByteArray(0))))
        try {
            BackupCrypto.readHeader(ByteArrayInputStream("pas chiffré du tout, mais assez long pour un en-tête".toByteArray()))
            fail("fichier en clair pris pour un fichier chiffré")
        } catch (e: BackupFormatException) {
            assertNotEquals(BackupCrypto.WRONG_PASSPHRASE_MESSAGE, e.message)
        }
    }

    @Test
    fun `en-tête relu tel qu'écrit`() {
        val file = encrypt(data(1))
        val h = BackupCrypto.readHeader(ByteArrayInputStream(file))
        assertEquals(BackupCrypto.ITERATIONS, h.iterations)
        assertEquals(seg, h.segmentSize)
        assertTrue(BackupCrypto.matches(key, h))
        assertEquals(60, BackupCrypto.HEADER_SIZE)
    }

    @Test
    fun `flux terminé refuse d'autres écritures et close termine le fichier`() {
        val out = ByteArrayOutputStream()
        val enc = BackupCrypto.encryptingStream(out, key)
        enc.write(byteArrayOf(1, 2, 3))
        enc.close()
        enc.close()
        assertArrayEquals(byteArrayOf(1, 2, 3), decrypt(out.toByteArray()))
        try {
            enc.write(4)
            fail("écriture après la fin acceptée")
        } catch (e: java.io.IOException) {
            // attendu
        }
    }
}
