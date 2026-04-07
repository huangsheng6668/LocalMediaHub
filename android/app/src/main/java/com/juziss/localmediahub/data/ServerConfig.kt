package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "server_config"
)

/**
 * Manages server connection settings via DataStore.
 */
class ServerConfig(private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SERVER_IP = stringPreferencesKey("server_ip")
        private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    val serverIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_IP] ?: ""
    }

    val serverPort: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_PORT] ?: "8000"
    }

    suspend fun saveServerConfig(ip: String, port: String) {
        val url = "http://$ip:$port"
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_IP] = ip
            prefs[KEY_SERVER_PORT] = port
            prefs[KEY_SERVER_URL] = url
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
