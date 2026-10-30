package com.v2ray.ang.omni.lab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class OmniTransferLabTest {
    @Test
    fun measuresActualDownloadAndUploadBytesAndClosesRuntime() = runBlocking {
        HttpFixture().use { fixture ->
            val runtime = FakeRuntime()
            val lab = OmniTransferLab(runtime, clientFactory = { OkHttpClient() })
            val result = lab.measure(
                originalConfig = "{\"outbounds\":[{\"protocol\":\"vless\"}]}",
                downloadUrl = fixture.url,
                uploadUrl = fixture.url,
                uploadBytes = 1_024,
                downloadLimitBytes = 65_536,
            )
            assertTrue(result.success)
            assertEquals(65_536L, result.downloadBytes)
            assertEquals(1_024L, result.uploadBytes)
            assertEquals(66_560L, result.dataUsedBytes)
            assertTrue(result.downloadDurationMs > 0L)
            assertTrue(result.uploadDurationMs > 0L)
            assertTrue(result.downloadMBps >= 0.0)
            assertTrue(result.uploadMbps >= 0.0)
            assertFalse(runtime.started)
            assertEquals(1_024, fixture.receivedUpload)
        }
    }

    @Test
    fun failedCoreStartIsNotATransferSuccess() = runBlocking {
        val runtime = FakeRuntime().apply { startResult = false }
        val result = OmniTransferLab(runtime, clientFactory = { OkHttpClient() }).measure(
            "{\"outbounds\":[{\"protocol\":\"vless\"}]}",
            "http://127.0.0.1:1/download",
            uploadBytes = 1_024,
            downloadLimitBytes = 1_024,
        )
        assertFalse(result.success)
        assertEquals(0L, result.dataUsedBytes)
        assertEquals(0, result.requestedUploadBytes)
    }

    private class FakeRuntime : OmniCoreRuntime {
        override var socksPort: Int = 1234
        var started = false
        var startResult = true
        override fun start(exitConfiguration: String): Boolean {
            if (!startResult) return false
            check(exitConfiguration.contains("exit-node"))
            started = true
            return true
        }
        override fun stop() { started = false }
    }

    private class HttpFixture : AutoCloseable {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        @Volatile var receivedUpload = 0
        private val worker = thread(isDaemon = true) {
            repeat(2) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
                        val request = reader.readLine() ?: return@use
                        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        if (request.startsWith("POST")) {
                            val body = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val read = reader.read(body, offset, length - offset)
                                if (read < 0) break
                                offset += read
                            }
                            receivedUpload = offset
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                        } else {
                            val body = ByteArray(65_536) { 7 }
                            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                            socket.getOutputStream().write(body)
                        }
                        socket.getOutputStream().flush()
                    }
                }
            }
        }
        val url: String get() = "http://127.0.0.1:${server.localPort}/download"
        override fun close() { server.close(); worker.join(1_000) }
    }
}
