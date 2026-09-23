import Foundation

/// Where the watermark is at a given moment, as an offset from the centre in -1...1 on each axis.
///
/// Each path is drawn at random when it is made: where it starts, which way it sets off, and how
/// fast it goes. Every video used to get the same path — same starting point, same figure traced
/// at the same pace — so the label sat in the same place at the same second of every clip, which
/// makes it easy to learn and edit around.
///
/// Still smooth. Each axis is a slow sine carrying a smaller, quicker one, all with periods drawn
/// from ranges and phases drawn from the full turn. Summed, they wander without settling into a
/// visible loop, and nothing ever jumps — a random position per frame would strobe and could not
/// be read. The weights add up to one, so the offset never leaves -1...1.
///
/// The Android twin is `WanderPath.kt`; the two draw from the same ranges.
struct WanderPath: Sendable {

    private let x: Axis
    private let y: Axis

    init() {
        var generator = SystemRandomNumberGenerator()
        self.init(using: &generator)
    }

    init<G: RandomNumberGenerator>(using generator: inout G) {
        x = Axis(slowRange: Self.slowXSeconds, using: &generator)
        y = Axis(slowRange: Self.slowYSeconds, using: &generator)
    }

    func x(at seconds: Double) -> Double { x.at(seconds) }
    func y(at seconds: Double) -> Double { y.at(seconds) }

    private struct Axis: Sendable {
        let slowPeriod: Double
        let slowPhase: Double
        let quickPeriod: Double
        let quickPhase: Double

        init<G: RandomNumberGenerator>(slowRange: ClosedRange<Double>, using generator: inout G) {
            slowPeriod = Double.random(in: slowRange, using: &generator)
            slowPhase = Double.random(in: 0..<WanderPath.tau, using: &generator)
            quickPeriod = Double.random(in: WanderPath.quickSeconds, using: &generator)
            quickPhase = Double.random(in: 0..<WanderPath.tau, using: &generator)
        }

        func at(_ seconds: Double) -> Double {
            WanderPath.slowWeight * sin(seconds * WanderPath.tau / slowPeriod + slowPhase)
                + WanderPath.quickWeight * sin(seconds * WanderPath.tau / quickPeriod + quickPhase)
        }
    }

    private static let tau = 2 * Double.pi

    // The slow sweeps, one per axis, around the 31 s and 23 s the fixed path used. The two ranges
    // do not overlap, so the axes never fall into step and trace a plain diagonal.
    private static let slowXSeconds: ClosedRange<Double> = 27...37
    private static let slowYSeconds: ClosedRange<Double> = 18...25

    /// The smaller, quicker sway on top, which is what keeps the path from looking drawn.
    private static let quickSeconds: ClosedRange<Double> = 10...16

    // Mostly the slow sweep, so the label still drifts rather than darts: at these weights it moves
    // at most about a fifth of the frame per second along an axis, and usually far less. The fixed
    // path peaked at about a seventh.
    private static let slowWeight = 0.8
    private static let quickWeight = 1 - slowWeight
}
