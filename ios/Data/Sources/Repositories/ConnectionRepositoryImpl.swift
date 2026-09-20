import Foundation
import Domain

public actor ConnectionRepositoryImpl: ConnectionRepository {
    private let serverConfig: ServerConfig
    private let keychain: KeychainStore

    private var transport: (any IMTransport)?
    private var pipeline: IMPipeline?
    private var readTask: _Concurrency.Task<Void, Never>?
    private var stateWatchTask: _Concurrency.Task<Void, Never>?
    private var reconnectTask: _Concurrency.Task<Void, Never>?
    private var seq: Int64 = 0
    private var currentUser: User?
    private var currentKind: TransportKind = .webSocket
    private var intentionalDisconnect = false

    private let stateContinuation: AsyncStream<ConnectionState>.Continuation
    private let eventsContinuation: AsyncStream<InboundEvent>.Continuation

    public nonisolated let state: AsyncStream<ConnectionState>
    public nonisolated let inboundEvents: AsyncStream<InboundEvent>

    public init(serverConfig: ServerConfig, keychain: KeychainStore) {
        self.serverConfig = serverConfig
        self.keychain = keychain
        var stateCont: AsyncStream<ConnectionState>.Continuation!
        state = AsyncStream { stateCont = $0 }
        stateContinuation = stateCont
        var eventsCont: AsyncStream<InboundEvent>.Continuation!
        inboundEvents = AsyncStream { eventsCont = $0 }
        eventsContinuation = eventsCont
    }

    public nonisolated func preferredTransport() -> TransportKind {
        keychain.preferredTransport
    }

    public func setPreferredTransport(_ kind: TransportKind) async {
        keychain.preferredTransport = kind
    }

    public func connect(user: User, transport kind: TransportKind) async throws {
        intentionalDisconnect = false
        currentUser = user
        currentKind = kind
        keychain.preferredTransport = kind
        await teardown(keepUser: true)
        stateContinuation.yield(.connecting)

        let endpoint = IMEndpoint(
            host: serverConfig.host,
            httpPort: serverConfig.httpPort,
            tcpPort: serverConfig.tcpPort,
            useTLS: serverConfig.useTLS,
            token: user.token
        )

        let t: any IMTransport = kind == .tcp
            ? NWTCPTransport(endpoint: endpoint)
            : NWWebSocketTransport(endpoint: endpoint)
        transport = t

        let pipe = IMPipeline()
        let isTCP = kind == .tcp
        let eventsCont = eventsContinuation
        await pipe.addLast(LoggingHandler())
        await pipe.addLast(LengthFrameInboundHandler(enabled: isTCP))
        await pipe.addLast(ProtobufDecodeHandler())
        await pipe.addLast(CmdDispatchHandler(selfUID: user.uid) { event in
            eventsCont.yield(event)
        })
        // Outbound chain is reversed at link time: encode first, then length-prefix (TCP).
        await pipe.addLast(LengthFrameOutboundHandler(enabled: isTCP))
        await pipe.addLast(ProtobufEncodeHandler())
        await pipe.setTransportWriter { [weak t] data in
            guard let t else { throw DomainError.invalidState("no transport") }
            try await t.send(data)
        }
        pipeline = pipe

        // Single consumer of transport.state: wait for ready, then keep watching.
        let readyBox = ReadyBox()
        stateWatchTask = _Concurrency.Task { [weak self] in
            for await s in t.state {
                guard let self else { break }
                switch s {
                case .ready:
                    await readyBox.markReady()
                    await self.onTransportReady()
                case .failed(let reason):
                    await readyBox.markFailed(reason)
                    await self.onTransportFailed()
                case .cancelled:
                    await readyBox.markFailed("cancelled")
                    await self.onTransportFailed()
                default:
                    break
                }
            }
        }

        readTask = _Concurrency.Task { [weak self] in
            for await chunk in t.bytes {
                guard let self else { break }
                do {
                    try await self.pipeline?.fireChannelRead(chunk)
                } catch {
                    #if DEBUG
                    print("[IM] pipeline read error:", error)
                    #endif
                }
            }
        }

        try await t.start()
        try await readyBox.wait(timeout: 10)

        if isTCP {
            var login = WireMessage()
            login.cmd = Cmd.login.rawValue
            login.content = user.token
            try await pipe.writeOutbound(login)
        }
        // Pull offline queue after the link is up (WS auth is via query token).
        var offline = WireMessage()
        offline.cmd = Cmd.offline.rawValue
        offline.seq = nextSeq()
        try await pipe.writeOutbound(offline)
        stateContinuation.yield(.connected)
    }

    public func disconnect() async {
        intentionalDisconnect = true
        reconnectTask?.cancel()
        reconnectTask = nil
        await teardown(keepUser: false)
        stateContinuation.yield(.disconnected)
    }

    public func send(_ envelope: OutboundEnvelope) async throws {
        guard let pipeline, let user = currentUser else {
            throw DomainError.invalidState("not connected")
        }
        let wire = try mapEnvelope(envelope, user: user)
        try await pipeline.writeOutbound(wire)
    }

    // MARK: - Private

    private func onTransportReady() {
        stateContinuation.yield(.connected)
    }

    private func onTransportFailed() {
        stateContinuation.yield(.disconnected)
        if !intentionalDisconnect, let user = currentUser {
            scheduleReconnect(user: user)
        }
    }

    private func nextSeq() -> Int64 {
        seq += 1
        return seq
    }

    private func mapEnvelope(_ envelope: OutboundEnvelope, user: User) throws -> WireMessage {
        var msg = WireMessage()
        msg.from = user.uid
        msg.timestamp = Int64(Date().timeIntervalSince1970 * 1000)

        switch envelope.kind {
        case let .chat(m):
            msg.cmd = Cmd.chat.rawValue
            msg.seq = m.clientSeq == 0 ? nextSeq() : m.clientSeq
            msg.to = m.toUID
            msg.chatType = m.chatType.rawValue
            msg.msgType = m.msgType.rawValue
            msg.content = m.content
            msg.needAck = true
        case let .file(m):
            msg.cmd = Cmd.file.rawValue
            msg.seq = m.clientSeq == 0 ? nextSeq() : m.clientSeq
            msg.to = m.toUID
            msg.chatType = m.chatType.rawValue
            msg.msgType = m.msgType.rawValue
            msg.content = m.content
            msg.needAck = true
        case .offline:
            msg.cmd = Cmd.offline.rawValue
            msg.seq = nextSeq()
        case let .history(peer, before, limit):
            msg.cmd = Cmd.history.rawValue
            msg.seq = nextSeq()
            msg.to = peer
            var payload: [String: Any] = ["limit": limit]
            if let before { payload["before"] = before }
            msg.content = (try? String(data: JSONSerialization.data(withJSONObject: payload), encoding: .utf8)) ?? ""
        case let .readReceipt(to, chatType):
            msg.cmd = Cmd.readReceipt.rawValue
            msg.seq = nextSeq()
            msg.to = to
            msg.chatType = chatType.rawValue
        case .unreadCount:
            msg.cmd = Cmd.unreadCount.rawValue
            msg.seq = nextSeq()
        case .heartbeat:
            msg.cmd = Cmd.heartbeat.rawValue
            msg.seq = nextSeq()
        case let .groupCreate(name, members):
            msg.cmd = Cmd.groupCreate.rawValue
            msg.seq = nextSeq()
            // Server expects JSON {"name":"...","members":[...]}.
            var payload: [String: Any] = ["name": name]
            if !members.isEmpty { payload["members"] = members }
            msg.content = (try? String(data: JSONSerialization.data(withJSONObject: payload), encoding: .utf8)) ?? name
        case let .groupJoin(groupId):
            msg.cmd = Cmd.groupJoin.rawValue
            msg.seq = nextSeq()
            msg.to = groupId
        case let .groupLeave(groupId):
            msg.cmd = Cmd.groupLeave.rawValue
            msg.seq = nextSeq()
            msg.to = groupId
        case let .groupInfo(groupId):
            msg.cmd = Cmd.groupInfo.rawValue
            msg.seq = nextSeq()
            msg.to = groupId
        case .groupList:
            msg.cmd = Cmd.groupList.rawValue
            msg.seq = nextSeq()
        case let .groupInvite(groupId, uid):
            msg.cmd = Cmd.groupInvite.rawValue
            msg.seq = nextSeq()
            msg.to = groupId
            msg.content = uid
        case let .friendRequest(to):
            msg.cmd = Cmd.friendRequest.rawValue
            msg.seq = nextSeq()
            msg.to = to
        case let .friendResponse(to, accept):
            msg.cmd = Cmd.friendResponse.rawValue
            msg.seq = nextSeq()
            msg.to = to
            // Server expects JSON {"action":"accept"|"reject"}; bare strings default to accept.
            let payload = ["action": accept ? "accept" : "reject"]
            msg.content = (try? String(data: JSONSerialization.data(withJSONObject: payload), encoding: .utf8)) ?? ""
        case let .search(query, peer, chatType, limit):
            msg.cmd = Cmd.search.rawValue
            msg.seq = nextSeq()
            var payload: [String: Any] = ["q": query, "limit": limit]
            if let peer { payload["peer"] = peer }
            if let chatType { payload["chat_type"] = chatType.rawValue }
            msg.content = (try? String(data: JSONSerialization.data(withJSONObject: payload), encoding: .utf8)) ?? query
        }
        return msg
    }

    private func scheduleReconnect(user: User) {
        reconnectTask?.cancel()
        stateContinuation.yield(.reconnecting)
        let kind = currentKind
        reconnectTask = _Concurrency.Task {
            try? await _Concurrency.Task.sleep(nanoseconds: 2_000_000_000)
            guard !_Concurrency.Task.isCancelled, !intentionalDisconnect else { return }
            try? await connect(user: user, transport: kind)
        }
    }

    private func teardown(keepUser: Bool) async {
        readTask?.cancel()
        readTask = nil
        stateWatchTask?.cancel()
        stateWatchTask = nil
        transport?.stop()
        transport = nil
        pipeline = nil
        if !keepUser {
            currentUser = nil
        }
    }
}

/// One-shot ready/fail gate shared between connect() and the state watcher.
private actor ReadyBox {
    private var ready = false
    private var failure: String?
    private var waiters: [CheckedContinuation<Void, Error>] = []

    func markReady() {
        ready = true
        let pending = waiters
        waiters.removeAll()
        for w in pending { w.resume() }
    }

    func markFailed(_ reason: String) {
        failure = reason
        let pending = waiters
        waiters.removeAll()
        for w in pending { w.resume(throwing: DomainError.network(reason)) }
    }

    func wait(timeout: TimeInterval) async throws {
        if ready { return }
        if let failure { throw DomainError.network(failure) }
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                    _Concurrency.Task { await self.enqueue(cont) }
                }
            }
            group.addTask {
                try await _Concurrency.Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw DomainError.network("connect timeout")
            }
            try await group.next()!
            group.cancelAll()
        }
    }

    private func enqueue(_ cont: CheckedContinuation<Void, Error>) {
        if ready {
            cont.resume()
        } else if let failure {
            cont.resume(throwing: DomainError.network(failure))
        } else {
            waiters.append(cont)
        }
    }
}
