package com.v2ray.ang.omni

/** Sensitive only inside OmniPanelStore's encrypted payload; never include this in a panel UI model. */
data class OmniScopedSession(
    val panelId: String,
    val accessToken: String,
    val userUuid: String,
    val scopes: Set<String>,
    val expiresAt: Long,
) {
    fun validAt(now: Long): Boolean = accessToken.isNotBlank() && userUuid.isNotBlank() && expiresAt > now

    fun hasScope(scope: String, now: Long): Boolean = validAt(now) && scope in scopes
}

data class OmniScannerPublishResult(
    val count: Int,
    val group: String,
    val otherGroupsPreserved: Boolean,
    val relaysUnchanged: Boolean,
)

data class OmniScopedTemplateGroup(
    val source: String,
    val uris: List<String>,
)

data class OmniScopedTemplates(
    val userUuid: String,
    val username: String,
    val subscriptionUrl: String,
    val groups: List<OmniScopedTemplateGroup>,
) {
    val uriCount: Int get() = groups.sumOf { it.uris.size }
}
