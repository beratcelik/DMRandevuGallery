import CoreMedia
import XCTest

@testable import DMRandevuGaleri

/// The conversion that crashed the first build on device.
///
/// A player answers `.invalid` for its position until its item is ready, and `.indefinite` for the
/// duration of a stream. Converting either straight to an integer traps, so every one of these has
/// to come back as nil instead.
final class CMTimeIntegersTests: XCTestCase {

    func testRealInstantsConvert() {
        XCTAssertEqual(CMTime(seconds: 1.5, preferredTimescale: 600).milliseconds, 1_500)
        XCTAssertEqual(CMTime(seconds: 1.5, preferredTimescale: 600).microseconds, 1_500_000)
        XCTAssertEqual(CMTime.zero.milliseconds, 0)
    }

    func testTimesThatAreNotInstantsComeBackNil() {
        // Each of these reaches Int64() as NaN or infinity and takes the app down with it.
        XCTAssertNil(CMTime.invalid.milliseconds)
        XCTAssertNil(CMTime.indefinite.milliseconds)
        XCTAssertNil(CMTime.positiveInfinity.milliseconds)
        XCTAssertNil(CMTime.negativeInfinity.milliseconds)
        XCTAssertNil(CMTime.invalid.microseconds)
        XCTAssertNil(CMTime.indefinite.microseconds)
    }

    func testAnAbsurdlyLargeTimeIsRefusedRatherThanWrapped() {
        // Valid, finite, and still far past what an Int64 of microseconds can hold.
        let huge = CMTime(seconds: 1e15, preferredTimescale: 600)
        XCTAssertNil(huge.microseconds)
    }
}
