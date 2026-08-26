import Foundation

/// The watermark the player should be drawing right now.
///
/// Held apart from the video composition on purpose. Replacing an `AVPlayerItem`'s
/// `videoComposition` makes AVFoundation rebuild its render pipeline, which the operator sees as
/// the video stopping and picking up again — switching the watermark on and off looked exactly
/// like it was pausing the video. Installing the composition once and letting it read this switch
/// on every frame means a toggle costs nothing at all.
///
/// Read from the composition's render threads and written from the main actor, so it locks.
final class WatermarkSwitch: @unchecked Sendable {

    private let lock = NSLock()
    private var handle: String?

    /// Rebuilt only when the handle changes: the label is drawn once and reused per frame.
    private var current: WanderingWatermark?

    var isOn: Bool {
        lock.lock()
        defer { lock.unlock() }
        return handle != nil
    }

    func set(_ handle: String?) {
        lock.lock()
        defer { lock.unlock() }
        guard handle != self.handle else { return }
        self.handle = handle
        current = handle.map(WanderingWatermark.init(handle:))
    }

    /// What to draw this frame, or nil while the watermark is off.
    func watermark() -> WanderingWatermark? {
        lock.lock()
        defer { lock.unlock() }
        return current
    }
}
