package com.boucle.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SavedDevice(val id: String, val name: String)

/** Profil physique, gardé sur le téléphone pour les calculs (âge, sexe, FC max). */
data class UserProfile(
    val female: Boolean,
    val birthDate: LocalDate,
    val heightCm: Float,
    val weightKg: Float,
    /** FC max saisie dans le profil ; peut n'être qu'une estimation. */
    val maxHr: Int?,
) {
    fun ageOn(date: LocalDate): Int = java.time.Period.between(birthDate, date).years
}

data class AppSettings(
    val device: SavedDevice? = null,
    /** Dernier jour entièrement synchronisé (yyyy-MM-dd). */
    val lastSyncedDate: String? = null,
    /** Date et heure de la dernière synchronisation réussie, pour affichage. */
    val lastSyncAt: String? = null,
    val profile: UserProfile? = null,
    /** Date et heure de la dernière lecture Health Connect réussie, pour affichage. */
    val lastHealthConnectSyncAt: String? = null,
    /** Jour de la dernière lecture Health Connect réussie (yyyy-MM-dd), pour reprendre à partir de là. */
    val lastHealthConnectSyncedDate: String? = null,
    /** Vrai une fois l'écran de première ouverture passé (Bluetooth autorisé ou démo chargée). */
    val onboardingDone: Boolean = false,
    val backup: BackupSettings = BackupSettings(),
    /** Augmente à chaque modification des données (synchro, lecture Health Connect, import) : décide des sauvegardes. */
    val dataVersion: Long = 0,
    /**
     * Vrai depuis le chargement de la démo jusqu'à « Tout effacer » : la base contient des données fictives, que la
     * sauvegarde (automatique ou manuelle) ne doit ni copier ni laisser évincer les vraies sauvegardes du dossier.
     */
    val demoMode: Boolean = false,
)

/**
 * Activation de la sauvegarde automatique en attente du choix d'un dossier, enregistrée avant d'ouvrir le
 * sélecteur : elle survit à l'arrêt du processus pendant que le sélecteur du système est affiché.
 */
enum class PendingBackupEnable {
    /** Sauvegardes en clair, choisi explicitement. */
    PLAIN,

    /** Sauvegardes chiffrées : la clé est déjà enregistrée ([BackupEncryptionSettings]) avant l'ouverture du sélecteur. */
    ENCRYPTED,
}

/** Sauvegarde automatique dans un dossier choisi par l'utilisateur (voir `data/backup`). */
data class BackupSettings(
    /** URI du dossier (arborescence SAF) ; `null` quand la sauvegarde automatique est désactivée. */
    val folderUri: String? = null,
    /** Nom du dossier tel que le sélecteur l'affichait, pour l'écran Appareils. */
    val folderName: String? = null,
    /** Date et heure de la dernière sauvegarde réussie dans ce dossier (dd/MM/yyyy HH:mm). */
    val lastSuccessAt: String? = null,
    /** Nom du fichier de la dernière sauvegarde réussie. */
    val lastFile: String? = null,
    /** Échec de la dernière tentative (effacé à la sauvegarde réussie suivante). */
    val lastErrorAt: String? = null,
    val lastError: String? = null,
    /** [AppSettings.dataVersion] lu au début de la dernière sauvegarde réussie. */
    val lastDataVersion: Long? = null,
    /** Chiffrement des sauvegardes ; `null` quand elles sont écrites en clair. */
    val encryption: BackupEncryptionSettings? = null,
    /** Activation en attente d'un dossier (sélecteur ouvert) ; `null` sinon. */
    val pendingEnable: PendingBackupEnable? = null,
    /**
     * Sauvegarde en cours d'écriture sous son nom final (fournisseur sans renommage) et son dossier : si elle est
     * encore marquée au passage suivant, l'écriture a été interrompue et le fichier est incomplet.
     */
    val writingFile: String? = null,
    val writingFolder: String? = null,
) {
    val enabled: Boolean get() = folderUri != null
}

/**
 * Clé des sauvegardes chiffrées. La phrase de passe n'est jamais enregistrée : seule la clé qui en est dérivée l'est,
 * chiffrée par une clé non exportable du Keystore Android ([com.boucle.app.data.backup.BackupKeyStore]). Ce fichier de
 * réglages est copié par le transfert d'appareil à appareil, mais pas la clé du Keystore : sur le nouveau téléphone,
 * [wrappedKey] est indéchiffrable et la phrase de passe doit être ressaisie.
 *
 * Tant que [wrappedKey] manque ou ne se déchiffre pas, la sauvegarde automatique échoue au lieu d'écrire en clair.
 */
