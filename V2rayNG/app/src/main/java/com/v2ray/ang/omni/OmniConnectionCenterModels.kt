package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem

enum class OmniConnectionStatus { UNKNOWN, STOPPED, STARTING, CONNECTING, RUNNING, STOPPING, RESTARTING, FAILURE }

enum class OmniConnectionMessage { NO_PROFILE, PERMISSION_DENIED, START_REQUEST_FAILED, START_FAILED, STOP_REQUEST_FAILED, RESTART_REQUEST_FAILED, REFRESH_FAILED }

data class OmniConnectionCenterUiState(
    val status: OmniConnectionStatus = OmniConnectionStatus.UNKNOWN,
    val selectedGuid: String? = null,
    val selectedProfile: ProfileItem? = null,
    val vpnMode: Boolean = true,
    val message: OmniConnectionMessage? = null,
)

fun omniConnectionCanStart(state: OmniConnectionCenterUiState): Boolean =
    !state.selectedGuid.isNullOrBlank() && state.selectedProfile != null && state.status !in setOf(
        OmniConnectionStatus.STARTING,
        OmniConnectionStatus.CONNECTING,
        OmniConnectionStatus.RUNNING,
        OmniConnectionStatus.STOPPING,
        OmniConnectionStatus.RESTARTING,
    )

fun omniConnectionCanStop(status: OmniConnectionStatus): Boolean = status in setOf(OmniConnectionStatus.RUNNING, OmniConnectionStatus.CONNECTING)

fun omniConnectionCanRestart(status: OmniConnectionStatus): Boolean = status == OmniConnectionStatus.RUNNING
