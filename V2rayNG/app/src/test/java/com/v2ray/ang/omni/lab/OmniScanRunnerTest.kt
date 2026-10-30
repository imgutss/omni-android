package com.v2ray.ang.omni.lab

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniScanRunnerTest {
    @Test
    fun maxTargetsBoundsLazyInputAndResultsCanBeRanked() = runBlocking {
        val plan = OmniTargetParser.parse("0.0.0.0/0")
        val results = OmniScanRunner(OmniEdgeProbe { target, options ->
            OmniEdgeProbeResult(target, options.mode, OmniLatencyStats.from(listOf(1L), 1))
        }).scan(plan, OmniScanOptions(concurrency = 2, maxTargets = 3)).toList()
        assertEquals(3, results.size)
        assertEquals(3, results.rankByEdgeQuality().size)
    }
}
