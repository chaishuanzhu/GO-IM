import Foundation
import Domain

public final class LoggingHandler: IMInboundHandler, IMOutboundHandler, @unchecked Sendable {
    public let name = "logging"
    public init() {}

    public func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws {
        #if DEBUG
        print("[IM][IN]", type(of: msg))
        #endif
        try await ctx.fireChannelRead(msg)
    }

    public func write(ctx: IMHandlerContext, msg: sending Any) async throws {
        #if DEBUG
        print("[IM][OUT]", type(of: msg))
        #endif
        try await ctx.write(msg)
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
    private let selfUID: String

    public init(selfUID: String, onEvent: @escaping @Sendable (InboundEvent) -> Void) {
        self.selfUID = selfUID
        self.onEvent = onEvent
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
        case .heartbeat, .loginResp:
            break
        default:
            try await ctx.fireChannelRead(wire)
        }
    }

    private func mapMessage(_ wire: WireMessage) -> Message {
        Message(
            serverMsgId: wire.msgId == 0 ? nil : wire.msgId,
            clientSeq: wire.seq,
            conversationId: conversationId(for: wire),
            fromUID: wire.from,
            toUID: wire.to,
            chatType: ChatType(rawValue: wire.chatType) ?? .single,
            msgType: MsgType(rawValue: wire.msgType) ?? .text,
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
