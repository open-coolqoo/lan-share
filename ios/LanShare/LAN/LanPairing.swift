import CryptoKit
import Foundation

/// One reachable `host:port` for the local signaling listener.
public struct LanAddress: Equatable, Hashable, Sendable {
    public let host: String
    public let port: UInt16

    public init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    /// `192.168.1.5:43000` or `[fe80::1]:43000`.
    public var authority: String {
        host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)"
    }

    public static func parse(_ raw: String) -> LanAddress? {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if value.hasPrefix("[") {
            guard let end = value.firstIndex(of: "]") else { return nil }
            let host = String(value[value.index(after: value.startIndex)..<end])
            let rest = value[value.index(after: end)...]
            let portText = rest.hasPrefix(":") ? rest.dropFirst() : rest
            guard let port = UInt16(portText), port > 0, !host.isEmpty else { return nil }
            return LanAddress(host: host, port: port)
        }
        guard let colon = value.lastIndex(of: ":") else { return nil }
        let host = String(value[..<colon])
        let portText = value[value.index(after: colon)...]
        guard !host.isEmpty, let port = UInt16(portText), port > 0 else { return nil }
        return LanAddress(host: host, port: port)
    }
}

public struct LanPairingPayload: Equatable, Sendable {
    public let code: String
    public let addresses: [LanAddress]

    public init(code: String, addresses: [LanAddress]) {
        self.code = code
        self.addresses = addresses
    }
}

public struct LanJoinTarget: Sendable {
    public let code: String
    public let roomKey: String
    public let addresses: [LanAddress]

    public init(code: String, roomKey: String, addresses: [LanAddress] = []) {
        self.code = code
        self.roomKey = roomKey
        self.addresses = addresses
    }
}

/// Pure LAN pairing rules: code generation (mirrors the Go server), HMAC
/// proofs, the mDNS identity, and the QR payload. No sockets or UI live here.
public enum LanPairing {
    public static let serviceType = "_lanshare._tcp"
    public static let pairingTTLMilliseconds: Int64 = 120_000
    public static let sessionTTLMilliseconds: Int64 = 24 * 60 * 60 * 1_000

    private static let qrScheme = "lanshare-lan"
    private static let authPrefix = "lanshare-lan-auth-v1|"
    private static let hostPrefix = "lanshare-lan-host-v1|"
    private static let resumePrefix = "lanshare-lan-resume-v1|"
    private static let mdnsPrefix = "lanshare-lan-mdns-v1|"

    private static let weakCodes: Set<String> = [
        "00000000", "11111111", "22222222", "33333333", "44444444",
        "55555555", "66666666", "77777777", "88888888", "99999999",
        "12345678", "87654321",
    ]

    /// Mirrors `generateConnectionCode` in the Go server.
    public static func generateCode() -> String {
        var generator = SystemRandomNumberGenerator()
        for _ in 0 ..< 128 {
            let code = String(format: "%08d", Int.random(in: 0 ..< 100_000_000, using: &generator))
            if isUsableCode(code) { return code }
        }
        preconditionFailure("unable to generate a secure connection code")
    }

    /// Mirrors `isUsableCode`, including its modulo-10 cyclic runs.
    public static func isUsableCode(_ code: String) -> Bool {
        let digits = Array(code.utf8)
        guard digits.count == 8, digits.allSatisfy({ $0 >= 48 && $0 <= 57 }) else { return false }
        if weakCodes.contains(code) { return false }
        var ascending = true
        var descending = true
        var same = true
        for index in 1 ..< digits.count {
            let previous = Int(digits[index - 1] - 48)
            let current = Int(digits[index] - 48)
            if current != (previous + 1) % 10 { ascending = false }
            if current != (previous + 9) % 10 { descending = false }
            if digits[index] != digits[0] { same = false }
        }
        return !ascending && !descending && !same
    }

    public static func roomKey(_ code: String) -> String { deriveRoomKey(code) }

