package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.omni.lab.OmniTransferLab
import com.v2ray.ang.omni.lab.OmniTransferResult
import com.v2ray.ang.omni.lab.OmniTunnelConnectivity
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.omni.lab.OmniBuildOptions
import com.v2ray.ang.omni.lab.OmniBuiltConfiguration
import com.v2ray.ang.omni.lab.OmniEdgeProbeResult
import com.v2ray.ang.omni.lab.OmniEndpointConfigFactory
import com.v2ray.ang.omni.lab.OmniScanOptions
import com.v2ray.ang.omni.lab.OmniScanRunner
import com.v2ray.ang.omni.lab.OmniTargetParser
import com.v2ray.ang.omni.lab.rankByEdgeQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_SCAN_TARGETS = 100_000

enum class OmniScannerMessage { INVALID_INPUT, NO_TEMPLATE, NO_SELECTION, BUILD_EMPTY, SAVED, SAVE_FAILED, NO_SAVED, SPEED_FAILED, NO_PANEL, PUBLISH_FAILED, PUBLISHED, SYNC_FAILED, SYNCED, SCAN_FAILED }

data class OmniScannerUiState(
    val targetsInput: String = "1.1.1.1",
    val portsInput: String = "443",
    val concurrencyInput: String = "24",
    val maxTargetsInput: String = "1000",
    val templates: List<OmniScannerTemplate> = emptyList(),
    val selectedTemplateGuid: String? = null,
    val plannedTargets: Long = 0L,
    val plannedProbes: Long = 0L,
    val invalidTargets: Int = 0,
    val results: List<OmniEdgeProbeResult> = emptyList(),
    val selectedEndpoints: Set<String> = emptySet(),
    val built: List<OmniBuiltConfiguration> = emptyList(),
    val scanning: Boolean = false,
    val building: Boolean = false,
    val saving: Boolean = false,
    val message: OmniScannerMessage? = null,
    val savedCount: Int = 0,
    val savedGuids: List<String> = emptyList(),
    val downloadUrlInput: String = "https://speed.cloudflare.com/__down?bytes=4194304",
    val uploadUrlInput: String = "",
    val speedRunning: Boolean = false,
    val connectivity: OmniTunnelConnectivity? = null,
    val transfer: OmniTransferResult? = null,
    val pingRunning: Boolean = false,
    val pingDelayMs: Long? = null,
    val pingSamples: Int = 0,
    val pingSuccesses: Int = 0,
    val pingJitterMs: Long? = null,
    val panels: List<OmniPanel> = emptyList(),
    val selectedPanelId: String? = null,
    val publishing: Boolean = false,
    val published: OmniScannerPublishResult? = null,
    val syncing: Boolean = false,
    val syncedCount: Int = 0,
)

class OmniScannerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniScannerRepository()
    private val runner = OmniScanRunner()
    private val hub = OmniHubRepository(application)
    private var pingWorker: RealPingWorkerService? = null
    private val mutableState = MutableStateFlow(OmniScannerUiState())
    val state: StateFlow<OmniScannerUiState> = mutableState.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            val templates = repository.templates()
            val panels = runCatching { hub.panels() }.getOrDefault(emptyList())
            mutableState.update {
                it.copy(
                    templates = templates,
                    selectedTemplateGuid = templates.firstOrNull()?.guid,
                    panels = panels,
                    selectedPanelId = panels.firstOrNull()?.id,
                )
            }
        }
    }

    fun updateTargets(value: String) = mutableState.update { it.copy(targetsInput = value, message = null) }
    fun updatePorts(value: String) = mutableState.update { it.copy(portsInput = value, message = null) }
    fun updateConcurrency(value: String) = mutableState.update { it.copy(concurrencyInput = value, message = null) }
    fun updateMaxTargets(value: String) = mutableState.update { it.copy(maxTargetsInput = value, message = null) }
    fun updateDownloadUrl(value: String) = mutableState.update { it.copy(downloadUrlInput = value, connectivity = null, transfer = null, message = null) }
    fun updateUploadUrl(value: String) = mutableState.update { it.copy(uploadUrlInput = value, connectivity = null, transfer = null, message = null) }
    fun selectTemplate(guid: String) = mutableState.update { it.copy(selectedTemplateGuid = guid, message = null) }
    fun selectPanel(panelId: String) = mutableState.update { it.copy(selectedPanelId = panelId, message = null) }

    fun startScan() {
        if (mutableState.value.scanning) return
        scanJob = viewModelScope.launch {
            val current = mutableState.value
            val ports = runCatching { OmniTargetParser.parsePorts(current.portsInput) }.getOrElse {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return@launch
            }
            val concurrency = OmniTargetParser.parseCount(current.concurrencyInput)
                ?.takeIf { it in 1L..128L }?.toInt()
            val maxTargets = OmniTargetParser.parseCount(current.maxTargetsInput)
                ?.takeIf { it in 1L..MAX_SCAN_TARGETS.toLong() }?.toInt()
            if (concurrency == null || maxTargets == null) {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return@launch
            }
            val plan = withContext(Dispatchers.Default) { OmniTargetParser.parse(current.targetsInput) }
            if (!plan.hasTargets) {
                mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT, invalidTargets = plan.invalid.size) }; return@launch
            }
            val plannedProbes = plan.plannedProbeCount(ports)
            mutableState.update {
                it.copy(
                    plannedTargets = minOf(plan.targetCount, maxTargets.toLong()),
                    plannedProbes = minOf(plannedProbes, maxTargets.toLong()),
                    invalidTargets = plan.invalid.size,
                    results = emptyList(), selectedEndpoints = emptySet(), built = emptyList(), savedCount = 0,
                    scanning = true, message = null,
                )
            }
            try {
                runner.scan(plan, OmniScanOptions(ports, concurrency, maxTargets)).collect { result ->
                    val ranked = withContext(Dispatchers.Default) { (mutableState.value.results + result).rankByEdgeQuality() }
                    mutableState.update { state -> state.copy(results = ranked) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniScannerMessage.SCAN_FAILED) }
            } finally {
                mutableState.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.update { it.copy(scanning = false) }
    }

    fun toggleSelection(endpointId: String) = mutableState.update { state ->
        val selected = state.selectedEndpoints.toMutableSet().apply { if (!add(endpointId)) remove(endpointId) }
        state.copy(selectedEndpoints = selected, message = null)
    }

    fun selectAllSuccessful() = mutableState.update { state ->
        state.copy(selectedEndpoints = state.results.filter { it.success }.map { it.target.endpointId }.toSet(), message = null)
    }

    fun buildSelected() {
        val current = mutableState.value
        val template = current.templates.firstOrNull { it.guid == current.selectedTemplateGuid }?.profile
        if (template == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_TEMPLATE) }; return
        }
        val selected = current.results.filter { it.target.endpointId in current.selectedEndpoints && it.success }.map { it.target }
        if (selected.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SELECTION) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(building = true, message = null) }
            try {
                val built = withContext(Dispatchers.Default) { OmniEndpointConfigFactory.buildBatch(template, selected, OmniBuildOptions()) }
                mutableState.update { it.copy(building = false, built = built, message = if (built.isEmpty()) OmniScannerMessage.BUILD_EMPTY else null) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(building = false, built = emptyList(), message = OmniScannerMessage.BUILD_EMPTY) }
            }
        }
    }

    fun saveBuilt() {
        if (mutableState.value.saving) return
        val built = mutableState.value.built
        if (built.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.BUILD_EMPTY) }; return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, message = null) }
            try {
                val ids = repository.saveBuilt(built)
                mutableState.update { it.copy(saving = false, savedCount = ids.size, savedGuids = ids, message = OmniScannerMessage.SAVED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, message = OmniScannerMessage.SAVE_FAILED) }
            }
        }
    }

    fun publishBuilt() {
        val current = mutableState.value
        val panel = current.panels.firstOrNull { it.id == current.selectedPanelId }
        if (panel == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_PANEL) }; return
        }
        if (current.built.isEmpty()) {
            mutableState.update { it.copy(message = OmniScannerMessage.BUILD_EMPTY) }; return
        }
        if (current.publishing) return
        viewModelScope.launch {
            mutableState.update { it.copy(publishing = true, published = null, message = null) }
            try {
                val result = hub.publishScannerPack(panel, current.built.map { it.profile })
                mutableState.update { it.copy(publishing = false, published = result, message = OmniScannerMessage.PUBLISHED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(publishing = false, message = OmniScannerMessage.PUBLISH_FAILED) }
            }
        }
    }

    fun syncSelectedPanel() {
        val panel = mutableState.value.panels.firstOrNull { it.id == mutableState.value.selectedPanelId }
        if (panel == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_PANEL) }; return
        }
        if (mutableState.value.syncing) return
        viewModelScope.launch {
            mutableState.update { it.copy(syncing = true, message = null) }
            try {
                val count = hub.sync(panel)
                mutableState.update { it.copy(syncing = false, syncedCount = count, message = OmniScannerMessage.SYNCED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(syncing = false, message = OmniScannerMessage.SYNC_FAILED) }
            }
        }
    }

    fun runSpeedTest() {
        val current = mutableState.value
        val guid = current.savedGuids.firstOrNull()
        if (guid == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SAVED) }; return
        }
        val downloadUrl = current.downloadUrlInput.trim()
        val uploadUrl = current.uploadUrlInput.trim().takeIf { it.isNotEmpty() }
        if (!isHttpUrl(downloadUrl) || (uploadUrl != null && !isHttpUrl(uploadUrl))) {
            mutableState.update { it.copy(message = OmniScannerMessage.INVALID_INPUT) }; return
        }
        if (current.speedRunning) return
        viewModelScope.launch {
            mutableState.update { it.copy(speedRunning = true, connectivity = null, transfer = null, message = null) }
            try {
                val config = withContext(Dispatchers.IO) { CoreConfigManager.getV2rayConfig4Speedtest(getApplication(), guid) }
                if (!config.status || config.content.isBlank() || config.aetherCore != null) {
                    mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
                    return@launch
                }
                val lab = OmniTransferLab(OmniNativeCoreRuntime(getApplication()))
                val connection = lab.connectivity(config.content, downloadUrl)
                mutableState.update { it.copy(connectivity = connection) }
                if (!connection.success) {
                    mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
                    return@launch
                }
                val transfer = lab.measure(config.content, downloadUrl, uploadUrl)
                mutableState.update { it.copy(transfer = transfer, message = if (transfer.success) null else OmniScannerMessage.SPEED_FAILED) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniScannerMessage.SPEED_FAILED) }
            } finally {
                mutableState.update { it.copy(speedRunning = false) }
            }
        }
    }

    fun retestSavedThroughCore() {
        val guid = mutableState.value.savedGuids.firstOrNull()
        if (guid == null) {
            mutableState.update { it.copy(message = OmniScannerMessage.NO_SAVED) }; return
        }
        if (mutableState.value.pingRunning) return
        mutableState.update { it.copy(pingRunning = true, pingDelayMs = null, pingSamples = 0, pingSuccesses = 0, pingJitterMs = null) }
        val worker = RealPingWorkerService(getApplication(), listOf(guid)) { event ->
            when (event) {
                is RealPingEvent.Result -> mutableState.update {
                    it.copy(
                        pingDelayMs = event.delayMillis.takeIf { delay -> delay >= 0L },
                        pingSamples = event.sampleCount,
                        pingSuccesses = event.successfulSamples,
                        pingJitterMs = event.jitterMillis.takeIf { delay -> delay >= 0L },
                    )
                }
                is RealPingEvent.Finish -> mutableState.update { it.copy(pingRunning = false) }
                is RealPingEvent.Progress -> Unit
            }
        }
        pingWorker = worker
        worker.start()
    }

    private fun isHttpUrl(value: String): Boolean = value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

    override fun onCleared() {
        scanJob?.cancel()
        pingWorker?.cancel()
        super.onCleared()
    }
}
