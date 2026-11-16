package com.boucle.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class SavedDevice(val id: String, val name: String)

data class AppSettings(
    val device: SavedDevice? = null,
    /** Dernier jour entièrement synchronisé (yyyy-MM-dd). */
    val lastSyncedDate: String? = null,
    /** Date et heure de la dernière synchronisation réussie, pour affichage. */
    val lastSyncAt: String? = null,
)

class Settings(private val context: Context) {

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val id = prefs[DEVICE_ID]
        AppSettings(
            device = id?.let { SavedDevice(it, prefs[DEVICE_NAME] ?: it) },
            lastSyncedDate = prefs[LAST_SYNCED_DATE],
            lastSyncAt = prefs[LAST_SYNC_AT],
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

    suspend fun saveSync(lastSyncedDate: String, lastSyncAt: String) {
        context.dataStore.edit {
            it[LAST_SYNCED_DATE] = lastSyncedDate
            it[LAST_SYNC_AT] = lastSyncAt
        }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val LAST_SYNCED_DATE = stringPreferencesKey("last_synced_date")
        val LAST_SYNC_AT = stringPreferencesKey("last_sync_at")
    }
}
