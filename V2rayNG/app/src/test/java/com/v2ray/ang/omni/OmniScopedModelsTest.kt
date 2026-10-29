package com.v2ray.ang.omni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniScopedModelsTest {
    private val session = OmniScopedSession(
        panelId = "panel-1",
        accessToken = "token",
        userUuid = "user-1",
        scopes = setOf("templates:read", "scanner:publish"),
        expiresAt = 2_000L,
    )

    @Test
    fun scopeRequiresLiveBoundSession() {
        assertTrue(session.hasScope("scanner:publish", 1_000L))
        assertTrue(session.hasScope("templates:read", 1_999L))
        assertFalse(session.hasScope("device:report", 1_000L))
        assertFalse(session.hasScope("scanner:publish", 2_000L))
        assertFalse(session.hasScope("scanner:publish", 2_001L))
    }

    @Test
    fun blankTokenOrUserCannotAuthorize() {
        assertFalse(session.copy(accessToken = "").validAt(1_000L))
        assertFalse(session.copy(userUuid = "").validAt(1_000L))
        assertFalse(session.copy(accessToken = "").hasScope("scanner:publish", 1_000L))
    }
}
