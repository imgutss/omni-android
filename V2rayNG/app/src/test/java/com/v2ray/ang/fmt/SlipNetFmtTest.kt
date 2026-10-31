package com.v2ray.ang.fmt

import com.v2ray.ang.core.DnsttCore
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SlipNetFmtTest {
    private val key = "f49e4f032b927429e1e0cf825b02cddadef90882e01ac99bb6940f39a2629a3a"

    private fun fixture(mode: String = "dnstt"): MutableList<String> = MutableList(82) { "" }.also {
        it[0] = "29"
        it[1] = mode
        it[2] = "SlipNet test"
        it[3] = "ns2.motoclub.site"
        it[4] = "192.168.88.118:53:1"
        it[5] = "0"
        it[6] = "5000"
        it[7] = "bbr"
        it[8] = "1080"
        it[9] = "127.0.0.1"
        it[10] = "0"
        it[11] = key
        it[14] = "0"
        it[22] = "udp"
        it[27] = "1"
        it[38] = "0"
        it[39] = "0"
        it[60] = "roundrobin"
        it[61] = "3"
    }

    private fun uri(fields: List<String>): String =
        "slipnet://" + Base64.getEncoder().encodeToString(fields.joinToString("|").toByteArray(StandardCharsets.UTF_8))

    @Test
    fun originalSlipNetDnsttLinkImportsWithoutManualConversion() {
        val profile = SlipNetFmt.parse(uri(fixture()))
        assertNotNull(profile)
        assertEquals(EConfigType.DNSTT, profile?.configType)
        assertEquals("ns2.motoclub.site", profile?.dnsttDomain)
        assertEquals("192.168.88.118:53", profile?.dnsttResolver)
        assertEquals(key, profile?.dnsttPublicKey)
        assertEquals(DnsttFmt.ENGINE_NOIZDNS, profile?.dnsttEngine)
        assertTrue(profile?.dnsttAuthoritative == true)
        assertFalse(profile?.dnsttNoiz == true)
        assertEquals("roundrobin", profile?.dnsttResolverMode)
        assertEquals(3, profile?.dnsttRrSpreadCount)
    }

    @Test
    fun noizAndMultiResolverFieldsSurviveImport() {
        val fields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,8.8.8.8:53:0"
            it[38] = "1"
            it[39] = "80"
            it[60] = "fanout"
        }
        val profile = SlipNetFmt.parse(uri(fields))
        assertNotNull(profile)
        assertEquals("1.1.1.1:53,8.8.8.8:53", profile?.dnsttResolver)
        assertTrue(profile?.dnsttNoiz == true)
        assertTrue(profile?.dnsttStealth == true)
        assertEquals(80, profile?.dnsttMaxPayload)
    }

    @Test
    fun resolverAuthoritativeFlagReachesTheNoizDnsCore() {
        val fields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:1"
            it[27] = "0"
        }
        val profile = SlipNetFmt.parse(uri(fields)) ?: error("Profile should import")
        assertTrue(profile.dnsttAuthoritative == true)
        assertTrue(DnsttCore.of(profile).authoritative)

        val ordinary = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:0"
            it[27] = "0"
        }
        assertFalse(SlipNetFmt.parse(uri(ordinary))?.dnsttAuthoritative == true)
    }

    @Test
    fun resolverAuthoritativeFlagSurvivesOmniUriRoundTrip() {
        val fields = fixture("sayedns").also {
            it[4] = "1.1.1.1:53:0,9.9.9.9:53:1"
            it[27] = ""
        }
        val imported = SlipNetFmt.parse(uri(fields)) ?: error("Profile should import")
        val omniUri = EConfigType.DNSTT.protocolScheme + DnsttFmt.toUri(imported)
        val restored = DnsttFmt.parse(omniUri) ?: error("Omni URI should parse")
        assertTrue(restored.dnsttAuthoritative == true)
        assertTrue(DnsttCore.of(restored).authoritative)
        assertEquals(imported.dnsttResolver, restored.dnsttResolver)
        assertTrue(omniUri.contains("authoritative=1"))
    }

    @Test
    fun hiddenResolverAuthoritativeFlagIsAlsoPreserved() {
        val hidden = fixture("sayedns").also {
            it[4] = ""
            it[27] = ""
            it[36] = "1"
            it[37] = "[2001:db8::1]:53:1,9.9.9.9:53:0"
        }
        val profile = SlipNetFmt.parse(uri(hidden)) ?: error("Hidden profile should import")
        assertEquals("[2001:db8::1]:53,9.9.9.9:53", profile.dnsttResolver)
        assertTrue(profile.dnsttAuthoritative == true)
        assertTrue(DnsttCore.of(profile).authoritative)
    }

    @Test
    fun hiddenResolversAndDohAreReadFromTheirOriginalFields() {
        val hidden = fixture().also {
            it[4] = ""
            it[36] = "1"
            it[37] = "9.9.9.9:53:0"
        }
        assertEquals("9.9.9.9:53", SlipNetFmt.parse(uri(hidden))?.dnsttResolver)

        val doh = fixture().also {
            it[4] = ""
            it[21] = "https://dns.google/dns-query"
            it[22] = "doh"
        }
        assertEquals("https://dns.google/dns-query", SlipNetFmt.parse(uri(doh))?.dnsttResolver)
        assertEquals("doh", SlipNetFmt.parse(uri(doh))?.dnsttTransport)

        val dot = fixture().also {
            it[4] = "1.1.1.1:53:0"
            it[22] = "dot"
        }
        assertEquals("1.1.1.1:853", SlipNetFmt.parse(uri(dot))?.dnsttResolver)
    }

    @Test
    fun olderKnownVersionsAndTcpUseTheSameDirectPath() {
        val old = fixture().take(28).toMutableList().also { it[0] = "13" }
        assertNotNull(SlipNetFmt.parse(uri(old)))
        val tcp = fixture().also { it[22] = "tcp" }
        assertEquals("tcp", SlipNetFmt.parse(uri(tcp))?.dnsttTransport)
    }

    @Test
    fun sshChainingAndMalformedRecordsAreNotFalselyImported() {
        assertNull(SlipNetFmt.parse(uri(fixture("dnstt_ssh"))))
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[14] = "1" })))
        assertNull(SlipNetFmt.parse("slipnet://not-base64"))
        assertNull(SlipNetFmt.parse(uri(fixture().also { it[11] = "short" })))
        assertNull(SlipNetFmt.parse("dnstt://?transport=udp&resolver=1.1.1.1&domain=t.example.com&pubkey=$key"))
    }
}
