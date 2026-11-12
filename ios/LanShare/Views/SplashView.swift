import SwiftUI

/// Launch splash mirroring Android's `LanShareSplash`: the two-tone
/// "Lan-Share" wordmark on a dark rounded plate with the tagline beneath,
/// shifted above centre. Android shows this from a dedicated `SplashActivity`
/// for 900 ms, so the same hold time is used here.
struct SplashView: View {
    let darkTheme: Bool

    private var palette: SplashPalette { SplashPalette(darkTheme: darkTheme) }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            VStack(spacing: 22) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18)
                        .fill(palette.plate)
                        .frame(width: 228, height: 104)
                    wordmark
                }
                Text("No internet needed. Keep Lan-Share open on this device, then pair another phone or a browser on the same Wi-Fi with an 8-digit code or QR code.")
                    .font(.system(size: 15))
                    .lineSpacing(4)
                    .foregroundStyle(palette.description)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    // One reading width, even when the phone is unfolded.
                    .frame(maxWidth: 420)
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 34)
            .offset(y: -34)
        }
    }

    private var wordmark: some View {
        // Verbatim: "Share" is also a localized UI string ("分享", "Partager",
        // …), but here it is part of the brand name and must not be translated.
        (Text(verbatim: "Lan-").foregroundStyle(palette.wordmark)
            + Text(verbatim: "Share").foregroundStyle(palette.accent))
            .font(.system(size: 38, weight: .black))
            .lineLimit(1)
    }
}

/// The splash uses these fixed brand values rather than the site manifest,
/// matching Android's `SplashPalette` so both platforms open identically.
struct SplashPalette {
    let background: Color
    let plate: Color
    let wordmark: Color
    let accent: Color
    let description: Color

    init(darkTheme: Bool) {
        wordmark = Color(hex: "#F8FAF7") ?? .white
        if darkTheme {
            background = Color(hex: "#111512") ?? .black
            plate = Color(hex: "#1B211D") ?? .black
            accent = Color(hex: "#18A57F") ?? .green
            description = Color(hex: "#B5C2B9") ?? .gray
        } else {
            background = Color(hex: "#F5F7F4") ?? .white
            plate = Color(hex: "#202421") ?? .black
            accent = Color(hex: "#0F6B55") ?? .green
            description = Color(hex: "#637068") ?? .gray
        }
    }
}
