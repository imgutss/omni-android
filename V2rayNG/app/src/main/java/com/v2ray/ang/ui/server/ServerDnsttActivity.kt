package com.v2ray.ang.ui.server

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.fmt.DnsttFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField

/** Editor for the official dnstt-client, including the remote SOCKS5 requirement. */
class ServerDnsttActivity : BaseServerActivity() {
    override val serverConfigType: EConfigType = EConfigType.DNSTT

    @Composable
    override fun EditorContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.fromProfileItem(initialConfig)
        }.apply { configType = serverConfigType }

        ServerEditorScaffold(
            title = stringResource(R.string.dnstt_title),
            onSaveClick = { saveServer(uiState) },
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = uiState.remarks,
                onValueChange = { uiState.remarks = it },
                isError = uiState.isRemarksError,
            )
            FormDropdownField(
                label = stringResource(R.string.dnstt_lab_transport),
                value = uiState.dnsttTransport,
                options = listOf("doh", "dot", "udp"),
                onValueChange = { uiState.dnsttTransport = it },
                supportingText = stringResource(R.string.dnstt_transport_support),
            )
            FormTextField(
                label = stringResource(R.string.dnstt_lab_resolver),
                value = uiState.dnsttResolver,
                onValueChange = {
                    uiState.dnsttResolver = it
                    uiState.isDnsttResolverError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_resolver),
                supportingText = stringResource(R.string.dnstt_resolver_support),
                isError = uiState.isDnsttResolverError,
            )
            FormTextField(
                label = stringResource(R.string.dnstt_lab_domain),
                value = uiState.dnsttDomain,
                onValueChange = {
                    uiState.dnsttDomain = it
                    uiState.isDnsttDomainError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_domain),
                supportingText = stringResource(R.string.dnstt_domain_support),
                isError = uiState.isDnsttDomainError,
            )
            FormTextField(
                label = stringResource(R.string.dnstt_lab_public_key),
                value = uiState.dnsttPublicKey,
                onValueChange = {
                    uiState.dnsttPublicKey = it
                    uiState.isDnsttPublicKeyError = false
                },
                placeholder = stringResource(R.string.dnstt_hint_public_key),
                supportingText = stringResource(R.string.dnstt_public_key_support),
                isError = uiState.isDnsttPublicKeyError,
            )
            FormTextField(
                label = stringResource(R.string.dnstt_lab_utls),
                value = uiState.dnsttUtls,
                onValueChange = {
                    uiState.dnsttUtls = it
                    uiState.isDnsttUtlsError = false
                },
                supportingText = stringResource(R.string.dnstt_utls_support),
                isError = uiState.isDnsttUtlsError,
            )
            Text(
                text = stringResource(R.string.dnstt_engine_notice),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    override fun validateBasicConfig(state: ServerUiState): Boolean {
        state.isRemarksError = state.remarks.isBlank()
        state.isAddressError = false
        state.isPortError = false
        return !state.isRemarksError
    }

    override fun validateCommonConfig(state: ServerUiState, config: ProfileItem): Boolean {
        val problem = DnsttFmt.normalize(config)
        state.isDnsttResolverError = problem == DnsttFmt.Problem.RESOLVER_MISSING || problem == DnsttFmt.Problem.RESOLVER_INVALID
        state.isDnsttDomainError = problem == DnsttFmt.Problem.DOMAIN_MISSING || problem == DnsttFmt.Problem.DOMAIN_INVALID
        state.isDnsttPublicKeyError = problem == DnsttFmt.Problem.PUBLIC_KEY_MISSING || problem == DnsttFmt.Problem.PUBLIC_KEY_INVALID
        state.isDnsttUtlsError = problem == DnsttFmt.Problem.UTLS_INVALID
        if (problem != null) {
            toast(
                when (problem) {
                    DnsttFmt.Problem.DOMAIN_MISSING -> R.string.dnstt_error_domain_missing
                    DnsttFmt.Problem.DOMAIN_INVALID -> R.string.dnstt_error_domain
                    DnsttFmt.Problem.TRANSPORT_INVALID -> R.string.dnstt_error_transport
                    DnsttFmt.Problem.RESOLVER_MISSING -> R.string.dnstt_error_resolver_missing
                    DnsttFmt.Problem.RESOLVER_INVALID -> R.string.dnstt_error_resolver
                    DnsttFmt.Problem.PUBLIC_KEY_MISSING -> R.string.dnstt_error_key_missing
                    DnsttFmt.Problem.PUBLIC_KEY_INVALID -> R.string.dnstt_error_key
                    DnsttFmt.Problem.UTLS_INVALID -> R.string.dnstt_error_utls
                }
            )
            return false
        }
        return super.validateCommonConfig(state, config)
    }
}
