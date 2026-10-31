package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsttFmt
import java.security.MessageDigest

/** Validated inputs for either the official DNSTT or the SlipNet-compatible NoizDNS process. */
data class DnsttCore(
    val transport: DnsttFmt.Transport,
    val resolver: String,
    val domain: String,
    val publicKey: String,
    val utls: String,
    val port: Int = DEFAULT_SOCKS_PORT,
    val engine: String = DnsttFmt.ENGINE_OFFICIAL,
    val authoritative: Boolean = false,
    val noiz: Boolean = false,
    val stealth: Boolean = false,
    val maxPayload: Int = 0,
    val resolverMode: String = "fanout",
    val rrSpreadCount: Int = 3,
    val socksUsername: String = "",
    val socksPassword: String = "",
) {
    init {
        require(port in 1..65535) { "Invalid DNSTT listen port" }
        require(engine == DnsttFmt.ENGINE_OFFICIAL || engine == DnsttFmt.ENGINE_NOIZDNS) {
            "Unknown DNSTT engine"
        }
        require(resolverMode == "fanout" || resolverMode == "roundrobin") {
            "Invalid DNSTT resolver mode"
        }
        require(rrSpreadCount in 1..5) { "Invalid DNSTT resolver spread" }
    }

    /** Stable identity for matching a live process without logging the configuration. */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            transport.type, resolver, domain, publicKey, utls, port.toString(), engine,
            authoritative.toString(), noiz.toString(), stealth.toString(), maxPayload.toString(),
            resolverMode, rrSpreadCount.toString(), socksUsername, socksPassword,
        ).forEach {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_SOCKS_PORT = 18001

        fun of(profile: ProfileItem, listenPort: Int = DEFAULT_SOCKS_PORT): DnsttCore {
            val normalized = profile.copy()
            require(DnsttFmt.normalize(normalized) == null) { "Invalid DNSTT profile" }
            return DnsttCore(
                transport = DnsttFmt.Transport.from(normalized.dnsttTransport)!!,
                resolver = normalized.dnsttResolver.orEmpty(),
                domain = normalized.dnsttDomain.orEmpty(),
                publicKey = normalized.dnsttPublicKey.orEmpty(),
                utls = normalized.dnsttUtls.orEmpty(),
                port = listenPort,
                engine = normalized.dnsttEngine ?: DnsttFmt.ENGINE_OFFICIAL,
                authoritative = normalized.dnsttAuthoritative == true,
                noiz = normalized.dnsttNoiz == true,
                stealth = normalized.dnsttStealth == true,
                maxPayload = normalized.dnsttMaxPayload ?: 0,
                resolverMode = normalized.dnsttResolverMode ?: "fanout",
                rrSpreadCount = normalized.dnsttRrSpreadCount ?: 3,
                socksUsername = normalized.dnsttSocksUsername.orEmpty(),
                socksPassword = normalized.dnsttSocksPassword.orEmpty(),
            )
        }
    }
}
