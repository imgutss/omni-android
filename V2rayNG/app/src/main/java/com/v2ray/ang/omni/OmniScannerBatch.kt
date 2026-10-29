package com.v2ray.ang.omni

import com.v2ray.ang.omni.lab.OmniBuiltConfiguration

/** Real-ping evidence keyed by the saved profile's stable configuration fingerprint. */
data class OmniBuiltPing(
    val delayMs: Long?,
    val sampleCount: Int,
    val successfulSamples: Int,
    val jitterMs: Long?,
) {
    val successful: Boolean get() = delayMs != null && delayMs > 0L && successfulSamples > 0
}

fun rankOmniBuiltConfigurations(
    configurations: List<OmniBuiltConfiguration>,
    pings: Map<String, OmniBuiltPing>,
): List<OmniBuiltConfiguration> = configurations.sortedWith(
    compareByDescending<OmniBuiltConfiguration> { pings[it.fingerprint]?.successful == true }
        .thenBy { pings[it.fingerprint]?.delayMs?.takeIf { delay -> delay > 0L } ?: Long.MAX_VALUE }
        .thenBy { pings[it.fingerprint]?.jitterMs?.takeIf { jitter -> jitter >= 0L } ?: Long.MAX_VALUE }
        .thenBy { it.target.endpointId },
)
