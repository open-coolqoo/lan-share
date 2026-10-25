import Foundation

public struct FooterLink: Codable, Equatable, Sendable {
    public let label: String
    public let url: String

    public init(label: String, url: String) {
        self.label = label
        self.url = url
    }
}

public struct FooterConfig: Codable, Equatable, Sendable {
    public let legalName: String
    public let developerName: String
    public let contactEmail: String
    public let copyright: String
    public let links: [FooterLink]

    public init(
        legalName: String,
        developerName: String,
        contactEmail: String,
        copyright: String,
        links: [FooterLink] = []
    ) {
        self.legalName = legalName
        self.developerName = developerName
        self.contactEmail = contactEmail
        self.copyright = copyright
        self.links = links
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        legalName = try container.decode(String.self, forKey: .legalName)
        developerName = try container.decode(String.self, forKey: .developerName)
        contactEmail = try container.decode(String.self, forKey: .contactEmail)
        copyright = try container.decode(String.self, forKey: .copyright)
        links = try container.decodeIfPresent([FooterLink].self, forKey: .links) ?? []
    }
}

public struct RtcIceServer: Codable, Equatable, Sendable {
    public let urls: [String]
    public let username: String?
    public let credential: String?

    public init(urls: [String], username: String? = nil, credential: String? = nil) {
        self.urls = urls
        self.username = username
        self.credential = credential
    }
}

public struct RtcConfig: Codable, Equatable, Sendable {
    public var iceServers: [RtcIceServer]

    public init(iceServers: [RtcIceServer] = []) {
        self.iceServers = iceServers
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        iceServers = try container.decodeIfPresent([RtcIceServer].self, forKey: .iceServers) ?? []
    }
}

public struct PublicSiteConfig: Codable, Equatable, Sendable {
    public let siteName: String
    public let description: String
    public let iconUrl: String
    public let primaryColor: String
    public let accentColor: String
    public let defaultLocale: String
    public let privacyUrl: String
    public let termsUrl: String
    public let footer: FooterConfig
    public let rtc: RtcConfig

    public init(
        siteName: String,
        description: String,
        iconUrl: String = "",
        primaryColor: String,
        accentColor: String,
        defaultLocale: String,
        privacyUrl: String = "/privacy",
        termsUrl: String = "/terms",
        footer: FooterConfig,
        rtc: RtcConfig = RtcConfig()
    ) {
        self.siteName = siteName
        self.description = description
        self.iconUrl = iconUrl
        self.primaryColor = primaryColor
        self.accentColor = accentColor
        self.defaultLocale = defaultLocale
        self.privacyUrl = privacyUrl
        self.termsUrl = termsUrl
        self.footer = footer
        self.rtc = rtc
    }

    private enum CodingKeys: String, CodingKey {
        case siteName, description, iconUrl, primaryColor, accentColor
        case defaultLocale, privacyUrl, termsUrl, footer, rtc
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        siteName = try container.decode(String.self, forKey: .siteName)
        description = try container.decode(String.self, forKey: .description)
        iconUrl = try container.decodeIfPresent(String.self, forKey: .iconUrl) ?? ""
        primaryColor = try container.decode(String.self, forKey: .primaryColor)
        accentColor = try container.decode(String.self, forKey: .accentColor)
        defaultLocale = try container.decode(String.self, forKey: .defaultLocale)
        privacyUrl = try container.decodeIfPresent(String.self, forKey: .privacyUrl) ?? "/privacy"
        termsUrl = try container.decodeIfPresent(String.self, forKey: .termsUrl) ?? "/terms"
        footer = try container.decode(FooterConfig.self, forKey: .footer)
        rtc = try container.decodeIfPresent(RtcConfig.self, forKey: .rtc) ?? RtcConfig()
    }
}

public struct PublicPage: Equatable, Sendable {
    public let slug: String
    public let title: String
    public let body: String
    public let status: String
    public let updatedAt: String
}

struct LocalizedPublicPage: Codable, Equatable, Sendable {
    let title: String
    let body: String
}

struct PublicPageRecord: Codable, Equatable, Sendable {
    let slug: String
    let updatedAt: String
    let locales: [String: LocalizedPublicPage]
}

struct PublicContentManifest: Codable, Sendable {
    let siteName: String
    let description: String
    let iconUrl: String?
    let primaryColor: String
    let accentColor: String
    let defaultLocale: String
    let footer: FooterConfig
    let rtc: RtcConfig?
    let pages: [PublicPageRecord]
}
