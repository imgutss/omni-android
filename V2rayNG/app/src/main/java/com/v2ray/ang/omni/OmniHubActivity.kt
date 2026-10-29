package com.v2ray.ang.omni

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class OmniHubActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        OmniHubScreen(onBack = { finish() })
    }
}

@Composable
fun OmniHubScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { OmniHubRepository(context) }
    val scope = rememberCoroutineScope()
    var panels by remember { mutableStateOf<List<OmniPanel>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showPair by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<OmniPanel?>(null) }

    suspend fun reload() {
        panels = repository.panels()
    }

    LaunchedEffect(Unit) {
        runCatching { reload() }.onFailure { message = it.message }
    }

    fun runTask(task: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                task()
                reload()
            } catch (error: Exception) {
                message = error.message ?: error.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.title_omni_panel), onBackClick = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.omni_hub_subtitle), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.omni_hub_secure_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAdd = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_hub_add_subscription))
                    }
                    OutlinedButton(onClick = { showPair = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.omni_hub_pair_code))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            runTask {
                                panels.forEach { panel -> repository.sync(panel) }
                                message = context.getString(R.string.omni_hub_sync_finished)
                            }
                        },
                        enabled = !busy && panels.isNotEmpty(),
                    ) { Text(stringResource(R.string.omni_hub_sync_all)) }
                    Spacer(Modifier.width(12.dp))
                    if (busy) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (panels.isEmpty() && !busy) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.omni_hub_empty), Modifier.padding(16.dp))
                    }
                }
            }
            items(panels, key = { it.id }) { panel ->
                OmniPanelCard(
                    panel = panel,
                    busy = busy,
                    onSync = {
                        runTask {
                            val count = repository.sync(panel)
                            message = context.getString(R.string.omni_hub_synced, count)
                        }
                    },
                    onCheck = {
                        runTask {
                            val health = repository.check(panel)
                            message = health.rawDetail
                        }
                    },
                    onRemove = { removeTarget = panel },
                    onDoctor = {
                        context.startActivity(
                            Intent(context, OmniDoctorActivity::class.java)
                                .putExtra(OmniDoctorActivity.EXTRA_PANEL_ID, panel.id)
                        )
                    },
                )
            }
        }
    }

    if (showAdd) {
        OmniAddSubscriptionDialog(
            busy = busy,
            onDismiss = { showAdd = false },
            onConfirm = { name, url, insecure ->
                showAdd = false
                runTask {
                    repository.addSubscription(name, url, insecure)
                    message = context.getString(R.string.omni_hub_added)
                }
            },
        )
    }
    if (showPair) {
        OmniPairDialog(
            busy = busy,
            onDismiss = { showPair = false },
            onConfirm = { base, code, name, insecure ->
                showPair = false
                runTask {
                    repository.pairWithCode(base, code, name, insecure)
                    message = context.getString(R.string.omni_hub_added)
                }
            },
        )
    }
    removeTarget?.let { panel ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.omni_hub_remove)) },
            text = {
                Column {
                    Text(stringResource(R.string.omni_hub_remove_confirm))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.omni_hub_remove_warning))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    runTask {
                        repository.remove(panel)
                        message = context.getString(R.string.omni_hub_removed)
                    }
                }) { Text(stringResource(R.string.omni_hub_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OmniPanelCard(
    panel: OmniPanel,
    busy: Boolean,
    onSync: () -> Unit,
    onCheck: () -> Unit,
    onRemove: () -> Unit,
    onDoctor: () -> Unit,
) {
    val status = runCatching { OmniPanelStatus.valueOf(panel.lastStatus) }.getOrDefault(OmniPanelStatus.INVALID)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(panel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(panel.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(omniPanelStatusText(status), color = MaterialTheme.colorScheme.primary)
            if (panel.lastStatusDetail.isNotBlank()) {
                Text(panel.lastStatusDetail, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (panel.lastSyncAt > 0L) {
                    stringResource(
                        R.string.omni_hub_last_sync,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(panel.lastSyncAt)),
                    )
                } else stringResource(R.string.omni_hub_not_synced),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSync, enabled = !busy) { Text(stringResource(R.string.omni_hub_sync)) }
                TextButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.omni_hub_check)) }
                TextButton(onClick = onDoctor) { Text(stringResource(R.string.title_omni_doctor)) }
                TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.omni_hub_remove)) }
            }
        }
    }
}

@Composable
private fun omniPanelStatusText(status: OmniPanelStatus): String = stringResource(
    when (status) {
        OmniPanelStatus.NOT_CHECKED -> R.string.omni_hub_status_not_checked
        OmniPanelStatus.READY -> R.string.omni_hub_status_ready
        OmniPanelStatus.AUTH_OR_EDGE_REFUSED -> R.string.omni_hub_status_auth
        OmniPanelStatus.NOT_FOUND -> R.string.omni_hub_status_not_found
        OmniPanelStatus.TIMEOUT -> R.string.omni_hub_status_timeout
        OmniPanelStatus.DNS_ERROR -> R.string.omni_hub_status_dns
        OmniPanelStatus.TLS_ERROR -> R.string.omni_hub_status_tls
        OmniPanelStatus.NETWORK_ERROR -> R.string.omni_hub_status_network
        OmniPanelStatus.HTTP_ERROR -> R.string.omni_hub_status_http
        OmniPanelStatus.INVALID -> R.string.omni_hub_status_invalid
    }
)

@Composable
private fun OmniAddSubscriptionDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.omni_hub_add_subscription)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.omni_hub_name)) }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(stringResource(R.string.omni_hub_subscription_url)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                    Text(stringResource(R.string.omni_hub_allow_insecure), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, insecure) }, enabled = !busy && url.isNotBlank()) {
                Text(stringResource(R.string.omni_hub_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun OmniPairDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, Boolean) -> Unit,
) {
    var base by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var insecure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.omni_hub_pair_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.omni_hub_pair_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text(stringResource(R.string.omni_hub_base_url)) }, singleLine = true)
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text(stringResource(R.string.omni_hub_pairing_code)) }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.omni_hub_name)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                    Text(stringResource(R.string.omni_hub_allow_insecure), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(base, code, name, insecure) }, enabled = !busy && base.isNotBlank() && code.isNotBlank()) {
                Text(stringResource(R.string.omni_hub_pair))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
