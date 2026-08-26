import SwiftUI

/// The scrubber under the video: where you are, how much is left, and a bar to move about with.
///
/// Kept out of ``ConversationPageView`` because that file already carries the whole screen, and
/// this needs to know nothing about players or downloads.
struct VideoScrubber: View {

    let positionMS: Int64
    let durationMS: Int64
    let onScrub: (Int64) -> Void
    let onScrubFinished: () -> Void

    var body: some View {
        // Until the player reports a duration there is nothing meaningful to scrub along.
        if durationMS > 0 {
            HStack(spacing: 12) {
                Text(clock(positionMS))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("elapsed")

                Slider(
                    value: Binding(
                        get: { min(max(Double(positionMS) / Double(durationMS), 0), 1) },
                        set: { onScrub(Int64($0 * Double(durationMS))) }
                    ),
                    in: 0...1,
                    onEditingChanged: { editing in if !editing { onScrubFinished() } }
                )
                .tint(.white)

                // Counting down rather than up: how much is left is the thing worth knowing.
                Text("-" + clock(durationMS - positionMS))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 16)
            // A container, not a leaf: naming the row without this collapses the times and the
            // slider into one element and hides them.
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("scrubber")
        }
    }

    private func clock(_ ms: Int64) -> String {
        let seconds = (max(ms, 0) + 500) / 1000
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// The badge shown while the screen is held down and the video is running fast.
struct SpeedBadge: View {

    let speed: Int

    var body: some View {
        Text(Strings.playbackSpeed(speed))
            .font(.callout.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.black.opacity(0.55), in: .capsule)
    }
}
