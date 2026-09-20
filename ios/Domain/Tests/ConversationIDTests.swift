import XCTest
@testable import Domain

final class ConversationIDTests: XCTestCase {
    func testDMSortingIsStable() {
        let a = ConversationID.dm(uidA: "bob", uidB: "alice")
        let b = ConversationID.dm(uidA: "alice", uidB: "bob")
        XCTAssertEqual(a, b)
        XCTAssertEqual(a, "dm:alice_bob")
    }

    func testGroupId() {
        XCTAssertEqual(ConversationID.group("g_123"), "group:g_123")
    }
}
