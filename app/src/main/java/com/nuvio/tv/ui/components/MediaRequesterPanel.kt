@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.requester.model.MediaRequesterStatus
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun MediaRequesterPanel(
    status: MediaRequesterStatus,
    isEpisode: Boolean,
    onRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (status is MediaRequesterStatus.Disabled) return

    val serviceName = if (isEpisode) "Sonarr" else "Radarr"
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(
                width = 1.dp,
                color = when (status) {
                    is MediaRequesterStatus.Available -> Color(0xFF4CAF50).copy(alpha = 0.5f)
                    is MediaRequesterStatus.Downloading -> NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                    is MediaRequesterStatus.MonitoredSearching -> Color(0xFFFFC107).copy(alpha = 0.5f)
                    else -> NuvioTheme.colors.TextSecondary.copy(alpha = 0.2f)
                },
                shape = shape
            )
            .padding(NuvioTheme.spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            // Ícone Indicador
            when (status) {
                is MediaRequesterStatus.Checking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = NuvioTheme.colors.Primary
                    )
                }
                is MediaRequesterStatus.NotMonitored -> {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                is MediaRequesterStatus.MonitoredSearching -> {
                    Icon(
                        imageVector = Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(26.dp)
                    )
                }
                is MediaRequesterStatus.Downloading -> {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Primary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                is MediaRequesterStatus.Available -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(26.dp)
                    )
                }
                is MediaRequesterStatus.Error -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Error,
                        modifier = Modifier.size(26.dp)
                    )
                }
                MediaRequesterStatus.Disabled -> Unit
            }

            // Textos de Status
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Media Requester · $serviceName",
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary
                )

                val statusMessage = when (status) {
                    is MediaRequesterStatus.Checking -> "Verificando biblioteca local..."
                    is MediaRequesterStatus.NotMonitored -> stringResource(R.string.requester_panel_not_monitored)
                    is MediaRequesterStatus.MonitoredSearching -> stringResource(R.string.requester_panel_searching)
                    is MediaRequesterStatus.Downloading -> {
                        val pct = status.progressPercent ?: 0
                        val eta = status.etaFormatted?.let { " · ${stringResource(R.string.requester_panel_eta, it)}" } ?: ""
                        stringResource(R.string.requester_panel_downloading, status.serverName, pct) + eta
                    }
                    is MediaRequesterStatus.Available -> stringResource(R.string.requester_panel_available)
                    is MediaRequesterStatus.Error -> status.message
                    MediaRequesterStatus.Disabled -> ""
                }

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (status) {
                        is MediaRequesterStatus.Available -> Color(0xFF4CAF50)
                        is MediaRequesterStatus.Error -> NuvioTheme.colors.Error
                        else -> NuvioTheme.colors.TextPrimary
                    }
                )

                // Barra de Progresso quando estiver baixando
                if (status is MediaRequesterStatus.Downloading) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val progressFloat = (status.progressPercent ?: 0).toFloat() / 100f
                    LinearProgressIndicator(
                        progress = { progressFloat.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = NuvioTheme.colors.Primary,
                        trackColor = NuvioTheme.colors.BackgroundCard
                    )
                }
            }

            // Botão de Ação (Acessível via D-Pad do controle remoto da TV)
            if (status is MediaRequesterStatus.NotMonitored || status is MediaRequesterStatus.MonitoredSearching) {
                var isFocused by remember { mutableStateOf(false) }
                Card(
                    onClick = onRequest,
                    modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                    colors = CardDefaults.colors(
                        containerColor = if (status is MediaRequesterStatus.NotMonitored) {
                            NuvioTheme.colors.Primary.copy(alpha = 0.25f)
                        } else {
                            NuvioTheme.colors.BackgroundCard
                        },
                        focusedContainerColor = NuvioTheme.colors.Primary
                    ),
                    shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.md)),
                    border = CardDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                    ) {
                        Icon(
                            imageVector = if (status is MediaRequesterStatus.NotMonitored) Icons.Default.CloudDownload else Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = if (status is MediaRequesterStatus.NotMonitored) {
                                stringResource(R.string.requester_panel_btn_request, serviceName)
                            } else {
                                "Forçar Busca"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
