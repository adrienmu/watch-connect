package com.boucle.app.data.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.boucle.app.data.BackupEncryptionSettings
import com.boucle.app.data.Settings
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La clé des sauvegardes chiffrées n'est pas disponible sur ce téléphone : il faut ressaisir la phrase de passe. */
class BackupKeyUnavailableException :
    IOException("phrase de passe à ressaisir : la clé de chiffrement n'est pas disponible sur ce téléphone, rien n'a été écrit")

/**
 * Garde la clé des sauvegardes chiffrées pour la sauvegarde automatique, qui tourne sans l'utilisateur.
 *
 * La phrase de passe n'est jamais enregistrée. La clé qui en est dérivée (PBKDF2, avec son sel et ses itérations)
 * est chiffrée en AES-256-GCM par une clé du Keystore Android, non exportable, créée sur ce téléphone et sans
 * authentification (le travail tourne en arrière-plan). Le résultat est rangé dans les réglages.
 *
 * Cette clé du Keystore ne voyage pas avec le transfert d'appareil à appareil, et c'est voulu : sur un autre
 * téléphone (ou si le Keystore a perdu la clé), [unlock] renvoie `null`, la sauvegarde automatique échoue proprement
 * ([BackupKeyUnavailableException]) et l'écran Appareils demande la phrase de passe.
 */
class BackupKeyStore(private val settings: Settings) {

    /** Chiffre les prochaines sauvegardes avec [key] (déjà dérivée de la phrase de passe). [key] n'est pas effacée. */
    suspend fun save(key: BackupKey) {
        val salt = key.salt
        val raw = key.encoded()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
            cipher.updateAAD(aad(salt, key.iterations))
            val sealed = cipher.doFinal(raw)
            val iv = cipher.iv
            val blob = ByteBuffer.allocate(1 + iv.size + sealed.size).put(iv.size.toByte()).put(iv).put(sealed).array()
            settings.saveBackupEncryption(b64(salt), key.iterations, b64(blob))
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Clé des sauvegardes, ou `null` si elle ne peut pas être relue sur ce téléphone (transfert d'appareil, clé du
     * Keystore supprimée ou invalidée, réglages abîmés). L'appelant ferme la clé après usage.
     */
    fun unlock(stored: BackupEncryptionSettings): BackupKey? {
        val salt = stored.salt?.let(::unb64) ?: return null
        val iterations = stored.iterations ?: return null
        val blob = stored.wrappedKey?.let(::unb64) ?: return null
        return try {
            val keystoreKey = wrappingKey(create = false) ?: return null
            val b = ByteBuffer.wrap(blob)
            val iv = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
            val sealed = ByteArray(b.remaining()).also { b.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(salt, iterations))
            val raw = cipher.doFinal(sealed)
            try {
                BackupKey.fromRaw(salt, iterations, raw)
            } finally {
                raw.fill(0)
            }
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException, AEADBadTagException, UnrecoverableKeyException, données tronquées…
            null
        }
    }

    /** Vrai si la clé stockée se relit sur ce téléphone (la clé relue est aussitôt effacée). */
    fun canUnlock(stored: BackupEncryptionSettings): Boolean = unlock(stored)?.use { true } ?: false

    /** Sauvegardes en clair désormais : efface la clé stockée et celle du Keystore. */
    suspend fun clear() {
        settings.clearBackupEncryption()
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (e: Exception) {
            // Déjà absente : rien à faire.
        }
    }

    private fun wrappingKey(create: Boolean): SecretKey? {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Lie la clé chiffrée à son sel et à ses itérations : un réglage modifié ne se déchiffre pas. */
    private fun aad(salt: ByteArray, iterations: Int): ByteArray =
        ByteBuffer.allocate(AAD_LABEL.size + salt.size + 4).put(AAD_LABEL).put(salt).putInt(iterations).array()

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "boucle-backup-key-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val AAD_LABEL = "boucle-backup-key v1".toByteArray(Charsets.US_ASCII)
    }
}
