import SwiftUI

/// Settings tab: appearance & language, help & support, about & legal.
/// Mirrors Android's `SettingsTabScreen` section layout.
///
/// The conversation is a tab now, so 连接详情 → 设置 simply selects this tab and
/// the title row always shows.
struct SettingsView: View {
    @Bindable var model: AppViewModel
    @State private var showLanguagePicker = false
    @State private var showContact = false
    @State private var presentedPage: PublicPage?

    private var primary: Color { Theme.primary(model.state.siteConfig) }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text("Settings")
                    .font(.title2.weight(.bold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 12)

                SettingsSectionCard(title: "Appearance & language") {
                    SettingsSwitchRow(icon: "moon.fill", title: "Dark theme", checked: model.state.darkTheme) { value in
                        model.setDarkTheme(value)
                    }
                    SettingsDivider()
                    SettingsRow(icon: "globe", title: "Switch language", subtitle: Locales.label(for: model.state.locale)) {
                        showLanguagePicker = true
                    }
                }

                SettingsSectionCard(title: "Help & support") {
                    SettingsRow(icon: "lock.shield", title: "Security") { openPage(slug: "security") }
                    SettingsDivider()
                    SettingsRow(icon: "checkmark.seal", title: "Compatibility") { openPage(slug: "compatibility") }
                    SettingsDivider()
                    SettingsRow(icon: "envelope", title: "Contact") { showContact = true }
                }

                SettingsSectionCard(title: "About & legal") {
                    SettingsRow(icon: "info.circle", title: "About") { openPage(slug: "about") }
                    SettingsDivider()
                    SettingsRow(icon: "hand.raised", title: "Privacy") { openPage(slug: "privacy") }
                    SettingsDivider()
                    SettingsRow(icon: "doc.text", title: "Terms") { openPage(slug: "terms") }
                }

                Text("Version \(Self.appVersion)")
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
                    .frame(maxWidth: .infinity)
            }
            .padding(.bottom, 24)
            // One reading column on the connect page's grid: the cards' edges
            // line up with the other tabs and the top bar on wide canvases.
            .frame(maxWidth: AdaptiveLayout.wideWidth)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.pageBackground)
        .sheet(isPresented: $showLanguagePicker) { languagePicker }
        .sheet(item: $presentedPage) { page in
            NavigationStack {
                PublicPageView(page: page)
            }
        }
        .alert("Contact", isPresented: $showContact) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.state.siteConfig?.footer.contactEmail ?? "")
        }
    }

    private func openPage(slug: String) {
        let pages = model.contentRepository?.pages(locale: model.state.locale) ?? []
        presentedPage = pages.first(where: { $0.slug == slug })
            ?? PublicPage(slug: slug, title: slug, body: "", status: "published", updatedAt: "")
    }

    private var languagePicker: some View {
        NavigationStack {
            List(Locales.supported) { option in
                Button {
                    model.setLocale(option.code)
                    showLanguagePicker = false
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option.native)
                                .font(.subheadline.weight(.bold))
                                .foregroundStyle(option.code == model.state.locale ? primary : .primary)
                            Text("\(option.english) · \(option.code)")
                                .font(.caption)
                                .foregroundStyle(Theme.muted)
                        }
                        Spacer()
                        if option.code == model.state.locale {
                            Image(systemName: "checkmark").foregroundStyle(primary).fontWeight(.bold)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .foregroundStyle(.primary)
            }
            .navigationTitle("Choose a language")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showLanguagePicker = false }
                }
            }
        }
    }

    private static var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

private struct SettingsSectionCard<Content: View>: View {
    let title: String
    private let content: Content

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringKey(title))
                .font(.caption.weight(.bold))
                .foregroundStyle(Theme.muted)
                .padding(.leading, 6)
            VStack(spacing: 0) { content }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.line, lineWidth: 1))
        }
    }
}

private struct SettingsRow: View {
    let icon: String
    let title: String
    var subtitle: String? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .frame(width: 22)
                    .foregroundStyle(Theme.primary(nil))
                VStack(alignment: .leading, spacing: 2) {
                    Text(LocalizedStringKey(title)).font(.subheadline.weight(.bold)).foregroundStyle(.primary)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(Theme.muted)
                    }
                }
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.muted)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            // Buttons hit-test their drawn content, so without this the tap
            // target excludes the icon padding and the Spacer gap.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SettingsSwitchRow: View {
    let icon: String
    let title: String
    let checked: Bool
    let onToggle: (Bool) -> Void

    var body: some View {
        Button(action: { onToggle(!checked) }) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .frame(width: 22)
                    .foregroundStyle(Theme.primary(nil))
                Text(LocalizedStringKey(title)).font(.subheadline.weight(.bold)).foregroundStyle(.primary)
                Spacer()
                Toggle("", isOn: Binding(get: { checked }, set: { onToggle($0) }))
                    .labelsHidden()
                    .tint(Theme.primary(nil))
                    .allowsHitTesting(false)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct SettingsDivider: View {
    var body: some View {
        Divider().padding(.leading, 48)
    }
}

/// Renders one bundled public page (privacy/terms/etc.).
struct PublicPageView: View {
    let page: PublicPage
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(page.title)
                    .font(.title2.bold())
                Text(page.body)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("Updated \(page.updatedAt)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(16)
        }
        .navigationTitle(page.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
    }
}

extension PublicPage: Identifiable {
    public var id: String { slug }
}