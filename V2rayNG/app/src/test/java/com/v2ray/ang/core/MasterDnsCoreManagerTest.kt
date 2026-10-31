package com.v2ray.ang.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class MasterDnsCoreManagerTest {
    private val core = MasterDnsCore(
        config = """{"DOMAINS":["v.example.com"],"DATA_ENCRYPTION_METHOD":1,"ENCRYPTION_KEY":"secret"}""",
        resolvers = "8.8.8.8\n",
    )

    @Test
    fun processArgumentsOverrideTheProfileListenerAndKeepSecretsOutOfArgv() {
        val arguments = MasterDnsCoreManager.processArguments(
            binary = File("/data/app/lib/libmasterdns.so"),
            config = File("/private/client_config.json"),
            resolvers = File("/private/client_resolvers.txt"),
            core = core,
            protectAddress = "@omni-test",
        )
        assertEquals("/data/app/lib/libmasterdns.so", arguments.first())
        assertTrue("-config=/private/client_config.json" in arguments)
        assertTrue("-resolvers=/private/client_resolvers.txt" in arguments)
        assertTrue("-protocol-type=SOCKS5" in arguments)
        assertTrue("-listen-ip=127.0.0.1" in arguments)
        assertTrue("-listen-port=18000" in arguments)
        assertTrue("-socks5-auth=false" in arguments)
        assertTrue("-local-dns-enabled=false" in arguments)
        assertTrue("-fd-control-unix-socket=@omni-test" in arguments)
        assertFalse(arguments.joinToString(" ").contains("secret"))
    }

    @Test
    fun proxyAndRootModesLeaveTheProtectEndpointEmpty() {
        val arguments = MasterDnsCoreManager.processArguments(
            File("/bin/libmasterdns.so"), File("/tmp/c.json"), File("/tmp/r.txt"), core, null,
        )
        assertTrue("-fd-control-unix-socket=" in arguments)
    }

    @Test
    fun configFileNameRetainsTheOfficialJsonOrTomlFormat() {
        assertEquals("client_config.json", MasterDnsCoreManager.configFileName(core.config))
        assertEquals("client_config.toml", MasterDnsCoreManager.configFileName("DOMAINS = [\"v.example.com\"]\nENCRYPTION_KEY = \"secret\""))
    }

    @Test
    fun protectProtocolRequiresOneByteOneDescriptorAndTheSameOwner() {
        assertTrue(MasterDnsProtectServer.accepts(1, 1, sameOwner = true))
        assertFalse(MasterDnsProtectServer.accepts(0, 1, sameOwner = true))
        assertFalse(MasterDnsProtectServer.accepts(1, 0, sameOwner = true))
        assertFalse(MasterDnsProtectServer.accepts(1, 2, sameOwner = true))
        assertFalse(MasterDnsProtectServer.accepts(1, 1, sameOwner = false))
    }

    @Test
    fun readinessRequiresARealSocksNoAuthGreeting() {
        assertTrue(answersWith(byteArrayOf(0x05, 0x00)))
        assertFalse(answersWith(byteArrayOf(0x05, 0x02)))
        assertFalse(answersWith(byteArrayOf(0x04, 0x00)))
    }

    private fun answersWith(response: ByteArray): Boolean {
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread(name = "test-socks-greeting", isDaemon = true) {
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
            val result = MasterDnsCoreManager.answersSocks(server.localPort)
            worker.join(2_000)
            assertFalse(worker.isAlive)
            return result
        }
    }
}
