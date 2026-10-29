package com.v2ray.ang.omni.lab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val MIN_TRANSFER_BYTES = 1_024
private const val MAX_TRANSFER_BYTES = 16 * 1_024 * 1_024

data class OmniTunnelConnectivity(
    val success: Boolean,
    val ttfbMs: Long? = null,
    val httpStatus: Int? = null,
    val responseBytes: Long = 0L,
    val error: String? = null,
)

data class OmniTransferResult(
    val success: Boolean,
    val downloadBytes: Long = 0L,
    val uploadBytes: Long = 0L,
    val downloadDurationMs: Long = 0L,
    val uploadDurationMs: Long = 0L,
    val requestedUploadBytes: Int = 0,
    val downloadLimitBytes: Int = 0,
    val error: String? = null,
) {
    val downloadMBps: Double get() = rateMBps(downloadBytes, downloadDurationMs)
    val uploadMBps: Double get() = rateMBps(uploadBytes, uploadDurationMs)
    val downloadMbps: Double get() = downloadMBps * 8.0
    val uploadMbps: Double get() = uploadMBps * 8.0
    val dataUsedBytes: Long get() = downloadBytes + uploadBytes
    val durationMs: Long get() = downloadDurationMs + uploadDurationMs
}

private fun rateMBps(bytes: Long, durationMs: Long): Double = if (bytes <= 0L || durationMs <= 0L) 0.0 else bytes / 1_048_576.0 / (durationMs / 1_000.0)

/**
 * Real byte/time transfer measurements through the core's loopback SOCKS listener. This class does not
 * own a native core object: OmniCoreRuntime is the service bridge and its start/stop methods own that lifecycle.
 */
class OmniTransferLab(
    private val runtime: OmniCoreRuntime,
    private val clientFactory: (Int) -> OkHttpClient = { port -> coreSocksClient(port) },
    private val configurationFactory: (String) -> String = ::createOmniNativeExitConfiguration,
) {
    suspend fun connectivity(
        originalConfig: String,
        url: String,
    ): OmniTunnelConnectivity = withContext(Dispatchers.IO) {
        val config = try {
            configurationFactory(originalConfig)
        } catch (error: Exception) {
            return@withContext OmniTunnelConnectivity(false, error = error.message ?: "Invalid configuration")
        }
        if (!runtime.start(config)) return@withContext OmniTunnelConnectivity(false, error = "Core did not start")
        try {
            val client = clientFactory(runtime.socksPort)
            val request = Request.Builder().url(url).header("Connection", "close").build()
            val started = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val ttfb = elapsedMs(started)
                // execute() has received the response headers; do not download the body a second time for TTFB.
                OmniTunnelConnectivity(response.isSuccessful, ttfb, response.code, 0L, if (response.isSuccessful) null else "HTTP ${response.code}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OmniTunnelConnectivity(false, error = error.message ?: error.javaClass.simpleName)
        } finally {
            runtime.stop()
        }
    }

    suspend fun measure(
        originalConfig: String,
        downloadUrl: String,
        uploadUrl: String? = null,
        uploadBytes: Int = 1_048_576,
        downloadLimitBytes: Int = 4 * 1_048_576,
    ): OmniTransferResult = withContext(Dispatchers.IO) {
        require(uploadBytes in MIN_TRANSFER_BYTES..MAX_TRANSFER_BYTES) { "Upload size is outside the bounded test range" }
        require(downloadLimitBytes in MIN_TRANSFER_BYTES..MAX_TRANSFER_BYTES) { "Download size is outside the bounded test range" }
        val config = try {
            configurationFactory(originalConfig)
        } catch (error: Exception) {
            return@withContext OmniTransferResult(false, requestedUploadBytes = if (uploadUrl == null) 0 else uploadBytes, downloadLimitBytes = downloadLimitBytes, error = error.message)
        }
        if (!runtime.start(config)) return@withContext OmniTransferResult(false, requestedUploadBytes = if (uploadUrl == null) 0 else uploadBytes, downloadLimitBytes = downloadLimitBytes, error = "Core did not start")
        var downloaded = 0L
        var uploaded = 0L
        var downloadDuration = 0L
        var uploadDuration = 0L
        var countingBody: CountingRequestBody? = null
        try {
            val client = clientFactory(runtime.socksPort)
            val downloadStarted = System.nanoTime()
            client.newCall(Request.Builder().url(downloadUrl).header("Connection", "close").build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Download HTTP ${response.code}")
                val input = response.body.byteStream()
                val buffer = ByteArray(32 * 1_024)
                while (downloaded < downloadLimitBytes) {
                    val wanted = minOf(buffer.size.toLong(), downloadLimitBytes - downloaded).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) break
                    if (read == 0) continue
                    downloaded += read
                }
            }
            downloadDuration = elapsedMs(downloadStarted).coerceAtLeast(1L)
            if (uploadUrl != null) {
                val payload = ByteArray(uploadBytes) { (it % 251).toByte() }
                val uploadStarted = System.nanoTime()
                val body = CountingRequestBody(payload)
                countingBody = body
                client.newCall(Request.Builder().url(uploadUrl).post(body).build()).execute().use { response ->
                    uploaded = body.bytesWritten
                    if (!response.isSuccessful) throw java.io.IOException("Upload HTTP ${response.code}")
                }
                uploadDuration = elapsedMs(uploadStarted).coerceAtLeast(1L)
            }
            OmniTransferResult(true, downloaded, uploaded, downloadDuration, uploadDuration, if (uploadUrl == null) 0 else uploadBytes, downloadLimitBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            uploaded = countingBody?.bytesWritten ?: uploaded
            OmniTransferResult(false, downloaded, uploaded, downloadDuration, uploadDuration, if (uploadUrl == null) 0 else uploadBytes, downloadLimitBytes, error.message ?: error.javaClass.simpleName)
        } finally {
            runtime.stop()
        }
    }

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
}

private class CountingRequestBody(private val payload: ByteArray) : RequestBody() {
    var bytesWritten: Long = 0L
        private set

    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = payload.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < payload.size) {
            val count = minOf(32 * 1_024, payload.size - offset)
            sink.write(payload, offset, count)
            bytesWritten += count
            offset += count
        }
    }
}

private fun coreSocksClient(port: Int): OkHttpClient = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
