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
    const val ENGINE_OFFICIAL = "official"
    const val ENGINE_NOIZDNS = "noizdns"
    private const val DEFAULT_REMARKS = "DNSTT"

    enum class Transport(val type: String, val defaultPort: Int) {
        DOH("doh", 443), DOT("dot", 853), UDP("udp", 53), TCP("tcp", 53);

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
        // Build the complete profile before validating it. In particular, the
        // NoizDNS engine and its multi-resolver/authoritative options must be
        // present during the first normalization; validating a temporary
        // official-DNSTT profile would reject valid Omni URIs prematurely.
        val profile = ProfileItem.create(EConfigType.DNSTT).also {
            it.remarks = remarks
            it.dnsttTransport = transport.type
            it.dnsttResolver = resolver
            it.dnsttDomain = domain
            it.dnsttPublicKey = publicKey
            it.dnsttUtls = query["utls"] ?: DEFAULT_UTLS
            it.dnsttEngine = query["engine"]
            it.dnsttAuthoritative = query["authoritative"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttNoiz = query["noiz"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttStealth = query["stealth"]?.let { value -> value == "1" || value.equals("true", true) }
            it.dnsttMaxPayload = query["maxPayload"]?.toIntOrNull()
            it.dnsttResolverMode = query["resolverMode"]
            it.dnsttRrSpreadCount = query["rrSpread"]?.toIntOrNull()
            it.dnsttSocksUsername = query["socksUsername"]
            it.dnsttSocksPassword = query["socksPassword"]
        }
        return profile.takeIf { normalize(it) == null }
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
        if (normalized.dnsttEngine != ENGINE_OFFICIAL) query["engine"] = normalized.dnsttEngine.orEmpty()
        if (normalized.dnsttAuthoritative == true) query["authoritative"] = "1"
        if (normalized.dnsttNoiz == true) query["noiz"] = "1"
        if (normalized.dnsttStealth == true) query["stealth"] = "1"
        normalized.dnsttMaxPayload?.takeIf { it > 0 }?.let { query["maxPayload"] = it.toString() }
        normalized.dnsttResolverMode?.takeIf { it.isNotBlank() }?.let { query["resolverMode"] = it }
        normalized.dnsttRrSpreadCount?.takeIf { it > 0 }?.let { query["rrSpread"] = it.toString() }
        normalized.dnsttSocksUsername?.takeIf { it.isNotEmpty() }?.let { query["socksUsername"] = it }
        normalized.dnsttSocksPassword?.takeIf { it.isNotEmpty() }?.let { query["socksPassword"] = it }
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
        val engine = config.dnsttEngine?.trim()?.lowercase()?.ifEmpty { ENGINE_OFFICIAL } ?: ENGINE_OFFICIAL
        if (engine != ENGINE_OFFICIAL && engine != ENGINE_NOIZDNS) return Problem.TRANSPORT_INVALID
        val transport = Transport.from(config.dnsttTransport) ?: return Problem.TRANSPORT_INVALID
        val noiz = config.dnsttNoiz == true
        val stealth = config.dnsttStealth == true
        val maxPayload = config.dnsttMaxPayload ?: 0
        val resolverMode = config.dnsttResolverMode?.trim()?.lowercase()
        val rrSpread = config.dnsttRrSpreadCount ?: 3
        if (stealth && !noiz) return Problem.TRANSPORT_INVALID
        if (maxPayload !in 0..4096 || rrSpread !in 1..5) return Problem.TRANSPORT_INVALID
        if (!resolverMode.isNullOrBlank() && resolverMode !in setOf("fanout", "roundrobin")) {
            return Problem.TRANSPORT_INVALID
        }
        // The official upstream client has no TCP transport or SlipNet tuning knobs.
        if (engine == ENGINE_OFFICIAL && (transport == Transport.TCP ||
                config.dnsttAuthoritative == true || noiz || stealth || maxPayload != 0 ||
                (!resolverMode.isNullOrBlank() && resolverMode != "fanout") || rrSpread != 3 ||
                !config.dnsttSocksUsername.isNullOrEmpty() || !config.dnsttSocksPassword.isNullOrEmpty())
        ) return Problem.TRANSPORT_INVALID
        val rawResolver = config.dnsttResolver?.trim().orEmpty()
        if (rawResolver.isEmpty()) return Problem.RESOLVER_MISSING
        val resolver = normalizeResolver(transport, rawResolver, allowMultiple = engine == ENGINE_NOIZDNS)
            ?: return Problem.RESOLVER_INVALID
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
        config.dnsttEngine = engine
        config.dnsttAuthoritative = config.dnsttAuthoritative == true
        config.dnsttNoiz = noiz
        config.dnsttStealth = stealth
        config.dnsttMaxPayload = maxPayload
        config.dnsttResolverMode = resolverMode?.takeIf { it.isNotBlank() } ?: "fanout"
        config.dnsttRrSpreadCount = rrSpread
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

    private fun normalizeResolver(transport: Transport, value: String, allowMultiple: Boolean = false): String? {
        if (value.any { it.isWhitespace() || it == '\u0000' }) return null
        if (transport == Transport.DOH) {
            if (value.contains(',')) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", true)) return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            return value
        }
        val values = if (allowMultiple) value.split(',') else listOf(value)
        if (values.isEmpty() || values.any { it.isBlank() }) return null
        if (allowMultiple) {
            val marked = values.map { authoritySuffix(it) != null }
            if (marked.any { it } && !marked.all { it }) return null
        }
        val normalized = values.map {
            if (allowMultiple) {
                normalizeNoizEndpoint(it, transport.defaultPort) ?: return null
            } else {
                normalizeEndpoint(it, transport.defaultPort) ?: return null
            }
        }
        return normalized.joinToString(",")
    }

    /**
     * NoizDNS profiles may retain SlipNet's optional :0/:1 authority suffix.
     * It is profile metadata, not part of the DNS endpoint passed to Go.
     */
    private fun normalizeNoizEndpoint(value: String, defaultPort: Int): String? {
        val authority = authoritySuffix(value)
        val endpoint = if (authority == null) value else value.removeSuffix(":$authority")
        val normalized = normalizeEndpoint(endpoint, defaultPort) ?: return null
        return if (authority == null) normalized else "$normalized:$authority"
    }

    /** Strips profile authority suffixes before constructing engine arguments. */
    internal fun engineResolver(value: String): String = value.split(',').joinToString(",") { entry ->
        val authority = authoritySuffix(entry)
        if (authority == null) entry else entry.removeSuffix(":$authority")
    }

    /** Returns the original per-resolver modes, or an empty list for legacy unmarked profiles. */
    internal fun resolverAuthorities(value: String): List<Boolean> {
        val values = value.split(',')
        if (values.isEmpty()) return emptyList()
        val flags = values.map { entry -> authoritySuffix(entry) ?: return emptyList() }
        return flags.map { it == "1" }
    }

    private fun authoritySuffix(value: String): String? {
        if (value.startsWith('[')) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val pieces = value.substring(close + 1).split(':')
            return pieces.getOrNull(2)?.takeIf {
                pieces.size == 3 && pieces[1].toIntOrNull() != null && (it == "0" || it == "1")
            }
        }
        val pieces = value.split(':')
        return pieces.getOrNull(2)?.takeIf {
            pieces.size == 3 && pieces[0].isNotEmpty() &&
                pieces[1].toIntOrNull() != null && (it == "0" || it == "1")
        }
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
