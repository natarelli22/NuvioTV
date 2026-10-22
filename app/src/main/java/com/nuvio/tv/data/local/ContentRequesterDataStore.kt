package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.requester.model.ContentRequesterPreferences
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRequesterDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "content_requester_settings"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val isSectionVisibleKey = booleanPreferencesKey("content_requester_visible")
    private val sonarrEnabledKey = booleanPreferencesKey("content_requester_sonarr_enabled")
    private val radarrEnabledKey = booleanPreferencesKey("content_requester_radarr_enabled")
    private val sonarrServersKey = stringPreferencesKey("content_requester_sonarr_servers")
    private val radarrServersKey = stringPreferencesKey("content_requester_radarr_servers")

    val preferences: Flow<ContentRequesterPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val sonarrJson = prefs[sonarrServersKey]
            val radarrJson = prefs[radarrServersKey]
            val sonarrServers: List<RequesterServerConfig> = if (!sonarrJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<RequesterServerConfig>>(sonarrJson) }.getOrDefault(emptyList())
            } else emptyList()
            val radarrServers: List<RequesterServerConfig> = if (!radarrJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<RequesterServerConfig>>(radarrJson) }.getOrDefault(emptyList())
            } else emptyList()

            ContentRequesterPreferences(
                isSectionVisible = prefs[isSectionVisibleKey] ?: false,
                sonarrEnabled = prefs[sonarrEnabledKey] ?: false,
                radarrEnabled = prefs[radarrEnabledKey] ?: false,
                sonarrServers = sonarrServers,
                radarrServers = radarrServers
            )
        }
    }

    suspend fun setSectionVisible(visible: Boolean) {
        store().edit { it[isSectionVisibleKey] = visible }
    }

    suspend fun setSonarrEnabled(enabled: Boolean) {
        store().edit { it[sonarrEnabledKey] = enabled }
    }

    suspend fun setRadarrEnabled(enabled: Boolean) {
        store().edit { it[radarrEnabledKey] = enabled }
    }

    suspend fun saveSonarrServer(server: RequesterServerConfig) {
        store().edit { prefs ->
            val currentJson = prefs[sonarrServersKey]
            val currentList = if (!currentJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<RequesterServerConfig>>(currentJson) }.getOrDefault(emptyList()).toMutableList()
            } else mutableListOf()

            val index = currentList.indexOfFirst { it.id == server.id }
            if (index >= 0) {
                currentList[index] = server
            } else {
                currentList.add(server)
            }
            prefs[sonarrServersKey] = json.encodeToString(currentList)
        }
    }

    suspend fun removeSonarrServer(serverId: String) {
        store().edit { prefs ->
            val currentJson = prefs[sonarrServersKey] ?: return@edit
            val currentList = runCatching { json.decodeFromString<List<RequesterServerConfig>>(currentJson) }.getOrDefault(emptyList())
            val filtered = currentList.filterNot { it.id == serverId }
            prefs[sonarrServersKey] = json.encodeToString(filtered)
        }
    }

    suspend fun saveRadarrServer(server: RequesterServerConfig) {
        store().edit { prefs ->
            val currentJson = prefs[radarrServersKey]
            val currentList = if (!currentJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<List<RequesterServerConfig>>(currentJson) }.getOrDefault(emptyList()).toMutableList()
            } else mutableListOf()

            val index = currentList.indexOfFirst { it.id == server.id }
            if (index >= 0) {
                currentList[index] = server
            } else {
                currentList.add(server)
            }
            prefs[radarrServersKey] = json.encodeToString(currentList)
        }
    }

    suspend fun removeRadarrServer(serverId: String) {
        store().edit { prefs ->
            val currentJson = prefs[radarrServersKey] ?: return@edit
            val currentList = runCatching { json.decodeFromString<List<RequesterServerConfig>>(currentJson) }.getOrDefault(emptyList())
            val filtered = currentList.filterNot { it.id == serverId }
            prefs[radarrServersKey] = json.encodeToString(filtered)
        }
    }
}
