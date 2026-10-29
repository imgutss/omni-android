package com.v2ray.ang.omni

import android.content.Context
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Config Manager uses the existing MMKV profile store and formatters; it has no shadow profile database. */
class OmniConfigManagerRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun load(): List<OmniConfigEntry> = withContext(Dispatchers.IO) {
        MmkvManager.decodeAllServerList().asSequence().distinct().mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val affiliation = MmkvManager.decodeServerAffiliationInfo(guid)
            val hasSamples = (affiliation?.testSampleCount ?: 0) > 0
            val hasLegacyResult = affiliation != null && affiliation.testSampleCount == 0 && affiliation.testDelayMillis != 0L
            val successful = affiliation?.testSuccessfulSamples ?: 0
            val delay = affiliation?.testDelayMillis?.takeIf {
                it > 0L && ((hasSamples && successful > 0) || hasLegacyResult)
            }
            OmniConfigEntry(
                guid = guid,
                profile = profile,
                delayMs = delay,
                sampleCount = affiliation?.testSampleCount ?: 0,
                successfulSamples = successful,
                jitterMs = affiliation?.testJitterMillis?.takeIf { hasSamples && successful > 0 && it >= 0L },
                tested = hasSamples || hasLegacyResult,
            )
        }.toList()
    }

    suspend fun delete(guid: String): Boolean = withContext(Dispatchers.IO) {
        MmkvManager.tryRemoveServer(guid)
    }

    suspend fun share(guid: String): Boolean = withContext(Dispatchers.IO) {
        AngConfigManager.share2Clipboard(appContext, guid) == 0
    }

    suspend fun recordPing(result: RealPingEvent.Result) = withContext(Dispatchers.IO) {
        MmkvManager.encodeServerTestResult(
            guid = result.guid,
            testResult = result.delayMillis,
            sampleCount = result.sampleCount,
            successfulSamples = result.successfulSamples,
            jitterMillis = result.jitterMillis,
            kind = result.kind,
        )
    }
}
