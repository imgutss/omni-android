package com.v2ray.ang.core

import com.v2ray.ang.fmt.DnsttFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class DnsttCoreManagerTest {
    private val core = DnsttCore(
        transport = DnsttFmt.Transport.UDP,
        resolver = "1.1.1.1:53",
        domain = "t.example.com",
        publicKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
        utls = DnsttFmt.DEFAULT_UTLS,
    )

    @Test
    fun processArgumentsUseTheOfficialClientFlagsAndAppOwnedListener() {
        val arguments = DnsttCoreManager.processArguments(
            binary = File("/data/app/lib/libdnstt.so"),
            core = core,
            protectAddress = "@omni-test",
        )
        assertEquals("/data/app/lib/libdnstt.so", arguments.first())
        assertTrue("-udp=1.1.1.1:53" in arguments)
        assertTrue("-pubkey=${core.publicKey}" in arguments)
        assertTrue("-utls=${core.utls}" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertEquals("t.example.com", arguments[arguments.lastIndex - 1])
        assertEquals("127.0.0.1:18001", arguments.last())
    }

    @Test
    fun proxyAndRootModesDoNotInventAProtectEndpoint() {
        val arguments = DnsttCoreManager.processArguments(File("/bin/libdnstt.so"), core, null)
        assertFalse(arguments.any { it.startsWith("-fd-control-unix-socket=") })
    }

    @Test
    fun readinessRequiresARealSocksNoAuthGreetingFromTheRemoteEnd() {
        assertTrue(answersWith(byteArrayOf(0x05, 0x00)))
        assertFalse(answersWith(byteArrayOf(0x05, 0x02)))
        assertFalse(answersWith(byteArrayOf(0x04, 0x00)))
        assertFalse(answersWith(byteArrayOf(0x05)))
    }

    private fun answersWith(response: ByteArray): Boolean {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "test-dnstt-socks-greeting", isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val request = ByteArray(3)
                        client.getInputStream().read(request)
                        if (request.contentEquals(byteArrayOf(0x05, 0x01, 0x00))) {
                            client.getOutputStream().write(response)
                        }
                        client.getOutputStream().flush()
                    }
                }
            }
            val result = DnsttCoreManager.answersSocks(server.localPort)
            worker.join(2_000)
            assertFalse(worker.isAlive)
            return result
        }
    }
}
