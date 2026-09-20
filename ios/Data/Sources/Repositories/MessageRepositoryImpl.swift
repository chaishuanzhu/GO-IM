import Foundation
import Domain

public actor MessageRepositoryImpl: MessageRepository {
    private let store: LocalStore
    private let connection: ConnectionRepository
    private var messageContinuations: [String: [UUID: AsyncStream<[Message]>.Continuation]] = [:]
    private var ackTracker: OutboundAckTracker?
    private var historyContinuation: CheckedContinuation<Int, Never>?
    private var historyTimeoutTask: Task<Void, Never>?
    private var historyInFlightCount = 0

    public init(store: LocalStore, connection: ConnectionRepository) {
        self.store = store
        self.connection = connection
    }

    public nonisolated func observeMessages(conversationId: String) -> AsyncStream<[Message]> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.registerObserver(conversationId: conversationId, id: id, continuation: continuation)
                if let msgs = try? await self.messages(conversationId: conversationId, before: nil, limit: 100) {
                    continuation.yield(msgs)
                }
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregisterObserver(conversationId: conversationId, id: id) }
            }
        }
    }

    public func messages(conversationId: String, before: Int64?, limit: Int) async throws -> [Message] {
        do {
            return try await store.messages(conversationId: conversationId, before: before, limit: limit)
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func upsert(_ message: Message) async throws {
        do {
            try await store.upsertMessage(message)
            await notify(conversationId: message.conversationId)
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func markStatus(clientSeq: Int64, status: MessageStatus, serverMsgId: Int64?) async throws {
        if status == .sent || status == .failed {
            await ackTracker?.acknowledge(seq: clientSeq)
        }
        do {
            try await store.markStatus(clientSeq: clientSeq, status: status, serverMsgId: serverMsgId)
            for conversationId in messageContinuations.keys {
                await notify(conversationId: conversationId)
            }
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func sendText(to: String, chatType: ChatType, text: String, from: User) async throws -> Message {
        let conversationId = chatType == .group
            ? ConversationID.group(to)
            : ConversationID.dm(uidA: from.uid, uidB: to)
        let seq = Int64(Date().timeIntervalSince1970 * 1000) % 1_000_000_000_000
        var message = Message(
            clientSeq: seq,
            conversationId: conversationId,
            fromUID: from.uid,
            toUID: to,
            chatType: chatType,
            msgType: .text,
            content: text,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            status: .sending,
            isOutgoing: true
        )
        try await upsert(message)
        do {
            try await connection.send(OutboundEnvelope(kind: .chat(message)))
            await trackAck(message)
        } catch {
            message.status = .failed
            try await upsert(message)
            throw error
        }
        return message
    }

    public func sendFile(to: String, chatType: ChatType, meta: FileMeta, from: User) async throws -> Message {
        let message = try await enqueueOutgoingFile(to: to, chatType: chatType, meta: meta, from: from)
        return try await deliverOutgoingFile(message, meta: meta)
    }

    public func enqueueOutgoingFile(
        to: String,
        chatType: ChatType,
        meta: FileMeta,
        from: User
    ) async throws -> Message {
        let conversationId = chatType == .group
            ? ConversationID.group(to)
            : ConversationID.dm(uidA: from.uid, uidB: to)
        let content = try Self.encodeFileContent(meta)
        let msgType = MsgType.from(mime: meta.mime)
        let seq = Int64(Date().timeIntervalSince1970 * 1000) % 1_000_000_000_000
        let message = Message(
            clientSeq: seq,
            conversationId: conversationId,
            fromUID: from.uid,
            toUID: to,
            chatType: chatType,
            msgType: msgType,
            content: content,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            status: .sending,
            isOutgoing: true
        )
        try await upsert(message)
        return message
    }

    public func deliverOutgoingFile(_ message: Message, meta: FileMeta) async throws -> Message {
        var pending = message
        pending.content = try Self.encodeFileContent(meta)
        pending.msgType = MsgType.from(mime: meta.mime)
        pending.status = .sending
        do {
            try await connection.send(OutboundEnvelope(kind: .file(pending)))
            // Persist final remote meta while waiting for CmdAck (status stays sending).
            try await upsert(pending)
            await trackAck(pending)
            return pending
        } catch {
            var failed = message
            failed.status = .failed
            try await upsert(failed)
            throw error
        }
    }

    public func retry(_ message: Message) async throws {
        guard message.isOutgoing else {
            throw DomainError.invalidState("only outgoing messages can be retried")
        }
        var pending = message
        pending.status = .sending
        try await upsert(pending)
        do {
            try await sendWire(pending)
            await trackAck(pending)
        } catch {
            pending.status = .failed
            try await upsert(pending)
            throw error
        }
    }

    private static func encodeFileContent(_ meta: FileMeta) throws -> String {
        var payload: [String: Any] = [
            "file_id": meta.fileId,
            "name": meta.name,
            "size": meta.size,
            "mime": meta.mime,
        ]
        if let width = meta.width, width > 0 { payload["width"] = width }
        if let height = meta.height, height > 0 { payload["height"] = height }
        if let tw = meta.thumbWidth, tw > 0 { payload["thumb_width"] = tw }
        if let th = meta.thumbHeight, th > 0 { payload["thumb_height"] = th }
        if let duration = meta.duration, duration > 0 { payload["duration"] = duration }
        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload),
              let content = String(data: jsonData, encoding: .utf8) else {
            throw DomainError.invalidState("file meta json encode failed")
        }
        return content
    }

    public func loadHistory(
        conversationId: String,
        peer: String,
        before: Int64?,
        limit: Int,
        chatType: ChatType
    ) async throws -> Int {
        _ = conversationId
        // Cancel any prior in-flight history wait (pairs its inFlight counter).
        finishHistoryWait(delivered: 0)

        historyInFlightCount += 1
        return await withCheckedContinuation { (cont: CheckedContinuation<Int, Never>) in
            historyContinuation = cont
            historyTimeoutTask = Task {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                await self.finishHistoryWait(delivered: 0)
            }
            Task {
                do {
                    try await self.connection.send(
                        OutboundEnvelope(kind: .history(
                            peer: peer,
                            before: before,
                            limit: limit,
                            chatType: chatType
                        ))
                    )
                } catch {
                    await self.finishHistoryWait(delivered: 0)
                }
            }
        }
    }

    public func completeHistory(delivered: Int) async {
        await finishHistoryWait(delivered: delivered)
    }

    public func isHistoryInFlight() async -> Bool {
        historyInFlightCount > 0
    }

    private func finishHistoryWait(delivered: Int) {
        historyTimeoutTask?.cancel()
        historyTimeoutTask = nil
        guard let cont = historyContinuation else { return }
        historyContinuation = nil
        if historyInFlightCount > 0 {
            historyInFlightCount -= 1
        }
        cont.resume(returning: delivered)
    }

    public func syncOffline() async throws {
        try await connection.send(OutboundEnvelope(kind: .offline))
    }

    public func markRead(conversationId: String, peer: String, chatType: ChatType) async throws {
        _ = conversationId
        try await connection.send(OutboundEnvelope(kind: .readReceipt(to: peer, chatType: chatType)))
    }

    // MARK: - ACK tracking

    private func trackAck(_ message: Message) async {
        let tracker = makeAckTrackerIfNeeded()
        await tracker.register(message)
    }

    private func makeAckTrackerIfNeeded() -> OutboundAckTracker {
        if let ackTracker { return ackTracker }
        let tracker = OutboundAckTracker(
            onRetry: { [weak self] message in
                guard let self else { return false }
                do {
                    try await self.sendWire(message)
                    return true
                } catch {
                    return false
                }
            },
            onFail: { [weak self] seq in
                guard let self else { return }
                try? await self.markStatus(clientSeq: seq, status: .failed, serverMsgId: nil)
            }
        )
        ackTracker = tracker
        return tracker
    }

    private func sendWire(_ message: Message) async throws {
        switch message.msgType {
        case .text:
            try await connection.send(OutboundEnvelope(kind: .chat(message)))
        case .image, .voice, .video, .file:
            try await connection.send(OutboundEnvelope(kind: .file(message)))
        }
    }

    // MARK: - Observers

    private func registerObserver(
        conversationId: String,
        id: UUID,
        continuation: AsyncStream<[Message]>.Continuation
    ) {
        var map = messageContinuations[conversationId] ?? [:]
        map[id] = continuation
        messageContinuations[conversationId] = map
    }

    private func unregisterObserver(conversationId: String, id: UUID) {
        messageContinuations[conversationId]?[id] = nil
        if messageContinuations[conversationId]?.isEmpty == true {
            messageContinuations[conversationId] = nil
        }
    }

    private func notify(conversationId: String) async {
        guard let msgs = try? await store.messages(conversationId: conversationId, before: nil, limit: 200) else { return }
        guard let conts = messageContinuations[conversationId]?.values else { return }
        for cont in conts {
            cont.yield(msgs)
        }
    }
}
