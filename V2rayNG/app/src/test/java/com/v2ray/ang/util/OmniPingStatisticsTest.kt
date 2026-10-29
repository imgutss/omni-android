package com.v2ray.ang.util

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniPingStatisticsTest {
    @Test
    fun medianUsesOnlyRealSuccessfulSamples() {
        val result = OmniPingStatistics.summarize(listOf(400L, 100L, 200L), OmniPingStatistics.KIND_HTTP)
        assertEquals(200L, result.delayMillis)
        assertEquals(3, result.sampleCount)
        assertEquals(3, result.successfulSamples)
        assertEquals(200L, result.jitterMillis)
    }

    @Test
    fun failuresAreNotConvertedIntoZeroLatency() {
        val result = OmniPingStatistics.summarize(listOf(-1L, -1L, -1L), OmniPingStatistics.KIND_HTTP)
        assertEquals(-1L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
        assertEquals(3, result.sampleCount)
    }

    @Test
    fun untestedRemainsDistinctFromFailure() {
        val result = OmniPingStatistics.summarize(listOf(0L), OmniPingStatistics.KIND_HTTP)
        assertEquals(0L, result.delayMillis)
        assertEquals(0, result.successfulSamples)
    }

    @Test
    fun partialSuccessRetainsTheAttemptCount() {
        val result = OmniPingStatistics.summarize(listOf(-1L, 250L, 300L), OmniPingStatistics.KIND_HTTP)
        assertEquals(275L, result.delayMillis)
        assertEquals(2, result.successfulSamples)
        assertEquals(3, result.sampleCount)
        assertEquals(50L, result.jitterMillis)
    }
}
