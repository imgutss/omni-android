package com.v2ray.ang.omni

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses the existing daemon broadcasts; a button request is never itself proof of a running tunnel. */
class OmniConnectionCenterActivity : HelperBaseComponentActivity() {
    private val uiState = mutableStateOf(OmniConnectionCenterUiState())
    private var receiverRegistered = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startCore() else {
            uiState.value = uiState.value.copy(status = OmniConnectionStatus.STOPPED, message = OmniConnectionMessage.PERMISSION_DENIED)
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> updateStatus(OmniConnectionStatus.RUNNING)
                AppConfig.MSG_STATE_CONNECTING -> updateStatus(OmniConnectionStatus.CONNECTING)
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> updateStatus(OmniConnectionStatus.STOPPED)
                AppConfig.MSG_STATE_START_FAILURE -> updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.START_FAILED)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshProfile()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, serviceReceiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags())
            receiverRegistered = true
        }
        refreshProfile()
        queryServiceState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(serviceReceiver) }
            receiverRegistered = false
        }
        super.onStop()
    }

    @Composable
    override fun ScreenContent() {
        val state = uiState.value
        OmniConnectionCenterScreen(
            state = state,
            onBack = { finish() },
            onStart = { startRequested() },
            onStop = { stopRequested() },
            onRestart = { restartRequested() },
            onRefresh = { refreshProfile(); queryServiceState() },
            onConfig = { startActivity(Intent(this, OmniConfigManagerActivity::class.java)) },
            onSpeed = { startActivity(Intent(this, OmniSpeedLabActivity::class.java)) },
            onDoctor = { startActivity(Intent(this, OmniDoctorActivity::class.java)) },
        )
    }

    private fun updateStatus(status: OmniConnectionStatus, message: OmniConnectionMessage? = null) {
        uiState.value = uiState.value.copy(status = status, message = message)
    }

    private fun refreshProfile() {
        lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val guid = MmkvManager.getSelectServer()
                    Triple(guid, guid?.let { MmkvManager.decodeServerConfig(it) }, SettingsManager.isVpnMode())
                }
                uiState.value = uiState.value.copy(selectedGuid = snapshot.first, selectedProfile = snapshot.second, vpnMode = snapshot.third, message = null)
            } catch (_: Exception) {
                uiState.value = uiState.value.copy(message = OmniConnectionMessage.REFRESH_FAILED)
            }
        }
    }

    private fun queryServiceState() {
        MessageHelper.sendMsg2ServiceForResult(this, AppConfig.MSG_REGISTER_CLIENT, "") { handled ->
            if (!handled) {
                val current = uiState.value
                if (current.status in setOf(OmniConnectionStatus.STARTING, OmniConnectionStatus.RESTARTING)) {
                    updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.START_REQUEST_FAILED)
                } else if (current.status != OmniConnectionStatus.STOPPING) {
                    updateStatus(OmniConnectionStatus.STOPPED)
                } else {
                    updateStatus(OmniConnectionStatus.STOPPED)
                }
            }
        }
    }

    private fun startRequested() {
        val state = uiState.value
        if (state.selectedGuid.isNullOrBlank() || state.selectedProfile == null) {
            updateStatus(state.status, OmniConnectionMessage.NO_PROFILE); return
        }
        if (!omniConnectionCanStart(state)) return
        updateStatus(OmniConnectionStatus.STARTING)
        if (SettingsManager.isVpnMode()) {
            val permission = VpnService.prepare(this)
            if (permission != null) requestVpnPermission.launch(permission) else startCore()
        } else startCore()
    }

    private fun startCore() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        try {
            if (!LauncherManager.startServiceFromToggle(this)) {
                updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.START_REQUEST_FAILED)
            }
        } catch (_: Exception) {
            updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.START_REQUEST_FAILED)
        }
    }

    private fun stopRequested() {
        if (!omniConnectionCanStop(uiState.value.status)) return
        updateStatus(OmniConnectionStatus.STOPPING)
        try {
            LauncherManager.stopService(this)
        } catch (_: Exception) {
            updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.STOP_REQUEST_FAILED)
        }
    }

    private fun restartRequested() {
        if (!omniConnectionCanRestart(uiState.value.status)) return
        updateStatus(OmniConnectionStatus.RESTARTING)
        try {
            LauncherManager.restartServiceOrStart(this) {
                updateStatus(OmniConnectionStatus.STOPPED)
                startRequested()
            }
        } catch (_: Exception) {
            updateStatus(OmniConnectionStatus.FAILURE, OmniConnectionMessage.RESTART_REQUEST_FAILED)
        }
    }
}

