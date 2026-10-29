package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType

/** A profile GUID remains the identity across filtering, refresh and asynchronous ping results. */
data class OmniConfigEntry(
    val guid: String,
    val profile: ProfileItem,
    val delayMs: Long? = null,
    val sampleCount: Int = 0,
    val successfulSamples: Int = 0,
    val jitterMs: Long? = null,
    val tested: Boolean = false,
)

enum class OmniProtocolFilter { ALL, VLESS, VMESS, TROJAN, OTHER }

enum class OmniConfigMessage { LOAD_FAILED, DELETE_FAILED, DELETED, SHARE_FAILED, SHARED, PING_FAILED }

data class OmniConfigManagerUiState(
    val entries: List<OmniConfigEntry> = emptyList(),
    val query: String = "",
    val protocolFilter: OmniProtocolFilter = OmniProtocolFilter.ALL,
    val selectedGuid: String? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val pingRunning: Boolean = false,
    val message: OmniConfigMessage? = null,
) {
    val visibleEntries: List<OmniConfigEntry>
        get() = filterOmniConfigurations(entries, query, protocolFilter)
}

fun filterOmniConfigurations(
    entries: List<OmniConfigEntry>,
    query: String,
    filter: OmniProtocolFilter,
): List<OmniConfigEntry> {
    val needle = query.trim().lowercase()
    return entries.filter { entry ->
        val protocolMatches = when (filter) {
            OmniProtocolFilter.ALL -> true
            OmniProtocolFilter.OTHER -> entry.profile.configType !in setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN)
            else -> entry.profile.configType.name == filter.name
        }
        val queryMatches = needle.isEmpty() || listOf(
            entry.profile.remarks,
            entry.profile.server.orEmpty(),
            entry.profile.serverPort.orEmpty(),
            entry.profile.configType.name,
            entry.guid,
        ).any { it.lowercase().contains(needle) }
        protocolMatches && queryMatches
    }
}
