import XCTest
@testable import DMRandevuGaleri

final class WanderPathTests: XCTestCase {

    /// Five minutes at twenty samples a second.
    private let seconds = (0...6_000).map { Double($0) * 0.05 }

    private func path(seed: UInt64) -> WanderPath {
        var generator = SeededGenerator(seed: seed)
        return WanderPath(using: &generator)
    }

    func testTheLabelNeverLeavesTheFrame() {
        for seed in 0..<200 {
            let path = path(seed: UInt64(seed))
            for t in seconds {
                XCTAssertLessThanOrEqual(abs(path.x(at: t)), 1)
                XCTAssertLessThanOrEqual(abs(path.y(at: t)), 1)
            }
        }
    }

    func testVideosDoNotStartInTheSamePlace() {
        let starts = (0..<50).map { seed -> (Double, Double) in
            let path = path(seed: UInt64(seed))
            return (path.x(at: 0), path.y(at: 0))
        }
        XCTAssertEqual(Set(starts.map { "\($0.0),\($0.1)" }).count, starts.count)
        // Spread across the frame, not bunched: some start on each side of the centre.
        XCTAssertTrue(starts.contains { $0.0 < -0.3 } && starts.contains { $0.0 > 0.3 })
        XCTAssertTrue(starts.contains { $0.1 < -0.3 } && starts.contains { $0.1 > 0.3 })
    }

    func testItDriftsRatherThanJumps() {
        for seed in 0..<200 {
            let path = path(seed: UInt64(seed))
            for (a, b) in zip(seconds, seconds.dropFirst()) {
                let step = hypot(path.x(at: b) - path.x(at: a), path.y(at: b) - path.y(at: a))
                // Offsets are in half-frames. The fastest the weights allow is about 0.51 a
                // second (0.41 vertically and 0.31 across at once), so 0.55 only fails on a path
                // that has actually got quicker than designed.
                XCTAssertLessThan(step / (b - a), 0.55, "seed \(seed) at \(a)")
            }
        }
    }

    func testItVisitsTheFrameRatherThanStayingNearTheCentre() {
        let path = path(seed: 7)
        XCTAssertGreaterThan(seconds.map { abs(path.x(at: $0)) }.max()!, 0.7)
        XCTAssertGreaterThan(seconds.map { abs(path.y(at: $0)) }.max()!, 0.7)
    }
}

/// SplitMix64, so a failing seed can be replayed.
private struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) { state = seed }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
