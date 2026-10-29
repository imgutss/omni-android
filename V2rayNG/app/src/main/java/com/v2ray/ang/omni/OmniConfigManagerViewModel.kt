package com.v2ray.ang.omni

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.service.RealPingWorkerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OmniConfigManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OmniConfigManagerRepository(application)
    private val mutableState = MutableStateFlow(OmniConfigManagerUiState())
    val state: StateFlow<OmniConfigManagerUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    @Volatile
    private var pingWorker: RealPingWorkerService? = null
    @Volatile
    private var pingResultReceived = false

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            try {
                val entries = repository.load()
                mutableState.update { current ->
                    val selected = current.selectedGuid?.takeIf { guid -> entries.any { it.guid == guid } }
                    current.copy(entries = entries, selectedGuid = selected, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = OmniConfigMessage.LOAD_FAILED) }
            }
        }
    }

    fun updateQuery(value: String) = mutableState.update { it.copy(query = value, message = null) }

    fun selectFilter(filter: OmniProtocolFilter) = mutableState.update { it.copy(protocolFilter = filter, message = null) }

    fun select(guid: String) = mutableState.update { current ->
        current.copy(selectedGuid = guid.takeIf { id -> current.entries.any { it.guid == id } }, message = null)
    }

    fun deleteSelected() {
        val guid = mutableState.value.selectedGuid ?: return
        if (mutableState.value.busy || mutableState.value.pingRunning) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                if (!repository.delete(guid)) throw IllegalStateException("Profile storage refused deletion")
                mutableState.update { current ->
                    current.copy(
                        entries = current.entries.filterNot { it.guid == guid },
                        selectedGuid = null,
                        busy = false,
                        message = OmniConfigMessage.DELETED,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(busy = false, message = OmniConfigMessage.DELETE_FAILED) }
            }
        }
    }

    fun shareSelected() {
        val guid = mutableState.value.selectedGuid ?: return
        if (mutableState.value.busy) return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            try {
                val shared = repository.share(guid)
                mutableState.update { it.copy(busy = false, message = if (shared) OmniConfigMessage.SHARED else OmniConfigMessage.SHARE_FAILED) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(busy = false, message = OmniConfigMessage.SHARE_FAILED) }
            }
        }
    }

    fun retestSelected() {
        val guid = mutableState.value.selectedGuid ?: return
        if (pingWorker != null || mutableState.value.pingRunning) return
        pingResultReceived = false
        mutableState.update { it.copy(pingRunning = true, message = null) }
        val worker = RealPingWorkerService(getApplication(), listOf(guid)) { event ->
            when (event) {
                is RealPingEvent.Result -> {
                    pingResultReceived = true
                    viewModelScope.launch {
                        try {
                            repository.recordPing(event)
                            mutableState.update { current ->
                                current.copy(entries = current.entries.map { entry ->
                                    if (entry.guid != event.guid) entry else entry.copy(
                                        delayMs = event.delayMillis.takeIf { it > 0L && event.successfulSamples > 0 },
                                        sampleCount = event.sampleCount,
                                        successfulSamples = event.successfulSamples,
                                        jitterMs = event.jitterMillis.takeIf { it >= 0L && event.successfulSamples > 0 },
                                        tested = true,
                                    )
                                })
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.update { it.copy(message = OmniConfigMessage.PING_FAILED) }
                        }
                    }
                }
                is RealPingEvent.Finish -> {
                    mutableState.update {
                        it.copy(
                            pingRunning = false,
                            message = if (pingResultReceived) it.message else OmniConfigMessage.PING_FAILED,
                        )
                    }
                    pingWorker = null
                }
                is RealPingEvent.Progress -> Unit
            }
        }
        pingWorker = worker
        worker.start()
    }

    fun canOptimizeSelected(): Boolean {
        val selected = mutableState.value.entries.firstOrNull { it.guid == mutableState.value.selectedGuid }
        return selected?.profile?.configType in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
    }

    override fun onCleared() {
        refreshJob?.cancel()
        pingWorker?.cancel()
        pingWorker = null
        super.onCleared()
    }
}
