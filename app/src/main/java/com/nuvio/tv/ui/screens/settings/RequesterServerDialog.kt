@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.requester.model.RequesterServerConfig
import com.nuvio.tv.data.requester.model.RequesterType
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun RequesterServerDialog(
    server: RequesterServerConfig?,
    type: RequesterType,
    onTestConnection: suspend (RequesterServerConfig) -> Result<String>,
    onSave: (RequesterServerConfig) -> Unit,
    onDelete: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val typeName = if (type == RequesterType.SONARR) "Sonarr" else "Radarr"
    val defaultName = if (type == RequesterType.SONARR) {
        stringResource(R.string.requester_default_sonarr_name)
    } else {
        stringResource(R.string.requester_default_radarr_name)
    }
    val nameHint = if (type == RequesterType.SONARR) {
        stringResource(R.string.requester_server_name_hint_sonarr)
    } else {
        stringResource(R.string.requester_server_name_hint_radarr)
    }

    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: "") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val cancelAndDismiss = {
        testJob?.cancel()
        isTesting = false
        onDismiss()
    }

    val canSave = url.isNotBlank() && !isTesting

    val unknownErrorText = stringResource(R.string.requester_error_unknown)

    val runTestAndSave = {
        if (canSave) {
            val credentialsChanged = server != null && (
                url.trim() != server.url.trim() ||
                username.trim() != server.username.trim() ||
                password != server.password
            )
            val updated = RequesterServerConfig(
                id = server?.id ?: UUID.randomUUID().toString(),
                name = name.ifBlank { defaultName },
                url = url.trim(),
                apiKey = if (credentialsChanged) "" else (server?.apiKey ?: ""),
                username = username.trim(),
                password = password,
                enabled = server?.enabled ?: true
            )
            isTesting = true
            testResult = null
            testJob?.cancel()
            testJob = scope.launch {
                val result = onTestConnection(updated)
                isTesting = false
                if (result.isSuccess) {
                    val resolvedKey = result.getOrDefault(if (credentialsChanged) "" else (server?.apiKey ?: ""))
                    testResult = true to ""
                    onSave(updated.copy(apiKey = resolvedKey))
                } else {
                    val err = result.exceptionOrNull()?.localizedMessage ?: unknownErrorText
                    testResult = false to err
                }
            }
        }
    }

    NuvioDialog(
        onDismiss = cancelAndDismiss,
        title = if (server == null) {
            stringResource(R.string.requester_dialog_title_add, typeName)
        } else {
            stringResource(R.string.requester_dialog_title_edit, typeName)
        },
        subtitle = stringResource(R.string.requester_add_server_hint),
        contentSpacing = NuvioTheme.spacing.sm,
        width = 620.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            RequesterFormField(label = stringResource(R.string.requester_server_name)) {
                InputField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = nameHint,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            RequesterFormField(label = stringResource(R.string.requester_server_url)) {
                InputField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = stringResource(R.string.requester_server_url_hint),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            RequesterFormField(label = stringResource(R.string.requester_server_username)) {
                InputField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = stringResource(R.string.requester_server_username_hint),
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            RequesterFormField(label = stringResource(R.string.requester_server_password)) {
                InputField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.requester_server_password_hint),
                    isPassword = true,
                    imeAction = ImeAction.Done,
                    onImeAction = { runTestAndSave() },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            testResult?.let { (success, msg) ->
                Text(
                    text = if (success) {
                        stringResource(R.string.requester_test_success)
                    } else {
                        stringResource(R.string.requester_test_failed, msg)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) NuvioTheme.colors.Primary else NuvioTheme.colors.Error
                )
            }
        }

        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = cancelAndDismiss,
                enabled = true
            )

            if (server != null && onDelete != null) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.requester_btn_delete),
                    onClick = { onDelete(server.id) },
                    enabled = !isTesting
                )
            }

            SettingsDialogActionButton(
                text = if (isTesting) stringResource(R.string.requester_btn_testing) else stringResource(R.string.requester_btn_test_save),
                onClick = { runTestAndSave() },
                primary = true,
                enabled = canSave
            )
        }
    }
}

@Composable
private fun RequesterFormField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        field()
    }
}
