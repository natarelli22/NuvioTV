package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.requester.model.ContentRequesterPreferences
import com.nuvio.tv.data.requester.model.MediaServerRequesterConfig
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

    private val serverConfigsKey = stringPreferencesKey("content_requester_server_configs")

    val preferences: Flow<ContentRequesterPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            val serverConfigsJson = prefs[serverConfigsKey]
            val serverConfigs: Map<String, MediaServerRequesterConfig> = if (!serverConfigsJson.isNullOrBlank()) {
                runCatching { json.decodeFromString<Map<String, MediaServerRequesterConfig>>(serverConfigsJson) }.getOrDefault(emptyMap())
            } else emptyMap()

            ContentRequesterPreferences(serverConfigs = serverConfigs)
        }
    }

    private fun getMutableConfigs(prefs: androidx.datastore.preferences.core.Preferences): MutableMap<String, MediaServerRequesterConfig> {
        val raw = prefs[serverConfigsKey]
        return if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString<Map<String, MediaServerRequesterConfig>>(raw).toMutableMap() }
                .getOrDefault(mutableMapOf())
        } else {
            mutableMapOf()
        }
    }

    suspend fun setServerSectionVisible(mediaServerId: String, visible: Boolean) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: MediaServerRequesterConfig(mediaServerId = mediaServerId)
            map[mediaServerId] = current.copy(isSectionVisible = visible)
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun setServerSonarrEnabled(mediaServerId: String, enabled: Boolean) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: MediaServerRequesterConfig(mediaServerId = mediaServerId)
            map[mediaServerId] = current.copy(sonarrEnabled = enabled)
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun setServerRadarrEnabled(mediaServerId: String, enabled: Boolean) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: MediaServerRequesterConfig(mediaServerId = mediaServerId)
            map[mediaServerId] = current.copy(radarrEnabled = enabled)
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun saveServerSonarrServer(mediaServerId: String, server: RequesterServerConfig) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: MediaServerRequesterConfig(mediaServerId = mediaServerId)
            val list = current.sonarrServers.toMutableList()
            val index = list.indexOfFirst { it.id == server.id }
            if (index >= 0) list[index] = server else list.add(server)
            map[mediaServerId] = current.copy(sonarrServers = list)
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun removeServerSonarrServer(mediaServerId: String, serverId: String) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: return@edit
            map[mediaServerId] = current.copy(sonarrServers = current.sonarrServers.filterNot { it.id == serverId })
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun saveServerRadarrServer(mediaServerId: String, server: RequesterServerConfig) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: MediaServerRequesterConfig(mediaServerId = mediaServerId)
            val list = current.radarrServers.toMutableList()
            val index = list.indexOfFirst { it.id == server.id }
            if (index >= 0) list[index] = server else list.add(server)
            map[mediaServerId] = current.copy(radarrServers = list)
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun removeServerRadarrServer(mediaServerId: String, serverId: String) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            val current = map[mediaServerId] ?: return@edit
            map[mediaServerId] = current.copy(radarrServers = current.radarrServers.filterNot { it.id == serverId })
            prefs[serverConfigsKey] = json.encodeToString(map)
        }
    }

    suspend fun removeServerConfig(mediaServerId: String) {
        store().edit { prefs ->
            val map = getMutableConfigs(prefs)
            if (map.remove(mediaServerId) != null) {
                prefs[serverConfigsKey] = json.encodeToString(map)
            }
        }
    }
}