    public static func authProof(roomKey: String, nonce: String, deviceId: String) -> String {
        encode(hmac(key: roomKey, data: authPrefix + nonce + "|" + deviceId))
    }

    public static func hostProof(roomKey: String, nonce: String, hostDeviceId: String) -> String {
        encode(hmac(key: roomKey, data: hostPrefix + nonce + "|" + hostDeviceId))
    }

    public static func resumeHostProof(roomKey: String, sessionToken: String, hostDeviceId: String) -> String {
        encode(hmac(key: roomKey, data: resumePrefix + sessionToken + "|" + hostDeviceId))
    }

    /// First 16 base64url characters of the salted room identity.
    public static func mdnsSid(roomKey: String, salt: String) -> String {
        String(encode(hmac(key: roomKey, data: mdnsPrefix + salt)).prefix(16))
    }

    public static func matchesSid(roomKey: String, salt: String, advertised: String) -> Bool {
        constantTimeEquals(mdnsSid(roomKey: roomKey, salt: salt), advertised)
    }

    /// Builds the QR a host shows. With a browser bridge the payload is a plain
    /// HTTP URL: a phone camera opens the browser guest, while the native
    /// scanner reads the same `pair`/`addr` parameters. Without one it falls
    /// back to the custom scheme the native apps understand.
    public static func buildQRPayload(
        code: String,
        addresses: [LanAddress],
        webOrigin: String? = nil,
    ) -> String {
        var components: URLComponents
        if let webOrigin, !webOrigin.isEmpty, let browser = URLComponents(string: "http://\(webOrigin)/") {
            components = browser
            components.path = "/"
        } else {
            components = URLComponents()
            components.scheme = qrScheme
            components.host = "pair"
        }
        var items = [URLQueryItem(name: "pair", value: code)]
        var seen = Set<String>()
        for address in addresses where seen.insert(address.authority).inserted {
            if items.count > 7 { break }
            items.append(URLQueryItem(name: "addr", value: address.authority))
        }
        components.queryItems = items
        return components.string ?? fallbackQRPayload(code: code, addresses: addresses)
    }

    private static func fallbackQRPayload(code: String, addresses: [LanAddress]) -> String {
        var result = "\(qrScheme)://pair?pair=\(code)"
        for address in addresses where address.port > 0 {
            result += "&addr=\(address.authority)"
        }
        return result
    }

    public static func parseQRPayload(_ raw: String?) -> LanPairingPayload? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              value.hasPrefix("\(qrScheme)://") ||
                value.hasPrefix("http://") ||
                value.hasPrefix("https://"),
              let components = URLComponents(string: value),
              let items = components.queryItems
        else { return nil }
        var code: String?
        var addresses: [LanAddress] = []
        for item in items {
            switch item.name {
            case "pair", "code":
                if let value = item.value, value.count == 8, value.allSatisfy({ $0.isASCII && $0.isNumber }) {
                    code = value
                }
            case "addr", "host":
                if let value = item.value, let address = LanAddress.parse(value) {
                    addresses.append(address)
                }
            default:
                break
            }
        }
        guard let code else { return nil }
        var seen = Set<String>()
        return LanPairingPayload(
            code: code,
            addresses: addresses.filter { seen.insert($0.authority).inserted },
        )
    }

    public static func randomToken(bytes: Int = 16) -> String {
        var generator = SystemRandomNumberGenerator()
        let data = Data((0 ..< bytes).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
        return data.base64URLEncodedString()
    }

    public static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        guard left.count == right.count else { return false }
        var difference: UInt8 = 0
        for index in left.indices {
            difference |= left[index] ^ right[index]
        }
        return difference == 0
    }

    private static func hmac(key: String, data: String) -> Data {
        let symmetricKey = SymmetricKey(data: Data(key.utf8))
        return Data(HMAC<SHA256>.authenticationCode(for: Data(data.utf8), using: symmetricKey))
    }

    private static func encode(_ data: Data) -> String {
        data.base64URLEncodedString()
    }
}
