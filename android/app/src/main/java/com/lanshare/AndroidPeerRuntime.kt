package com.lanshare

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription


class AndroidPeerRuntime(
    private val factory: PeerConnectionFactory,
    iceServers: List<PeerConnection.IceServer>,
    private val sendSignal: (SignalingEnvelope) -> Unit,
    private val onRemoteDataChannel: (DataChannel) -> Unit,
    private val onConnectionState: (PeerConnection.PeerConnectionState) -> Unit = {},
    private val onLog: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var remoteDeviceId: String = ""
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private val dataChannels = linkedSetOf<DataChannel>()
    private var closed = false
    private val peerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(iceServers),
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                runRtcCallback("ice candidate") {
                    if (remoteDeviceId.isBlank()) return@runRtcCallback
                    sendSignal(
                        SignalingEnvelope(
                            type = "ice_candidate",
                            to = remoteDeviceId,
                            payload = json.encodeToJsonElement(candidate.toPayload()),
                        ),
                    )
                }
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                runRtcCallback("remote datachannel") {
                    if (closed) {
                        dataChannel.dispose()
                        return@runRtcCallback
                    }
                    dataChannels += dataChannel
                    onLog("webrtc: remote datachannel")
                    onRemoteDataChannel(dataChannel)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runRtcCallback("connection state") {
                    onLog("webrtc: connection $newState")
                    onConnectionState(newState)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
        },
    ) ?: error("Failed to create peer connection")

    fun createOffer(to: String) {
        if (closed) return
        remoteDeviceId = to
        val channel = peerConnection.createDataChannel("lanshare-files", DataChannel.Init().apply { ordered = true })
            ?: run {
                onLog("webrtc: create datachannel failed")
                return
            }
        if (closed) {
            channel.dispose()
            return
        }
        dataChannels += channel
        onRemoteDataChannel(channel)
        peerConnection.createOffer(object : BasicSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                runRtcCallback("create offer") {
                    peerConnection.setLocalDescription(BasicSdpObserver(onLog), description)
                    sendSignal(SignalingEnvelope(type = "offer", to = to, payload = json.encodeToJsonElement(description.toPayload())))
                }
            }
        }, MediaConstraints())
    }

    fun handleSignal(message: SignalingEnvelope) {
        if (closed) return
        if (!message.from.isNullOrBlank()) remoteDeviceId = message.from
        when (message.type) {
            "offer" -> handleRemoteDescription(SessionDescription.Type.OFFER, message.payload) {
                peerConnection.createAnswer(object : BasicSdpObserver() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        runRtcCallback("create answer") {
                            peerConnection.setLocalDescription(BasicSdpObserver(onLog), description)
                            sendSignal(SignalingEnvelope(type = "answer", to = remoteDeviceId, payload = json.encodeToJsonElement(description.toPayload())))
                        }
                    }
                }, MediaConstraints())
            }
            "answer" -> handleRemoteDescription(SessionDescription.Type.ANSWER, message.payload) {}
            "ice_candidate" -> handleIceCandidate(message.payload)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        dataChannels.toList().forEach { channel ->
            runCatching { channel.unregisterObserver() }
            runCatching { channel.dispose() }
        }
        dataChannels.clear()
        pendingIceCandidates.clear()
        runCatching { peerConnection.dispose() }
        runCatching { factory.dispose() }
    }

    private fun handleRemoteDescription(type: SessionDescription.Type, payload: JsonElement?, afterSet: () -> Unit) {
        if (payload == null) return
        val sdp = json.decodeFromJsonElement(SessionDescriptionPayload.serializer(), payload)
        peerConnection.setRemoteDescription(object : BasicSdpObserver() {
            override fun onSetSuccess() {
                runRtcCallback("set remote description") {
                    flushPendingIceCandidates()
                    afterSet()
                }
            }
        }, SessionDescription(type, sdp.sdp))
    }

    private fun handleIceCandidate(payload: JsonElement?) {
        if (payload == null) return
        runRtcCallback("ice candidate signal") {
            val candidate = json.decodeFromJsonElement(IceCandidatePayload.serializer(), payload).toIceCandidate()
            if (peerConnection.remoteDescription == null) {
                pendingIceCandidates += candidate
                onLog("webrtc: queued remote ice candidate")
                return@runRtcCallback
            }
            peerConnection.addIceCandidate(candidate)
        }
    }

    private fun flushPendingIceCandidates() {
        val candidates = pendingIceCandidates.toList()
        pendingIceCandidates.clear()
        candidates.forEach { candidate -> peerConnection.addIceCandidate(candidate) }
    }

    private fun runRtcCallback(label: String, block: () -> Unit) {
        runCatching(block).onFailure { safeLog("webrtc: $label failed ${it.message}") }
    }

    private fun safeLog(message: String) {
        runCatching { onLog(message) }
    }
}

open class BasicSdpObserver(private val onLog: ((String) -> Unit)? = null) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) {
        runCatching { onLog?.invoke("webrtc: create sdp failed $error") }
    }
    override fun onSetFailure(error: String) {
        runCatching { onLog?.invoke("webrtc: set sdp failed $error") }
    }
}
