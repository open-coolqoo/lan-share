import SwiftUI

/// The conversation page's colour tokens (design `docs/ios/chat-ui-design.md`
/// §3, including the 2026-10-19 "fixed island" revision).
///
/// Own bubbles are a fixed island: fill, ink, meta, action and progress each
/// carry their own value in both schemes, so no dynamic token can flip a
/// light value onto a dark island. The pinned island values are:
/// text `#95EC69` / `#0F6B55`, file `#DDF5CD` / `#173D32`, meta `#A7B6AD`,
/// action `#C9D6CE`, progress `#6FD0A6`, track `rgba(255,255,255,.18)`.
struct ChatTheme: Sendable {
    let dark: Bool

    // Canvas and chrome
    var canvas: Color { dark ? .black : Theme.pageBackground }
    var barBackground: Color { dark ? Color(hex: "#1C1C1E")! : Color(hex: "#F7F7F7")! }
    var hairline: Color { dark ? Color(hex: "#2C2C2E")! : Color(hex: "#DCE5DF")! }
    var ink: Color { dark ? Color(hex: "#EAF2ED")! : Color(hex: "#17211D")! }
    var meta: Color { dark ? Color(hex: "#A7B3AD")! : Color(hex: "#637269")! }
    var warning: Color { dark ? Color(hex: "#E8C46A")! : Color(hex: "#7A5200")! }
    var warningFill: Color { dark ? Color(hex: "#3A3220")! : Color(hex: "#F3E6C4")! }
    var statusGreen: Color { Color(hex: "#2AA05A")! }
    var statusAmber: Color { Theme.amber }

    // Peer bubble (dynamic surface)
    var peerBubble: Color { dark ? Color(hex: "#1C1C1E")! : .white }
    var peerAction: Color { dark ? Color(hex: "#4DB78E")! : Color(hex: "#0F6B55")! }

    // Own text bubble — fixed island
    var ownTextFill: Color { dark ? Color(hex: "#0F6B55")! : Color(hex: "#95EC69")! }
    var ownTextInk: Color { dark ? .white : Color(hex: "#17211D")! }

    // Own file bubble — fixed island
    var ownFileFill: Color { dark ? Color(hex: "#173D32")! : Color(hex: "#DDF5CD")! }
    var ownFileInk: Color { dark ? Color(hex: "#EAF2ED")! : Color(hex: "#10201A")! }
    var ownFileMeta: Color { dark ? Color(hex: "#A7B6AD")! : Color(hex: "#55645B")! }
    var ownFileAction: Color { dark ? Color(hex: "#C9D6CE")! : Color(hex: "#0F6B55")! }
    var ownFileProgress: Color { dark ? Color(hex: "#6FD0A6")! : Color(hex: "#0F6B55")! }
    var ownFileTrack: Color { dark ? Color.white.opacity(0.18) : Color(hex: "#E6E6E6")! }

    // Peer file bubble uses the general track/brand pair, never the own values.
    var peerFileTrack: Color { dark ? Color(hex: "#3A3A3C")! : Color(hex: "#E6E6E6")! }
    var peerFileProgress: Color { dark ? Color(hex: "#17836A")! : Color(hex: "#0F6B55")! }

    // Avatars
    var ownAvatarFill: Color { dark ? Color(hex: "#173D32")! : Color(hex: "#0F6B55")! }
    var ownAvatarInk: Color { dark ? Color(hex: "#EAF2ED")! : .white }
    var peerAvatarFill: Color { Color(hex: "#4F7A91")! }

    // System notes
    var noteFill: Color { dark ? Color(hex: "#2A342E")! : Color(hex: "#E3EAE5")! }
    var noteInk: Color { dark ? Color(hex: "#A7B6AD")! : Color(hex: "#637269")! }

    // Composer
    var inputArea: Color { dark ? Color(hex: "#1C1C1E")! : .white }
    var inputField: Color { dark ? Color(hex: "#2C2C2E")! : Color(hex: "#F5F5F5")! }
    var inputInk: Color { dark ? Color(hex: "#EAF2ED")! : Color(hex: "#17211D")! }
    var iconInk: Color { dark ? Color(hex: "#C9D6CE")! : Color(hex: "#4A554E")! }
    var tileFill: Color { dark ? Color(hex: "#2C2C2E")! : Color(hex: "#F5F5F5")! }

    // Errors use the dynamic system red in dark (design §3).
    var errorInk: Color { dark ? Color(red: 1.0, green: 0.27, blue: 0.23) : Theme.danger }

