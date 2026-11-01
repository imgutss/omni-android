package com.v2ray.ang.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.v2ray.ang.AppConfig
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Owns the official DNSTT or SlipNet-compatible NoizDNS process for the selected profile. */
object DnsttCoreManager {
    private const val OFFICIAL_BINARY_NAME = "libdnstt.so"
    private const val NOIZDNS_BINARY_NAME = "libnoizdns.so"
    private const val SESSION_ENV = "OMNI_DNSTT_SESSION"
    private const val KEY_ENV = "OMNI_DNSTT_KEY"
    private const val SOCKS_USER_ENV = "OMNI_DNSTT_SOCKS_USER"
    private const val SOCKS_PASSWORD_ENV = "OMNI_DNSTT_SOCKS_PASSWORD"
    private const val READY_POLL_MS = 250L
    private const val EXIT_WAIT_MS = 2_000L
    private const val EXIT_POLL_MS = 50L
    internal const val PORT_CHECK_WAIT_MS = 5_000L

    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DnsttLifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var session: Session? = null

    val isRunning: Boolean get() = session != null
    val currentPort: Int? get() = session?.core?.port
    val currentKey: String? get() = session?.core?.key

    internal data class LiveProcess(val pid: Int, val port: Int, val key: String?)

    internal fun liveProcess(context: Context): LiveProcess? {
        val expected = listOf(
            binary(context, DnsttFmt.ENGINE_OFFICIAL).absolutePath,
            binary(context, DnsttFmt.ENGINE_NOIZDNS).absolutePath,
        )
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val argv = readProcWords(File(entry, "cmdline")) ?: return@forEach
            if (argv.firstOrNull() !in expected) return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if ("$SESSION_ENV=1" !in environment) return@forEach
            val port = argv.lastOrNull()?.substringAfterLast(':')?.toIntOrNull() ?: return@forEach
            val key = environment.firstOrNull { it.startsWith("$KEY_ENV=") }?.substringAfter('=')
            return LiveProcess(pid, port, key)
        }
        return null
    }

    private fun readProcWords(file: File): List<String>? = try {
        file.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    internal fun awaitStopped(timeoutMs: Long): Boolean {
        val barrier = FutureTask(Callable { Unit })
        lifecycle.execute(barrier)
        return try {
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            barrier.cancel(true)
            false
        }
    }

    private fun binary(context: Context, engine: String = DnsttFmt.ENGINE_OFFICIAL): File {
        val name = if (engine == DnsttFmt.ENGINE_NOIZDNS) NOIZDNS_BINARY_NAME else OFFICIAL_BINARY_NAME
        return File(context.applicationInfo.nativeLibraryDir, name)
    }

    fun isSupported(context: Context, engine: String = DnsttFmt.ENGINE_OFFICIAL): Boolean =
        binary(context, engine).canExecute()

    internal sealed interface PortCheck {
        data object Free : PortCheck
        data object Taken : PortCheck
        data class Unchecked(val cause: Exception? = null) : PortCheck
    }

    internal fun checkSessionPort(port: Int, timeoutMs: Long = PORT_CHECK_WAIT_MS): PortCheck {
        val check = FutureTask(Callable { if (portFree(port)) PortCheck.Free else PortCheck.Taken })
        lifecycle.execute(check)
        return try {
            check.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            check.cancel(true)
            PortCheck.Unchecked(e)
        } catch (e: Exception) {
            PortCheck.Unchecked(e)
        }
    }

    internal fun portFree(port: Int): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(AppConfig.LOOPBACK, port))
            true
        }
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun start(
        context: Context,
        core: DnsttCore,
        protect: ((Int) -> Boolean)?,
        reapStaleProcesses: Boolean = true,
        onExit: () -> Unit,
    ) {
        stop()
        val next = Session(context.applicationContext, core, onExit)
        session = next
        lifecycle.execute { open(next, protect, reapStaleProcesses) }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        current.stopRequested.set(true)
        session = null
        DnsttSshTunnel.stop(current.core.key)
        lifecycle.execute { finish(current, notifyExit = false) }
    }

    private fun open(target: Session, protect: ((Int) -> Boolean)?, reapStaleProcesses: Boolean) {
        try {
            if (session !== target) return
            if (reapStaleProcesses) reapStale(target.context)
            val directory = sessionDirectory(target)
            target.directory = directory
            if (protect != null) {
                val socketName = "omni-dnstt-${android.os.Process.myPid()}-${target.id}"
                target.protectServer = MasterDnsProtectServer(socketName, protect) { error ->
                    LogUtil.w(AppConfig.TAG, "DNSTT: protect server request failed", error)
                }
            }
            val process = ProcessBuilder(processArguments(target)).apply {
                directory(directory)
                redirectErrorStream(true)
                environment().apply {
                    put(SESSION_ENV, "1")
                    put(KEY_ENV, target.core.key)
                    put("HOME", directory.absolutePath)
                    put("TMPDIR", target.context.cacheDir.absolutePath)
                    if (target.core.ssh == null && target.core.socksUsername.isNotEmpty()) {
                        put(SOCKS_USER_ENV, target.core.socksUsername)
                        put(SOCKS_PASSWORD_ENV, target.core.socksPassword)
                    }
                }
            }.start()
            runCatching { process.outputStream.close() }
            target.process = process
            target.outputThread = thread(name = "DnsttOutput", isDaemon = true) { forward(target, process) }
            target.watchThread = thread(name = "DnsttWatch", isDaemon = true) {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    // finish still runs below.
                }
                finish(target, notifyExit = true)
            }
            if (target.core.ssh != null) {
                DnsttSshTunnel.start(target.core) { error ->
                    LogUtil.e(AppConfig.TAG, "DNSTT+SSH: chained session failed", error)
                    lifecycle.execute {
                        if (session === target && !target.stopRequested.get()) finish(target, notifyExit = true)
                    }
                }
            }
            LogUtil.i(AppConfig.TAG, "DNSTT: ${target.core.engine} core started on ${AppConfig.LOOPBACK}:${target.core.tunnelPort}")
            if (session !== target) finish(target, notifyExit = false)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "DNSTT: core start failed", e)
            finish(target, notifyExit = true)
        }
    }

    internal fun processArguments(target: Session): List<String> = processArguments(
        binary = binary(target.context, target.core.engine),
        core = target.core,
        protectAddress = target.protectServer?.configAddress,
    )

    internal fun processArguments(binary: File, core: DnsttCore, protectAddress: String?): List<String> = buildList {
        add(binary.absolutePath)
        if (core.engine == DnsttFmt.ENGINE_NOIZDNS) {
            // The Go client takes host:port endpoints, not SlipNet's profile
            // authority suffixes. Keep those suffixes in the saved profile and
            // Omni URI, and pass their values separately to the mixed-tunnel
            // composite; only the explicit/uniform pool mode becomes -authoritative.
            val engineResolver = DnsttFmt.engineResolver(core.resolver)
            val resolverAuthorities = DnsttFmt.resolverAuthorities(core.resolver)
            if (resolverAuthorities.isNotEmpty()) {
                add("-resolver-authoritative=${resolverAuthorities.joinToString(",") { if (it) "1" else "0" }}")
            }
            if (core.ssh != null) add("-raw-tcp")
            when (core.transport) {
                DnsttFmt.Transport.DOH -> add("-doh=$engineResolver")
                DnsttFmt.Transport.DOT -> add("-dot=$engineResolver")
                DnsttFmt.Transport.UDP -> add("-udp=$engineResolver")
                DnsttFmt.Transport.TCP -> add("-tcp=$engineResolver")
            }
            add("-pubkey=${core.publicKey}")
            if (core.utls.isNotBlank()) add("-utls=${core.utls}")
            if (core.authoritative) add("-authoritative")
            if (core.noiz) add("-noiz")
            if (core.stealth) add("-stealth")
            if (core.maxPayload > 0) add("-max-payload=${core.maxPayload}")
            add("-resolver-mode=${core.resolverMode}")
            add("-rr-spread=${core.rrSpreadCount}")
        } else {
            add("-${core.transport.type}=${core.resolver}")
            add("-pubkey=${core.publicKey}")
            if (core.utls.isNotBlank()) add("-utls=${core.utls}")
        }
        if (protectAddress != null) add("-fd-control-unix-socket=$protectAddress")
        add(core.domain)
        add("${AppConfig.LOOPBACK}:${core.tunnelPort}")
    }

    private fun sessionDirectory(target: Session): File {
        val directory = File(target.context.cacheDir, "dnstt/${target.id}")
        if (!directory.mkdirs() && !directory.isDirectory) error("Cannot create DNSTT session directory")
        Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        return directory
    }

    private fun forward(target: Session, process: Process) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val text = DnsttFmt.redact(line)
                    if (text.isNotEmpty()) LogUtil.i(AppConfig.TAG, "[dnstt] $text")
                }
            }
        } catch (e: Exception) {
            if (session === target) LogUtil.w(AppConfig.TAG, "DNSTT: output reader ended unexpectedly", e)
        }
    }

    /** Readiness requires a real no-auth SOCKS5 response through the remote tunnel end. */
    suspend fun awaitListening(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (session != null) {
            val current = session ?: return@withTimeoutOrNull false
            val process = current.process
            if (process != null && !isAlive(process)) return@withTimeoutOrNull false
            val sshReady = current.core.ssh == null || DnsttSshTunnel.isReady(current.core.key)
            if (process != null && sshReady && withContext(Dispatchers.IO) { answersSocks(current.core.port) }) {
                return@withTimeoutOrNull true
            }
            delay(READY_POLL_MS)
        }
        false
    } ?: false

    internal fun answersSocks(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(AppConfig.LOOPBACK, port), 500)
            socket.soTimeout = 1_000
            socket.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val response = ByteArray(2)
            val read = socket.getInputStream().read(response)
            read == 2 && response.contentEquals(byteArrayOf(0x05, 0x00))
        }
    } catch (_: Exception) {
        false
    }

    private fun finish(target: Session, notifyExit: Boolean) {
        val current = session === target
        val shouldNotify = notifyExit && current && !target.stopRequested.get()
        if (current) session = null
        if (!target.cleaned.compareAndSet(false, true)) {
            if (shouldNotify) target.onExit()
            return
        }
        var stopped = true
        DnsttSshTunnel.stop(target.core.key)
        try {
            stopped = target.process?.let(::endProcess) ?: true
        } catch (e: Exception) {
            stopped = false
            LogUtil.w(AppConfig.TAG, "DNSTT: process cleanup failed", e)
        }
        target.protectServer?.close()
        if (stopped) {
            target.directory?.deleteRecursively()
        } else {
            LogUtil.e(AppConfig.TAG, "DNSTT: child is still alive after bounded teardown")
        }
        if (shouldNotify) target.onExit()
    }

    private fun endProcess(process: Process): Boolean {
        process.destroy()
        if (awaitUntil(EXIT_WAIT_MS) { !isAlive(process) }) return true
        process.destroyForcibly()
        awaitUntil(EXIT_WAIT_MS) { !isAlive(process) }
        return !isAlive(process)
    }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(EXIT_POLL_MS)
        }
        return condition()
    }

    private fun reapStale(context: Context) {
        val expected = listOf(
            binary(context, DnsttFmt.ENGINE_OFFICIAL).absolutePath,
            binary(context, DnsttFmt.ENGINE_NOIZDNS).absolutePath,
        )
        val stale = mutableListOf<Int>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            if (pid == android.os.Process.myPid()) return@forEach
            val command = runCatching {
                String(File(entry, "cmdline").readBytes(), Charsets.UTF_8).substringBefore('\u0000')
            }.getOrNull() ?: return@forEach
            val environment = readProcWords(File(entry, "environ")).orEmpty()
            if (command in expected && "$SESSION_ENV=1" in environment) stale += pid
        }
        stale.forEach { pid -> runCatching { android.os.Process.sendSignal(pid, OsConstants.SIGTERM) } }
        if (stale.isEmpty()) return
        if (awaitUntil(EXIT_WAIT_MS) { stale.none { File("/proc/$it").exists() } }) return
        stale.filter { File("/proc/$it").exists() }.forEach { pid -> runCatching { android.os.Process.killProcess(pid) } }
    }

    internal class Session(
        val context: Context,
        val core: DnsttCore,
        val onExit: () -> Unit,
        val id: String = UUID.randomUUID().toString(),
    ) {
        @Volatile var process: Process? = null
        @Volatile var protectServer: MasterDnsProtectServer? = null
        @Volatile var directory: File? = null
        @Volatile var outputThread: Thread? = null
        @Volatile var watchThread: Thread? = null
        val cleaned = AtomicBoolean(false)
        val stopRequested = AtomicBoolean(false)
    }
}
