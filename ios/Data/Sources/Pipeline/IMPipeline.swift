import Foundation

public protocol IMHandler: AnyObject {
    var name: String { get }
}

public protocol IMInboundHandler: IMHandler {
    func channelRead(ctx: IMHandlerContext, msg: sending Any) async throws
}

public protocol IMOutboundHandler: IMHandler {
    func write(ctx: IMHandlerContext, msg: sending Any) async throws
}

public final class IMHandlerContext: @unchecked Sendable {
    let handler: IMHandler
    var nextInbound: IMHandlerContext?
    var nextOutbound: IMHandlerContext?
    var terminalWrite: (@Sendable (Any) async throws -> Void)?

    init(handler: IMHandler) {
        self.handler = handler
    }

    public func fireChannelRead(_ msg: sending Any) async throws {
        guard let next = nextInbound else { return }
        if let inbound = next.handler as? IMInboundHandler {
            try await inbound.channelRead(ctx: next, msg: msg)
        } else {
            try await next.fireChannelRead(msg)
        }
    }

    public func write(_ msg: sending Any) async throws {
        guard let next = nextOutbound else {
            try await terminalWrite?(msg)
            return
        }
        if let outbound = next.handler as? IMOutboundHandler {
            try await outbound.write(ctx: next, msg: msg)
        } else {
            try await next.write(msg)
        }
    }
}

public actor IMPipeline {
    private var contexts: [IMHandlerContext] = []
    private var headInbound: IMHandlerContext?
    private var headOutbound: IMHandlerContext?
    private var transportWriter: (@Sendable (Data) async throws -> Void)?

    public init() {}

    public func setTransportWriter(_ writer: @escaping @Sendable (Data) async throws -> Void) {
        transportWriter = writer
        for ctx in contexts {
            ctx.terminalWrite = { [weak self] msg in
                guard let data = msg as? Data else { return }
                try await self?.transportWriter?(data)
            }
        }
    }

    public func addLast(_ handler: IMHandler) {
        let ctx = IMHandlerContext(handler: handler)
        ctx.terminalWrite = { [weak self] msg in
            guard let data = msg as? Data else { return }
            try await self?.transportWriter?(data)
        }
        contexts.append(ctx)
        rebuildLinks()
    }

    public func fireChannelRead(_ msg: sending Any) async throws {
        guard let head = headInbound else { return }
        if let inbound = head.handler as? IMInboundHandler {
            try await inbound.channelRead(ctx: head, msg: msg)
        } else {
            try await head.fireChannelRead(msg)
        }
    }

    public func writeOutbound(_ msg: sending Any) async throws {
        guard let head = headOutbound else {
            if let data = msg as? Data {
                try await transportWriter?(data)
            }
            return
        }
        if let outbound = head.handler as? IMOutboundHandler {
            try await outbound.write(ctx: head, msg: msg)
        } else {
            try await head.write(msg)
        }
    }

    private func rebuildLinks() {
        let inbound = contexts.filter { $0.handler is IMInboundHandler }
        for i in 0..<inbound.count {
            inbound[i].nextInbound = i + 1 < inbound.count ? inbound[i + 1] : nil
        }
        headInbound = inbound.first

        let outboundArr = Array(contexts.filter { $0.handler is IMOutboundHandler }.reversed())
        for i in 0..<outboundArr.count {
            outboundArr[i].nextOutbound = i + 1 < outboundArr.count ? outboundArr[i + 1] : nil
        }
        headOutbound = outboundArr.first
    }
}
