package com.v2ray.ang.omni

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class OmniConfigManagerActivity : BaseComponentActivity() {
    private val model: OmniConfigManagerViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        var showDelete by remember { mutableStateOf(false) }
        OmniConfigManagerScreen(
            state = state,
            onBack = { finish() },
            onQuery = model::updateQuery,
            onFilter = model::selectFilter,
            onSelect = model::select,
            onRefresh = model::refresh,
            onPing = model::retestSelected,
            onShare = model::shareSelected,
            onDelete = { showDelete = true },
            onOptimize = { guid ->
                startActivity(
                    Intent(this, OmniScannerActivity::class.java)
                        .putExtra(OmniScannerActivity.EXTRA_TEMPLATE_GUID, guid),
                )
            },
        )
        if (showDelete) {
            AlertDialog(
                onDismissRequest = { showDelete = false },
                title = { Text(stringResource(R.string.omni_config_delete_title)) },
                text = { Text(stringResource(R.string.omni_config_delete_confirm)) },
                confirmButton = {
                    TextButton(onClick = { showDelete = false; model.deleteSelected() }) {
                        Text(stringResource(R.string.omni_config_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDelete = false }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
fun OmniConfigManagerScreen(
    state: OmniConfigManagerUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (OmniProtocolFilter) -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOptimize: (String) -> Unit,
) {
    val selected = state.entries.firstOrNull { it.guid == state.selectedGuid }
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_config_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.omni_config_subtitle), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.omni_config_search)) },
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(OmniProtocolFilter.entries) { filter ->
                            FilterChip(
                                selected = state.protocolFilter == filter,
                                onClick = { onFilter(filter) },
                                label = { Text(protocolFilterText(filter)) },
                            )
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_config_refresh))
                        }
                        Button(onClick = onPing, enabled = selected != null && !state.pingRunning && !state.busy, modifier = Modifier.weight(1f)) {
                            if (state.pingRunning) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text(stringResource(R.string.omni_config_ping))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { selected?.let { onOptimize(it.guid) } },
                            enabled = selected?.profile?.configType in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN) && !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.omni_config_optimize)) }
                        OutlinedButton(onClick = onShare, enabled = selected != null && !state.busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.omni_config_share))
                        }
                        TextButton(onClick = onDelete, enabled = selected != null && !state.busy && !state.pingRunning) {
                            Text(stringResource(R.string.omni_config_delete))
                        }
                    }
                    state.message?.let { Text(configMessageText(it), color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(16.dp)) }
            } else if (state.visibleEntries.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.entries.isEmpty()) R.string.omni_config_empty else R.string.omni_config_no_matches),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(state.visibleEntries, key = { it.guid }) { entry ->
                OmniConfigRow(entry, entry.guid == state.selectedGuid, onSelect)
            }
        }
    }
}

@Composable
private fun OmniConfigRow(entry: OmniConfigEntry, selected: Boolean, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .selectable(selected = selected, onClick = { onSelect(entry.guid) }, role = Role.RadioButton),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.profile.remarks.ifBlank { stringResource(R.string.omni_config_unnamed) }, style = MaterialTheme.typography.titleMedium)
            Text(entry.profile.configType.name, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(
                    R.string.omni_config_endpoint,
                    entry.profile.server.orEmpty().ifBlank { stringResource(R.string.omni_config_no_endpoint) },
                    entry.profile.serverPort.orEmpty().ifBlank { stringResource(R.string.omni_config_no_endpoint) },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(omniConfigPingText(entry), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun omniConfigPingText(entry: OmniConfigEntry): String = when {
    !entry.tested -> stringResource(R.string.omni_config_untested)
    entry.delayMs == null -> stringResource(R.string.omni_config_no_response)
    else -> stringResource(
        R.string.omni_config_ping_metrics,
        entry.successfulSamples,
        entry.sampleCount,
        entry.delayMs,
        entry.jitterMs?.toString() ?: stringResource(R.string.omni_config_no_response),
    )
}

@Composable
private fun protocolFilterText(filter: OmniProtocolFilter): String = when (filter) {
    OmniProtocolFilter.ALL -> stringResource(R.string.omni_config_filter_all)
    OmniProtocolFilter.VLESS -> stringResource(R.string.omni_config_filter_vless)
    OmniProtocolFilter.VMESS -> stringResource(R.string.omni_config_filter_vmess)
    OmniProtocolFilter.TROJAN -> stringResource(R.string.omni_config_filter_trojan)
    OmniProtocolFilter.OTHER -> stringResource(R.string.omni_config_filter_other)
}

@Composable
private fun configMessageText(message: OmniConfigMessage): String = when (message) {
    OmniConfigMessage.LOAD_FAILED -> stringResource(R.string.omni_config_load_failed)
    OmniConfigMessage.DELETE_FAILED -> stringResource(R.string.omni_config_delete_failed)
    OmniConfigMessage.DELETED -> stringResource(R.string.omni_config_deleted)
    OmniConfigMessage.SHARE_FAILED -> stringResource(R.string.omni_config_share_failed)
    OmniConfigMessage.SHARED -> stringResource(R.string.omni_config_shared)
    OmniConfigMessage.PING_FAILED -> stringResource(R.string.omni_config_ping_failed)
}
