import CryptoKit
import Foundation

private let roomKeySalt = "lanshare-room-v1:2026-06-25"
private let roomKeyInfo = "lanshare-room-v1"

/// Derives the Web-compatible room key from the 8-digit pairing code:
/// HKDF-SHA256 extract+expand, then a final SHA-256 over the derived bytes,
/// then Base64 URL-safe without padding.
public func deriveRoomKey(_ code: String) -> String {
    let inputKeyMaterial = SymmetricKey(data: Data(code.utf8))
    let derived = HKDF<SHA256>.deriveKey(
        inputKeyMaterial: inputKeyMaterial,
        salt: Data(roomKeySalt.utf8),
        info: Data(roomKeyInfo.utf8),
        outputByteCount: 32,
    )
    let okm = derived.withUnsafeBytes { buffer in Data(buffer) }
    let digest = SHA256.hash(data: okm)
    return Data(digest).base64URLEncodedString()
}

extension Data {
    public func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
