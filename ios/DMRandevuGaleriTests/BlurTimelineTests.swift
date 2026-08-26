import CoreGraphics
import XCTest

@testable import DMRandevuGaleri

/// The timeline is where a handful of detections per second turn into cover on every frame, and
/// every rule in it exists because something leaked without it. None of this needs a device.
final class BlurTimelineTests: XCTestCase {

    private let duration: Int64 = 10_000_000 // 10 s

    // MARK: - Padding

    func testFaceBoxIsPaddedGenerouslyOnBothAxes() {
        let detected = CGRect(x: 0.40, y: 0.40, width: 0.20, height: 0.20)
        let covered = box(at: 1_000_000, from: [sample(1_000_000, detected, .ellipse)])

        // 30% of the longer side, out of every edge.
        XCTAssertEqual(covered.minX, 0.34, accuracy: 0.001)
        XCTAssertEqual(covered.maxX, 0.66, accuracy: 0.001)
        XCTAssertEqual(covered.minY, 0.34, accuracy: 0.001)
        XCTAssertEqual(covered.maxY, 0.66, accuracy: 0.001)
    }

    func testPlateBoxKeepsItsShape() {
        // A plate is wide and short; padding it like a face would paint a square over the car.
        let detected = CGRect(x: 0.40, y: 0.50, width: 0.20, height: 0.04)
        let covered = box(at: 1_000_000, from: [sample(1_000_000, detected, .rectangle)])

        XCTAssertEqual(covered.width, 0.20 * 1.2, accuracy: 0.001, "10% per side across")
        XCTAssertEqual(covered.height, 0.04 * 1.6, accuracy: 0.001, "30% per side down")
        XCTAssertGreaterThan(covered.width / covered.height, 3, "still plate-shaped")
    }

    func testPaddingIsClampedToTheFrame() {
        // The detector happily reports a box running off the edge; clamping only one side of it
        // would leave the rect inside-out with a negative width.
        let detected = CGRect(x: -0.05, y: 0.90, width: 0.20, height: 0.20)
        let covered = box(at: 1_000_000, from: [sample(1_000_000, detected, .ellipse)])

        XCTAssertGreaterThanOrEqual(covered.minX, 0)
        XCTAssertLessThanOrEqual(covered.maxY, 1)
        XCTAssertGreaterThan(covered.width, 0)
        XCTAssertGreaterThan(covered.height, 0)
    }

    // MARK: - Lead-in and hold

    func testCoverStartsBeforeAndOutlastsTheDetections() {
        let timeline = build([sample(2_000_000, face, .ellipse)])

        XCTAssertEqual(timeline.regions(at: 1_400_000)?.count, 1, "covered 600 ms early")
        XCTAssertEqual(timeline.regions(at: 2_800_000)?.count, 1, "still covered 800 ms later")
        XCTAssertEqual(timeline.regions(at: 1_200_000)?.count, 0, "before the lead-in")
        XCTAssertEqual(timeline.regions(at: 3_000_000)?.count, 0, "after the hold")
    }

    func testTheWindowIsClampedToTheVideo() {
        // The lead-in would start before zero and the hold would run past the last frame; both
        // have to be trimmed or the track claims time the video does not have.
        let atStart = build([sample(0, face, .ellipse)])
        XCTAssertEqual(atStart.regions(at: 0)?.count, 1, "covered from the very first frame")

        let atEnd = build([sample(duration - 100_000, face, .ellipse)])
        XCTAssertEqual(atEnd.regions(at: duration)?.count, 1, "covered to the very last frame")
        XCTAssertEqual(atEnd.regions(at: duration + 1)?.count, 0, "and no further")
    }

    // MARK: - Motion

    func testBoxIsInterpolatedBetweenSamples() {
        let timeline = build([
            sample(1_000_000, CGRect(x: 0.10, y: 0.40, width: 0.10, height: 0.10), .ellipse),
            sample(1_200_000, CGRect(x: 0.30, y: 0.40, width: 0.10, height: 0.10), .ellipse)
        ])
        let midway = timeline.regions(at: 1_100_000)!.first!.box
        XCTAssertEqual(midway.midX, 0.25, accuracy: 0.005, "halfway between the two sightings")
    }

