package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Reader for SlipNet's public, plain-text profile exchange format.
 *
 * SlipNet's plain URI is not a URL with query parameters: the part after
 * slipnet:// is Base64 of a pipe-delimited record. This reader deliberately
 * lives in Omni and does not import SlipNet code. It handles the stable field
 * prefix used by versions 2–29 and ignores unknown trailing fields, just as
 * SlipNet's forward-compatible importer does.
 *
 * Only protocols with a real Omni engine are returned. In particular, an
 * SSH-chained or otherwise unsupported record is not turned into a seemingly
 * usable profile merely because its Base64 decoded successfully.
 */
object SlipNetFmt : FmtBase() {
    private const val SCHEME = "slipnet://"
    private const val MAX_ENCODED_CHARS = 2_000_000
    private const val MODE_DNSTT = "dnstt"
    private const val MODE_NOIZDNS = "sayedns"

    /** Parses one original slipnet:// URI, without a conversion step. */
    fun parse(str: String): ProfileItem? {
        val trimmed = str.trim()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        val encoded = trimmed.substring(SCHEME.length).trim()
        if (encoded.isEmpty() || encoded.length > MAX_ENCODED_CHARS) return null
        val decoded = decode(encoded) ?: return null
        return parseDecoded(decoded)
    }

    fun parseAny(str: String): ProfileItem? = parse(str)

    /**
     * Decodes the common v2+ record prefix. Versions before v2 predate DNSTT;
     * newer versions are accepted when their known prefix remains intact.
     */
    internal fun parseDecoded(data: String): ProfileItem? {
        if (data.isBlank() || data.length > MAX_ENCODED_CHARS) return null
        val fields = data.split('|')
        if (fields.size < 14) return null
        val version = fields[0].toIntOrNull() ?: return null
        if (version < 2) return null

        val mode = fields[1].trim().lowercase()
        val isNoiz = when (mode) {
            MODE_DNSTT -> false
            MODE_NOIZDNS -> true
            else -> return null
        }
        // SSH chaining is a separate lifecycle, not a DNSTT boolean. Do not
        // silently import it as an unchained tunnel.
        if (fields.getOrNull(14) == "1") return null

        val name = fields[2].trim()
        val domain = fields[3].trim()
        val publicKey = fields[11].trim()
        if (name.isEmpty() || domain.isEmpty() || publicKey.isEmpty()) return null

        val transport = fields.getOrNull(22)?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() } ?: "udp"
        if (transport !in setOf("udp", "tcp", "dot", "doh")) return null

        // v17 moved hidden resolvers to field 37 while leaving field 4 empty.
        val resolverData = if (fields.getOrNull(36) == "1" &&
            !fields.getOrNull(37).isNullOrBlank()
        ) fields[37] else fields[4]
        var resolverAuthoritative = false
        val resolver = if (transport == "doh") {
            fields.getOrNull(21)?.trim().orEmpty()
        } else {
            val parsed = canonicalResolvers(resolverData, transport) ?: return null
            resolverAuthoritative = parsed.authoritative
            parsed.endpoints
        }
        if (resolver.isEmpty()) return null

        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = name
            // Both plain DNSTT and NoizDNS SlipNet records use the NoizDNS
            // core: its plain mode retains DNSTT compatibility and also
            // implements authoritative and multi-resolver behavior.
            it.dnsttEngine = DnsttFmt.ENGINE_NOIZDNS
            it.dnsttTransport = transport
            it.dnsttResolver = resolver
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = DnsttFmt.DEFAULT_UTLS
            // SlipNet stores this flag both globally (v13+) and on each
            // resolver. The NoizDNS mobile API applies authoritative tuning to
            // the selected resolver pool, so retain either source of intent.
            it.dnsttAuthoritative = fields.getOrNull(27) == "1" || resolverAuthoritative
            it.dnsttNoiz = isNoiz
            it.dnsttStealth = isNoiz && fields.getOrNull(38) == "1"
            it.dnsttMaxPayload = fields.getOrNull(39)?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
            it.dnsttResolverMode = fields.getOrNull(60)?.trim()?.lowercase()
                ?.takeIf { mode -> mode == "fanout" || mode == "roundrobin" } ?: "fanout"
            it.dnsttRrSpreadCount = fields.getOrNull(61)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
            it.dnsttSocksUsername = fields.getOrNull(12)?.takeIf { value -> value.isNotEmpty() }
            it.dnsttSocksPassword = fields.getOrNull(13)?.takeIf { value -> value.isNotEmpty() }
        }
        return profile.takeIf { DnsttFmt.normalize(it) == null }
    }

    private data class CanonicalResolvers(
        val endpoints: String,
        val authoritative: Boolean,
    )

    /** Converts SlipNet host:port:authoritative entries without losing their mode. */
    private fun canonicalResolvers(raw: String, transport: String): CanonicalResolvers? {
        if (raw.isBlank()) return null
        val result = mutableListOf<String>()
        var authoritative = false
        raw.split(',').forEach { entry ->
            val value = entry.trim()
            if (value.isEmpty() || value.any { it.isWhitespace() || it == '\u0000' }) return null
            val hostPort = when {
                value.startsWith('[') -> {
                    val close = value.indexOf(']')
                    if (close < 0) return null
                    val host = value.substring(1, close)
                    val rest = value.substring(close + 1)
                    val pieces = rest.split(':')
                    val port = pieces.getOrNull(1)?.toIntOrNull() ?: 53
                    if (host.isBlank() || port !in 1..65535) return null
                    authoritative = authoritative || pieces.getOrNull(2) == "1"
                    "[$host]:${dnsPort(port, transport)}"
                }
                else -> {
                    val pieces = value.split(':')
                    if (pieces.size < 2) return null
                    val host = pieces[0]
                    val port = pieces[1].toIntOrNull() ?: 53
                    if (host.isBlank() || port !in 1..65535 || host.contains(':')) return null
                    authoritative = authoritative || pieces.getOrNull(2) == "1"
                    "$host:${dnsPort(port, transport)}"
                }
            }
            result += hostPort
        }
        return result.takeIf { it.isNotEmpty() }?.let {
            CanonicalResolvers(it.joinToString(","), authoritative)
        }
    }

    private fun dnsPort(port: Int, transport: String): Int =
        if (transport == "dot" && port == 53) 853 else port

    private fun decode(encoded: String): String? {
        val compact = encoded.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.length % 4 == 1) return null
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        val bytes = listOf(
            runCatching { Base64.getDecoder().decode(padded) },
            runCatching { Base64.getUrlDecoder().decode(padded) },
        ).firstNotNullOfOrNull { it.getOrNull() } ?: return null
        return runCatching { String(bytes, StandardCharsets.UTF_8) }.getOrNull()
    }
}
