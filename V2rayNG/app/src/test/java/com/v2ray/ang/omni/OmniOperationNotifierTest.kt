package com.v2ray.ang.omni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniOperationNotifierTest {
    @Test
    fun notificationsRequireBothOptInAndSystemPermission() {
        assertFalse(omniOperationNotificationAllowed(false, false))
        assertFalse(omniOperationNotificationAllowed(false, true))
        assertFalse(omniOperationNotificationAllowed(true, false))
        assertTrue(omniOperationNotificationAllowed(true, true))
    }
}
