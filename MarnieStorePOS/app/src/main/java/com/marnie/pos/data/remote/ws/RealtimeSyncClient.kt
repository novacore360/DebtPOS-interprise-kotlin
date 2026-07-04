package com.marnie.pos.data.remote.ws

import android.content.Context
import com.marnie.pos.security.CryptoManager
import com.marnie.pos.sync.SyncWorker
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Thin realtime layer: the socket carries no entity data (see backend
 * RealtimeRoutes) — on any message it simply schedules an immediate sync
 * pull via WorkManager, so cross-device changes (another cashier's phone,
 * a web admin, etc.) show up within a second or two without polling.
 * Auto-reconnects with backoff; safe to call connect() repeatedly.
 */
class RealtimeSyncClient(
    private val okHttpClient: OkHttpClient,
    private val cryptoManager: CryptoManager,
    private val context: Context,
    private val baseWsUrl: String,
) {
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect() {
        val token = cryptoManager.getAccessToken() ?: return
        disconnect()

        val request = Request.Builder()
            .url(baseWsUrl)
            .addHeader("Authorization", "Bearer $token")
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                Timber.d("Realtime connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Any message = something changed server-side for our store.
                SyncWorker.triggerImmediate(context)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.d(t, "Realtime disconnected, will retry")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectAttempts++
        val delaySeconds = minOf(60L, 2L shl minOf(reconnectAttempts, 5))
        scope.launch {
            delay(delaySeconds * 1000)
            connect()
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "client_disconnect")
        webSocket = null
    }
}
