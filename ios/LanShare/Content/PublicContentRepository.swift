import Foundation

public struct PublicContentError: Error, Equatable {
    public let message: String
}

/// Parses and validates the bundled iOS copy of `public-content.json`.
/// iOS never calls the Web public-content APIs; the manifest may intentionally
/// differ from the Web and Android copies.
public final class PublicContentRepository: @unchecked Sendable {
    public static let canonicalSlugs: [String] = [
        "privacy", "terms", "security", "faq", "compatibility", "about",
    ]

    public let siteConfig: PublicSiteConfig
    private let pages: [String: PublicPageRecord]

    init(siteConfig: PublicSiteConfig, pages: [String: PublicPageRecord]) {
        self.siteConfig = siteConfig
        self.pages = pages
    }

    public func page(_ slug: String, locale: String) -> PublicPage? {
        guard let record = pages[slug] else { return nil }
        guard
            let localized = record.locales[locale]
                ?? record.locales[siteConfig.defaultLocale]
        else { return nil }
        return PublicPage(
            slug: record.slug,
            title: localized.title,
            body: localized.body,
            status: "published",
            updatedAt: record.updatedAt,
        )
    }

    public func pages(locale: String) -> [PublicPage] {
        PublicContentRepository.canonicalSlugs.compactMap { page($0, locale: locale) }
    }

    public static func fromBundle(_ bundle: Bundle = .main) -> PublicContentRepository? {
        guard let url = bundle.url(forResource: "public-content", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let value = String(data: data, encoding: .utf8)
        else { return nil }
        return try? fromJSON(value)
    }

    public static func fromJSON(_ value: String) throws -> PublicContentRepository {
        guard let data = value.data(using: .utf8) else {
            throw PublicContentError(message: "public content is not valid UTF-8")
        }
        let decoder = JSONDecoder()
        let manifest: PublicContentManifest
        do {
            manifest = try decoder.decode(PublicContentManifest.self, from: data)
        } catch {
            throw PublicContentError(message: "invalid public content manifest: \(error.localizedDescription)")
        }
        if manifest.siteName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PublicContentError(message: "missing iOS public content field: siteName")
        }
        if manifest.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PublicContentError(message: "missing iOS public content field: description")
        }
        if manifest.defaultLocale.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PublicContentError(message: "missing iOS public content field: defaultLocale")
        }
        if manifest.footer.legalName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PublicContentError(message: "missing iOS public content field: footer.legalName")
        }
        let pages = Dictionary(manifest.pages.map { ($0.slug, $0) }, uniquingKeysWith: { _, _ in
            PublicPageRecord(slug: "", updatedAt: "", locales: [:])
        })
        if pages.count != manifest.pages.count {
            throw PublicContentError(message: "duplicate iOS public page slug")
        }
        for slug in canonicalSlugs {
            guard let record = pages[slug] else {
                throw PublicContentError(message: "missing canonical page: \(slug)")
            }
            guard let localized = record.locales[manifest.defaultLocale] else {
                throw PublicContentError(message: "missing default locale content for page: \(slug)")
            }
            if localized.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                || localized.body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                throw PublicContentError(message: "empty default locale content for page: \(slug)")
            }
        }
        let siteConfig = PublicSiteConfig(
            siteName: manifest.siteName,
            description: manifest.description,
            iconUrl: manifest.iconUrl ?? "",
            primaryColor: manifest.primaryColor,
            accentColor: manifest.accentColor,
            defaultLocale: manifest.defaultLocale,
            footer: manifest.footer,
            rtc: manifest.rtc ?? RtcConfig()
        )
        return PublicContentRepository(siteConfig: siteConfig, pages: pages)
    }
}
