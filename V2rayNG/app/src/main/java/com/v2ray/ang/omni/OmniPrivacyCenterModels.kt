package com.v2ray.ang.omni

data class OmniPrivacyCenterUiState(
    val panels: List<OmniPanel> = emptyList(),
    val scopes: Map<String, Set<String>> = emptyMap(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: OmniPrivacyMessage? = null,
)

enum class OmniPrivacyMessage { LOAD_FAILED, NO_PAIRINGS, REVOKED, PARTIAL, LOCAL_FORGOTTEN, FORGET_FAILED }

fun omniActivePairedPanelIds(scopes: Map<String, Set<String>>): Set<String> =
    scopes.filterValues { it.isNotEmpty() }.keys

fun omniPrivacyRunMessage(attempted: Int, failures: Int): OmniPrivacyMessage = when {
    attempted == 0 -> OmniPrivacyMessage.NO_PAIRINGS
    failures == 0 -> OmniPrivacyMessage.REVOKED
    else -> OmniPrivacyMessage.PARTIAL
}
