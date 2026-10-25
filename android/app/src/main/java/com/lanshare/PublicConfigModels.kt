package com.lanshare

import kotlinx.serialization.Serializable
import org.webrtc.PeerConnection

@Serializable
data class FooterLink(
    val label: String,
    val url: String,
)

@Serializable
data class FooterConfig(
    val legalName: String,
    val developerName: String,
    val contactEmail: String,
    val copyright: String,
    val links: List<FooterLink> = emptyList(),
)

@Serializable
data class RtcIceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
data class RtcConfig(
    val iceServers: List<RtcIceServer> = emptyList(),
)

@Serializable
data class PublicSiteConfig(
    val siteName: String,
    val description: String,
    val iconUrl: String,
    val primaryColor: String,
    val accentColor: String,
    val defaultLocale: String,
    val privacyUrl: String = "/privacy",
    val termsUrl: String = "/terms",
    val footer: FooterConfig,
    val rtc: RtcConfig = RtcConfig(),
)

@Serializable
data class PublicPage(
    val slug: String,
    val title: String,
    val body: String,
    val status: String,
    val updatedAt: String,
)

fun parseHexColor(value: String): Int? {
    if (!Regex("""^#[0-9A-Fa-f]{6}$""").matches(value)) return null
    return (0xFF000000 or value.drop(1).toLong(16)).toInt()
}

fun publicPageSlugFromUrl(url: String): String? {
    if (!url.startsWith("/")) return null
    val path = url.substringBefore("?").trim('/')
    if (path.isBlank()) return null
    val parts = path.split('/').filter { it.isNotBlank() }
    return parts.lastOrNull()?.takeIf { slug ->
        Regex("""^[A-Za-z0-9_-]+$""").matches(slug)
    }
}

fun isContactSlug(slug: String): Boolean =
    slug.equals("contact", ignoreCase = true)

fun RtcConfig.toPeerConnectionIceServers(): List<PeerConnection.IceServer> = iceServers.mapNotNull { server ->
    val directOnlyUrls = server.urls.filterNot { url ->
        url.startsWith("stun:", ignoreCase = true) ||
            url.startsWith("turn:", ignoreCase = true) ||
            url.startsWith("turns:", ignoreCase = true)
    }
    if (directOnlyUrls.isEmpty()) {
        null
    } else {
        PeerConnection.IceServer.builder(directOnlyUrls)
            .apply {
                if (!server.username.isNullOrBlank()) setUsername(server.username)
                if (!server.credential.isNullOrBlank()) setPassword(server.credential)
            }
            .createIceServer()
    }
}
