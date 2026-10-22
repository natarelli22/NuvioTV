@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import com.nuvio.tv.data.requester.model.RequesterType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun MediaServersSettingsContent(
    initialFocusRequester: FocusRequester?,
    onOpenServer: (String) -> Unit,
    viewModel: MediaServersViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val requesterPrefs by viewModel.requesterPrefs.collectAsStateWithLifecycle()
    var signIn by remember { mutableStateOf<ServerSignInRequest?>(null) }
    var requesterDialogServer by remember { mutableStateOf<Pair<RequesterServerConfig?, RequesterType>?>(null) }
    val connections = uiState.connections
    val focusModifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_media_servers),
            subtitle = stringResource(R.string.settings_media_servers_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "servers_label") { ServerSectionLabel(stringResource(R.string.servers_section_connected)) }
                    if (connections.isEmpty()) {
                        item(key = "servers_empty") {
                            Text(
                                text = stringResource(R.string.servers_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NuvioTheme.colors.TextSecondary,
                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
                            )
                        }
                    }
                    items(connections, key = { "server_${it.id}" }) { connection ->
                        SettingsActionRow(
                            title = connection.name,
                            subtitle = connection.statusText(
                                providerName = viewModel.provider(connection)?.displayName ?: connection.providerId,
                                failure = uiState.failures[connection.id]
                            ),
                            onClick = { onOpenServer(connection.id) },
                            leadingIcon = Icons.Default.Dns,
                            modifier = if (connection == connections.first()) focusModifier else Modifier
                        )
                    }
                    item(key = "servers_add_label") { ServerSectionLabel(stringResource(R.string.servers_section_add)) }
                    items(viewModel.providers, key = { "provider_${it.id}" }) { provider ->
                        SettingsActionRow(
                            title = provider.displayName,
                            subtitle = stringResource(R.string.servers_add_description, provider.displayName),
                            onClick = { signIn = ServerSignInRequest(provider) },
                            leadingRawIconRes = serverLogoRes(provider.id),
                            modifier = if (connections.isEmpty() && provider == viewModel.providers.first()) focusModifier else Modifier
                        )
                    }

                    // --- CONTENT REQUESTER (SONARR & RADARR) ---
                    item(key = "content_requester_label") {
                        ServerSectionLabel(stringResource(R.string.settings_content_requester_title))
                    }
                    item(key = "content_requester_show_hide") {
                        SettingsActionRow(
                            title = if (requesterPrefs.isSectionVisible) {
                                stringResource(R.string.settings_content_requester_hide)
                            } else {
                                stringResource(R.string.settings_content_requester_show)
                            },
                            subtitle = stringResource(R.string.settings_content_requester_subtitle),
                            leadingIcon = if (requesterPrefs.isSectionVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            onClick = { viewModel.toggleContentRequesterVisibility() }
                        )
                    }

                    if (requesterPrefs.isSectionVisible) {
                        // SONARR (SÉRIES)
                        item(key = "sonarr_toggle") {
                            SettingsToggleRow(
                                title = stringResource(R.string.requester_sonarr_title),
                                subtitle = stringResource(R.string.requester_sonarr_description),
                                checked = requesterPrefs.sonarrEnabled,
                                onToggle = { viewModel.setSonarrEnabled(!requesterPrefs.sonarrEnabled) }
                            )
                        }
                        if (requesterPrefs.sonarrEnabled) {
                            items(requesterPrefs.sonarrServers, key = { "sonarr_${it.id}" }) { server ->
                                SettingsActionRow(
                                    title = server.name.ifBlank { stringResource(R.string.requester_default_sonarr_name) },
                                    subtitle = server.url,
                                    leadingIcon = Icons.Default.CloudDownload,
                                    onClick = { requesterDialogServer = server to RequesterType.SONARR }
                                )
                            }
                            item(key = "sonarr_add") {
                                SettingsActionRow(
                                    title = stringResource(R.string.requester_add_sonarr_server),
                                    subtitle = stringResource(R.string.requester_add_server_hint),
                                    leadingIcon = Icons.Default.Add,
                                    onClick = { requesterDialogServer = null to RequesterType.SONARR }
                                )
                            }
                        }

                        // RADARR (FILMES)
                        item(key = "radarr_toggle") {
                            SettingsToggleRow(
                                title = stringResource(R.string.requester_radarr_title),
                                subtitle = stringResource(R.string.requester_radarr_description),
                                checked = requesterPrefs.radarrEnabled,
                                onToggle = { viewModel.setRadarrEnabled(!requesterPrefs.radarrEnabled) }
                            )
                        }
                        if (requesterPrefs.radarrEnabled) {
                            items(requesterPrefs.radarrServers, key = { "radarr_${it.id}" }) { server ->
                                SettingsActionRow(
                                    title = server.name.ifBlank { stringResource(R.string.requester_default_radarr_name) },
                                    subtitle = server.url,
                                    leadingIcon = Icons.Default.Movie,
                                    onClick = { requesterDialogServer = server to RequesterType.RADARR }
                                )
                            }
                            item(key = "radarr_add") {
                                SettingsActionRow(
                                    title = stringResource(R.string.requester_add_radarr_server),
                                    subtitle = stringResource(R.string.requester_add_server_hint),
                                    leadingIcon = Icons.Default.Add,
                                    onClick = { requesterDialogServer = null to RequesterType.RADARR }
                                )
                            }
                        }
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    signIn?.let { request ->
        ServerSignInDialog(
            request = request,
            viewModel = viewModel,
            onConnected = { connection ->
                signIn = null
                onOpenServer(connection.id)
            },
            onDismiss = { signIn = null }
        )
    }

    requesterDialogServer?.let { (server, type) ->
        RequesterServerDialog(
            server = server,
            type = type,
            onTestConnection = { viewModel.testRequesterConnection(it) },
            onSave = { saved ->
                if (type == RequesterType.SONARR) {
                    viewModel.saveSonarrServer(saved)
                } else {
                    viewModel.saveRadarrServer(saved)
                }
                requesterDialogServer = null
            },
            onDelete = if (server != null) {
                { serverId ->
                    if (type == RequesterType.SONARR) {
                        viewModel.removeSonarrServer(serverId)
                    } else {
                        viewModel.removeRadarrServer(serverId)
                    }
                    requesterDialogServer = null
                }
            } else null,
            onDismiss = { requesterDialogServer = null }
        )
    }
}

@Composable
internal fun ServerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
    )
}

@Composable
internal fun ServerConnection.statusText(providerName: String, failure: ServerFailure?): String {
    val identity = stringResource(R.string.servers_signed_in_as, providerName, userName)
    return listOfNotNull(identity, statusLabel(failure)).joinToString(" · ")
}

@Composable
internal fun ServerConnection.statusLabel(failure: ServerFailure?): String? = when {
    !enabled -> stringResource(R.string.servers_status_disabled)
    failure == ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_status_auth)
    failure == ServerFailure.UNREACHABLE -> stringResource(R.string.servers_status_unreachable)
    else -> null
}

@RawRes
internal fun serverLogoRes(providerId: String): Int? = when (providerId) {
    "jellyfin" -> R.raw.jellyfin_logo
    "emby" -> R.raw.emby_logo
    else -> null
}
