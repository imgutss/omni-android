package com.v2ray.ang.omni

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OmniConfigManagerLogicTest {
    private val entries = listOf(
        OmniConfigEntry("guid-vless", ProfileItem(configType = EConfigType.VLESS, remarks = "Germany edge", server = "de.example", serverPort = "443")),
        OmniConfigEntry("guid-vmess", ProfileItem(configType = EConfigType.VMESS, remarks = "Backup", server = "backup.example", serverPort = "8443")),
        OmniConfigEntry("guid-trojan", ProfileItem(configType = EConfigType.TROJAN, remarks = "Home", server = "home.example", serverPort = "443")),
        OmniConfigEntry("guid-other", ProfileItem(configType = EConfigType.SOCKS, remarks = "SOCKS endpoint", server = "127.0.0.1", serverPort = "1080")),
    )

    @Test
    fun filtersByProtocolAndQueryWithoutUsingListPosition() {
        assertEquals(listOf("guid-vless"), filterOmniConfigurations(entries, "GERMANY", OmniProtocolFilter.ALL).map { it.guid })
        assertEquals(listOf("guid-vmess"), filterOmniConfigurations(entries, "8443", OmniProtocolFilter.VMESS).map { it.guid })
        assertEquals(listOf("guid-other"), filterOmniConfigurations(entries, "", OmniProtocolFilter.OTHER).map { it.guid })
        assertEquals(emptyList<String>(), filterOmniConfigurations(entries, "Germany", OmniProtocolFilter.TROJAN).map { it.guid })
    }

    @Test
    fun emptyQueryAndAllFilterPreserveEveryGuid() {
        assertEquals(entries.map { it.guid }, filterOmniConfigurations(entries, "  ", OmniProtocolFilter.ALL).map { it.guid })
    }
}
