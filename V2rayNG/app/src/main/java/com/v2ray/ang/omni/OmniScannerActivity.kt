package com.v2ray.ang.omni

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.viewModels
import com.v2ray.ang.R
import com.v2ray.ang.omni.lab.OmniEdgeProbeResult
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.res.stringResource

class OmniScannerActivity : BaseComponentActivity() {
    private val model: OmniScannerViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        val requestedTemplate = remember { intent.getStringExtra(EXTRA_TEMPLATE_GUID) }
        var templatePreselected by remember { mutableStateOf(false) }
        LaunchedEffect(requestedTemplate, state.templates) {
            if (!templatePreselected && requestedTemplate != null && state.templates.any { it.guid == requestedTemplate }) {
                model.selectTemplate(requestedTemplate)
                templatePreselected = true
            }
        }
        OmniScannerScreen(
            state = state,
            onBack = { finish() },
            onTargets = model::updateTargets,
            onPorts = model::updatePorts,
            onConcurrency = model::updateConcurrency,
            onMaxTargets = model::updateMaxTargets,
            onTemplate = model::selectTemplate,
            onStart = model::startScan,
            onStop = model::stopScan,
            onToggle = model::toggleSelection,
            onSelectAll = model::selectAllSuccessful,
            onBuild = model::buildSelected,
            onSave = model::saveBuilt,
            onDownloadUrl = model::updateDownloadUrl,
            onUploadUrl = model::updateUploadUrl,
            onSpeed = model::runSpeedTest,
            onRetest = model::retestSavedThroughCore,
            onPanel = model::selectPanel,
            onPublish = model::publishBuilt,
            onSync = model::syncSelectedPanel,
        )
    }

    companion object {
        const val EXTRA_TEMPLATE_GUID = "omni.scanner.template_guid"
    }
}

