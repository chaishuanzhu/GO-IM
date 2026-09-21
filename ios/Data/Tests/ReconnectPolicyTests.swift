import XCTest
@testable import Data
import Domain

final class ReconnectPolicyTests: XCTestCase {
    func testBackoffSequenceWithoutJitter() {
        let delays = (0..<6).map { ReconnectPolicy.delayNanoseconds(attempt: $0, jitter: 0) }
        XCTAssertEqual(delays[0], 1_000_000_000)
        XCTAssertEqual(delays[1], 2_000_000_000)
        XCTAssertEqual(delays[2], 4_000_000_000)
        XCTAssertEqual(delays[3], 8_000_000_000)
        XCTAssertEqual(delays[4], 16_000_000_000)
        XCTAssertEqual(delays[5], 30_000_000_000) // capped
    }

    func testBackoffCapsAtThirtySeconds() {
        let delay = ReconnectPolicy.delayNanoseconds(attempt: 20, jitter: 0)
        XCTAssertEqual(delay, ReconnectPolicy.maxDelayNs)
    }

    func testJitterStaysWithinTwentyPercent() {
        let base = ReconnectPolicy.delayNanoseconds(attempt: 0, jitter: 0)
        let low = ReconnectPolicy.delayNanoseconds(attempt: 0, jitter: -0.2)
        let high = ReconnectPolicy.delayNanoseconds(attempt: 0, jitter: 0.2)
        XCTAssertEqual(low, UInt64(Double(base) * 0.8))
        XCTAssertEqual(high, UInt64(Double(base) * 1.2))
    }

    func testMayReconnectGate() {
        XCTAssertTrue(ReconnectPolicy.mayReconnect(
            intentionalDisconnect: false, authExpired: false, hasUser: true
        ))
        XCTAssertFalse(ReconnectPolicy.mayReconnect(
            intentionalDisconnect: true, authExpired: false, hasUser: true
        ))
        XCTAssertFalse(ReconnectPolicy.mayReconnect(
            intentionalDisconnect: false, authExpired: true, hasUser: true
        ))
        XCTAssertFalse(ReconnectPolicy.mayReconnect(
            intentionalDisconnect: false, authExpired: false, hasUser: false
        ))
    }

    func testAuthFailureClassification() {
        XCTAssertTrue(ReconnectPolicy.isAuthFailure("HTTP 401 Unauthorized"))
        XCTAssertTrue(ReconnectPolicy.isAuthFailure("invalid token: expired"))
        XCTAssertTrue(ReconnectPolicy.isAuthFailure("jwt: signature invalid"))
        XCTAssertTrue(ReconnectPolicy.isAuthFailure("missing token"))
        XCTAssertFalse(ReconnectPolicy.isAuthFailure("connection reset by peer"))
        XCTAssertFalse(ReconnectPolicy.isAuthFailure("connect timeout"))
    }

    func testParseUnreadCounts() {
        let json = #"{"uid":"alice","counts":{"bob":3,"carol":1}}"#
        let map = CmdDispatchHandler.parseUnreadCounts(json)
        XCTAssertEqual(map?["bob"], 3)
        XCTAssertEqual(map?["carol"], 1)
        XCTAssertNil(CmdDispatchHandler.parseUnreadCounts("not-json"))
    }
}
