import XCTest
@testable import Data

final class ProtobufCodecTests: XCTestCase {
    func testRoundTripWireMessage() throws {
        var msg = WireMessage()
        msg.seq = 42
        msg.msgId = 1001
        msg.cmd = Cmd.chat.rawValue
        msg.from = "alice"
        msg.to = "bob"
        msg.chatType = 1
        msg.msgType = 1
        msg.content = "hello"
        msg.timestamp = 1_700_000_000_000
        msg.needAck = true

        let encoded = ProtobufCodec.encode(msg)
        let decoded = try ProtobufCodec.decode(encoded)

        XCTAssertEqual(decoded.seq, msg.seq)
        XCTAssertEqual(decoded.msgId, msg.msgId)
        XCTAssertEqual(decoded.cmd, msg.cmd)
        XCTAssertEqual(decoded.from, msg.from)
        XCTAssertEqual(decoded.to, msg.to)
        XCTAssertEqual(decoded.chatType, msg.chatType)
        XCTAssertEqual(decoded.msgType, msg.msgType)
        XCTAssertEqual(decoded.content, msg.content)
        XCTAssertEqual(decoded.timestamp, msg.timestamp)
        XCTAssertEqual(decoded.needAck, msg.needAck)
    }

    func testLengthFrameEncodeAndFeed() {
        let payload = Data([0x01, 0x02, 0x03, 0x04, 0x05])
        let framed = LengthFrameCodec.encode(payload)
        XCTAssertEqual(framed.count, 4 + payload.count)

        let len = UInt32(bigEndian: framed.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self) })
        XCTAssertEqual(len, UInt32(payload.count))

        var buffer = framed
        let frames = LengthFrameCodec.feed(buffer: &buffer)
        XCTAssertEqual(frames.count, 1)
        XCTAssertEqual(frames[0], payload)
        XCTAssertTrue(buffer.isEmpty)
    }

    func testLengthFramePartialThenComplete() {
        let a = Data("aaa".utf8)
        let b = Data("bbbb".utf8)
        var stream = LengthFrameCodec.encode(a) + LengthFrameCodec.encode(b)
        // Feed first 2 bytes only
        var partial = stream.prefix(2)
        stream.removeFirst(2)
        var buf = Data(partial)
        XCTAssertTrue(LengthFrameCodec.feed(buffer: &buf).isEmpty)
        buf.append(stream)
        let frames = LengthFrameCodec.feed(buffer: &buf)
        XCTAssertEqual(frames, [a, b])
        XCTAssertTrue(buf.isEmpty)
    }
}