@Composable
fun OmniScannerScreen(
    state: OmniScannerUiState,
    onBack: () -> Unit,
    onTargets: (String) -> Unit,
    onPorts: (String) -> Unit,
    onConcurrency: (String) -> Unit,
    onMaxTargets: (String) -> Unit,
    onTemplate: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onBuild: () -> Unit,
    onSave: () -> Unit,
    onDownloadUrl: (String) -> Unit,
    onUploadUrl: (String) -> Unit,
    onSpeed: () -> Unit,
    onRetest: () -> Unit,
    onPanel: (String) -> Unit,
    onPublish: () -> Unit,
    onSync: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_scanner_title), onBackClick = onBack) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.consumeWindowInsets(innerPadding).imePadding(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.omni_scanner_subtitle), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.targetsInput,
                        onValueChange = onTargets,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        label = { Text(stringResource(R.string.omni_scanner_targets)) },
                        supportingText = { Text(stringResource(R.string.omni_scanner_targets_hint)) },
                    )
                    OutlinedTextField(
                        value = state.portsInput,
                        onValueChange = onPorts,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.omni_scanner_ports)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.concurrencyInput,
                            onValueChange = onConcurrency,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.omni_scanner_concurrency)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = state.maxTargetsInput,
                            onValueChange = onMaxTargets,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.omni_scanner_limit)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    TemplatePicker(state, onTemplate)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.scanning) {
                            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.omni_scanner_stop)) }
                        } else {
                            Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.omni_scanner_start)) }
                        }
                        OutlinedButton(onClick = onSelectAll, enabled = state.results.any { it.success }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_scanner_select_successful))
                        }
                    }
                    if (state.scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.omni_scanner_plan, state.plannedTargets, state.plannedProbes, state.invalidTargets))
                    state.message?.let { message ->
                        Text(
                            text = scannerMessageText(message, state.savedCount),
                            color = if (message in setOf(OmniScannerMessage.SAVED, OmniScannerMessage.PUBLISHED, OmniScannerMessage.SYNCED)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onBuild, enabled = !state.building && state.selectedEndpoints.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        if (state.building) CircularProgressIndicator() else Text(stringResource(R.string.omni_scanner_build))
                    }
                    Button(onClick = onSave, enabled = !state.saving && state.built.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        if (state.saving) CircularProgressIndicator() else Text(stringResource(R.string.omni_scanner_save))
                    }
                }
            }
            if (state.built.isNotEmpty()) {
                item { Text(stringResource(R.string.omni_scanner_built, state.built.size), modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            item {
                OmniScannerPanelSection(state, onPanel, onPublish, onSync)
            }
            item {
                OmniSpeedLabSection(state, onDownloadUrl, onUploadUrl, onSpeed, onRetest)
            }
            items(state.results, key = { it.target.endpointId }) { result ->
                OmniScannerResultRow(
                    result = result,
                    selected = result.target.endpointId in state.selectedEndpoints,
                    onToggle = { onToggle(result.target.endpointId) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun OmniSpeedLabSection(
    state: OmniScannerUiState,
    onDownloadUrl: (String) -> Unit,
    onUploadUrl: (String) -> Unit,
    onSpeed: () -> Unit,
    onRetest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.omni_scanner_speed_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.omni_scanner_speed_note), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = state.downloadUrlInput,
                onValueChange = onDownloadUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.omni_scanner_download_url)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.uploadUrlInput,
                onValueChange = onUploadUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.omni_scanner_upload_url)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSpeed, enabled = state.savedGuids.isNotEmpty() && !state.speedRunning, modifier = Modifier.weight(1f)) {
                    if (state.speedRunning) CircularProgressIndicator() else Text(stringResource(R.string.omni_scanner_speed_run))
                }
                OutlinedButton(onClick = onRetest, enabled = state.savedGuids.isNotEmpty() && !state.pingRunning, modifier = Modifier.weight(1f)) {
                    Text(if (state.pingRunning) stringResource(R.string.omni_scanner_running) else stringResource(R.string.omni_scanner_retest))
                }
            }
            val noResponse = stringResource(R.string.omni_scanner_no_response)
            state.connectivity?.let { connection ->
                Text(stringResource(R.string.omni_scanner_tunnel_metrics, connection.ttfbMs?.toString() ?: noResponse, connection.httpStatus?.toString() ?: noResponse))
            }
            state.transfer?.let { transfer ->
                Text(stringResource(R.string.omni_scanner_download_metrics, transfer.downloadBytes, transfer.downloadDurationMs, transfer.downloadMBps, transfer.downloadMbps))
                if (transfer.uploadBytes > 0L || transfer.requestedUploadBytes > 0 && state.uploadUrlInput.isNotBlank()) {
                    Text(stringResource(R.string.omni_scanner_upload_metrics, transfer.uploadBytes, transfer.uploadDurationMs, transfer.uploadMBps, transfer.uploadMbps))
                }
                Text(stringResource(R.string.omni_scanner_data_use, transfer.downloadLimitBytes, transfer.requestedUploadBytes, transfer.dataUsedBytes))
            }
            if (state.pingSamples > 0 || state.pingSuccesses > 0) {
                Text(stringResource(R.string.omni_scanner_ping_metrics, state.pingSuccesses, state.pingSamples, state.pingDelayMs?.toString() ?: noResponse, state.pingJitterMs?.toString() ?: noResponse))
            }
        }
    }
}

