import Foundation
import Domain

public final class LoggingHandler: IMInboundHandler, IMOutboundHandler, @unchecked Sendable {
    public let name = "logging"
    public init() {}

    public func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws {
        #if DEBUG
        print("[IM][IN]", Self.describe(msg))
        #endif
        try await ctx.fireChannelRead(msg)
    }

    public func write(ctx: IMHandlerContext, msg: sending Any) async throws {
        #if DEBUG
        print("[IM][OUT]", Self.describe(msg))
        #endif
        try await ctx.write(msg)
    }

    private static func describe(_ msg: Any) -> String {
        if let wire = msg as? WireMessage {
            return format(wire)
        }
        if let data = msg as? Data {
            // Pipeline logs raw bytes around encode/decode; try to surface the protobuf payload.
            if let wire = try? ProtobufCodec.decode(data) {
                return "Data(\(data.count)B) → \(format(wire))"
            }
            // TCP length-prefixed frame: skip 4-byte header and decode body.
            if data.count > 4,
               let wire = try? ProtobufCodec.decode(Data(data.dropFirst(4)))
            {
                return "Frame(\(data.count)B) → \(format(wire))"
            }
            return "Data(\(data.count)B)"
        }
        return String(describing: type(of: msg))
    }

    private static func format(_ wire: WireMessage) -> String {
        let cmdName = Cmd(rawValue: wire.cmd).map { String(describing: $0) } ?? "cmd=\(wire.cmd)"
        var parts: [String] = [cmdName]
        if wire.seq != 0 { parts.append("seq=\(wire.seq)") }
        if wire.msgId != 0 { parts.append("msgId=\(wire.msgId)") }
        if !wire.from.isEmpty { parts.append("from=\(wire.from)") }
        if !wire.to.isEmpty { parts.append("to=\(wire.to)") }
        if wire.chatType != 0 { parts.append("chatType=\(wire.chatType)") }
        if wire.msgType != 0 { parts.append("msgType=\(wire.msgType)") }
        if wire.needAck { parts.append("needAck") }
        if wire.timestamp != 0 { parts.append("ts=\(wire.timestamp)") }
        if !wire.content.isEmpty {
            parts.append("content=\(truncate(wire.content, max: 200))")
        }
        return parts.joined(separator: " ")
    }

    private static func truncate(_ text: String, max: Int) -> String {
        guard text.count > max else { return text }
        return String(text.prefix(max)) + "…"
    }
}


public final class LengthFrameInboundHandler: IMInboundHandler, @unchecked Sendable {
    public let name = "length-frame-in"
    private var buffer = Data()
    private let enabled: Bool

    public init(enabled: Bool) {
        self.enabled = enabled
    }

    public func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws {
        guard enabled, let data = msg as? Data else {
            try await ctx.fireChannelRead(msg)
            return
        }
        buffer.append(data)
        for frame in LengthFrameCodec.feed(buffer: &buffer) {
            try await ctx.fireChannelRead(frame)
        }
    }
}

public final class LengthFrameOutboundHandler: IMOutboundHandler, @unchecked Sendable {
    public let name = "length-frame-out"
    private let enabled: Bool

    public init(enabled: Bool) {
        self.enabled = enabled
    }

    public func write(ctx: IMHandlerContext, msg: sending Any) async throws {
        guard enabled, let data = msg as? Data else {
            try await ctx.write(msg)
            return
        }
        try await ctx.write(LengthFrameCodec.encode(data))
    }
}

public final class ProtobufDecodeHandler: IMInboundHandler, @unchecked Sendable {
    public let name = "protobuf-decode"
    public init() {}

    public func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws {
        guard let data = msg as? Data else {
            try await ctx.fireChannelRead(msg)
            return
        }
        try await ctx.fireChannelRead(try ProtobufCodec.decode(data))
    }
}

public final class ProtobufEncodeHandler: IMOutboundHandler, @unchecked Sendable {
    public let name = "protobuf-encode"
    public init() {}

    public func write(ctx: IMHandlerContext, msg: sending Any) async throws {
        if let wire = msg as? WireMessage {
            try await ctx.write(ProtobufCodec.encode(wire))
        } else {
            try await ctx.write(msg)
        }
    }
}

