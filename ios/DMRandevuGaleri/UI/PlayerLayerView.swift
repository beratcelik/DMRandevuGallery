import AVFoundation
import SwiftUI
import UIKit

/// The video itself. SwiftUI's `VideoPlayer` brings its own controls and its own gestures, both of
/// which this screen replaces, so the layer is used directly.
struct PlayerLayerView: UIViewRepresentable {

    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerHostView {
        let view = PlayerHostView()
        view.backgroundColor = .black
        view.player = player
        return view
    }

    func updateUIView(_ view: PlayerHostView, context: Context) {
        view.player = player
    }

    static func dismantleUIView(_ view: PlayerHostView, coordinator: ()) {
        view.player = nil
    }
}

final class PlayerHostView: UIView {

    override class var layerClass: AnyClass { AVPlayerLayer.self }

    private var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }

    var player: AVPlayer? {
        get { playerLayer.player }
        set {
            guard playerLayer.player !== newValue else { return }
            playerLayer.player = newValue
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        // Letterbox rather than crop: a portrait clip on a portrait screen still has to show all
        // of itself, because what is cut off might be the thing being reported.
        playerLayer.videoGravity = .resizeAspect
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
