package com.boucle.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
)

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
        }
    }

    suspend fun saveHealthConnectSync(at: String, syncedDate: String) {
        context.dataStore.edit {
            it[LAST_HEALTH_CONNECT_SYNC_AT] = at
            it[LAST_HEALTH_CONNECT_SYNCED_DATE] = syncedDate
        }
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
    }
}
