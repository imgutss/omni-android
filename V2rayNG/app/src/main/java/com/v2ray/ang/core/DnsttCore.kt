package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.fmt.DnsttFmt
import java.security.MessageDigest

/** The validated official dnstt-client inputs and its app-owned loopback listener. */
data class DnsttCore(
    val transport: DnsttFmt.Transport,
    val resolver: String,
    val domain: String,
    val publicKey: String,
    val utls: String,
    val port: Int = DEFAULT_SOCKS_PORT,
) {
    init {
        require(port in 1..65535) { "Invalid DNSTT listen port" }
    }

    /** Stable identity for matching a live process without logging the public configuration. */
    val key: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(transport.type, resolver, domain, publicKey, utls, port.toString()).forEach {
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
            )
        }
    }
}