@Composable
private fun TemplatePicker(state: OmniScannerUiState, onTemplate: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.templates.firstOrNull { it.guid == state.selectedTemplateGuid }
    val selectedName = selected?.profile?.remarks?.takeIf { it.isNotBlank() }
        ?: if (selected == null) stringResource(R.string.omni_scanner_choose_template) else stringResource(R.string.omni_scanner_unnamed_template)
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = state.templates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(selectedName)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(template.profile.remarks.ifBlank { template.guid }) },
                    onClick = { onTemplate(template.guid); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun OmniScannerResultRow(result: OmniEdgeProbeResult, selected: Boolean, onToggle: () -> Unit) {
    val label = stringResource(R.string.omni_scanner_result_label, result.target.endpointId)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = selected, onValueChange = { onToggle() }, role = Role.Checkbox)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(result.target.endpointId, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.omni_scanner_metrics, result.stats.succeeded, result.stats.attempted, result.stats.medianMs?.toString() ?: stringResource(R.string.omni_scanner_no_response), result.stats.jitterMs?.toString() ?: stringResource(R.string.omni_scanner_no_response)))
            result.evidence?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}


@Composable
private fun OmniScannerPanelSection(
    state: OmniScannerUiState,
    onPanel: (String) -> Unit,
    onPublish: () -> Unit,
    onSync: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.panels.firstOrNull { it.id == state.selectedPanelId }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.omni_scanner_panel_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.omni_scanner_panel_note), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { expanded = true },
                enabled = state.panels.isNotEmpty() && !state.publishing && !state.syncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selected?.name ?: stringResource(R.string.omni_scanner_panel_none))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.panels.forEach { panel ->
                    DropdownMenuItem(
                        text = { Text(panel.name) },
                        onClick = { onPanel(panel.id); expanded = false },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPublish,
                    enabled = selected != null && state.built.isNotEmpty() && !state.publishing && !state.syncing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.publishing) CircularProgressIndicator() else Text(stringResource(R.string.omni_scanner_publish))
                }
                OutlinedButton(
                    onClick = onSync,
                    enabled = selected != null && !state.publishing && !state.syncing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.syncing) CircularProgressIndicator() else Text(stringResource(R.string.omni_scanner_hub_sync))
                }
            }
            state.published?.let { result ->
                Text(
                    stringResource(R.string.omni_scanner_publish_result, result.count, result.group),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(
                        if (result.otherGroupsPreserved && result.relaysUnchanged) {
                            R.string.omni_scanner_publish_preserved
                        } else {
                            R.string.omni_scanner_publish_unconfirmed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.message == OmniScannerMessage.SYNCED) {
                Text(stringResource(R.string.omni_scanner_sync_result, state.syncedCount))
            }
        }
    }
}

@Composable
private fun scannerMessageText(message: OmniScannerMessage, savedCount: Int): String = when (message) {
    OmniScannerMessage.INVALID_INPUT -> stringResource(R.string.omni_scanner_invalid)
    OmniScannerMessage.NO_TEMPLATE -> stringResource(R.string.omni_scanner_no_template)
    OmniScannerMessage.NO_SELECTION -> stringResource(R.string.omni_scanner_no_selection)
    OmniScannerMessage.BUILD_EMPTY -> stringResource(R.string.omni_scanner_build_empty)
    OmniScannerMessage.SAVED -> stringResource(R.string.omni_scanner_saved, savedCount)
    OmniScannerMessage.SAVE_FAILED -> stringResource(R.string.omni_scanner_save_failed)
    OmniScannerMessage.NO_SAVED -> stringResource(R.string.omni_scanner_no_saved)
    OmniScannerMessage.SPEED_FAILED -> stringResource(R.string.omni_scanner_speed_failed)
    OmniScannerMessage.NO_PANEL -> stringResource(R.string.omni_scanner_no_panel)
    OmniScannerMessage.PUBLISH_FAILED -> stringResource(R.string.omni_scanner_publish_failed)
    OmniScannerMessage.PUBLISHED -> stringResource(R.string.omni_scanner_published)
    OmniScannerMessage.SYNC_FAILED -> stringResource(R.string.omni_scanner_sync_failed)
    OmniScannerMessage.SYNCED -> stringResource(R.string.omni_scanner_synced)
    OmniScannerMessage.SCAN_FAILED -> stringResource(R.string.omni_scanner_scan_failed)
}
