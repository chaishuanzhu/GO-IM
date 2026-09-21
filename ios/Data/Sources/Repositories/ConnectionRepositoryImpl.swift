import Foundation
import Network
import Domain

public actor ConnectionRepositoryImpl: ConnectionRepository {
    private let serverConfig: ServerConfig
    private let keychain: KeychainStore

    private var transport: (any IMTransport)?
    private var pipeline: IMPipeline?
    private var readTask: _Concurrency.Task<Void, Never>?
    private var stateWatchTask: _Concurrency.Task<Void, Never>?
    private var reconnectTask: _Concurrency.Task<Void, Never>?
    private var heartbeatTask: _Concurrency.Task<Void, Never>?
    private var pathMonitor: NWPathMonitor?
    private var pathMonitorQueue: DispatchQueue?

    private var seq: Int64 = 0
    private var currentUser: User?
    private var currentKind: TransportKind = .webSocket
    private var intentionalDisconnect = false
    private var authExpired = false
    private var linkEstablished = false
    private var reconnectAttempt = 0
    private var lastPathSatisfied: Bool?
    private var missedHeartbeats = 0
    private var cachedState: ConnectionState = .disconnected

    private let loginBox = LoginBox()
    private let heartbeatBox = HeartbeatBox()

    private var stateObservers: [UUID: AsyncStream<ConnectionState>.Continuation] = [:]
    private let eventsContinuation: AsyncStream<InboundEvent>.Continuation

    public nonisolated let inboundEvents: AsyncStream<InboundEvent>

    private static let heartbeatIntervalNs: UInt64 = 25_000_000_000
    private static let maxMissedHeartbeats = 3

    public init(serverConfig: ServerConfig, keychain: KeychainStore) {
        self.serverConfig = serverConfig
        self.keychain = keychain
        var eventsCont: AsyncStream<InboundEvent>.Continuation!
        inboundEvents = AsyncStream { eventsCont = $0 }
        eventsContinuation = eventsCont
        _Concurrency.Task { await self.startPathMonitor() }
    }

    public nonisolated func observeState() -> AsyncStream<ConnectionState> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.registerStateObserver(id: id, continuation: continuation)
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregisterStateObserver(id: id) }
            }
        }
    }

    private func registerStateObserver(
        id: UUID,
        continuation: AsyncStream<ConnectionState>.Continuation
    ) {
        stateObservers[id] = continuation
        continuation.yield(cachedState)
    }

    private func unregisterStateObserver(id: UUID) {
        stateObservers.removeValue(forKey: id)
    }

    public nonisolated func preferredTransport() -> TransportKind {
        keychain.preferredTransport
    }

    public func setPreferredTransport(_ kind: TransportKind) async {
        keychain.preferredTransport = kind
    }

    public func currentConnectionState() async -> ConnectionState {
        cachedState
    }

    public func ensureConnected() async {
        guard ReconnectPolicy.mayReconnect(
            intentionalDisconnect: intentionalDisconnect,
            authExpired: authExpired,
            hasUser: currentUser != nil
        ) else { return }

        switch cachedState {
        case .connected, .connecting, .reconnecting:
            return
        case .disconnected, .authExpired:
            break
        }

        guard let user = currentUser else { return }
        do {
            try await connect(user: user, transport: currentKind)
        } catch {
            if isAuthError(error) {
                await handleAuthExpired()
            } else {
                scheduleReconnect(user: user, reason: .connectFailed)
            }
        }
    }

    public func connect(user: User, transport kind: TransportKind) async throws {
        try await connectInternal(user: user, transport: kind, cancelReconnect: true)
    }

    private func connectInternal(user: User, transport kind: TransportKind, cancelReconnect: Bool) async throws {
        intentionalDisconnect = false
        authExpired = false
        linkEstablished = false
        currentUser = user
        currentKind = kind
        keychain.preferredTransport = kind
        if cancelReconnect {
            reconnectTask?.cancel()
            reconnectTask = nil
        }
        await stopHeartbeat()
        await teardown(keepUser: true)
        yieldState(.connecting)
        await loginBox.reset()
        await heartbeatBox.reset()

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
        await pipe.addLast(CmdDispatchHandler(
            selfUID: user.uid,
            onEvent: { event in eventsCont.yield(event) },
            onLoginResp: { [loginBox] in
                _Concurrency.Task { await loginBox.markReady() }
            },
            onHeartbeat: { [heartbeatBox] in
                _Concurrency.Task { await heartbeatBox.markPong() }
            }
        ))
        await pipe.addLast(LengthFrameOutboundHandler(enabled: isTCP))
        await pipe.addLast(ProtobufEncodeHandler())
        await pipe.setTransportWriter { [weak t] data in
            guard let t else { throw DomainError.invalidState("no transport") }
            try await t.send(data)
        }
        pipeline = pipe

        let readyBox = ReadyBox()
        stateWatchTask = _Concurrency.Task { [weak self] in
            for await s in t.state {
                guard let self else { break }
                switch s {
                case .ready:
                    await readyBox.markReady()
                case .failed(let reason):
                    await readyBox.markFailed(reason)
                    await self.loginBox.markFailed(reason)
                    await self.onTransportFailed(reason: reason)
                case .cancelled:
                    await readyBox.markFailed("cancelled")
                    await self.loginBox.markFailed("cancelled")
                    await self.onTransportFailed(reason: "cancelled")
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

        do {
            try await t.start()
            try await readyBox.wait(timeout: 10)

            if isTCP {
                var login = WireMessage()
                login.cmd = Cmd.login.rawValue
                login.content = user.token
                try await pipe.writeOutbound(login)
                // Transport is already ready; missing LoginResp means the server rejected the token.
                do {
                    try await loginBox.wait(timeout: 5)
                } catch {
                    throw DomainError.notAuthenticated
                }
            }

            var offline = WireMessage()
            offline.cmd = Cmd.offline.rawValue
            offline.seq = nextSeq()
            try await pipe.writeOutbound(offline)

            linkEstablished = true
            reconnectAttempt = 0
            startHeartbeatLoop()
            yieldState(.connected)
        } catch {
            await stopHeartbeat()
            linkEstablished = false
            if isAuthError(error) || ReconnectPolicy.isAuthFailure(String(describing: error)) {
                await handleAuthExpired()
                throw DomainError.notAuthenticated
            }
            if let user = currentUser,
               ReconnectPolicy.mayReconnect(
                intentionalDisconnect: intentionalDisconnect,
                authExpired: authExpired,
                hasUser: true
               ) {
                scheduleReconnect(user: user, reason: .connectFailed)
            }
            throw error
        }
    }

    public func disconnect() async {
        intentionalDisconnect = true
        authExpired = false
        reconnectAttempt = 0
        reconnectTask?.cancel()
        reconnectTask = nil
        await stopHeartbeat()
        await teardown(keepUser: false)
        yieldState(.disconnected)
    }

    public func send(_ envelope: OutboundEnvelope) async throws {
        guard let pipeline, let user = currentUser, linkEstablished else {
            throw DomainError.invalidState("not connected")
        }
        let wire = try mapEnvelope(envelope, user: user)
        try await pipeline.writeOutbound(wire)
    }

    // MARK: - Private

    private func yieldState(_ state: ConnectionState) {
        cachedState = state
        for (_, cont) in stateObservers {
            cont.yield(state)
        }
    }

    private func isAuthError(_ error: Error) -> Bool {
        if let domain = error as? DomainError, domain == .notAuthenticated {
            return true
        }
        return ReconnectPolicy.isAuthFailure(error.localizedDescription)
    }

    private func handleAuthExpired() async {
        authExpired = true
        intentionalDisconnect = true
        reconnectTask?.cancel()
        reconnectTask = nil
        await stopHeartbeat()
        await teardown(keepUser: false)
        yieldState(.authExpired)
    }

    private func onTransportFailed(reason: String) async {
        await stopHeartbeat()
        let wasEstablished = linkEstablished
        linkEstablished = false

        if intentionalDisconnect || authExpired {
            return
        }

        // Auth rejection during WS handshake (never became established).
        if !wasEstablished, ReconnectPolicy.isAuthFailure(reason) {
            await handleAuthExpired()
            return
        }

        // Failures while connect() is still running are handled by connect()'s catch /
        // reconnectTask — avoid double-scheduling.
        if !wasEstablished {
            return
        }

        yieldState(.disconnected)
        if let user = currentUser {
            scheduleReconnect(user: user, reason: .transportFailed)
        }
    }

    private enum ReconnectTrigger {
        case transportFailed
        case pathRestored
        case connectFailed
    }

    private func scheduleReconnect(user: User, reason: ReconnectTrigger) {
        guard ReconnectPolicy.mayReconnect(
            intentionalDisconnect: intentionalDisconnect,
            authExpired: authExpired,
            hasUser: true
        ) else { return }

        // Already looping — leave it running (path restore while reconnecting is a no-op).
        if let existing = reconnectTask, !existing.isCancelled {
            #if DEBUG
            print("[IM] scheduleReconnect skipped (already running) reason=\(reason)")
            #endif
            return
        }

        yieldState(.reconnecting)
        let kind = currentKind
        #if DEBUG
        print("[IM] scheduleReconnect start reason=\(reason) attempt=\(reconnectAttempt)")
        #endif
        reconnectTask = _Concurrency.Task { [weak self] in
            guard let self else { return }
            defer {
                _Concurrency.Task { await self.clearReconnectTask() }
            }
            while await self.mayReconnectNow() {
                guard !_Concurrency.Task.isCancelled else { return }
                let attempt = await self.nextReconnectAttempt()
                let delay = ReconnectPolicy.delayNanoseconds(attempt: attempt)
                #if DEBUG
                print("[IM] reconnect sleep attempt=\(attempt) delayMs=\(delay / 1_000_000)")
                #endif
                try? await _Concurrency.Task.sleep(nanoseconds: delay)
                guard !_Concurrency.Task.isCancelled else { return }
                guard await self.mayReconnectNow() else { return }
                do {
                    try await self.connectInternal(user: user, transport: kind, cancelReconnect: false)
                    return
                } catch {
                    if await self.isAuthError(error) {
                        await self.handleAuthExpired()
                        return
                    }
                    // Loop continues with higher attempt.
                }
            }
        }
    }

    private func clearReconnectTask() {
        reconnectTask = nil
    }

    private func nextReconnectAttempt() -> Int {
        let attempt = reconnectAttempt
        reconnectAttempt += 1
        return attempt
    }

    private func mayReconnectNow() -> Bool {
        ReconnectPolicy.mayReconnect(
            intentionalDisconnect: intentionalDisconnect,
            authExpired: authExpired,
            hasUser: currentUser != nil
        )
    }

    private func startHeartbeatLoop() {
        heartbeatTask?.cancel()
        missedHeartbeats = 0
        // Grace the first interval so we don't count a miss before any ping was sent.
        _Concurrency.Task { await self.heartbeatBox.markPong() }
        heartbeatTask = _Concurrency.Task { [weak self] in
            while let self, !_Concurrency.Task.isCancelled {
                try? await _Concurrency.Task.sleep(nanoseconds: Self.heartbeatIntervalNs)
                guard !_Concurrency.Task.isCancelled else { break }
                let stillUp = await self.tickHeartbeat()
                if !stillUp { break }
            }
        }
    }

    private func tickHeartbeat() async -> Bool {
        guard linkEstablished, !intentionalDisconnect, !authExpired, currentUser != nil else {
            return false
        }
        // Missed pong from the previous interval?
        let gotPong = await heartbeatBox.consumePong()
        if !gotPong {
            missedHeartbeats += 1
            if missedHeartbeats >= Self.maxMissedHeartbeats {
                await tearDownStaleLink()
                return false
            }
        } else {
            missedHeartbeats = 0
        }
        do {
            try await send(OutboundEnvelope(kind: .heartbeat))
        } catch {
            await tearDownStaleLink()
            return false
        }
        return true
    }

    private func tearDownStaleLink() async {
        guard linkEstablished else { return }
        linkEstablished = false
        await stopHeartbeat()
        transport?.stop()
        yieldState(.disconnected)
        if let user = currentUser {
            scheduleReconnect(user: user, reason: .transportFailed)
        }
    }

    private func stopHeartbeat() async {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        missedHeartbeats = 0
        await heartbeatBox.reset()
    }

    private func startPathMonitor() {
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "goim.path")
        pathMonitor = monitor
        pathMonitorQueue = queue
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            _Concurrency.Task { await self?.onPathUpdate(satisfied: satisfied) }
        }
        monitor.start(queue: queue)
    }

    private func onPathUpdate(satisfied: Bool) async {
        let previous = lastPathSatisfied
        lastPathSatisfied = satisfied
        guard satisfied else { return }
        // Only act on unsatisfied → satisfied transitions (skip initial probe).
        guard previous == false else { return }
        guard ReconnectPolicy.mayReconnect(
            intentionalDisconnect: intentionalDisconnect,
            authExpired: authExpired,
            hasUser: currentUser != nil
        ) else { return }
        if linkEstablished, cachedState == .connected { return }
        guard let user = currentUser else { return }
        if cachedState == .reconnecting || cachedState == .connecting { return }
        scheduleReconnect(user: user, reason: .pathRestored)
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
        case let .history(peer, before, limit, chatType):
            msg.cmd = Cmd.history.rawValue
            msg.seq = Int64(limit)
            msg.to = peer
            msg.chatType = chatType.rawValue
            msg.timestamp = before ?? Int64(Date().timeIntervalSince1970 * 1000)
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

    private func teardown(keepUser: Bool) async {
        readTask?.cancel()
        readTask = nil
        stateWatchTask?.cancel()
        stateWatchTask = nil
        transport?.stop()
        transport = nil
        pipeline = nil
        linkEstablished = false
        if !keepUser {
            currentUser = nil
        }
    }
}

// MARK: - Gates

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

private actor LoginBox {
    private var ready = false
    private var failure: String?
    private var waiters: [CheckedContinuation<Void, Error>] = []

    func reset() {
        ready = false
        failure = nil
        let pending = waiters
        waiters.removeAll()
        for w in pending { w.resume(throwing: DomainError.cancelled) }
    }

    func markReady() {
        ready = true
        let pending = waiters
        waiters.removeAll()
        for w in pending { w.resume() }
    }

    func markFailed(_ reason: String) {
        if ready { return }
        failure = reason
        let pending = waiters
        waiters.removeAll()
        let err: DomainError = ReconnectPolicy.isAuthFailure(reason)
            ? .notAuthenticated
            : .network(reason)
        for w in pending { w.resume(throwing: err) }
    }

    func wait(timeout: TimeInterval) async throws {
        if ready { return }
        if let failure {
            throw ReconnectPolicy.isAuthFailure(failure)
                ? DomainError.notAuthenticated
                : DomainError.network(failure)
        }
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                    _Concurrency.Task { await self.enqueue(cont) }
                }
            }
            group.addTask {
                try await _Concurrency.Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                throw DomainError.notAuthenticated
            }
            try await group.next()!
            group.cancelAll()
        }
    }

    private func enqueue(_ cont: CheckedContinuation<Void, Error>) {
        if ready {
            cont.resume()
        } else if let failure {
            cont.resume(throwing: ReconnectPolicy.isAuthFailure(failure)
                ? DomainError.notAuthenticated
                : DomainError.network(failure))
        } else {
            waiters.append(cont)
        }
    }
}

private actor HeartbeatBox {
    private var pong = false

    func reset() {
        pong = false
    }

    func markPong() {
        pong = true
    }

    /// Returns whether a pong arrived since the last consume, then clears the flag.
    func consumePong() -> Bool {
        let had = pong
        pong = false
        return had
    }
}
