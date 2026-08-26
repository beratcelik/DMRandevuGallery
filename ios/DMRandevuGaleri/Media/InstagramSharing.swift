import Foundation
import UIKit

/// Hands a video straight to one of Instagram's composers.
///
/// On iOS the hand-off is a URL scheme plus a pasteboard item rather than an intent, but the shape
/// of the problem is the same as on Android: Stories takes a video directly, Reels does not.
///
/// Note on captions: no Instagram entry point accepts one. The caption can only travel via the
/// clipboard for the operator to paste.
enum InstagramSharing {

    /// Public Meta app identifier; Instagram requires it to attribute the incoming share.
    private static let sourceApplication = "685976850839286"

    private static let storiesScheme = "instagram-stories://share?source_application="
    private static let appScheme = "instagram://app"

    /// The pasteboard key Instagram reads a Story's background video from.
    private static let backgroundVideoKey = "com.instagram.sharedSticker.backgroundVideo"

    @MainActor
    static var isInstalled: Bool {
        guard let url = URL(string: appScheme) else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    /// Opens the Stories composer with `video` already loaded.
    ///
    /// The video travels on the pasteboard rather than in the URL, which is how Instagram's
    /// documented Stories hand-off works. It is given a short expiry so a large video is not left
    /// sitting in the system pasteboard afterwards.
    @MainActor
    static func openStoryComposer(video: URL) -> Bool {
        guard let url = URL(string: storiesScheme + sourceApplication),
              UIApplication.shared.canOpenURL(url),
              let data = try? Data(contentsOf: video) else { return false }

        UIPasteboard.general.setItems(
            [[backgroundVideoKey: data]],
            options: [.expirationDate: Date().addingTimeInterval(pasteboardLifetime)]
        )
        UIApplication.shared.open(url)
        return true
    }

    /// Opens Instagram so a Reel can be created from a video already sitting in the photo library,
    /// with the caption waiting on the clipboard.
    ///
    /// There is no way to hand a video straight to the Reels composer: the reels entry point is
    /// only honoured for apps Meta approved for "Sharing to Reels", and an unapproved caller is
    /// silently bounced elsewhere — the same wall the Android build ran into. So the video is
    /// saved to the photo library first and picked there instead.
    @MainActor
    static func openInstagram() -> Bool {
        guard let url = URL(string: appScheme), UIApplication.shared.canOpenURL(url) else {
            return false
        }
        UIApplication.shared.open(url)
        return true
    }

    @MainActor
    static func copyCaption(_ caption: String) {
        UIPasteboard.general.string = caption
    }

    /// Long enough for the operator to finish the post, short enough that a video is not left on
    /// the pasteboard for the rest of the day.
    private static let pasteboardLifetime: TimeInterval = 5 * 60
}
