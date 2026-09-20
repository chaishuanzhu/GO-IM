import Foundation

/// Wire-compatible encoder/decoder for api/proto/message.proto `Message`.
/// Field numbers match the server/Web client schema.
public struct WireMessage: Sendable, Equatable {
    public var seq: Int64 = 0
    public var msgId: Int64 = 0
    public var cmd: Int32 = 0
    public var from: String = ""
    public var to: String = ""
    public var chatType: Int32 = 0
    public var msgType: Int32 = 0
    public var content: String = ""
    public var timestamp: Int64 = 0
    public var needAck: Bool = false

    public init() {}
}

public enum Cmd: Int32, Sendable {
    case none = 0
    case chat = 1
    case ack = 2
    case login = 3
    case loginResp = 4
    case offline = 5
    case heartbeat = 6
    case kick = 7
    case history = 8
    case readReceipt = 9
    case unreadCount = 10
    case search = 11
    case groupCreate = 12
    case groupJoin = 13
    case groupLeave = 14
    case groupInfo = 15
    case groupList = 16
    case file = 17
    case groupInvite = 18
    case recall = 19
    case friendRequest = 20
    case friendResponse = 21
    case typing = 22
    case forward = 23
    case edit = 24
}

public enum ProtobufCodec {
    private static let wireVarint: UInt8 = 0
    private static let wireLength: UInt8 = 2

    public static func encode(_ msg: WireMessage) -> Data {
        var out = Data()
        writeVarintField(&out, 1, msg.seq)
        writeVarintField(&out, 2, msg.msgId)
        writeVarintField(&out, 3, Int64(msg.cmd))
        writeStringField(&out, 4, msg.from)
        writeStringField(&out, 5, msg.to)
        writeVarintField(&out, 6, Int64(msg.chatType))
        writeVarintField(&out, 7, Int64(msg.msgType))
        writeStringField(&out, 8, msg.content)
        writeVarintField(&out, 9, msg.timestamp)
        if msg.needAck {
            writeVarintField(&out, 10, 1)
        }
        return out
    }

    public static func decode(_ data: Data) throws -> WireMessage {
        var msg = WireMessage()
        var i = 0
        let bytes = [UInt8](data)
        while i < bytes.count {
            let (tag, ni) = try readVarint(bytes, i)
            i = ni
            let field = Int(tag >> 3)
            let wire = UInt8(tag & 0x7)
            switch wire {
            case wireVarint:
                let (v, nj) = try readVarint(bytes, i)
                i = nj
                switch field {
                case 1: msg.seq = Int64(bitPattern: v)
                case 2: msg.msgId = Int64(bitPattern: v)
                case 3: msg.cmd = Int32(truncatingIfNeeded: v)
                case 6: msg.chatType = Int32(truncatingIfNeeded: v)
                case 7: msg.msgType = Int32(truncatingIfNeeded: v)
                case 9: msg.timestamp = Int64(bitPattern: v)
                case 10: msg.needAck = v != 0
                default: break
                }
            case wireLength:
                let (len, nj) = try readVarint(bytes, i)
                i = nj
                let l = Int(len)
                guard i + l <= bytes.count else { throw CodecError.truncated }
                let slice = Data(bytes[i..<(i + l)])
                i += l
                let s = String(data: slice, encoding: .utf8) ?? ""
                switch field {
                case 4: msg.from = s
                case 5: msg.to = s
                case 8: msg.content = s
                default: break
                }
            default:
                throw CodecError.unsupportedWireType
            }
        }
        return msg
    }

    private static func writeVarintField(_ out: inout Data, _ field: Int, _ value: Int64) {
        guard value != 0 else { return }
        out.append(UInt8((field << 3) | Int(wireVarint)))
        writeVarint(&out, UInt64(bitPattern: value))
    }

    private static func writeStringField(_ out: inout Data, _ field: Int, _ value: String) {
        guard !value.isEmpty, let data = value.data(using: .utf8) else { return }
        out.append(UInt8((field << 3) | Int(wireLength)))
        writeVarint(&out, UInt64(data.count))
        out.append(data)
    }

    private static func writeVarint(_ out: inout Data, _ value: UInt64) {
        var v = value
        while v > 0x7F {
            out.append(UInt8((v & 0x7F) | 0x80))
            v >>= 7
        }
        out.append(UInt8(v & 0x7F))
    }

    private static func readVarint(_ bytes: [UInt8], _ start: Int) throws -> (UInt64, Int) {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        var i = start
        while true {
            guard i < bytes.count else { throw CodecError.truncated }
            let b = bytes[i]
            i += 1
            result |= UInt64(b & 0x7F) << shift
            if b & 0x80 == 0 { return (result, i) }
            shift += 7
            if shift > 63 { throw CodecError.overflow }
        }
    }

    public enum CodecError: Error {
        case truncated
        case unsupportedWireType
        case overflow
    }
}

public enum LengthFrameCodec {
    public static func encode(_ payload: Data) -> Data {
        var out = Data(count: 4 + payload.count)
        let len = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: len) { out.replaceSubrange(0..<4, with: $0) }
        out.replaceSubrange(4..<(4 + payload.count), with: payload)
        return out
    }

    public static func feed(buffer: inout Data) -> [Data] {
        var frames: [Data] = []
        while buffer.count >= 4 {
            let len = UInt32(bigEndian: buffer.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self) })
            let total = 4 + Int(len)
            guard buffer.count >= total else { break }
            frames.append(Data(buffer[4..<total]))
            buffer.removeSubrange(0..<total)
        }
        return frames
    }
}
