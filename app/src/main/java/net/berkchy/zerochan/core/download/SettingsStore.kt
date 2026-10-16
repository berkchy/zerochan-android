package net.berkchy.zerochan.core.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("zerochan_settings")

data class AppSettings(
    val maxConcurrentDownloads: Int = 2,
    val wifiOnly: Boolean = false,
    val autoStartQueue: Boolean = true,
    val notifyOnComplete: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val CONCURRENCY = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val AUTO_START = booleanPreferencesKey("auto_start_queue")
        val NOTIFY_DONE = booleanPreferencesKey("notify_on_complete")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            maxConcurrentDownloads = (prefs[Keys.CONCURRENCY] ?: 2).coerceIn(1, 5),
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: false,
            autoStartQueue = prefs[Keys.AUTO_START] ?: true,
            notifyOnComplete = prefs[Keys.NOTIFY_DONE] ?: true,
        )
    }

    suspend fun setConcurrency(value: Int) {
        context.dataStore.edit { it[Keys.CONCURRENCY] = value.coerceIn(1, 5) }
    }

    suspend fun setWifiOnly(value: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY] = value }
    }

    suspend fun setAutoStart(value: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = value }
    }

    suspend fun setNotifyOnComplete(value: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFY_DONE] = value }
    }
}