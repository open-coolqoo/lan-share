import Foundation

/// 8-digit pairing-code rules ported from the Web client: NFKC normalization,
/// digits only, weak-code rejection (repeated digits, sequential runs, the
/// known list), and pairing-code extraction from QR payloads.
public enum PairingCodes {
    public static let explicitWeakCodes: Set<String> = [
        "00000000", "11111111", "22222222", "33333333", "44444444",
        "55555555", "66666666", "77777777", "88888888", "99999999",
        "12345678", "87654321",
    ]

    /// NFKC-normalizes, strips spaces/dashes, keeps ASCII digits only.
    public static func normalizeCode(_ raw: String) -> String {
        let nfkc = raw.precomposedStringWithCompatibilityMapping
        var digits = ""
        for scalar in nfkc.unicodeScalars where scalar.value >= 48 && scalar.value <= 57 {
            digits.append(Character(scalar))
        }
        return digits
    }

    public static func isWeakCode(_ code: String) -> Bool {
        guard code.count == 8, code.allSatisfy({ $0.isNumber && $0.isASCII }) else { return true }
        if explicitWeakCodes.contains(code) { return true }
        let digits = code.compactMap { $0.wholeNumberValue }
        if Set(digits).count == 1 { return true }
        var ascending = true
        var descending = true
        for index in 0 ..< digits.count - 1 {
            if digits[index + 1] != digits[index] + 1 { ascending = false }
            if digits[index + 1] != digits[index] - 1 { descending = false }
        }
        return ascending || descending
    }

    public static func isUsableCode(_ code: String) -> Bool {
        !isWeakCode(code)
    }

    /// Accepts a raw 8-digit code or a scanned payload that embeds one.
    public static func pairingCode(fromScanResult raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        if value.count == 8, isUsableCode(value), value.allSatisfy({ $0.isNumber }) {
            return value
        }
        guard let query = scanResultQuery(value) else { return nil }
        return pairingCode(fromQuery: query)
    }

    public static func pairingCode(fromQrURL raw: String?) -> String? {
        guard let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        guard value.contains("://") else { return nil }
        guard let url = URL(string: value), let query = url.query else { return nil }
        return pairingCode(fromQuery: query)
    }

    private static func pairingCode(fromQuery query: String) -> String? {
        for parameter in query.split(separator: "&") {
            let keyValue = parameter.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard keyValue.count == 2 else { continue }
            let key = urlDecode(String(keyValue[0]))
            let code = urlDecode(String(keyValue[1]))
            if key == "pair", code.count == 8, code.allSatisfy({ $0.isNumber }), isUsableCode(code) {
                return code
            }
        }
        return nil
    }

    private static func scanResultQuery(_ value: String) -> String? {
        if let url = URL(string: value), let query = url.query, !query.isEmpty {
            return query
        }
        if let range = value.range(of: "?") {
            let tail = value[range.upperBound...]
            let beforeFragment = tail.split(separator: "#").first.map(String.init) ?? String(tail)
            if !beforeFragment.isEmpty { return beforeFragment }
        }
        if value.contains("="), !value.contains("://") {
            return value.split(separator: "#").first.map(String.init)
        }
        return nil
    }

    private static func urlDecode(_ value: String) -> String {
        value.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? value
    }
}
