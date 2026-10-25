package com.lanshare

import kotlinx.serialization.Serializable
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

@Serializable
data class SessionDescriptionPayload(
    val type: String,
    val sdp: String,
)

@Serializable
data class IceCandidatePayload(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int,
)

fun SessionDescription.toPayload(): SessionDescriptionPayload =
    SessionDescriptionPayload(type = type.canonicalForm(), sdp = description)

fun IceCandidate.toPayload(): IceCandidatePayload =
    IceCandidatePayload(candidate = sdp, sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex)

fun IceCandidatePayload.toIceCandidate(): IceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
