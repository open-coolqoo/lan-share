import SwiftUI

/// Layout metrics for canvases wider than a single phone column: the iPhone
/// Duo's unfolded display, split view, and any regular-width window.
///
/// Every cap only takes effect once the canvas is wider than it, so the phone
/// layouts are unchanged. The two-column breakpoint is the platform's own
/// `horizontalSizeClass`: `.regular` is the width class the Duo's inner
/// display reports once the phone unfolds.
enum AdaptiveLayout {
    /// A single column of panels (settings, the paired connect page) reads as
    /// a stretched form past this width.
    static let readableWidth: CGFloat = 640
    /// Total width of the two-column connect page.
    static let wideWidth: CGFloat = 960
    /// The conversation's stream and composer column: the same grid the
    /// connect page's cards and the top bar sit on, so every tab's edges line
    /// up on a wide canvas.
    static let conversationWidth: CGFloat = 960
    /// A bubble's maximum width: the design's 520pt text cap, so a bubble does
    /// not become a full-width paragraph on a folded-open canvas.
    static let bubbleWidth: CGFloat = 520
    /// The tab bar keeps phone spacing instead of spanning the whole display.
    static let tabBarWidth: CGFloat = 520
}

extension View {
    /// Caps a column at `maxWidth` and centres it in a wider canvas.
    func adaptiveColumn(_ maxWidth: CGFloat = AdaptiveLayout.readableWidth) -> some View {
        frame(maxWidth: maxWidth).frame(maxWidth: .infinity)
    }
}
