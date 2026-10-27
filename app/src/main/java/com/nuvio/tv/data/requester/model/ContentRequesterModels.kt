package com.nuvio.tv.data.requester.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import kotlinx.serialization.Serializable
import java.util.UUID

enum class RequesterType {
    SONARR,
    RADARR
}

enum class RequesterDotColor {
    GREEN,
    YELLOW,
    RED,
    GRAY
}

@Immutable
data class RequesterDot(
    val color: RequesterDotColor,
    val label: String = "",
    @StringRes val labelRes: Int = 0
)

@Serializable
@Immutable
data class RequesterServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true
)

@Serializable
@Immutable
data class MediaServerRequesterConfig(
    val mediaServerId: String = "",
    val isSectionVisible: Boolean = false,
    val sonarrEnabled: Boolean = false,
    val radarrEnabled: Boolean = false,
    val sonarrServers: List<RequesterServerConfig> = emptyList(),
    val radarrServers: List<RequesterServerConfig> = emptyList()
)

@Serializable
@Immutable
data class ContentRequesterPreferences(
    val serverConfigs: Map<String, MediaServerRequesterConfig> = emptyMap()
) {
    fun getConfigForServer(connectionId: String): MediaServerRequesterConfig {
        return serverConfigs[connectionId] ?: MediaServerRequesterConfig(mediaServerId = connectionId)
    }

    private fun activeConfigs(enabledMediaServerIds: Set<String>?): Collection<MediaServerRequesterConfig> {
        return if (enabledMediaServerIds != null) {
            serverConfigs.filterKeys { it in enabledMediaServerIds }.values
        } else {
            serverConfigs.values
        }
    }

    fun hasActiveServer(isSeries: Boolean, enabledMediaServerIds: Set<String>? = null): Boolean {
        return activeConfigs(enabledMediaServerIds).any { cfg ->
            if (isSeries) {
                cfg.sonarrEnabled && cfg.sonarrServers.any { it.enabled && it.url.isNotBlank() }
            } else {
                cfg.radarrEnabled && cfg.radarrServers.any { it.enabled && it.url.isNotBlank() }
            }
        }
    }

    fun getActiveServers(isSeries: Boolean, enabledMediaServerIds: Set<String>? = null): List<RequesterServerConfig> {
        val list = mutableListOf<RequesterServerConfig>()
        activeConfigs(enabledMediaServerIds).forEach { cfg ->
            if (isSeries && cfg.sonarrEnabled) {
                list.addAll(cfg.sonarrServers.filter { it.enabled && it.url.isNotBlank() })
            } else if (!isSeries && cfg.radarrEnabled) {
                list.addAll(cfg.radarrServers.filter { it.enabled && it.url.isNotBlank() })
            }
        }
        return list
    }
}

sealed class MediaRequesterStatus {
    data object Disabled : MediaRequesterStatus()
    data object Checking : MediaRequesterStatus()

    open val dots: List<RequesterDot> get() = emptyList()

    /** Nível 1: Mídia ainda não solicitada no Sonarr/Radarr */
    data class Level1NotRequested(
        val serverName: String,
        val serverDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_server),
        val requestDot: RequesterDot = RequesterDot(RequesterDotColor.GRAY, labelRes = R.string.requester_stepper_request),
        val availabilityDot: RequesterDot = RequesterDot(RequesterDotColor.GRAY, labelRes = R.string.requester_stepper_availability)
    ) : MediaRequesterStatus() {
        override val dots: List<RequesterDot> get() = listOf(serverDot, requestDot, availabilityDot)
    }

    /** Nível 2: Conteúdo Solicitado (Buscando releases ou Baixando) */
    data class Level2Requested(
        val serverName: String,
        val phaseDescription: String = "",
        val progressPercent: Int? = null,
        val etaFormatted: String? = null,
        val sizeFormatted: String? = null,
        val serverDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_server),
        val requestDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_request),
        val availabilityDot: RequesterDot = RequesterDot(
            if (progressPercent != null) RequesterDotColor.YELLOW else RequesterDotColor.GRAY,
            labelRes = R.string.requester_stepper_availability
        )
    ) : MediaRequesterStatus() {
        override val dots: List<RequesterDot> get() = listOf(serverDot, requestDot, availabilityDot)
    }

    /** Nível 3: Conteúdo Disponível na biblioteca */
    data class Level3Available(
        val serverName: String,
        val message: String = "",
        val serverDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_server),
        val requestDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_request),
        val availabilityDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_availability)
    ) : MediaRequesterStatus() {
        override val dots: List<RequesterDot> get() = listOf(serverDot, requestDot, availabilityDot)
    }

    /** Nível 3: Conteúdo Indisponível ou Falha */
    data class Level3Unavailable(
        val serverName: String,
        val message: String = "",
        val canRetry: Boolean = true,
        val serverDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_server),
        val requestDot: RequesterDot = RequesterDot(RequesterDotColor.GREEN, labelRes = R.string.requester_stepper_request),
        val availabilityDot: RequesterDot = RequesterDot(RequesterDotColor.RED, labelRes = R.string.requester_stepper_availability)
    ) : MediaRequesterStatus() {
        override val dots: List<RequesterDot> get() = listOf(serverDot, requestDot, availabilityDot)
    }
}
