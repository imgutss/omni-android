package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmniConnectionCenterLogicTest {
    private val ready = OmniConnectionCenterUiState(selectedGuid = "guid", selectedProfile = ProfileItem(configType = EConfigType.VLESS), status = OmniConnectionStatus.STOPPED)

    @Test
    fun startRequiresARealSelectedProfileAndQuietState() {
        assertTrue(omniConnectionCanStart(ready))
        assertFalse(omniConnectionCanStart(ready.copy(selectedGuid = null)))
        assertFalse(omniConnectionCanStart(ready.copy(selectedProfile = null)))
        assertFalse(omniConnectionCanStart(ready.copy(status = OmniConnectionStatus.STARTING)))
        assertFalse(omniConnectionCanStart(ready.copy(status = OmniConnectionStatus.RUNNING)))
    }

    @Test
    fun stopAndRestartFollowConfirmedDaemonState() {
        assertTrue(omniConnectionCanStop(OmniConnectionStatus.RUNNING))
        assertTrue(omniConnectionCanStop(OmniConnectionStatus.CONNECTING))
        assertFalse(omniConnectionCanStop(OmniConnectionStatus.STARTING))
        assertTrue(omniConnectionCanRestart(OmniConnectionStatus.RUNNING))
        assertFalse(omniConnectionCanRestart(OmniConnectionStatus.UNKNOWN))
    }
}