public final class CmdDispatchHandler: IMInboundHandler, @unchecked Sendable {
    public let name = "cmd-dispatch"
    private let onEvent: @Sendable (InboundEvent) -> Void
    private let onLoginResp: (@Sendable () -> Void)?
    private let onHeartbeat: (@Sendable () -> Void)?
    private let selfUID: String

    public init(
        selfUID: String,
        onEvent: @escaping @Sendable (InboundEvent) -> Void,
        onLoginResp: (@Sendable () -> Void)? = nil,
        onHeartbeat: (@Sendable () -> Void)? = nil
    ) {
        self.selfUID = selfUID
        self.onEvent = onEvent
        self.onLoginResp = onLoginResp
        self.onHeartbeat = onHeartbeat
    }

    public func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws {
        guard let wire = msg as? WireMessage else {
            try await ctx.fireChannelRead(msg)
            return
        }
        switch Cmd(rawValue: wire.cmd) {
        case .ack:
            onEvent(.ack(seq: wire.seq, msgId: wire.msgId))
        case .kick:
            onEvent(.kick)
        case .chat, .file:
            onEvent(.message(mapMessage(wire)))
        case .history:
            // Completion frame only (history rows arrive as CmdChat/CmdFile).
            onEvent(.historyFinished(delivered: Int(wire.seq)))
        case .friendRequest:
            onEvent(.friendRequest(FriendRequest(
                fromUID: wire.from,
                toUID: wire.to,
                status: .pending,
                createdAt: wire.timestamp
            )))
        case .friendResponse:
            let accept = wire.content.lowercased().contains("accept")
                || wire.content == "1"
                || wire.content.lowercased().contains("true")
            onEvent(.friendResponse(FriendRequest(
                fromUID: wire.from,
                toUID: wire.to,
                status: accept ? .accepted : .rejected,
                createdAt: wire.timestamp
            )))
        case .search:
            if wire.content.isEmpty && wire.msgId == 0 {
                onEvent(.searchHits([], finished: true))
            } else {
                onEvent(.searchHits([
                    SearchHit(
                        serverMsgId: wire.msgId,
                        conversationId: conversationId(for: wire),
                        fromUID: wire.from,
                        content: wire.content,
                        timestampMs: wire.timestamp
                    ),
                ], finished: false))
            }
        case .unreadCount:
            if let map = Self.parseUnreadCounts(wire.content) {
                onEvent(.unread(map))
            }
        case .heartbeat:
            onHeartbeat?()
        case .loginResp:
            onLoginResp?()
        default:
            try await ctx.fireChannelRead(wire)
        }
    }

    /// Server payload: `{"uid":"...","counts":{"peer":1}}`.
    static func parseUnreadCounts(_ content: String) -> [String: Int]? {
        guard let data = content.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let counts = json["counts"] as? [String: Any] else {
            return nil
        }
        var map: [String: Int] = [:]
        for (key, value) in counts {
            if let n = value as? Int {
                map[key] = n
            } else if let n = value as? Int64 {
                map[key] = Int(n)
            } else if let n = value as? Double {
                map[key] = Int(n)
            } else if let n = value as? NSNumber {
                map[key] = n.intValue
            }
        }
        return map
    }

    private func mapMessage(_ wire: WireMessage) -> Message {
        Message(
            serverMsgId: wire.msgId == 0 ? nil : wire.msgId,
            clientSeq: wire.seq,
            conversationId: conversationId(for: wire),
            fromUID: wire.from,
            toUID: wire.to,
            chatType: ChatType(rawValue: wire.chatType) ?? .single,
            msgType: MsgType(rawValue: wire.msgType),
            content: wire.content,
            timestampMs: wire.timestamp,
            status: .sent,
            isOutgoing: wire.from == selfUID
        )
    }

    private func conversationId(for wire: WireMessage) -> String {
        if wire.chatType == ChatType.group.rawValue {
            let gid = wire.to.hasPrefix("g_") ? wire.to : wire.to
            return ConversationID.group(gid)
        }
        let peer = wire.from == selfUID ? wire.to : wire.from
        return ConversationID.dm(uidA: selfUID, uidB: peer)
    }
}
