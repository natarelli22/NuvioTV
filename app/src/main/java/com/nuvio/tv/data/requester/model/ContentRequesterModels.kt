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
data class ContentRequesterPreferences(
    val isSectionVisible: Boolean = false,
    val sonarrEnabled: Boolean = false,
    val radarrEnabled: Boolean = false,
    val sonarrServers: List<RequesterServerConfig> = emptyList(),
    val radarrServers: List<RequesterServerConfig> = emptyList()
) {
    fun hasActiveServer(isSeries: Boolean): Boolean {
        return if (isSeries) {
            sonarrEnabled && sonarrServers.any { it.enabled && it.url.isNotBlank() }
        } else {
            radarrEnabled && radarrServers.any { it.enabled && it.url.isNotBlank() }
        }
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
        val requestDot: RequesterDot = RequesterDot(RequesterDotColor.RED, labelRes = R.string.requester_stepper_request),
        val availabilityDot: RequesterDot = RequesterDot(RequesterDotColor.RED, labelRes = R.string.requester_stepper_availability)
    ) : MediaRequesterStatus() {
        override val dots: List<RequesterDot> get() = listOf(serverDot, requestDot, availabilityDot)
    }
}
