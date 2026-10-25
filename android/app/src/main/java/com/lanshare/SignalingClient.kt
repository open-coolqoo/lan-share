package com.lanshare

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class SignalingClient(
    private val origin: String,
    private val room: JoinedRoom,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val onEvent: (SignalingClient, SignalingEnvelope) -> Unit,
    private val onDisconnect: (SignalingClient, String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private var socket: WebSocket? = null
    private val closedByClient = AtomicBoolean(false)
    private val disconnectNotified = AtomicBoolean(false)

    fun connect() {
        closedByClient.set(false)
        disconnectNotified.set(false)
        val request = Request.Builder().url(signalingUrl(origin)).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(json.encodeToString(SignalingEnvelope.auth(room.roomId, room.deviceId, room.sessionToken)))
                onLog("signaling: auth sent")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { json.decodeFromString(SignalingEnvelope.serializer(), text) }
                    .getOrElse {
                        onLog("signaling: invalid message ${it.message}")
                        return
                    }
                onEvent(this@SignalingClient, event)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val reason = t.message ?: "unknown"
                onLog("signaling: failed $reason")
                notifyDisconnect(reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onLog("signaling: closed $code $reason")
                notifyDisconnect("closed $code $reason")
            }
        })
    }

    fun send(message: SignalingEnvelope) {
        socket?.send(json.encodeToString(message))
    }

    fun close() {
        closedByClient.set(true)
        socket?.close(1000, "client_close")
        socket = null
    }

    private fun notifyDisconnect(reason: String) {
        if (closedByClient.get() || !disconnectNotified.compareAndSet(false, true)) return
        onDisconnect(this, reason)
    }
}
