package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class UrlSchemeActivity : BaseComponentActivity() {

    private val OmniSchemes = setOf(
        "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "wireguard", "aether", "omni"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            intent.apply {
                if (action == Intent.ACTION_SEND) {
                    if ("text/plain" == type) {
                        intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                            parseUri(it, null)
                        }
                    }
                } else if (action == Intent.ACTION_VIEW) {
                    val uri: Uri? = intent.data
                    when {
                        uri?.scheme == "v2rayng" && uri.host == "install-config" -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri?.scheme == "v2rayng" && uri.host == "install-sub" -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri?.scheme == "omni" && (uri.host == "install-config" || uri.host == "install-sub") -> {
                            parseUri(uri.getQueryParameter("url").orEmpty(), uri.fragment)
                        }

                        uri != null && uri.scheme in OmniSchemes -> {
                            // vless://, aether://, ... go through the same batch importer as QR/clipboard.
                            parseUri(uri.toString(), null)
                        }

                        else -> toastError(R.string.toast_failure)
                    }
                }
            }

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
        }
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun parseUri(uriString: String?, fragment: String?) {
        if (uriString.isNullOrEmpty()) {
            return
        }
        LogUtil.i(AppConfig.TAG, uriString)

        var decodedUrl = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decodedUrl)
        if (uri != null) {
            if (uri.fragment.isNullOrEmpty() && !fragment.isNullOrEmpty()) {
                decodedUrl += "#${fragment}"
            }
            LogUtil.i(AppConfig.TAG, decodedUrl)
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, countSub) = AngConfigManager.importBatchConfig(decodedUrl, "", false)
                // An open main screen reloads its tabs
                if (count + countSub > 0) MessageHelper.sendMsg2UI(applicationContext, AppConfig.MSG_SERVERS_CHANGED, "")
                withContext(Dispatchers.Main) {
                    if (count + countSub > 0) {
                        toast(R.string.import_subscription_success)
                    } else {
                        toast(R.string.import_subscription_failure)
                    }
                }
            }
        }
    }
}
