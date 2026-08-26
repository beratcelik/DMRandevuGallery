import SwiftUI
import UIKit

/// How far the controls have to stay clear of the edges of the screen.
///
/// The video is deliberately full-bleed — it runs under the notch and the home indicator, which is
/// what makes the feed feel like a feed. The controls laid over it are not: the header sat under
/// the notch, and on a phone that has one the face filter was completely invisible behind it.
private struct ChromeInsetsKey: EnvironmentKey {
    static let defaultValue = EdgeInsets()
}

extension EnvironmentValues {
    var chromeInsets: EdgeInsets {
        get { self[ChromeInsetsKey.self] }
        set { self[ChromeInsetsKey.self] = newValue }
    }
}

enum ScreenInsets {

    /// The window's safe area.
    ///
    /// Read from UIKit rather than a `GeometryReader` on purpose. A proxy only reports the insets
    /// of the region it occupies, so once anything above it has ignored the safe area — which on
    /// this screen is always, by design — it answers zero, and the controls go back under the
    /// notch. The window knows the real numbers wherever the question is asked from.
    @MainActor
    static var current: EdgeInsets {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
        guard let insets = window?.safeAreaInsets else { return EdgeInsets() }
        return EdgeInsets(
            top: insets.top,
            leading: insets.left,
            bottom: insets.bottom,
            trailing: insets.right
        )
    }
}
