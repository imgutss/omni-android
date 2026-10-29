package com.v2ray.ang.omni.lab

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniProbeOptionsTest {
    @Test
    fun preservesExplicitModeAndTransportInputs() {
        val options = OmniProbeOptions(
            mode = OmniProbeMode.WEBSOCKET,
            samples = 4,
            sni = "edge.example",
            hostHeader = "origin.example",
            httpPath = "/ws",
        )
        assertEquals(OmniProbeMode.WEBSOCKET, options.mode)
        assertEquals(4, options.samples)
        assertEquals("/ws", options.httpPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRequestHeaderInjection() {
        OmniProbeOptions(hostHeader = "example.com\r\nX-Injected: yes")
    }
}