@Composable
fun OmniConnectionCenterScreen(
    state: OmniConnectionCenterUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onRefresh: () -> Unit,
    onConfig: () -> Unit,
    onSpeed: () -> Unit,
    onDoctor: () -> Unit,
) {
    androidx.compose.material3.Scaffold(topBar = { AppTopBar(title = androidx.compose.ui.res.stringResource(R.string.omni_connection_title), onBackClick = onBack) }) { padding ->
        LazyColumn(
            modifier = androidx.compose.ui.Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = androidx.compose.ui.Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Text(connectionStatusText(state.status), style = MaterialTheme.typography.headlineSmall, color = if (state.status == OmniConnectionStatus.RUNNING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_mode, if (state.vpnMode) androidx.compose.ui.res.stringResource(R.string.omni_connection_vpn) else androidx.compose.ui.res.stringResource(R.string.omni_connection_proxy)))
                        Text(
                            state.selectedProfile?.let { profile ->
                                androidx.compose.ui.res.stringResource(R.string.omni_connection_selected, profile.remarks.ifBlank { profile.configType.name }, profile.configType.name)
                            } ?: androidx.compose.ui.res.stringResource(R.string.omni_connection_no_profile),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.selectedGuid?.let { Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_guid, it), style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onStart, enabled = omniConnectionCanStart(state), modifier = androidx.compose.ui.Modifier.weight(1f)) { Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_start)) }
                            OutlinedButton(onClick = onStop, enabled = omniConnectionCanStop(state.status), modifier = androidx.compose.ui.Modifier.weight(1f)) { Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_stop)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRestart, enabled = omniConnectionCanRestart(state.status), modifier = androidx.compose.ui.Modifier.weight(1f)) { Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_restart)) }
                            OutlinedButton(onClick = onRefresh, modifier = androidx.compose.ui.Modifier.weight(1f)) { Text(androidx.compose.ui.res.stringResource(R.string.omni_config_refresh)) }
                        }
                        state.message?.let { Text(connectionMessageText(it), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item {
                Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = androidx.compose.ui.Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(androidx.compose.ui.res.stringResource(R.string.omni_connection_actions), style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = onConfig, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(R.string.omni_config_title)) }
                        OutlinedButton(onClick = onSpeed, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(R.string.omni_speed_title)) }
                        OutlinedButton(onClick = onDoctor, modifier = androidx.compose.ui.Modifier.fillMaxWidth()) { Text(androidx.compose.ui.res.stringResource(R.string.title_omni_doctor)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun connectionStatusText(status: OmniConnectionStatus): String = androidx.compose.ui.res.stringResource(
    when (status) {
        OmniConnectionStatus.UNKNOWN -> R.string.omni_connection_unknown
        OmniConnectionStatus.STOPPED -> R.string.omni_connection_stopped
        OmniConnectionStatus.STARTING -> R.string.omni_connection_starting
        OmniConnectionStatus.CONNECTING -> R.string.omni_connection_connecting
        OmniConnectionStatus.RUNNING -> R.string.omni_connection_running
        OmniConnectionStatus.STOPPING -> R.string.omni_connection_stopping
        OmniConnectionStatus.RESTARTING -> R.string.omni_connection_restarting
        OmniConnectionStatus.FAILURE -> R.string.omni_connection_failure
    },
)

@Composable
private fun connectionMessageText(message: OmniConnectionMessage): String = androidx.compose.ui.res.stringResource(
    when (message) {
        OmniConnectionMessage.NO_PROFILE -> R.string.omni_connection_select_first
        OmniConnectionMessage.PERMISSION_DENIED -> R.string.omni_connection_permission_denied
        OmniConnectionMessage.START_REQUEST_FAILED -> R.string.omni_connection_start_request_failed
        OmniConnectionMessage.START_FAILED -> R.string.omni_connection_start_failed
        OmniConnectionMessage.STOP_REQUEST_FAILED -> R.string.omni_connection_stop_request_failed
        OmniConnectionMessage.RESTART_REQUEST_FAILED -> R.string.omni_connection_restart_request_failed
        OmniConnectionMessage.REFRESH_FAILED -> R.string.omni_connection_refresh_failed
    },
)
