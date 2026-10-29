package com.v2ray.ang.omni

import com.v2ray.ang.omni.lab.OmniTransferResult
import com.v2ray.ang.omni.lab.OmniTunnelConnectivity

data class OmniSpeedLabUiState(
    val entries: List<OmniConfigEntry> = emptyList(),
    val selectedGuid: String? = null,
    val loading: Boolean = false,
    val downloadUrlInput: String = "https://speed.cloudflare.com/__down?bytes=4194304",
    val uploadUrlInput: String = "",
    val downloadSizeInput: String = "4194304",
    val uploadSizeInput: String = "1048576",
    val speedRunning: Boolean = false,
    val pingRunning: Boolean = false,
    val connectivity: OmniTunnelConnectivity? = null,
    val transfer: OmniTransferResult? = null,
    val pingDelayMs: Long? = null,
    val pingSamples: Int = 0,
    val pingSuccesses: Int = 0,
    val pingJitterMs: Long? = null,
    val pingTested: Boolean = false,
    val message: OmniSpeedLabMessage? = null,
)

enum class OmniSpeedLabMessage {
    LOAD_FAILED,
    NO_PROFILE,
    INVALID_INPUT,
    SPEED_FAILED,
    PING_FAILED,
}

fun parseOmniTransferSize(value: String): Int? = value.trim().toIntOrNull()?.takeIf { it in 1_024..16 * 1_024 * 1_024 }
