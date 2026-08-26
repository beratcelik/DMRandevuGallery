import AVFoundation
import SwiftUI

/// Single window, single state toggle: Login ↔ Gallery.
/// A navigation stack would only add indirection for two destinations.
@main
struct DMRandevuGaleriApp: App {

    /// The resolved Instagram id doubles as the "logged in" flag: we only ever reach the gallery
    /// by resolving an account, which requires a live session.
    @State private var igId: String?

    init() {
        ServiceLocator.start()
        // Videos have sound worth hearing, and the operator should not have to notice the ring
        // switch to get it.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                Color.black.ignoresSafeArea()
                if let igId {
                    GalleryView(igId: igId, onSessionLost: { self.igId = nil })
                        .id(igId)
                } else {
                    LoginView(onAuthenticated: { igId = $0 })
                }
            }
            .preferredColorScheme(.dark)
            .statusBarHidden()
        }
    }
}
