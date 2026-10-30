package com.v2ray.ang.omni

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OmniSpeedLabLogicTest {
    @Test
    fun transferSizesAreBoundedAndNeverDefaultToAClaim() {
        assertEquals(1_024, parseOmniTransferSize("1024"))
        assertEquals(16 * 1_024 * 1_024, parseOmniTransferSize("16777216"))
        assertNull(parseOmniTransferSize("1023"))
        assertNull(parseOmniTransferSize("16777217"))
        assertNull(parseOmniTransferSize("not-a-number"))
    }
}