    // File type badge hues (web styles.css:749-772, Android `ChatBadgeColors`).
    func badgeFill(for kind: ChatFileKind) -> Color {
        switch kind {
        case .image: return Color(hex: "#7C6FF0")!
        case .video: return Color(hex: "#EF7D33")!
        case .audio: return Color(hex: "#14A08A")!
        case .pdf: return Color(hex: "#E5484D")!
        case .doc: return Color(hex: "#2F7DE1")!
        case .sheet: return Color(hex: "#2AA05A")!
        case .slide: return Color(hex: "#E8842C")!
        case .archive: return Color(hex: "#A08430")!
        case .apk: return Color(hex: "#3DDC84")!
        case .text: return Color(hex: "#78909C")!
        case .file: return Color(hex: "#64748B")!
        }
    }

    func badgeInk(for kind: ChatFileKind) -> Color {
        kind == .apk ? Color(hex: "#14381F")! : .white
    }
}

/// Theme colors derived from the bundled `public-content.json` manifest so the
/// app matches the Web brand without a second source of truth.
enum Theme {
    static func color(from hex: String, fallback: Color) -> Color {
        Color(hex: hex) ?? fallback
    }

    static func primary(_ config: PublicSiteConfig?) -> Color {
        color(from: config?.primaryColor ?? "", fallback: Color(red: 0.059, green: 0.42, blue: 0.333))
    }

    static func accent(_ config: PublicSiteConfig?) -> Color {
        color(from: config?.accentColor ?? "", fallback: Color(red: 0.192, green: 0.365, blue: 0.549))
    }

    static var pageBackground: Color { Color(uiColor: .systemGroupedBackground) }
    static var surface: Color { Color(uiColor: .secondarySystemGroupedBackground) }
    static var surfaceAlt: Color { Color(uiColor: .tertiarySystemGroupedBackground) }
    static var muted: Color { Color(uiColor: .secondaryLabel) }
    static var line: Color { Color(uiColor: .separator) }
    static var danger: Color { Color(red: 0.75, green: 0.22, blue: 0.17) }
    static var amber: Color { Color(red: 0.85, green: 0.6, blue: 0.11) }
}

// MARK: - Liquid Glass (iOS 26+)

/// Liquid Glass adoption helpers. The app deploys to iOS 17, so every glass
/// surface goes through these availability-gated wrappers: on iOS 26+ they
/// draw the system Liquid Glass material, on older systems they fall back to
/// the hand-drawn styles unchanged. Per the HIG, glass carries the functional
/// layer only — bars, floating controls, prominent actions — while content
/// cards and bubbles keep their opaque fills.
extension View {
    /// The system Liquid Glass surface (`glassEffect`) on iOS 26+; the view
    /// unchanged below. The shape is required because the system default is a
    /// capsule, which is almost never what a bar or strip wants.
    @ViewBuilder
    func liquidGlass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            // Composed in a closure: bare assignment `if`s inside a
            // @ViewBuilder are parsed as builder branches.
            let glass: Glass = {
                var base = Glass.regular
                if let tint { base = base.tint(tint) }
                if interactive { base = base.interactive() }
                return base
            }()
            self.glassEffect(glass, in: shape)
        } else {
            self
        }
    }

    /// The system's prominent action: tinted glass on iOS 26+, the pre-26
    /// bordered prominent below.
    @ViewBuilder
    func prominentGlassButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent)
        } else {
            self.buttonStyle(.borderedProminent)
        }
    }

    /// The system's secondary action: glass on iOS 26+, bordered below.
    @ViewBuilder
    func secondaryGlassButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glass)
        } else {
            self.buttonStyle(.bordered)
        }
    }
}

