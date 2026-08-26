import CoreGraphics
import Foundation

/// The numbers that decide how generously a subject is covered. Gathered here rather than spread
/// through the type because they are the knobs anyone tuning the blur actually reaches for.
private enum Tuning {

    /// Regions the renderer covers individually; past this the whole frame goes.
    static let maxRegions = 16

    /// The nominal gap between samples, which the travel and gap allowances below are reckoned
    /// in. Each `RegionFinder` picks its own rate around this.
    static let samplePeriodMS: Int64 = 200

    static let facePad: CGFloat = 0.30

    /// Per-axis for plates, so the cover stays the shape of the plate rather than a blob.
    static let platePadX: CGFloat = 0.10
    static let platePadY: CGFloat = 0.30

    /// How many sample intervals a track may be carried past its last detection.
    static let maxExtrapolation: CGFloat = 6
    static let smoothing: CGFloat = 0.5
    static let minIOU: CGFloat = 0.2

    /// How far a subject may travel between samples, in multiples of its own size.
    static let travelPerSample: CGFloat = 2.5

    /// Two sightings of one subject do not change size wildly between samples.
    static let maxSizeRatio: CGFloat = 3

    /// A track quiet for longer than this is done; the next detection starts a new one.
    ///
    /// Generous on purpose. The detector regularly loses a face that never left — turned away,
    /// behind a hand, briefly out of focus — and reacquires it a second or two later. Bridging
    /// that gap keeps the blur on it throughout instead of uncovering the face for the whole
    /// dropout. The cost is over-blurring a stretch where the person really did leave and someone
    /// else stepped into the same spot, which is the safe direction to be wrong in.
    static let maxTrackGapMS: Int64 = 5_000

    /// Blur starts this long before the first detection and lingers this long after the last.
    ///
    /// Generous, because a subject is legible for a while before the detector can name it and for
    /// a while after it loses it — a plate is readable well before it is big and square-on enough
    /// to be read, and again as it swings away. Extrapolation keeps the cover on the subject's
    /// path through both windows rather than parking it where the subject used to be.
    static let leadMS: Int64 = 700
    static let holdMS: Int64 = 900
}

/// Where the faces and plates are, over time.
///
/// Detection only runs on frames sampled every ``samplePeriodMS``, so this fills the gaps: boxes
/// are padded, chained into tracks, smoothed, interpolated between samples, and extended by a
/// lead-in and a hold so a subject is already covered on the frames around the ones it was seen in.
///
/// Coordinates are normalized to the upright frame and **y-down**, matching the detectors' image
/// space. Core Image samples bottom-up, so the one flip lives in ``MosaicRenderer``.
struct BlurTimeline {

    /// What a covered region is shaped like. A head is round; a numberplate is not.
    enum Shape {
        case ellipse
        case rectangle
    }

    /// One thing to cover in one frame, normalized to it.
    struct Region {
        let box: CGRect
        let shape: Shape
    }

    /// A region resolved to one instant, still normalized and still y-down.
    struct Placed {
        let box: CGRect
        let shape: Shape
    }

    /// One subject followed across samples. `from`/`to` already include lead-in and hold.
    struct Track {
        let from: Int64
        let to: Int64
        let times: [Int64]
        let boxes: [CGRect]
        let shape: Shape

        /// Where the subject is at `timeUS`: interpolated between the samples that bracket it,
        /// and carried along its own motion outside them.
        ///
        /// Freezing the box at the first and last detection is what made a passing car slide out
        /// from under its own mosaic — the detector loses the plate while the car is still moving,
        /// and a stationary blur then uncovers it just before it leaves the frame. Extrapolating
        /// keeps the cover travelling with it.
        func box(at timeUS: Int64) -> CGRect {
            guard let first = times.first, let last = times.last else { return .zero }
            if timeUS <= first { return extrapolated(anchor: 0, neighbour: 1, timeUS: timeUS) }
            if timeUS >= last {
                let end = times.count - 1
                return extrapolated(anchor: end, neighbour: end - 1, timeUS: timeUS)
            }

            // Both ends are handled above, so the bracketing pair always exists.
            let high = upperBound(of: timeUS)
            if times[high] == timeUS { return boxes[high] }
            let low = high - 1
            let span = CGFloat(times[high] - times[low])
            let t = span <= 0 ? 0 : CGFloat(timeUS - times[low]) / span
            return lerp(boxes[low], boxes[high], t)
        }

