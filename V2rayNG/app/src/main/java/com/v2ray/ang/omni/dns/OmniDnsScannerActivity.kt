package com.v2ray.ang.omni.dns

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar

class OmniDnsScannerActivity : BaseComponentActivity() {
    private val model: OmniDnsScannerViewModel by viewModels()

    @Composable
    override fun ScreenContent() {
        val state by model.state.collectAsStateWithLifecycle()
        OmniDnsScannerScreen(state, model, onBack = { finish() })
    }
}

@Composable
fun OmniDnsScannerScreen(state: OmniDnsScannerUiState, model: OmniDnsScannerViewModel, onBack: () -> Unit) {
    OmniDnsScannerContent(state, model, onBack)
}

@Composable
private fun OmniDnsScannerContent(state: OmniDnsScannerUiState, model: OmniDnsScannerViewModel, onBack: () -> Unit) {
    val busy = state.scanning || state.e2eRunning || state.applying
    var showAll by remember { mutableStateOf(false) }
    val noResponse = stringResource(R.string.omni_scanner_no_response)
    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.omni_dns_scanner_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_dns_scanner_subtitle), style = MaterialTheme.typography.bodyMedium)
                        OmniDnsTemplatePicker(state, model::selectTemplate, busy)
                        OutlinedTextField(
                            value = state.targetsInput, onValueChange = model::updateTargets, enabled = !busy,
                            modifier = Modifier.fillMaxWidth(), minLines = 3,
                            label = { Text(stringResource(R.string.omni_dns_scanner_targets)) },
                        )
                        OutlinedTextField(
                            value = state.portsInput, onValueChange = model::updatePorts, enabled = !busy,
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.omni_scanner_ports)) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OmniDnsNumberField(state.samplesInput, model::updateSamples, !busy, R.string.omni_scanner_samples, Modifier.weight(1f))
                            OmniDnsNumberField(state.timeoutInput, model::updateTimeout, !busy, R.string.omni_dns_scanner_timeout, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OmniDnsNumberField(state.concurrencyInput, model::updateConcurrency, !busy, R.string.omni_scanner_concurrency, Modifier.weight(1f))
                            OmniDnsNumberField(state.maxTargetsInput, model::updateMaxTargets, !busy, R.string.omni_dns_scanner_sample_limit, Modifier.weight(1f))
                        }
                        if (state.sampledTargets > 0) {
                            Text(stringResource(R.string.omni_dns_scanner_plan, state.totalCandidates, state.sampledTargets, state.invalidInputs), style = MaterialTheme.typography.bodySmall)
                        }
                        if (state.scanning) {
                            OutlinedButton(onClick = model::stopScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_scanner_stop)) }
                            LinearProgressIndicator(progress = { state.scanCompleted.toFloat() / state.sampledTargets.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(onClick = model::startScan, enabled = !busy && state.templates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.omni_dns_scanner_start))
                            }
                        }
                        Text(stringResource(R.string.omni_dns_scanner_progress, state.scanCompleted, state.results.count { it.working }), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.omni_dns_scanner_e2e_note), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = state.e2eUrlInput, onValueChange = model::updateE2eUrl, enabled = !busy,
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.omni_dns_scanner_e2e_url)) },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OmniDnsNumberField(state.e2eSamplesInput, model::updateE2eSamples, !busy, R.string.omni_dns_scanner_ping_samples, Modifier.weight(1f))
                            OmniDnsNumberField(state.e2eTimeoutInput, model::updateE2eTimeout, !busy, R.string.omni_dns_scanner_e2e_timeout, Modifier.weight(1f))
                        }
                        if (state.e2eRunning) {
                            OutlinedButton(onClick = model::stopE2e, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.omni_scanner_stop)) }
                            LinearProgressIndicator(progress = { state.e2eCompleted.toFloat() / state.e2eTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(onClick = model::startE2e, enabled = !busy && state.results.any { it.working }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.omni_dns_scanner_run_e2e))
                            }
                        }
                        Text(stringResource(R.string.omni_dns_scanner_e2e_progress, state.e2eCompleted, state.e2eTotal, state.e2eResults.values.count { it.success }), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = model::applyBest, enabled = !busy && state.bestEndpoint != null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.omni_dns_scanner_use_best, state.bestEndpoint ?: noResponse))
                        }
                        state.appliedEndpoint?.let { Text(stringResource(R.string.omni_dns_scanner_applied, it), color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
            state.message?.let { message ->
                item { Text(stringResource(message.resource()), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.primary) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.omni_dns_scanner_show_all), Modifier.weight(1f))
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }
            val visible = if (showAll) state.results else state.results.filter { it.working || state.e2eResults[it.target.endpointId] != null }
            itemsIndexed(visible, key = { _, result -> result.target.endpointId }) { index, result ->
                OmniDnsResultCard(index, result, state.e2eResults[result.target.endpointId], state.bestEndpoint == result.target.endpointId)
            }
        }
    }
}

