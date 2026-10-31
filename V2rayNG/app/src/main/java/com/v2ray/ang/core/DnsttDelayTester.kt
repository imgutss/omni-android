package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Runs measurements through a live or bounded test DNSTT process; never probes an empty port. */
object DnsttDelayTester {
    internal const val TEST_BUDGET_MS = 12_000L
    internal const val STARTUP_BUDGET_MS = 45_000L
    const val UNTESTED = 0L
    private const val POLL_INTERVAL_MS = 200L
    private val tunnels = Mutex()

    suspend fun measureVia(
        context: Context,
        guid: String,
        core: DnsttCore,
        probe: suspend (port: Int, deadline: Long) -> Long,
    ): Long {
        val deadline = deadlineAfter(TEST_BUDGET_MS)
        return withCore(context, core, TEST_BUDGET_MS, guid) {
            probe(core.port, deadline)
        } ?: UNTESTED
    }

    suspend fun <T> withCore(
        context: Context,
        core: DnsttCore,
        startupTimeoutMs: Long = TEST_BUDGET_MS,
        guid: String? = null,
        block: suspend () -> T,
    ): T? = tunnels.withLock {
        if (!DnsttCoreManager.isSupported(context, core.engine)) return@withLock null
        val live = withContext(Dispatchers.IO) { DnsttCoreManager.liveProcess(context) }
        if (live != null) {
            if (live.key != core.key || live.port != core.port) {
                LogUtil.i(AppConfig.TAG, "DnsttTest: another core owns the listener, guid=$guid")
                return@withLock null
            }
            if (!awaitLive(live.port, startupTimeoutMs)) return@withLock null
            return@withLock try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "DnsttTest: live-session probe failed", error)
                null
            }
        }
        if (withContext(Dispatchers.IO) { DnsttCoreManager.checkSessionPort(core.port) } != DnsttCoreManager.PortCheck.Free) {
            LogUtil.i(AppConfig.TAG, "DnsttTest: listener is unavailable, guid=$guid")
            return@withLock null
        }
        var owned = false
        try {
            owned = true
            DnsttCoreManager.start(context, core, protect = null, reapStaleProcesses = false) {}
            if (!DnsttCoreManager.awaitListening(startupTimeoutMs)) {
                LogUtil.i(AppConfig.TAG, "DnsttTest: core did not become ready, guid=$guid")
                null
            } else {
                block()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LogUtil.e(AppConfig.TAG, "DnsttTest: test tunnel failed", error)
            null
        } finally {
            if (owned) {
                DnsttCoreManager.stop()
                withContext(NonCancellable + Dispatchers.IO) { DnsttCoreManager.awaitStopped(5_000L) }
            }
        }
    }

    private suspend fun awaitLive(port: Int, timeoutMs: Long): Boolean {
        val deadline = deadlineAfter(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (withContext(Dispatchers.IO) { DnsttCoreManager.answersSocks(port) }) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun deadlineAfter(ms: Long): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
}