extension Color {
    /// Parses `#RRGGBB` / `RRGGBB` / `#RRGGBBAA` hex color strings.
    init?(hex: String) {
        var value = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("#") { value.removeFirst() }
        guard value.count == 6 || value.count == 8 else { return nil }
        var bytes: UInt64 = 0
        guard Scanner(string: value).scanHexInt64(&bytes) else { return nil }
        let r, g, b, a: Double
        if value.count == 8 {
            r = Double((bytes >> 24) & 0xFF) / 255.0
            g = Double((bytes >> 16) & 0xFF) / 255.0
            b = Double((bytes >> 8) & 0xFF) / 255.0
            a = Double(bytes & 0xFF) / 255.0
        } else {
            r = Double((bytes >> 16) & 0xFF) / 255.0
            g = Double((bytes >> 8) & 0xFF) / 255.0
            b = Double(bytes & 0xFF) / 255.0
            a = 1.0
        }
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

enum FileIcons {
    static func symbol(forMime mime: String) -> String {
        if mime.hasPrefix("image/") { return "photo" }
        if mime.hasPrefix("video/") { return "video" }
        if mime.hasPrefix("audio/") { return "waveform" }
        if mime == "application/pdf" { return "doc.richtext" }
        if mime.hasPrefix("text/") { return "doc.plaintext" }
        if mime.contains("zip") || mime.contains("compressed") { return "doc.zipper" }
        return "doc"
    }

    static func symbol(forName name: String) -> String {
        let ext = (name as NSString).pathExtension.lowercased()
        switch ext {
        case "png", "jpg", "jpeg", "gif", "heic", "webp": return "photo"
        case "mp4", "mov", "webm", "avi": return "video"
        case "mp3", "wav", "aac", "flac", "m4a": return "waveform"
        case "pdf": return "doc.richtext"
        case "txt", "md", "csv", "log": return "doc.plaintext"
        case "zip", "rar", "7z", "tar", "gz": return "doc.zipper"
        default: return "doc"
        }
    }
}

extension Int {
    /// Human-readable byte size, e.g. `1.5 MB`.
    var fileSizeLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(self), countStyle: .file)
    }
}

/// The 14 locales supported by the Android client.
struct AppLocale: Identifiable, Equatable {
    let code: String
    let native: String
    let english: String
    var id: String { code }
}

enum Locales {
    static let supported: [AppLocale] = [
        AppLocale(code: "en", native: "English", english: "English"),
        AppLocale(code: "es", native: "Español", english: "Spanish"),
        AppLocale(code: "fr", native: "Français", english: "French"),
        AppLocale(code: "de", native: "Deutsch", english: "German"),
        AppLocale(code: "ja", native: "日本語", english: "Japanese"),
        AppLocale(code: "ko", native: "한국어", english: "Korean"),
        AppLocale(code: "pt-BR", native: "Português", english: "Portuguese"),
        AppLocale(code: "hi", native: "हिन्दी", english: "Hindi"),
        AppLocale(code: "ar", native: "العربية", english: "Arabic"),
        AppLocale(code: "id", native: "Bahasa Indonesia", english: "Indonesian"),
        AppLocale(code: "vi", native: "Tiếng Việt", english: "Vietnamese"),
        AppLocale(code: "th", native: "ไทย", english: "Thai"),
        AppLocale(code: "zh-CN", native: "简体中文", english: "Simplified Chinese"),
        AppLocale(code: "zh-TW", native: "繁體中文", english: "Traditional Chinese"),
    ]

    static func label(for code: String) -> String {
        supported.first(where: { $0.code == code })?.native ?? code
    }

    /// Maps a BCP-47 language tag onto a supported app locale, mirroring the
    /// Web and Android matchers: Chinese resolves by script or region, and
    /// Portuguese only matches the Brazilian variant.
    static func match(languageTag: String) -> String? {
        let parts = languageTag
            .replacingOccurrences(of: "_", with: "-")
            .split(separator: "-")
            .map { $0.lowercased() }
        guard let language = parts.first else { return nil }
        let rest = Array(parts.dropFirst())
        if language == "zh" {
            return rest.contains(where: { ["hant", "tw", "hk", "mo"].contains($0) }) ? "zh-TW" : "zh-CN"
        }
        if language == "pt" {
            return rest.contains("br") ? "pt-BR" : nil
        }
        return supported.first(where: { $0.code.lowercased() == language })?.code
    }

    /// The supported locale matching the device's preferred language, if any.
    static func systemPreferred() -> String? {
        for tag in Locale.preferredLanguages {
            if let code = match(languageTag: tag) { return code }
        }
        return nil
    }

    /// The locale to use when the user has not picked one: the device language
    /// when supported, otherwise the site default, otherwise English.
    static func resolve(defaultLocale: String) -> String {
        if let system = systemPreferred() { return system }
        return supported.contains(where: { $0.code == defaultLocale }) ? defaultLocale : "en"
    }

    /// Mirrors the Web `localeDirection`: Arabic is the only RTL locale.
    static func layoutDirection(for code: String) -> LayoutDirection {
        match(languageTag: code) == "ar" ? .rightToLeft : .leftToRight
    }
}