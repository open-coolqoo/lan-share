package com.lanshare

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PublicContentRepository private constructor(
    val siteConfig: PublicSiteConfig,
    private val pages: Map<String, PublicPageRecord>,
) {
    val canonicalSlugs: List<String> = CANONICAL_SLUGS

    fun page(slug: String, locale: String): PublicPage? {
        val record = pages[slug] ?: return null
        val localized = record.locales[locale] ?: record.locales[siteConfig.defaultLocale] ?: return null
        return PublicPage(
            slug = record.slug,
            title = localized.title,
            body = localized.body,
            status = "published",
            updatedAt = record.updatedAt,
        )
    }

    companion object {
        private val CANONICAL_SLUGS = listOf("privacy", "terms", "security", "faq", "compatibility", "about")
        private val json = Json { ignoreUnknownKeys = true }

        fun fromAssets(context: Context): PublicContentRepository =
            context.assets.open("public-content.json").bufferedReader().use { fromJson(it.readText()) }

        internal fun fromJson(value: String): PublicContentRepository {
            val manifest = json.decodeFromString<PublicContentManifest>(value)
            require(manifest.siteName.isNotBlank()) { "missing Android public content field: siteName" }
            require(manifest.description.isNotBlank()) { "missing Android public content field: description" }
            require(manifest.defaultLocale.isNotBlank()) { "missing Android public content field: defaultLocale" }
            require(manifest.footer.legalName.isNotBlank()) { "missing Android public content field: footer.legalName" }
            val pages = manifest.pages.associateBy { it.slug }
            require(pages.size == manifest.pages.size) { "duplicate Android public page slug" }
            CANONICAL_SLUGS.forEach { slug ->
                val record = pages[slug] ?: throw IllegalArgumentException("missing canonical page: $slug")
                val localized = record.locales[manifest.defaultLocale]
                    ?: throw IllegalArgumentException("missing default locale content for page: $slug")
                require(localized.title.isNotBlank() && localized.body.isNotBlank()) {
                    "empty default locale content for page: $slug"
                }
            }
            return PublicContentRepository(
                siteConfig = PublicSiteConfig(
                    siteName = manifest.siteName,
                    description = manifest.description,
                    iconUrl = manifest.iconUrl,
                    primaryColor = manifest.primaryColor,
                    accentColor = manifest.accentColor,
                    defaultLocale = manifest.defaultLocale,
                    footer = manifest.footer,
                    rtc = manifest.rtc,
                ),
                pages = pages,
            )
        }
    }
}

@Serializable
private data class PublicContentManifest(
    val siteName: String,
    val description: String,
    val iconUrl: String = "",
    val primaryColor: String,
    val accentColor: String,
    val defaultLocale: String,
    val footer: FooterConfig,
    val rtc: RtcConfig = RtcConfig(),
    val pages: List<PublicPageRecord>,
)

@Serializable
private data class PublicPageRecord(
    val slug: String,
    val updatedAt: String,
    val locales: Map<String, LocalizedPublicPage>,
)

@Serializable
private data class LocalizedPublicPage(
    val title: String,
    val body: String,
)
