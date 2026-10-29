package com.v2ray.ang.omni

import android.content.Context
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.subscription.SubEditRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Hub operations over the existing subscription database; there is no central Omni panel. */
class OmniHubRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = OmniPanelStore(appContext)
    private val subscriptions = SubEditRepository()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun panels(): List<OmniPanel> = withContext(Dispatchers.IO) { store.load() }

    suspend fun addSubscription(
        displayName: String,
        subscriptionUrl: String,
        allowInsecure: Boolean,
    ): OmniPanel = withContext(Dispatchers.IO) {
        val parsed = validateUrl(subscriptionUrl, allowInsecure)
        val name = displayName.trim().ifBlank { parsed.host }
        val base = originOf(parsed)
        val existing = store.load().firstOrNull { it.subscriptionUrl.equals(parsed.toString(), ignoreCase = true) }
        if (existing != null) throw OmniHubException("This subscription is already connected")

        val subId = subscriptions.saveSubscription("") { item ->
            item.remarks = name
            item.url = parsed.toString()
            item.enabled = true
            item.autoUpdate = true
            item.allowInsecureUrl = allowInsecure
        } ?: throw OmniHubException("The subscription storage refused the new panel")
        val panel = OmniPanel(
            name = name,
            baseUrl = base,
            subscriptionUrl = parsed.toString(),
            subscriptionId = subId,
            allowInsecure = allowInsecure,
        )
        try {
            store.replace(panel)
        } catch (error: Exception) {
            // Do not leave an invisible subscription behind if encrypted Hub storage fails.
            runCatching { subscriptions.deleteSubscription(subId) }
            throw error
        }
        panel
    }

    /** Optional panel pairing. Current panels without this endpoint return an honest 404. */
    suspend fun pairWithCode(
        baseUrl: String,
        code: String,
        displayName: String,
        allowInsecure: Boolean,
    ): OmniPanel = withContext(Dispatchers.IO) {
        val base = normalizeBase(baseUrl, allowInsecure)
        val pairingCode = code.trim()
        if (pairingCode.isEmpty()) throw OmniHubException("Enter the pairing code from your panel")
        val endpoint = "$base/api/app/pair"
        val body = JSONObject()
            .put("code", pairingCode)
            .put("client", "Omni Android")
            .toString()
            .toRequestBody(JSON)
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .post(body)
                    .build()
            ).execute()
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val hint = if (it.code == 404) {
                    "This panel has no pairing endpoint (HTTP 404); use its subscription link instead"
                } else {
                    "Pairing refused: HTTP ${it.code}"
                }
                throw OmniHubException(hint, it.code)
            }
            val subscription = parseSubscriptionResponse(text)
                ?: throw OmniHubException("Pairing returned no subscription URL")
            val parsed = validateUrl(subscription, allowInsecure)
            val responseName = runCatching { JSONObject(text).optString("name").takeIf { it.isNotBlank() } }.getOrNull()
            val name = displayName.trim().ifBlank { responseName ?: parsed.host }
            val subId = subscriptions.saveSubscription("") { item ->
                item.remarks = name
                item.url = parsed.toString()
                item.enabled = true
                item.autoUpdate = true
                item.allowInsecureUrl = allowInsecure
            } ?: throw OmniHubException("The subscription storage refused the paired panel")
            val panel = OmniPanel(
                name = name,
                baseUrl = base,
                subscriptionUrl = parsed.toString(),
                subscriptionId = subId,
                allowInsecure = allowInsecure,
                lastStatus = OmniPanelStatus.READY.name,
                lastStatusDetail = "Pairing succeeded; subscription not yet synced",
                lastCheckedAt = System.currentTimeMillis(),
            )
            try {
                store.replace(panel)
            } catch (error: Exception) {
                runCatching { subscriptions.deleteSubscription(subId) }
                throw error
            }
            panel
        }
    }

    suspend fun remove(panel: OmniPanel): Unit = withContext(Dispatchers.IO) {
        if (!subscriptions.deleteSubscription(panel.subscriptionId)) {
            throw OmniHubException("The panel could not be removed; its subscription was left intact")
        }
        store.remove(panel.id)
    }

    suspend fun sync(panel: OmniPanel): Int = withContext(Dispatchers.IO) {
        val subscription = MmkvManager.decodeSubscription(panel.subscriptionId)
            ?: throw OmniHubException("The panel subscription no longer exists")
        val result = AngConfigManager.updateConfigViaSub(SubscriptionCache(panel.subscriptionId, subscription))
        if (result.successCount <= 0 || result.configCount <= 0) {
            throw OmniHubException("Sync failed: no valid configurations were received")
        }
        store.replace(
            panel.copy(
                lastSyncAt = System.currentTimeMillis(),
                lastStatus = OmniPanelStatus.READY.name,
                lastStatusDetail = "Subscription sync succeeded: ${result.configCount} configurations",
            )
        )
        result.configCount
    }

    suspend fun check(panel: OmniPanel): OmniPanelHealth = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val health = try {
            val response = client.newCall(
                Request.Builder()
                    .url("${panel.baseUrl}/api/version")
                    .header("Accept", "application/json")
                    .get()
                    .build()
            ).execute()
            response.use {
                val elapsed = elapsedMillis(started)
                val status = when (it.code) {
                    in 200..299 -> OmniPanelStatus.READY
                    401, 403 -> OmniPanelStatus.AUTH_OR_EDGE_REFUSED
                    404 -> OmniPanelStatus.NOT_FOUND
                    408, 504 -> OmniPanelStatus.TIMEOUT
                    else -> OmniPanelStatus.HTTP_ERROR
                }
                OmniPanelHealth(
                    status = status,
                    httpStatus = it.code,
                    elapsedMillis = elapsed,
                    rawDetail = "HTTP ${it.code} ${it.message}; ${elapsed} ms; endpoint=/api/version",
                )
            }
        } catch (error: Exception) {
            val status = when (error) {
                is SocketTimeoutException -> OmniPanelStatus.TIMEOUT
                is UnknownHostException -> OmniPanelStatus.DNS_ERROR
                is SSLException -> OmniPanelStatus.TLS_ERROR
                else -> OmniPanelStatus.NETWORK_ERROR
            }
            OmniPanelHealth(
                status = status,
                elapsedMillis = elapsedMillis(started),
                rawDetail = "${error.javaClass.simpleName}: ${error.message ?: "no detail"}; ${elapsedMillis(started)} ms",
            )
        }
        runCatching {
            store.replace(
                panel.copy(
                    lastStatus = health.status.name,
                    lastStatusDetail = health.rawDetail,
                    lastCheckedAt = System.currentTimeMillis(),
                )
            )
        }
        health
    }

    private fun parseSubscriptionResponse(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return listOf("subscription_url", "subscriptionUrl", "url", "subscription")
            .firstOrNull { key -> json.optString(key).startsWith("http") }
            ?.let { json.optString(it) }
    }

    private fun validateUrl(raw: String, allowInsecure: Boolean): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw OmniHubException("Enter a valid HTTP(S) URL")
        if (url.scheme !in setOf("http", "https")) throw OmniHubException("Only HTTP(S) panel URLs are supported")
        if (!allowInsecure && url.scheme == "http") {
            throw OmniHubException("HTTPS is required; enable insecure HTTP only for a trusted local panel")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw OmniHubException("Put credentials in the panel pairing flow, not in the URL")
        }
        return url
    }

    private fun normalizeBase(raw: String, allowInsecure: Boolean): String {
        val url = validateUrl(raw, allowInsecure)
        if (url.encodedQuery != null || url.encodedFragment != null) {
            throw OmniHubException("The panel base URL must not contain a query or fragment")
        }
        return url.toString().trimEnd('/')
    }

    private fun originOf(url: HttpUrl): String =
        url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

    private fun networkFailure(error: Exception): OmniHubException = when (error) {
        is SocketTimeoutException -> OmniHubException("Panel timeout: ${error.message ?: "no response"}")
        is UnknownHostException -> OmniHubException("DNS failed: ${error.message ?: "host not found"}")
        is SSLException -> OmniHubException("TLS failed: ${error.message ?: "handshake failed"}")
        is IOException -> OmniHubException("Network failed: ${error.message ?: "I/O error"}")
        else -> OmniHubException("Panel request failed: ${error.javaClass.simpleName}")
    }

    private fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