        /// Index of the first sample at or after `timeUS`.
        private func upperBound(of timeUS: Int64) -> Int {
            var low = 0
            var high = times.count - 1
            while low < high {
                let mid = (low + high) / 2
                if times[mid] < timeUS { low = mid + 1 } else { high = mid }
            }
            return low
        }

        /// The `anchor` sample carried forward (or back) at the speed it was moving relative to
        /// `neighbour`. A track seen only once has no speed to carry, so it stays put.
        private func extrapolated(anchor: Int, neighbour: Int, timeUS: Int64) -> CGRect {
            let box = boxes[anchor]
            guard boxes.indices.contains(neighbour) else { return box }
            let span = CGFloat(times[anchor] - times[neighbour])
            guard span != 0 else { return box }
            let other = boxes[neighbour]
            // Capped so a badly-placed final detection cannot fling the cover across the frame.
            let raw = CGFloat(timeUS - times[anchor]) / span
            let steps = min(max(raw, -Tuning.maxExtrapolation), Tuning.maxExtrapolation)
            return box.offsetBy(
                dx: (box.midX - other.midX) * steps,
                dy: (box.midY - other.midY) * steps
            )
        }
    }

    private let tracks: [Track]

    private init(tracks: [Track]) {
        self.tracks = tracks
    }

    static func empty() -> BlurTimeline { BlurTimeline(tracks: []) }

    /// Everything `parts` cover, as one timeline the renderer reads in a single pass.
    static func of(_ parts: [BlurTimeline]) -> BlurTimeline {
        BlurTimeline(tracks: parts.flatMap(\.tracks))
    }

    var isEmpty: Bool { tracks.isEmpty }

    static var maxRegions: Int { Tuning.maxRegions }
    static var samplePeriodMS: Int64 { Tuning.samplePeriodMS }

    /// The regions visible at `presentationTimeUS`.
    ///
    /// Returns nil when there are more than ``maxRegions`` — the caller then mosaics the whole
    /// frame rather than dropping any, which is the safe direction to be wrong in.
    func regions(at presentationTimeUS: Int64) -> [Placed]? {
        var placed: [Placed] = []
        for track in tracks {
            guard presentationTimeUS >= track.from, presentationTimeUS <= track.to else { continue }
            if placed.count == Tuning.maxRegions { return nil }
            placed.append(Placed(box: track.box(at: presentationTimeUS), shape: track.shape))
        }
        return placed
    }

    /// Collects detections sample by sample. `videoDurationUS` bounds the lead-in and hold so a
    /// track never claims time the video does not have.
    final class Builder {

        /// Always holds at least one sample by the time it is a candidate for chaining.
        private final class OpenTrack {
            let shape: Shape
            var times: [Int64] = []
            var boxes: [CGRect] = []
            var smoothed: CGRect?

            init(shape: Shape) { self.shape = shape }
        }

        private let videoDurationUS: Int64
        private var open: [OpenTrack] = []

        init(videoDurationUS: Int64) {
            self.videoDurationUS = videoDurationUS
        }

        /// `regions` are what one finder saw in the frame at `timeUS`.
        @discardableResult
        func addSample(timeUS: Int64, regions: [Region]) -> Builder {
            var unmatched = open
            for region in regions {
                let padded = pad(region.box, shape: region.shape)
                let track = claim(from: unmatched, timeUS: timeUS, box: padded)
                    ?? {
                        let fresh = OpenTrack(shape: region.shape)
                        open.append(fresh)
                        return fresh
                    }()
                unmatched.removeAll { $0 === track }
                let smoothed = track.smoothed.map { sizeSmoothed(padded, from: $0) } ?? padded
                track.smoothed = smoothed
                track.times.append(timeUS)
                track.boxes.append(smoothed)
            }
            return self
        }

        func build() -> BlurTimeline {
            BlurTimeline(
                tracks: open.compactMap { track in
                    guard let first = track.times.first, let last = track.times.last else {
                        return nil
                    }
                    return Track(
                        from: max(first - Tuning.leadMS * 1_000, 0),
                        to: min(last + Tuning.holdMS * 1_000, videoDurationUS),
                        times: track.times,
                        boxes: track.boxes,
                        shape: track.shape
                    )
                }
            )
        }

