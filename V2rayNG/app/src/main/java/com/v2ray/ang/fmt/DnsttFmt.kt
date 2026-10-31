package com.v2ray.ang.fmt

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Omni's DNSTT profile format. The engine is the official dnstt-client; its
 * remote end must forward the tunnel to a no-auth SOCKS5 service for Omni's
 * loopback SOCKS outbound to be meaningful. The URI is Omni's own format, not
 * a claim that another application's DNSTT share link is identical.
 */
object DnsttFmt : FmtBase() {
    const val DEFAULT_UTLS = "4*random,3*Firefox_120,1*Firefox_105,3*Chrome_120,1*Chrome_102,1*iOS_14,1*iOS_13"
    private const val DEFAULT_REMARKS = "DNSTT"

    enum class Transport(val type: String, val defaultPort: Int) {
        DOH("doh", 443), DOT("dot", 853), UDP("udp", 53);

        companion object {
            fun from(value: String?): Transport? = entries.firstOrNull { it.type.equals(value?.trim(), true) }
        }
    }

    enum class Problem {
        DOMAIN_MISSING,
        DOMAIN_INVALID,
        TRANSPORT_INVALID,
        RESOLVER_MISSING,
        RESOLVER_INVALID,
        PUBLIC_KEY_MISSING,
        PUBLIC_KEY_INVALID,
        UTLS_INVALID,
    }

    fun parse(str: String): ProfileItem? {
        val uri = runCatching { URI(str.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("dnstt", true)) return null
        val query = queryParameters(uri.rawQuery.orEmpty())
        val transport = Transport.from(query["transport"])
            ?: Transport.entries.firstOrNull { query[it.type] != null }
            ?: return null
        val resolver = query["resolver"] ?: query[transport.type] ?: return null
        val domain = query["domain"] ?: query["d"] ?: return null
        val publicKey = query["pubkey"] ?: query["key"] ?: return null
        val remarks = uri.rawFragment
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull() }
            ?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_REMARKS
        return fromValues(
            remarks = remarks,
            transport = transport.type,
            resolver = resolver,
            domain = domain,
            publicKey = publicKey,
            utls = query["utls"] ?: DEFAULT_UTLS,
        )
    }

    /** Only the explicit dnstt:// form is accepted here; raw CLI text is not guessed as a profile. */
    fun parseAny(str: String): ProfileItem? = parse(str)

    fun toUri(config: ProfileItem): String {
        val normalized = config.copy()
        normalize(normalized)
        val query = linkedMapOf(
            "transport" to normalized.dnsttTransport.orEmpty(),
            "resolver" to normalized.dnsttResolver.orEmpty(),
            "domain" to normalized.dnsttDomain.orEmpty(),
            "pubkey" to normalized.dnsttPublicKey.orEmpty(),
            "utls" to normalized.dnsttUtls.orEmpty(),
        )
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "?$encoded#${encode(normalized.remarks.ifBlank { DEFAULT_REMARKS })}"
    }

    fun fromValues(
        remarks: String,
        transport: String,
        resolver: String,
        domain: String,
        publicKey: String,
        utls: String = DEFAULT_UTLS,
    ): ProfileItem? {
        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = remarks
            it.dnsttTransport = transport
            it.dnsttResolver = resolver
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = utls
        }
        return profile.takeIf { normalize(it) == null }
    }

    /** Validates and canonicalizes the fields consumed by DnsttCore. */
    fun normalize(config: ProfileItem): Problem? {
        val domain = normalizeDomain(config.dnsttDomain ?: config.server)
            ?: return if (config.dnsttDomain.isNullOrBlank() && config.server.isNullOrBlank()) {
                Problem.DOMAIN_MISSING
            } else {
                Problem.DOMAIN_INVALID
            }
        val transport = Transport.from(config.dnsttTransport) ?: return Problem.TRANSPORT_INVALID
        val rawResolver = config.dnsttResolver?.trim().orEmpty()
        if (rawResolver.isEmpty()) return Problem.RESOLVER_MISSING
        val resolver = normalizeResolver(transport, rawResolver) ?: return Problem.RESOLVER_INVALID
        val key = config.dnsttPublicKey?.trim().orEmpty()
        if (key.isEmpty()) return Problem.PUBLIC_KEY_MISSING
        if (!key.matches(Regex("(?i)[0-9a-f]{64}"))) return Problem.PUBLIC_KEY_INVALID
        val utls = config.dnsttUtls?.trim().orEmpty().ifEmpty { DEFAULT_UTLS }
        if (utls.length > 512 || utls.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            return Problem.UTLS_INVALID
        }

        config.dnsttDomain = domain
        config.dnsttTransport = transport.type
        config.dnsttResolver = resolver
        config.dnsttPublicKey = key.lowercase()
        config.dnsttUtls = utls
        config.server = domain
        config.serverPort = null
        config.remarks = config.remarks.trim().ifEmpty { DEFAULT_REMARKS }
        return null
    }

    internal fun normalizeDomain(value: String?): String? {
        val domain = value?.trim()?.lowercase()?.removeSuffix(".") ?: return null
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) return null
        val labels = domain.split('.')
        return domain.takeIf {
            labels.all { label ->
                label.length in 1..63 && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
                    !label.startsWith('-') && !label.endsWith('-')
            }
        }
    }

    private fun normalizeResolver(transport: Transport, value: String): String? {
        if (value.any { it.isWhitespace() || it == '\u0000' }) return null
        if (transport == Transport.DOH) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            return value
        }
        return normalizeEndpoint(value, transport.defaultPort)
    }

    /** Accepts host:port, bracketed IPv6, and bare host/IP with the transport's standard port. */
    internal fun normalizeEndpoint(value: String, defaultPort: Int): String? {
        var host: String
        var port = defaultPort
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            host = value.substring(1, end)
            val tail = value.substring(end + 1)
            if (tail.isNotEmpty()) {
                if (!tail.startsWith(":")) return null
                port = tail.substring(1).toIntOrNull() ?: return null
            }
        } else {
            val colons = value.count { it == ':' }
            when {
                colons == 0 -> host = value
                colons == 1 -> {
                    host = value.substringBefore(':')
                    port = value.substringAfter(':').toIntOrNull() ?: return null
                }
                else -> host = value // bare IPv6, using the standard port
            }
        }
        if (port !in 1..65535 || !validHost(host)) return null
        val printableHost = if (host.contains(':')) "[$host]" else host
        return "$printableHost:$port"
    }

    private fun validHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253 || host.any { it.isWhitespace() || it == '/' || it == '[' || it == ']' }) return false
        if (host.contains(':')) return MasterDnsFmt.validResolver(host)
        if (host.all { it.isDigit() || it == '.' }) return MasterDnsFmt.validResolver(host)
        return normalizeDomain(host) != null
    }

    internal fun redact(line: String): String = line.trim().let {
        if (it.length > 2_000) it.take(2_000) + "…" else it
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun queryParameters(raw: String): Map<String, String> = raw.split('&')
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', missingDelimiterValue = "")
            runCatching {
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }.getOrNull()
        }.toMap()
}