data class BackupEncryptionSettings(
    /** Sel PBKDF2 (base64), pas secret. */
    val salt: String?,
    val iterations: Int?,
    /** Clé dérivée chiffrée par le Keystore (base64) : IV ‖ texte chiffré AES-GCM. */
    val wrappedKey: String?,
)

/** Réglages repris d'une sauvegarde par [Settings.restoreIfEmpty]. */
data class RestoredSettings(val profile: Boolean = false, val device: Boolean = false, val sync: Boolean = false)

class Settings(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val id = prefs[DEVICE_ID]
        AppSettings(
            device = id?.let { SavedDevice(it, prefs[DEVICE_NAME] ?: it) },
            lastSyncedDate = prefs[LAST_SYNCED_DATE],
            lastSyncAt = prefs[LAST_SYNC_AT],
            lastHealthConnectSyncAt = prefs[LAST_HEALTH_CONNECT_SYNC_AT],
            lastHealthConnectSyncedDate = prefs[LAST_HEALTH_CONNECT_SYNCED_DATE],
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
            dataVersion = prefs[DATA_VERSION] ?: 0,
            demoMode = prefs[DEMO_MODE] ?: false,
            backup = BackupSettings(
                folderUri = prefs[BACKUP_FOLDER_URI],
                folderName = prefs[BACKUP_FOLDER_NAME],
                lastSuccessAt = prefs[BACKUP_LAST_SUCCESS_AT],
                lastFile = prefs[BACKUP_LAST_FILE],
                lastErrorAt = prefs[BACKUP_LAST_ERROR_AT],
                lastError = prefs[BACKUP_LAST_ERROR],
                lastDataVersion = prefs[BACKUP_LAST_DATA_VERSION],
                encryption = if (prefs[BACKUP_ENCRYPT] == true) {
                    BackupEncryptionSettings(prefs[BACKUP_KEY_SALT], prefs[BACKUP_KEY_ITERATIONS], prefs[BACKUP_KEY_WRAPPED])
                } else {
                    null
                },
                pendingEnable = prefs[BACKUP_PENDING_ENABLE]?.let { name ->
                    PendingBackupEnable.entries.firstOrNull { it.name == name }
                },
                writingFile = prefs[BACKUP_WRITING_FILE],
                writingFolder = prefs[BACKUP_WRITING_FOLDER],
            ),
            profile = prefs[PROFILE_BIRTH_DATE]?.let { birth ->
                runCatching {
                    UserProfile(
                        female = prefs[PROFILE_FEMALE] ?: false,
                        birthDate = LocalDate.parse(birth),
                        heightCm = prefs[PROFILE_HEIGHT] ?: 0f,
                        weightKg = prefs[PROFILE_WEIGHT] ?: 0f,
                        maxHr = prefs[PROFILE_MAX_HR],
                    )
                }.getOrNull()
            },
        )
    }

    suspend fun saveDevice(device: SavedDevice) {
        context.dataStore.edit {
            it[DEVICE_ID] = device.id
            it[DEVICE_NAME] = device.name
        }
    }

    suspend fun forgetDevice() {
        context.dataStore.edit {
            it.remove(DEVICE_ID)
            it.remove(DEVICE_NAME)
        }
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit {
            it[PROFILE_FEMALE] = profile.female
            it[PROFILE_BIRTH_DATE] = profile.birthDate.toString()
            it[PROFILE_HEIGHT] = profile.heightCm
            it[PROFILE_WEIGHT] = profile.weightKg
            if (profile.maxHr != null) it[PROFILE_MAX_HR] = profile.maxHr else it.remove(PROFILE_MAX_HR)
        }
    }

    suspend fun markOnboardingDone() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    suspend fun saveSync(lastSyncedDate: String, lastSyncAt: String) {
        context.dataStore.edit {
            it[LAST_SYNCED_DATE] = lastSyncedDate
            it[LAST_SYNC_AT] = lastSyncAt
            it[DATA_VERSION] = (it[DATA_VERSION] ?: 0) + 1
        }
    }

    /** Signale une modification des données (lecture Health Connect, import…) : la prochaine sauvegarde la reprendra. */
    suspend fun markDataChanged() {
        context.dataStore.edit { it[DATA_VERSION] = (it[DATA_VERSION] ?: 0) + 1 }
    }

    /** Données de démo chargées ([demo] vrai) ou effacées : la sauvegarde est suspendue pendant la démo. */
    suspend fun setDemoMode(demo: Boolean) {
        context.dataStore.edit { if (demo) it[DEMO_MODE] = true else it.remove(DEMO_MODE) }
    }

    /** Enregistre l'activation demandée, juste avant d'ouvrir le sélecteur de dossier. */
    suspend fun savePendingBackupEnable(mode: PendingBackupEnable) {
        context.dataStore.edit { it[BACKUP_PENDING_ENABLE] = mode.name }
    }

    suspend fun clearPendingBackupEnable() {
        context.dataStore.edit { it.remove(BACKUP_PENDING_ENABLE) }
    }

    /** Marque [file] (dans [folderUri]) comme en cours d'écriture sous son nom final, avant de le créer. */
    suspend fun saveBackupWriting(folderUri: String, file: String) {
        context.dataStore.edit {
            it[BACKUP_WRITING_FOLDER] = folderUri
            it[BACKUP_WRITING_FILE] = file
        }
    }

    /** L'écriture marquée par [saveBackupWriting] est terminée, ou son fichier incomplet a été supprimé. */
    suspend fun clearBackupWriting() {
        context.dataStore.edit {
            it.remove(BACKUP_WRITING_FOLDER)
            it.remove(BACKUP_WRITING_FILE)
        }
    }

    /**
     * Active la sauvegarde automatique dans [uri], ou change de dossier : l'historique des sauvegardes repart de zéro.
     * L'activation en attente, s'il y en avait une, est terminée.
     */
    suspend fun saveBackupFolder(uri: String, name: String?) {
        context.dataStore.edit {
            clearBackupResult(it)
            it.remove(BACKUP_PENDING_ENABLE)
            it[BACKUP_FOLDER_URI] = uri
            if (name != null) it[BACKUP_FOLDER_NAME] = name else it.remove(BACKUP_FOLDER_NAME)
        }
    }

    /** Désactive la sauvegarde automatique (les fichiers déjà écrits restent dans le dossier). */
    suspend fun clearBackupFolder() {
        context.dataStore.edit {
            clearBackupResult(it)
            it.remove(BACKUP_PENDING_ENABLE)
            it.remove(BACKUP_FOLDER_URI)
            it.remove(BACKUP_FOLDER_NAME)
        }
    }

    /** Enregistre une sauvegarde réussie, si elle a bien été écrite dans le dossier encore choisi. */
    suspend fun saveBackupSuccess(folderUri: String, at: String, file: String, dataVersion: Long) {
        context.dataStore.edit {
            if (it[BACKUP_FOLDER_URI] != folderUri) return@edit
            it[BACKUP_LAST_SUCCESS_AT] = at
            it[BACKUP_LAST_FILE] = file
            it[BACKUP_LAST_DATA_VERSION] = dataVersion
            it.remove(BACKUP_LAST_ERROR_AT)
            it.remove(BACKUP_LAST_ERROR)
        }
    }

    /** Enregistre l'échec d'une sauvegarde, si le dossier est encore celui choisi. */
    suspend fun saveBackupError(folderUri: String, at: String, error: String) {
        context.dataStore.edit {
            if (it[BACKUP_FOLDER_URI] != folderUri) return@edit
            it[BACKUP_LAST_ERROR_AT] = at
            it[BACKUP_LAST_ERROR] = error
        }
    }

    /**
     * Chiffre les prochaines sauvegardes avec cette clé (nouvelle phrase de passe ou phrase ressaisie). La prochaine
     * sauvegarde automatique se fera même sans changement des données, pour que la plus récente soit chiffrée ainsi.
     */
    suspend fun saveBackupEncryption(salt: String, iterations: Int, wrappedKey: String) {
        context.dataStore.edit {
            it[BACKUP_ENCRYPT] = true
            it[BACKUP_KEY_SALT] = salt
            it[BACKUP_KEY_ITERATIONS] = iterations
            it[BACKUP_KEY_WRAPPED] = wrappedKey
            it.remove(BACKUP_LAST_DATA_VERSION)
        }
    }

    /** Les prochaines sauvegardes seront écrites en clair ; la clé stockée est effacée. */
    suspend fun clearBackupEncryption() {
        context.dataStore.edit {
            it.remove(BACKUP_ENCRYPT)
            it.remove(BACKUP_KEY_SALT)
            it.remove(BACKUP_KEY_ITERATIONS)
            it.remove(BACKUP_KEY_WRAPPED)
            it.remove(BACKUP_LAST_DATA_VERSION)
        }
    }

    private fun clearBackupResult(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs.remove(BACKUP_LAST_SUCCESS_AT)
        prefs.remove(BACKUP_LAST_FILE)
        prefs.remove(BACKUP_LAST_ERROR_AT)
        prefs.remove(BACKUP_LAST_ERROR)
        prefs.remove(BACKUP_LAST_DATA_VERSION)
    }

    suspend fun saveHealthConnectSync(at: String, syncedDate: String) {
        context.dataStore.edit {
            it[LAST_HEALTH_CONNECT_SYNC_AT] = at
            it[LAST_HEALTH_CONNECT_SYNCED_DATE] = syncedDate
        }
    }

    /**
     * Restauration d'une sauvegarde : chaque réglage n'est repris que s'il est vide sur ce téléphone. Un bracelet
     * déjà associé, un profil déjà saisi ou une synchronisation déjà faite ne sont jamais écrasés. Les deux dates
     * d'une même synchronisation (jour et heure) sont reprises ensemble, pour rester cohérentes.
     */
    suspend fun restoreIfEmpty(
        profile: UserProfile?,
        device: SavedDevice?,
        lastSyncedDate: String?,
        lastSyncAt: String?,
        lastHealthConnectSyncedDate: String?,
        lastHealthConnectSyncAt: String?,
    ): RestoredSettings {
        var restored = RestoredSettings()
        context.dataStore.edit {
            if (profile != null && it[PROFILE_BIRTH_DATE] == null) {
                it[PROFILE_FEMALE] = profile.female
                it[PROFILE_BIRTH_DATE] = profile.birthDate.toString()
                it[PROFILE_HEIGHT] = profile.heightCm
                it[PROFILE_WEIGHT] = profile.weightKg
                if (profile.maxHr != null) it[PROFILE_MAX_HR] = profile.maxHr else it.remove(PROFILE_MAX_HR)
                restored = restored.copy(profile = true)
            }
            if (device != null && it[DEVICE_ID] == null) {
                it[DEVICE_ID] = device.id
                it[DEVICE_NAME] = device.name
                restored = restored.copy(device = true)
            }
            if (lastSyncedDate != null && it[LAST_SYNCED_DATE] == null && it[LAST_SYNC_AT] == null) {
                it[LAST_SYNCED_DATE] = lastSyncedDate
                lastSyncAt?.let { at -> it[LAST_SYNC_AT] = at }
                restored = restored.copy(sync = true)
            }
            if (lastHealthConnectSyncedDate != null &&
                it[LAST_HEALTH_CONNECT_SYNCED_DATE] == null && it[LAST_HEALTH_CONNECT_SYNC_AT] == null
            ) {
                it[LAST_HEALTH_CONNECT_SYNCED_DATE] = lastHealthConnectSyncedDate
                lastHealthConnectSyncAt?.let { at -> it[LAST_HEALTH_CONNECT_SYNC_AT] = at }
                restored = restored.copy(sync = true)
            }
        }
        return restored
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
        val LAST_HEALTH_CONNECT_SYNC_AT = stringPreferencesKey("last_health_connect_sync_at")
        val LAST_HEALTH_CONNECT_SYNCED_DATE = stringPreferencesKey("last_health_connect_synced_date")
        val PROFILE_FEMALE = booleanPreferencesKey("profile_female")
        val PROFILE_BIRTH_DATE = stringPreferencesKey("profile_birth_date")
        val PROFILE_HEIGHT = floatPreferencesKey("profile_height_cm")
        val PROFILE_WEIGHT = floatPreferencesKey("profile_weight_kg")
        val PROFILE_MAX_HR = intPreferencesKey("profile_max_hr")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DATA_VERSION = longPreferencesKey("data_version")
        val BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
        val BACKUP_FOLDER_NAME = stringPreferencesKey("backup_folder_name")
        val BACKUP_LAST_SUCCESS_AT = stringPreferencesKey("backup_last_success_at")
        val BACKUP_LAST_FILE = stringPreferencesKey("backup_last_file")
        val BACKUP_LAST_ERROR_AT = stringPreferencesKey("backup_last_error_at")
        val BACKUP_LAST_ERROR = stringPreferencesKey("backup_last_error")
        val BACKUP_LAST_DATA_VERSION = longPreferencesKey("backup_last_data_version")
        val BACKUP_ENCRYPT = booleanPreferencesKey("backup_encrypt")
        val BACKUP_KEY_SALT = stringPreferencesKey("backup_key_salt")
        val BACKUP_KEY_ITERATIONS = intPreferencesKey("backup_key_iterations")
        val BACKUP_KEY_WRAPPED = stringPreferencesKey("backup_key_wrapped")
        val BACKUP_PENDING_ENABLE = stringPreferencesKey("backup_pending_enable")
        val BACKUP_WRITING_FILE = stringPreferencesKey("backup_writing_file")
        val BACKUP_WRITING_FOLDER = stringPreferencesKey("backup_writing_folder")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
    }
}
