package com.nuvio.tv.data.requester.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.util.UUID

enum class RequesterType {
    SONARR,
    RADARR
}

@Serializable
@Immutable
data class RequesterServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true
)

@Serializable
@Immutable
data class ContentRequesterPreferences(
    val isSectionVisible: Boolean = true,
    val sonarrEnabled: Boolean = false,
    val radarrEnabled: Boolean = false,
    val sonarrServers: List<RequesterServerConfig> = emptyList(),
    val radarrServers: List<RequesterServerConfig> = emptyList()
)

sealed class MediaRequesterStatus {
    data object Disabled : MediaRequesterStatus()
    data object Checking : MediaRequesterStatus()
    data object NotMonitored : MediaRequesterStatus()
    data class MonitoredSearching(val serverName: String) : MediaRequesterStatus()
    data class Downloading(
        val serverName: String,
        val progressPercent: Int?,
        val sizeLeftBytes: Long? = null,
        val totalSizeBytes: Long? = null,
        val etaFormatted: String? = null
    ) : MediaRequesterStatus()
    data class Available(val serverName: String) : MediaRequesterStatus()
    data class Error(val message: String) : MediaRequesterStatus()
}
