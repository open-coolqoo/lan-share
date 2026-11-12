import Darwin
import Foundation

struct LanInterfaceCandidate: Equatable {
    let name: String
    let host: String
    let isIPv4: Bool
}

/// Picks the addresses a peer on the same Wi-Fi can reach. VPN, cellular,
/// AWDL, and the fake-ip ranges an OpenClash-style router hands out are
/// dropped.
func selectLanAddresses(
    _ candidates: [LanInterfaceCandidate],
    port: UInt16,
    limit: Int = 6,
) -> [LanAddress] {
    let excludedNames = ["tun", "utun", "ppp", "ipsec", "rmnet", "dummy", "lo", "awdl", "llw"]
    func priority(_ name: String) -> Int {
        let lower = name.lowercased()
        if lower.hasPrefix("wlan") || lower.hasPrefix("ap") || lower.hasPrefix("eth") ||
            lower.hasPrefix("en") {
            return 0
        }
        return 1
    }
    let usable = candidates.filter { candidate in
        let lower = candidate.name.lowercased()
        let excluded = excludedNames.contains { lower.hasPrefix($0) }
        return !excluded && isReachableLanAddress(candidate.host, isIPv4: candidate.isIPv4)
    }
    var seen = Set<String>()
    return usable
        .sorted {
            if $0.isIPv4 != $1.isIPv4 { return $0.isIPv4 }
            return priority($0.name) < priority($1.name)
        }
        .filter { seen.insert($0.host.substring(before: "%")).inserted }
        .prefix(limit)
        .map { LanAddress(host: $0.host.substring(before: "%"), port: port) }
}

func enumerateLanAddresses(port: UInt16) -> [LanAddress] {
    var candidates: [LanInterfaceCandidate] = []
    var interfaces: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&interfaces) == 0, let first = interfaces else { return [] }
    defer { freeifaddrs(interfaces) }
    var cursor: UnsafeMutablePointer<ifaddrs>? = first
    while let current = cursor {
        defer { cursor = current.pointee.ifa_next }
        guard let address = current.pointee.ifa_addr else { continue }
        let family = address.pointee.sa_family
        guard family == UInt8(AF_INET) || family == UInt8(AF_INET6) else { continue }
        let flags = current.pointee.ifa_flags
        guard flags & UInt32(IFF_UP) != 0, flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let result = getnameinfo(
            address,
            socklen_t(address.pointee.sa_len),
            &host,
            socklen_t(host.count),
            nil,
            0,
            NI_NUMERICHOST,
        )
        guard result == 0 else { continue }
        let name = String(cString: current.pointee.ifa_name)
        let hostString = String(cString: host)
        candidates.append(
            LanInterfaceCandidate(name: name, host: hostString, isIPv4: family == UInt8(AF_INET)),
        )
    }
    return selectLanAddresses(candidates, port: port)
}

private func isReachableLanAddress(_ host: String, isIPv4: Bool) -> Bool {
    if isIPv4 {
        let octets = host.substring(before: "%").split(separator: ".").compactMap { Int($0) }
        guard octets.count == 4, octets.allSatisfy({ $0 >= 0 && $0 <= 255 }) else { return false }
        return (octets[0] == 10) ||
            (octets[0] == 172 && (16 ... 31).contains(octets[1])) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254)
    }
    let lower = host.substring(before: "%").lowercased()
    guard lower != "::1", lower != "::", let firstHextet = lower.split(separator: ":").first,
          let value = Int(firstHextet, radix: 16) else { return false }
    // ULA (fc00::/7) and global unicast (2000::/3). Link-local is skipped: its
    // zone ID belongs to this device and cannot be carried to the peer.
    return (value & 0xfe00) == 0xfc00 || (value & 0xe000) == 0x2000
}

extension String {
    func substring(before separator: Character) -> String {
        guard let index = firstIndex(of: separator) else { return self }
        return String(self[..<index])
    }
}
