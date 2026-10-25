package com.lanshare

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Duration

class RoomApiClient(
    private val origin: String,
    private val client: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val base = origin.trimEnd('/')

    suspend fun create(deviceId: String, deviceLabel: String): CreatedRoom = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RoomJoinRequest.serializer(),
            RoomJoinRequest(
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                deviceType = "mobile",
                capabilities = defaultAndroidCapabilities(),
            ),
        )
        executeJson("$base${BuildConfig.PRIVATE_ROOM_API_PREFIX}/create", body, CreatedRoom.serializer())
    }

    suspend fun join(roomKey: String, deviceId: String, deviceLabel: String): JoinedRoom = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RoomJoinRequest.serializer(),
            RoomJoinRequest(
                roomKey = roomKey,
                deviceId = deviceId,
                deviceLabel = deviceLabel,
                deviceType = "mobile",
                capabilities = defaultAndroidCapabilities(),
            ),
        )
        executeJson("$base${BuildConfig.PRIVATE_ROOM_API_PREFIX}/join", body, JoinedRoom.serializer())
    }

    suspend fun close(room: JoinedRoom) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RoomCloseRequest.serializer(),
            RoomCloseRequest(deviceId = room.deviceId, sessionToken = room.sessionToken),
        )
        val request = Request.Builder()
            .url("$base${BuildConfig.PRIVATE_ROOM_API_PREFIX}/${room.roomId}/close")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw RoomApiException(
                    code = roomApiErrorCode(responseBody, "http_${response.code}"),
                    message = roomApiErrorMessage(responseBody, "Close failed: HTTP ${response.code}"),
                )
            }
        }
    }

    suspend fun heartbeat(room: JoinedRoom): RoomHeartbeatResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            RoomCloseRequest.serializer(),
            RoomCloseRequest(deviceId = room.deviceId, sessionToken = room.sessionToken),
        )
        executeJson("$base${BuildConfig.PRIVATE_ROOM_API_PREFIX}/${room.roomId}/heartbeat", body, RoomHeartbeatResponse.serializer())
    }

    private fun <T> executeJson(url: String, body: String, serializer: kotlinx.serialization.KSerializer<T>): T {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw RoomApiException(
                    code = roomApiErrorCode(responseBody, "http_${response.code}"),
                    message = roomApiErrorMessage(responseBody, "Connection failed."),
                )
            }
            return json.decodeFromString(serializer, responseBody)
        }
    }
}

class RoomApiException(val code: String, message: String) : IOException(message)

fun roomApiErrorCode(responseBody: String, fallback: String): String =
    runCatching {
        Json.parseToJsonElement(responseBody)
            .jsonObject["code"]
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: fallback

fun roomApiErrorMessage(responseBody: String, fallback: String): String =
    runCatching {
        Json.parseToJsonElement(responseBody)
            .jsonObject["message"]
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: fallback

private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(Duration.ofSeconds(10))
    .readTimeout(Duration.ofSeconds(10))
    .writeTimeout(Duration.ofSeconds(10))
    .build()

fun signalingUrl(origin: String): String {
    val clean = origin.trimEnd('/')
    return when {
        clean.startsWith("https://") -> "wss://${clean.removePrefix("https://")}${BuildConfig.PRIVATE_SIGNALING_PATH}"
        clean.startsWith("http://") -> "ws://${clean.removePrefix("http://")}${BuildConfig.PRIVATE_SIGNALING_PATH}"
        else -> "ws://$clean${BuildConfig.PRIVATE_SIGNALING_PATH}"
    }
}
