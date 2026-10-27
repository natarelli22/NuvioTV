@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.nuvio.tv.data.requester.model.RequesterDot
import com.nuvio.tv.data.requester.model.RequesterDotColor
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

    val borderColor = when (status) {
        is MediaRequesterStatus.Level3Available -> Color(0xFF4CAF50).copy(alpha = 0.5f)
        is MediaRequesterStatus.Level2Requested -> NuvioTheme.colors.Primary.copy(alpha = 0.5f)
        is MediaRequesterStatus.Level3Unavailable -> NuvioTheme.colors.Error.copy(alpha = 0.5f)
        else -> NuvioTheme.colors.TextSecondary.copy(alpha = 0.2f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .padding(
                start = NuvioTheme.spacing.md,
                end = NuvioTheme.spacing.md,
                top = 11.dp,
                bottom = 7.dp
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Linha Principal de Informações e Ação
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                // Ícone Indicador
                when (status) {
                    is MediaRequesterStatus.Checking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = NuvioTheme.colors.Primary
                        )
                    }
                    is MediaRequesterStatus.Level1NotRequested -> {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = NuvioTheme.colors.Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    is MediaRequesterStatus.Level2Requested -> {
                        if (status.progressPercent != null) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = NuvioTheme.colors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    is MediaRequesterStatus.Level3Available -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    is MediaRequesterStatus.Level3Unavailable -> {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = NuvioTheme.colors.Error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    MediaRequesterStatus.Disabled -> {}
                }

                // Textos de Status
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    val title = when (status) {
                        is MediaRequesterStatus.Checking -> stringResource(R.string.requester_status_checking_title, serviceName)
                        is MediaRequesterStatus.Level1NotRequested -> stringResource(R.string.requester_status_level1)
                        is MediaRequesterStatus.Level2Requested -> stringResource(R.string.requester_status_level2)
                        is MediaRequesterStatus.Level3Available -> stringResource(R.string.requester_status_level3_available)
                        is MediaRequesterStatus.Level3Unavailable -> stringResource(R.string.requester_status_level3_unavailable)
                        MediaRequesterStatus.Disabled -> ""
                    }

                    val subtitle = when (status) {
                        is MediaRequesterStatus.Checking -> stringResource(R.string.requester_status_checking_desc, serviceName)
                        is MediaRequesterStatus.Level1NotRequested -> {
                            stringResource(R.string.requester_status_level1_desc)
                        }
                        is MediaRequesterStatus.Level2Requested -> {
                            if (status.progressPercent != null) {
                                val eta = status.etaFormatted?.let { " · " + stringResource(R.string.requester_panel_eta, it) } ?: ""
                                stringResource(R.string.requester_panel_downloading, status.serverName, status.progressPercent) + eta
                            } else {
                                status.phaseDescription.ifBlank { stringResource(R.string.requester_panel_searching) }
                            }
                        }
                        is MediaRequesterStatus.Level3Available -> {
                            status.message.ifBlank { stringResource(R.string.requester_status_level3_available_desc) }
                        }
                        is MediaRequesterStatus.Level3Unavailable -> {
                            status.message.ifBlank { stringResource(R.string.requester_panel_sources_unavailable) }
                        }
                        MediaRequesterStatus.Disabled -> ""
                    }

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Barra de progresso para Level 2 se processando
                    if (status is MediaRequesterStatus.Level2Requested && status.progressPercent != null) {
                        Spacer(modifier = Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = NuvioTheme.colors.Primary,
                            trackColor = NuvioTheme.colors.BackgroundCard
                        )
                    }
                }

                // Botão de Ação (Level 1 ou Retry no Level 3 Unavailable)
                val showActionButton = status is MediaRequesterStatus.Level1NotRequested ||
                    (status is MediaRequesterStatus.Level3Unavailable && status.canRetry)

                if (showActionButton) {
                    var isFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onRequest,
                        modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary.copy(alpha = 0.25f),
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
                            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                        ) {
                            Icon(
                                imageVector = if (status is MediaRequesterStatus.Level1NotRequested) Icons.Default.CloudDownload else Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary
                            )
                            Text(
                                text = if (status is MediaRequesterStatus.Level1NotRequested) {
                                    stringResource(R.string.requester_panel_btn_request)
                                } else {
                                    stringResource(R.string.requester_btn_retry)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFocused) Color.White else NuvioTheme.colors.TextPrimary
                            )
                        }
                    }
                }
            }

            // Stepper com as 3 bolinhas (o-o-o): Servidor, Solicitação, Disponibilidade
            val dots = status.dots
            if (dots.isNotEmpty()) {
                RequesterStepperRow(dots = dots)
            }
        }
    }
}

@Composable
private fun RequesterStepperRow(dots: List<RequesterDot>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        dots.forEachIndexed { index, dot ->
            val dotColor = when (dot.color) {
                RequesterDotColor.GREEN -> Color(0xFF4CAF50)
                RequesterDotColor.YELLOW -> Color(0xFFFFC107)
                RequesterDotColor.RED -> Color(0xFFF44336)
                RequesterDotColor.GRAY -> NuvioTheme.colors.TextSecondary.copy(alpha = 0.35f)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(dotColor, CircleShape)
                )
                val dotLabel = if (dot.labelRes != 0) stringResource(dot.labelRes) else dot.label
                Text(
                    text = dotLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (dot.color == RequesterDotColor.GRAY) {
                        NuvioTheme.colors.TextSecondary.copy(alpha = 0.6f)
                    } else {
                        NuvioTheme.colors.TextPrimary
                    }
                )
            }

            if (index < dots.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(24.dp)
                        .height(1.dp)
                        .background(NuvioTheme.colors.TextSecondary.copy(alpha = 0.25f))
                )
            }
        }
    }
}
