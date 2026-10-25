import SwiftUI

@main
struct LanShareApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppViewModel()
    @State private var localNetwork = LocalNetworkAuthorization()
    @State private var minimumSplashElapsed = false
    @State private var splashReleased = false

    /// The splash also carries the local network prompt, so it stays up until
    /// that prompt is answered rather than dismissing on a timer alone.
    ///
    /// The app is inactive while a system alert is on screen, which is what
    /// keeps the splash up under the prompt: the browse goes ready *before* the
    /// user answers, so the probe alone cannot be trusted to hold it.
    private var splashGateOpen: Bool {
        minimumSplashElapsed && scenePhase == .active && localNetwork.status != .pending
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if splashReleased {
                    RootView(model: model)
                } else {
                    SplashView(darkTheme: model.state.darkTheme)
                }
            }
            // `\.locale` localizes strings, but it does not mirror layout:
            // without `\.layoutDirection` an in-app Arabic pick would render
            // right-aligned text inside a left-to-right shell.
            .environment(\.locale, Locale(identifier: model.state.locale))
            .environment(\.layoutDirection, Locales.layoutDirection(for: model.state.locale))
            .preferredColorScheme(model.state.darkTheme ? .dark : .light)
            // One-way latch: once the launch hold is satisfied the splash never
            // comes back, not even when a later interruption makes the app
            // inactive again.
            .onChange(of: splashGateOpen, initial: true) { _, open in
                guard open else { return }
                withAnimation(.easeInOut(duration: 0.25)) { splashReleased = true }
            }
            .task {
                // Android holds its splash for 900 ms; room setup already runs
                // concurrently inside AppViewModel, so this is not dead time.
                localNetwork.start()
                try? await Task.sleep(nanoseconds: 900_000_000)
                minimumSplashElapsed = true
            }
            // The permission outcome is the one signal that says the Wi-Fi path
            // is usable again. It cannot be replaced by the scene phase: an alert
            // can be answered without the scene ever leaving `.active`, and the
            // scheduled retries may already have run out by then.
            .onChange(of: localNetwork.status) { _, status in
                if status == .granted {
                    model.handleLocalNetworkGranted()
                }
            }
            // `initial: true` matters: the launch transition to `.active` is the
            // baseline the probe compares an alert against, and `onChange`
            // otherwise never reports it.
            .onChange(of: scenePhase, initial: true) { _, phase in
                localNetwork.handleScenePhase(phase)
                if phase == .active {
                    model.handleScenePhaseActive()
                }
                if phase == .background {
                    model.handleScenePhaseBackground()
                }
            }
        }
    }
}
