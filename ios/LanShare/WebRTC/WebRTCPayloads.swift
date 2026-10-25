import Foundation

public struct SessionDescriptionPayload: Codable, Equatable, Sendable {
    public let type: String
    public let sdp: String

    public init(type: String, sdp: String) {
        self.type = type
        self.sdp = sdp
    }
}

public struct IceCandidatePayload: Codable, Equatable, Sendable {
    public let candidate: String
    public let sdpMid: String?
    public let sdpMLineIndex: Int

    public init(candidate: String, sdpMid: String?, sdpMLineIndex: Int) {
        self.candidate = candidate
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
    }
}
