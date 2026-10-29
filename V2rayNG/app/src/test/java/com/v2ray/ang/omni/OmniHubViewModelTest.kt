package com.v2ray.ang.omni

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniHubViewModelTest {
    @Test
    fun hubStateStartsEmptyAndDoesNotClaimBusySuccess() {
        val state = OmniHubUiState()
        assertTrue(state.panels.isEmpty())
        assertTrue(state.scopedScopes.isEmpty())
        assertFalse(state.busy)
        assertFalse(state.messageIsError)
    }

    @Test
    fun syncAllOutcomeRetainsPartialFailure() {
        val success = OmniHubSyncOutcome(attempted = 3, succeeded = 3)
        val partial = OmniHubSyncOutcome(attempted = 3, succeeded = 1)
        assertTrue(success.allSucceeded)
        assertTrue(success.failed == 0)
        assertFalse(partial.allSucceeded)
        assertTrue(partial.failed == 2)
        assertTrue(OmniHubSyncOutcome(0, 0).failed == 0)
    }
}
