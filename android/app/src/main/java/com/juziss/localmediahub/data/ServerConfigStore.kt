package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "server_config"
)

data class KnownServer(
    val ip: String,
    val port: String,
    val lastConnected: Long = System.currentTimeMillis(),
)

class ServerConfigStore @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_SERVER_IP = stringPreferencesKey("server_ip")
        private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
        private val KEY_KNOWN_SERVERS = stringPreferencesKey("known_servers")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_APP_THEME = stringPreferencesKey("app_theme")
        private val KEY_BLE_ENABLED = booleanPreferencesKey("ble_enabled")
    }

    private val gson = Gson()

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    val serverIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_IP] ?: ""
    }

    val serverPort: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_PORT] ?: "8000"
    }

    val authToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    val appTheme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_APP_THEME] ?: "AUTO"
    }

    val bleEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BLE_ENABLED] ?: false
    }

    val knownServers: Flow<List<KnownServer>> = context.dataStore.data.map { prefs ->
        decodeKnownServers(prefs[KEY_KNOWN_SERVERS])
    }

    suspend fun saveAppTheme(theme: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_THEME] = theme
        }
    }

    suspend fun saveBleEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLE_ENABLED] = enabled
        }
    }

    suspend fun saveServerConfig(ip: String, port: String) {
        val url = "http://$ip:$port"
        val server = KnownServer(ip, port)
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_IP] = ip
            prefs[KEY_SERVER_PORT] = port
            prefs[KEY_SERVER_URL] = url

            val current = decodeKnownServers(prefs[KEY_KNOWN_SERVERS])
            val updated = (listOf(server) + current.filterNot {
                it.ip == server.ip && it.port == server.port
            }).take(10)
            prefs[KEY_KNOWN_SERVERS] = gson.toJson(updated)
        }
    }

    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTH_TOKEN] = token
        }
    }

    suspend fun saveKnownServer(server: KnownServer) {
        context.dataStore.edit { prefs ->
            val current = decodeKnownServers(prefs[KEY_KNOWN_SERVERS])
            val updated = (listOf(server) + current.filterNot {
                it.ip == server.ip && it.port == server.port
            }).take(10)
            prefs[KEY_KNOWN_SERVERS] = gson.toJson(updated)
        }
    }

    suspend fun removeKnownServer(ip: String, port: String) {
        context.dataStore.edit { prefs ->
            val current = decodeKnownServers(prefs[KEY_KNOWN_SERVERS])
            prefs[KEY_KNOWN_SERVERS] = gson.toJson(
                current.filterNot { it.ip == ip && it.port == port }
            )
        }
    }

    suspend fun clearConfig() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }

    private fun decodeKnownServers(json: String?): List<KnownServer> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<KnownServer>>() {}.type
            gson.fromJson<List<KnownServer>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
