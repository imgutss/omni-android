package com.v2ray.ang.omni

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