    func testCoverKeepsTravellingAfterTheLastDetection() {
        // Freezing the box here is what let a passing car slide out from under its own mosaic.
        let timeline = build([
            sample(1_000_000, CGRect(x: 0.10, y: 0.40, width: 0.08, height: 0.04), .rectangle),
            sample(1_200_000, CGRect(x: 0.30, y: 0.40, width: 0.08, height: 0.04), .rectangle)
        ])
        let last = timeline.regions(at: 1_200_000)!.first!.box
        let after = timeline.regions(at: 1_600_000)!.first!.box
        XCTAssertGreaterThan(after.midX, last.midX + 0.1, "carried on along its own path")
    }

    // MARK: - Chaining

    func testOverlappingSightingsBecomeOneTrack() {
        let timeline = build([
            sample(1_000_000, CGRect(x: 0.40, y: 0.40, width: 0.10, height: 0.10), .ellipse),
            sample(1_200_000, CGRect(x: 0.42, y: 0.40, width: 0.10, height: 0.10), .ellipse)
        ])
        XCTAssertEqual(timeline.regions(at: 1_100_000)?.count, 1, "one subject, not two")
    }

    func testAFastPlateIsChainedEvenWithoutOverlap() {
        // A plate on a passing car crosses more than its own width between samples. Treating each
        // sighting as new would leave every one of them a lone box with no motion to carry it.
        let timeline = build([
            sample(1_000_000, CGRect(x: 0.10, y: 0.50, width: 0.06, height: 0.03), .rectangle),
            sample(1_200_000, CGRect(x: 0.24, y: 0.50, width: 0.06, height: 0.03), .rectangle)
        ])
        XCTAssertEqual(timeline.regions(at: 1_200_000)?.count, 1, "one car, chained by distance")
    }

    func testTwoSubjectsInOneFrameStaySeparate() {
        // How the scanner really reports a frame: one sample carrying every detection in it.
        let builder = BlurTimeline.Builder(videoDurationUS: duration)
        builder.addSample(timeUS: 1_000_000, regions: [
            BlurTimeline.Region(
                box: CGRect(x: 0.05, y: 0.10, width: 0.08, height: 0.08),
                shape: .ellipse
            ),
            BlurTimeline.Region(
                box: CGRect(x: 0.80, y: 0.80, width: 0.08, height: 0.08),
                shape: .ellipse
            )
        ])
        XCTAssertEqual(builder.build().regions(at: 1_000_000)?.count, 2)
    }

    // MARK: - Overflow

    func testACrowdedFrameAsksForTheWholeFrame() {
        // Nil is the renderer's instruction to mosaic everything rather than drop any region.
        let builder = BlurTimeline.Builder(videoDurationUS: duration)
        let crowd = (0..<(BlurTimeline.maxRegions + 4)).map { index -> BlurTimeline.Region in
            let column = CGFloat(index % 5) * 0.19
            let row = CGFloat(index / 5) * 0.19
            return BlurTimeline.Region(
                box: CGRect(x: column, y: row, width: 0.04, height: 0.04),
                shape: .ellipse
            )
        }
        builder.addSample(timeUS: 1_000_000, regions: crowd)
        XCTAssertNil(builder.build().regions(at: 1_000_000))
    }

    func testAnEmptyTimelineCoversNothing() {
        XCTAssertTrue(BlurTimeline.empty().isEmpty)
        XCTAssertEqual(BlurTimeline.empty().regions(at: 0)?.count, 0)
    }

    // MARK: - Helpers

    private let face = CGRect(x: 0.40, y: 0.40, width: 0.10, height: 0.10)

    private func sample(
        _ timeUS: Int64,
        _ box: CGRect,
        _ shape: BlurTimeline.Shape
    ) -> (Int64, BlurTimeline.Region) {
        (timeUS, BlurTimeline.Region(box: box, shape: shape))
    }

    private func build(_ samples: [(Int64, BlurTimeline.Region)]) -> BlurTimeline {
        let builder = BlurTimeline.Builder(videoDurationUS: duration)
        for (timeUS, region) in samples {
            builder.addSample(timeUS: timeUS, regions: [region])
        }
        return builder.build()
    }

    private func box(at timeUS: Int64, from samples: [(Int64, BlurTimeline.Region)]) -> CGRect {
        build(samples).regions(at: timeUS)!.first!.box
    }
}
