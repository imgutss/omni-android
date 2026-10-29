package com.v2ray.ang.omni

import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class OmniSpeedLabActivity : BaseComponentActivity() {
    private val model: OmniSpeedLabViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniSpeedLabScreen(
            state = state,
            onBack = { finish() },
            onProfile = model::selectProfile,
            onDownloadUrl = model::updateDownloadUrl,
            onUploadUrl = model::updateUploadUrl,
            onDownloadSize = model::updateDownloadSize,
            onUploadSize = model::updateUploadSize,
            onSpeed = model::runSpeedTest,
            onPing = model::runRealPing,
            onRefresh = model::refresh,
        )
    }
}

@Composable
fun OmniSpeedLabScreen(
    state: OmniSpeedLabUiState,
    onBack: () -> Unit,
    onProfile: (String) -> Unit,
    onDownloadUrl: (String) -> Unit,
    onUploadUrl: (String) -> Unit,
    onDownloadSize: (String) -> Unit,
    onUploadSize: (String) -> Unit,
    onSpeed: () -> Unit,
    onPing: () -> Unit,
    onRefresh: () -> Unit,
) {
    val selected = state.entries.firstOrNull { it.guid == state.selectedGuid }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_speed_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_speed_subtitle), style = MaterialTheme.typography.bodyMedium)
                        OmniSpeedProfilePicker(state, onProfile)
                        selected?.let {
                            Text(stringResource(R.string.omni_speed_selected_profile, it.profile.remarks.ifBlank { it.guid }, it.profile.configType.name), style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(value = state.downloadUrlInput, onValueChange = onDownloadUrl, enabled = !state.speedRunning, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_scanner_download_url)) })
                        OutlinedTextField(value = state.uploadUrlInput, onValueChange = onUploadUrl, enabled = !state.speedRunning, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.omni_scanner_upload_url)) })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.downloadSizeInput,
                                onValueChange = onDownloadSize,
                                enabled = !state.speedRunning,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.omni_speed_download_size)) },
                            )
                            OutlinedTextField(
                                value = state.uploadSizeInput,
                                onValueChange = onUploadSize,
                                enabled = !state.speedRunning,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(stringResource(R.string.omni_speed_upload_size)) },
                            )
                        }
                        Text(stringResource(R.string.omni_speed_size_note), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onSpeed, enabled = selected != null && !state.speedRunning && !state.pingRunning, modifier = Modifier.weight(1f)) {
                                if (state.speedRunning) CircularProgressIndicator() else Text(stringResource(R.string.omni_speed_run))
                            }
                            OutlinedButton(onClick = onPing, enabled = selected != null && !state.speedRunning && !state.pingRunning, modifier = Modifier.weight(1f)) {
                                if (state.pingRunning) CircularProgressIndicator() else Text(stringResource(R.string.omni_speed_ping))
                            }
                        }
                        OutlinedButton(onClick = onRefresh, enabled = !state.speedRunning && !state.pingRunning && !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.omni_config_refresh))
                        }
                        state.message?.let { Text(speedMessageText(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.omni_speed_results), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.omni_speed_separate_metrics), style = MaterialTheme.typography.bodySmall)
                        val noResponse = stringResource(R.string.omni_scanner_no_response)
                        state.connectivity?.let { connection ->
                            Text(stringResource(R.string.omni_scanner_tunnel_metrics, connection.ttfbMs?.toString() ?: noResponse, connection.httpStatus?.toString() ?: noResponse))
                            connection.error?.let { Text(stringResource(R.string.omni_speed_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        state.transfer?.let { transfer ->
                            Text(stringResource(R.string.omni_scanner_download_metrics, transfer.downloadBytes, transfer.downloadDurationMs, transfer.downloadMBps, transfer.downloadMbps))
                            if (transfer.requestedUploadBytes > 0) {
                                Text(stringResource(R.string.omni_scanner_upload_metrics, transfer.uploadBytes, transfer.uploadDurationMs, transfer.uploadMBps, transfer.uploadMbps))
                            }
                            Text(stringResource(R.string.omni_scanner_data_use, transfer.downloadLimitBytes, transfer.requestedUploadBytes, transfer.dataUsedBytes))
                            transfer.error?.let { Text(stringResource(R.string.omni_speed_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        if (state.pingTested) {
                            Text(stringResource(R.string.omni_scanner_ping_metrics, state.pingSuccesses, state.pingSamples, state.pingDelayMs?.toString() ?: noResponse, state.pingJitterMs?.toString() ?: noResponse))
                        } else if (!state.pingRunning) {
                            Text(stringResource(R.string.omni_speed_ping_untested), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.connectivity == null && state.transfer == null && !state.speedRunning) {
                            Text(stringResource(R.string.omni_speed_no_transfer), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OmniSpeedProfilePicker(state: OmniSpeedLabUiState, onProfile: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.entries.firstOrNull { it.guid == state.selectedGuid }
    OutlinedButton(onClick = { expanded = true }, enabled = state.entries.isNotEmpty() && !state.speedRunning && !state.pingRunning, modifier = Modifier.fillMaxWidth()) {
        Text(selected?.profile?.remarks?.ifBlank { selected.guid } ?: stringResource(R.string.omni_speed_choose_profile))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        state.entries.forEach { entry ->
            DropdownMenuItem(
                text = { Text("${entry.profile.remarks.ifBlank { entry.guid }} · ${entry.profile.configType.name}") },
                onClick = { onProfile(entry.guid); expanded = false },
            )
        }
    }
}

@Composable
private fun speedMessageText(message: OmniSpeedLabMessage): String = stringResource(
    when (message) {
        OmniSpeedLabMessage.LOAD_FAILED -> R.string.omni_speed_load_failed
        OmniSpeedLabMessage.NO_PROFILE -> R.string.omni_speed_no_profile
        OmniSpeedLabMessage.INVALID_INPUT -> R.string.omni_speed_invalid_input
        OmniSpeedLabMessage.SPEED_FAILED -> R.string.omni_speed_failed
        OmniSpeedLabMessage.PING_FAILED -> R.string.omni_speed_ping_failed
    },
)
