package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.omni.lab.OmniTransferLab
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class OmniSpeedLabViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniConfigManagerRepository(application)
    private val mutableState = MutableStateFlow(OmniSpeedLabUiState())
    val state: StateFlow<OmniSpeedLabUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var speedJob: Job? = null
    @Volatile private var pingWorker: RealPingWorkerService? = null
    @Volatile private var activeRuntime: OmniNativeCoreRuntime? = null
    @Volatile private var pingResultReceived = false

    init { refresh() }

    fun refresh() {
        if (mutableState.value.speedRunning || mutableState.value.pingRunning || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val entries = repository.load()
                mutableState.update { current ->
                    current.copy(
                        entries = entries,
                        selectedGuid = current.selectedGuid?.takeIf { guid -> entries.any { it.guid == guid } } ?: entries.firstOrNull()?.guid,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniSpeedLabMessage.LOAD_FAILED) }
            }
        }
    }

    fun selectProfile(guid: String) {
        if (mutableState.value.speedRunning || mutableState.value.pingRunning) return
        mutableState.update { current ->
            if (current.entries.none { it.guid == guid }) current else current.copy(
                selectedGuid = guid,
                connectivity = null,
                transfer = null,
                pingTested = false,
                pingDelayMs = null,
                pingSamples = 0,
                pingSuccesses = 0,
                pingJitterMs = null,
                message = null,
            )
        }
    }

    fun updateDownloadUrl(value: String) = clearTransferOnInput { it.copy(downloadUrlInput = value) }
    fun updateUploadUrl(value: String) = clearTransferOnInput { it.copy(uploadUrlInput = value) }
    fun updateDownloadSize(value: String) = clearTransferOnInput { it.copy(downloadSizeInput = value) }
    fun updateUploadSize(value: String) = clearTransferOnInput { it.copy(uploadSizeInput = value) }

    private fun clearTransferOnInput(change: (OmniSpeedLabUiState) -> OmniSpeedLabUiState) {
        if (!mutableState.value.speedRunning) {
            mutableState.update { current -> change(current).copy(connectivity = null, transfer = null, message = null) }
        }
    }

    fun runSpeedTest() {
        val current = mutableState.value
        val guid = current.selectedGuid
        if (guid == null || current.entries.none { it.guid == guid }) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.NO_PROFILE) }; return
        }
        val downloadUrl = current.downloadUrlInput.trim()
        val uploadUrl = current.uploadUrlInput.trim().takeIf { it.isNotEmpty() }
        val downloadSize = parseOmniTransferSize(current.downloadSizeInput)
        val uploadSize = parseOmniTransferSize(current.uploadSizeInput)
        if (!validHttpUrl(downloadUrl) || (uploadUrl != null && !validHttpUrl(uploadUrl)) || downloadSize == null || (uploadUrl != null && uploadSize == null)) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.INVALID_INPUT) }; return
        }
        if (current.speedRunning || current.pingRunning) return
        speedJob = viewModelScope.launch {
            mutableState.update { it.copy(speedRunning = true, connectivity = null, transfer = null, message = null) }
            val runtime = OmniNativeCoreRuntime(getApplication())
            activeRuntime = runtime
            try {
                val config = withContext(Dispatchers.IO) { CoreConfigManager.getV2rayConfig4Speedtest(getApplication(), guid) }
                if (!config.status || config.content.isBlank() || config.aetherCore != null) {
                    mutableState.update { it.copy(message = OmniSpeedLabMessage.SPEED_FAILED) }
                    return@launch
                }
                val lab = OmniTransferLab(runtime)
                val connection = lab.connectivity(config.content, downloadUrl)
                mutableState.update { it.copy(connectivity = connection) }
                if (!connection.success) {
                    mutableState.update { it.copy(message = OmniSpeedLabMessage.SPEED_FAILED) }
                    return@launch
                }
                val result = lab.measure(config.content, downloadUrl, uploadUrl, uploadSize ?: 1_048_576, downloadSize)
                mutableState.update { it.copy(transfer = result, message = if (result.success) null else OmniSpeedLabMessage.SPEED_FAILED) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = OmniSpeedLabMessage.SPEED_FAILED) }
            } finally {
                runCatching { runtime.stop() }
                activeRuntime = null
                mutableState.update { it.copy(speedRunning = false) }
            }
        }
    }

    fun runRealPing() {
        val current = mutableState.value
        val guid = current.selectedGuid
        if (guid == null || current.entries.none { it.guid == guid }) {
            mutableState.update { it.copy(message = OmniSpeedLabMessage.NO_PROFILE) }; return
        }
        if (current.speedRunning || current.pingRunning || pingWorker != null) return
        pingResultReceived = false
        mutableState.update {
            it.copy(pingRunning = true, pingTested = false, pingDelayMs = null, pingSamples = 0, pingSuccesses = 0, pingJitterMs = null, message = null)
        }
        val worker = RealPingWorkerService(getApplication(), listOf(guid)) { event ->
            when (event) {
                is RealPingEvent.Result -> {
                    pingResultReceived = true
                    viewModelScope.launch {
                        runCatching { repository.recordPing(event) }
                        mutableState.update {
                            it.copy(
                                pingTested = true,
                                pingDelayMs = event.delayMillis.takeIf { delay -> delay > 0L && event.successfulSamples > 0 },
                                pingSamples = event.sampleCount,
                                pingSuccesses = event.successfulSamples,
                                pingJitterMs = event.jitterMillis.takeIf { delay -> delay >= 0L && event.successfulSamples > 0 },
                            )
                        }
                    }
                }
                is RealPingEvent.Finish -> {
                    mutableState.update { it.copy(pingRunning = false, message = if (pingResultReceived) it.message else OmniSpeedLabMessage.PING_FAILED) }
                    pingWorker = null
                }
                is RealPingEvent.Progress -> Unit
            }
        }
        pingWorker = worker
        worker.start()
    }

    private fun validHttpUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        return url.scheme in setOf("http", "https") && url.username.isEmpty() && url.password.isEmpty()
    }

    override fun onCleared() {
        refreshJob?.cancel()
        speedJob?.cancel()
        pingWorker?.cancel()
        runCatching { activeRuntime?.stop() }
        activeRuntime = null
        super.onCleared()
    }
}
