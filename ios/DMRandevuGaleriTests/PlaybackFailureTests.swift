import XCTest
@testable import DMRandevuGaleri

final class PlaybackFailureTests: XCTestCase {

    private func error(_ domain: String, _ code: Int, under: NSError? = nil) -> NSError {
        NSError(domain: domain, code: code, userInfo: under.map { [NSUnderlyingErrorKey: $0] } ?? [:])
    }

    /// The shape seen on device: an AVFoundation error over the URL error over CoreMedia's code.
    func testAFourOhFourBeforePlaybackIsADeadLinkNotATransientFailure() {
        let seen = error(
            AVFoundationErrorDomainForTests, -11800,
            under: error(NSURLErrorDomain, NSURLErrorFileDoesNotExist,
                         under: error(NSOSStatusErrorDomain, -12938))
        )
        XCTAssertEqual(PlaybackFailure.httpStatus(in: seen), 404)
        XCTAssertEqual(PlaybackFailure(status: PlaybackFailure.httpStatus(in: seen)), .linkDead)
    }

    func testCoreMediasCodeAloneIsReadAsAFourOhFour() {
        XCTAssertEqual(PlaybackFailure.httpStatus(in: error(NSOSStatusErrorDomain, -12938)), 404)
    }

    func testAnAuthenticationErrorIsTheSessionEnding() {
        let seen = error(AVFoundationErrorDomainForTests, -11800,
                         under: error(NSURLErrorDomain, NSURLErrorUserAuthenticationRequired))
        XCTAssertEqual(PlaybackFailure(status: PlaybackFailure.httpStatus(in: seen)), .sessionLost)
    }

    func testADroppedConnectionStaysRetryable() {
        let seen = error(AVFoundationErrorDomainForTests, -11800,
                         under: error(NSURLErrorDomain, NSURLErrorNetworkConnectionLost))
        XCTAssertNil(PlaybackFailure.httpStatus(in: seen))
        XCTAssertEqual(PlaybackFailure(status: nil), .transient)
    }

    func testNoErrorHasNoStatus() {
        XCTAssertNil(PlaybackFailure.httpStatus(in: nil))
    }
}

private let AVFoundationErrorDomainForTests = "AVFoundationErrorDomain"