        /// Picks the track this detection continues. Only tracks seen within
        /// `Tuning.maxTrackGapMS` qualify — chaining onto one that went quiet long ago would
        /// splice two different subjects together and blur the empty stretch between them.
        private func claim(from candidates: [OpenTrack], timeUS: Int64, box: CGRect) -> OpenTrack? {
            let alive = candidates.filter { track in
                guard let last = track.times.last else { return false }
                return timeUS - last <= Tuning.maxTrackGapMS * 1_000
            }

            // Overlap first: when two sightings of the same thing overlap, that is the surest
            // match there is.
            var best: OpenTrack?
            var bestIOU = Tuning.minIOU
            for candidate in alive {
                guard let previous = candidate.boxes.last else { continue }
                let overlap = iou(previous, box)
                if overlap > bestIOU {
                    bestIOU = overlap
                    best = candidate
                }
            }
            if let best { return best }

            // Nothing overlapped, which does not mean it is something new. A plate on a passing
            // car crosses more than its own width between samples, and treating each sighting as
            // a fresh track leaves every one of them a lone box with no motion to carry it — the
            // blur then sits still while the car drives out from under it. So fall back to the
            // nearest sighting that could plausibly have travelled here in the time available.
            var bestDistance = CGFloat.greatestFiniteMagnitude
            for candidate in alive {
                guard let previous = candidate.boxes.last,
                      let lastSeen = candidate.times.last,
                      comparableSize(previous, box) else { continue }
                let samples = max(
                    CGFloat(timeUS - lastSeen) / CGFloat(Tuning.samplePeriodMS * 1_000),
                    1
                )
                let reach = max(max(previous.width, previous.height), max(box.width, box.height))
                    * Tuning.travelPerSample * samples
                let distance = hypot(previous.midX - box.midX, previous.midY - box.midY)
                if distance <= reach, distance < bestDistance {
                    bestDistance = distance
                    best = candidate
                }
            }
            return best
        }

        /// Eases the box size toward this detection while keeping the freshly detected centre.
        ///
        /// Detector boxes pulse in size between samples, which reads as a shivering blur. The
        /// centre is deliberately left alone: smoothing it too would make the box trail a moving
        /// face, and at a fast pan that lag outruns the padding and leaves part of the face out in
        /// the open.
        private func sizeSmoothed(_ box: CGRect, from previous: CGRect) -> CGRect {
            let halfWidth = (previous.width + (box.width - previous.width) * Tuning.smoothing) / 2
            let halfHeight = (previous.height + (box.height - previous.height) * Tuning.smoothing) / 2
            return CGRect(
                x: box.midX - halfWidth,
                y: box.midY - halfHeight,
                width: halfWidth * 2,
                height: halfHeight * 2
            )
        }

        /// Grows the box to cover what the detector's box leaves out.
        ///
        /// A face box hugs the features, so it is grown generously in both directions to take in
        /// hair, ears and chin. A plate box hugs the characters, and the plate is only a little
        /// wider and taller than they are — growing that one by the same amount would paint a
        /// patch far bigger than the plate over the car.
        private func pad(_ box: CGRect, shape: Shape) -> CGRect {
            let padX: CGFloat
            let padY: CGFloat
            if shape == .rectangle {
                padX = box.width * Tuning.platePadX
                padY = box.height * Tuning.platePadY
            } else {
                padX = max(box.width, box.height) * Tuning.facePad
                padY = padX
            }
            // Clamped on both sides: the detector happily reports a box that runs off the edge,
            // and clamping only one side of it leaves the rect inside-out with a negative width.
            let left = min(max(box.minX - padX, 0), 1)
            let top = min(max(box.minY - padY, 0), 1)
            let right = min(max(box.maxX + padX, 0), 1)
            let bottom = min(max(box.maxY + padY, 0), 1)
            return CGRect(
                x: left,
                y: top,
                width: max(right - left, 0),
                height: max(bottom - top, 0)
            )
        }
    }
}

// MARK: - Box arithmetic

private func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
    let left = a.minX + (b.minX - a.minX) * t
    let top = a.minY + (b.minY - a.minY) * t
    let right = a.maxX + (b.maxX - a.maxX) * t
    let bottom = a.maxY + (b.maxY - a.maxY) * t
    return CGRect(x: left, y: top, width: right - left, height: bottom - top)
}

private func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
    let intersection = a.intersection(b)
    guard !intersection.isNull, intersection.width > 0, intersection.height > 0 else { return 0 }
    let overlap = intersection.width * intersection.height
    let union = a.width * a.height + b.width * b.height - overlap
    return union <= 0 ? 0 : overlap / union
}

private func comparableSize(_ a: CGRect, _ b: CGRect) -> Bool {
    let one = max(a.width, a.height)
    let other = max(b.width, b.height)
    guard one > 0, other > 0 else { return false }
    return max(one, other) / min(one, other) <= Tuning.maxSizeRatio
}
