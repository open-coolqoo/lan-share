package com.lanshare.lan

import java.net.Inet4Address
import java.net.NetworkInterface

internal data class LanInterfaceCandidate(
    val name: String,
    val host: String,
    val isIpv4: Boolean,
)

/**
 * Picks the addresses a peer on the same Wi-Fi can actually reach. VPN,
 * cellular, and local test interfaces are dropped, as are the fake-ip ranges
 * an OpenClash-style router hands out (`198.18.0.0/15`, `100.64.0.0/10`).
 */
internal fun selectLanAddresses(
    candidates: List<LanInterfaceCandidate>,
    port: Int,
    limit: Int = 6,
): List<LanAddress> {
    val usable = candidates.filter { candidate ->
        val name = candidate.name.lowercase()
        val excludedName = listOf("tun", "utun", "ppp", "ipsec", "rmnet", "dummy", "lo").any(name::startsWith)
        !excludedName && isReachableLanAddress(candidate.host, candidate.isIpv4)
    }
    fun priority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("wlan") || lower.startsWith("ap") || lower.startsWith("eth") || lower.startsWith("en") -> 0
            else -> 1
        }
    }
    return usable
        .sortedWith(compareBy({ if (it.isIpv4) 0 else 1 }, { priority(it.name) }))
        .distinctBy { it.host }
        .take(limit)
        .map { LanAddress(stripZone(it.host), port) }
        .distinctBy { it.host }
}

internal fun enumerateLanAddresses(port: Int): List<LanAddress> {
    val candidates = mutableListOf<LanInterfaceCandidate>()
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .forEach { networkInterface ->
            val up = runCatching { networkInterface.isUp }.getOrDefault(false)
            val loopback = runCatching { networkInterface.isLoopback }.getOrDefault(true)
            if (!up || loopback) return@forEach
            val name = networkInterface.name.orEmpty()
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val address = interfaceAddress.address ?: return@forEach
                val host = address.hostAddress ?: return@forEach
                candidates += LanInterfaceCandidate(name, host, address is Inet4Address)
            }
        }
    return selectLanAddresses(candidates, port)
}

private fun isReachableLanAddress(host: String, isIpv4: Boolean): Boolean {
    if (isIpv4) {
        val octets = ipv4Octets(host) ?: return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }
    val lower = host.lowercase().substringBefore('%')
    if (lower == "::1" || lower == "::") return false
    val firstHextet = lower.substringBefore(':').toIntOrNull(16) ?: return false
    // ULA (fc00::/7) and global unicast (2000::/3). Link-local (fe80::/10) is
    // deliberately skipped: its zone ID is local to the host and cannot be
    // carried to the peer, so it would only produce unconnectable entries.
    return (firstHextet and 0xfe00) == 0xfc00 ||
        (firstHextet and 0xe000) == 0x2000
}

private fun ipv4Octets(host: String): IntArray? {
    val parts = host.substringBefore('%').split('.')
    if (parts.size != 4) return null
    val octets = IntArray(4)
    for (index in 0..3) {
        val value = parts[index].toIntOrNull() ?: return null
        if (value !in 0..255) return null
        octets[index] = value
    }
    return octets
}

/** The `%wlan0` zone is only valid on this device; peers connect by address. */
private fun stripZone(host: String): String = host.substringBefore('%')