@Composable
private fun OmniDnsNumberField(value: String, onChange: (String) -> Unit, enabled: Boolean, label: Int, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(label)) },
    )
}

@Composable
private fun OmniDnsTemplatePicker(state: OmniDnsScannerUiState, onSelect: (String) -> Unit, busy: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.templates.firstOrNull { it.guid == state.selectedTemplateGuid }
    OutlinedButton(onClick = { expanded = true }, enabled = !busy && state.templates.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text(selected?.let { "${it.profile.remarks.ifBlank { it.guid }} · ${it.profile.dnsttEngine}" } ?: stringResource(R.string.omni_dns_scanner_choose_profile))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        state.templates.forEach { template ->
            DropdownMenuItem(
                text = { Text("${template.profile.remarks.ifBlank { template.guid }} · ${template.profile.dnsttEngine}") },
                onClick = { onSelect(template.guid); expanded = false },
            )
        }
    }
}

@Composable
private fun OmniDnsResultCard(index: Int, result: OmniDnsResolverResult, e2e: OmniDnsE2eResult?, best: Boolean) {
    val noResponse = stringResource(R.string.omni_scanner_no_response)
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("#${index + 1} · ${result.target.endpointId}" + if (best) " ★" else "", style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (result.working) R.string.omni_dns_scanner_dns_working else R.string.omni_dns_scanner_dns_failed, result.responseCode?.toString() ?: noResponse), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.omni_scanner_metrics, result.stats.succeeded, result.stats.attempted, result.stats.medianMs?.toString() ?: noResponse, result.stats.jitterMs?.toString() ?: noResponse, result.stats.lossPercent), style = MaterialTheme.typography.bodySmall)
            when {
                e2e == null -> Text(stringResource(R.string.omni_dns_scanner_e2e_untested), style = MaterialTheme.typography.bodySmall)
                e2e.stats.attempted == 0 -> Text(stringResource(R.string.omni_dns_scanner_e2e_incomplete, e2e.error ?: noResponse), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(stringResource(R.string.omni_dns_scanner_e2e_ping, e2e.stats.succeeded, e2e.stats.attempted, e2e.stats.medianMs?.toString() ?: noResponse, e2e.stats.jitterMs?.toString() ?: noResponse, e2e.stats.lossPercent), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.omni_scanner_tunnel_metrics, e2e.stats.medianMs?.toString() ?: noResponse, e2e.httpStatus?.toString() ?: noResponse), color = if (e2e.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun OmniDnsScannerMessage.resource(): Int = when (this) {
    OmniDnsScannerMessage.INVALID -> R.string.omni_dns_scanner_invalid
    OmniDnsScannerMessage.NO_TEMPLATE -> R.string.omni_dns_scanner_no_template
    OmniDnsScannerMessage.NO_WORKING -> R.string.omni_dns_scanner_no_working
    OmniDnsScannerMessage.SCAN_FAILED -> R.string.omni_dns_scanner_failed
    OmniDnsScannerMessage.E2E_COMPLETE -> R.string.omni_dns_scanner_e2e_complete
    OmniDnsScannerMessage.E2E_PARTIAL -> R.string.omni_dns_scanner_e2e_partial
    OmniDnsScannerMessage.APPLIED -> R.string.omni_dns_scanner_apply_done
    OmniDnsScannerMessage.APPLY_FAILED -> R.string.omni_dns_scanner_apply_failed
}
