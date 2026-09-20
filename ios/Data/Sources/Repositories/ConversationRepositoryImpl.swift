import Foundation
import Domain

public actor ConversationRepositoryImpl: ConversationRepository {
    private let store: LocalStore
    private var continuations: [UUID: AsyncStream<[Conversation]>.Continuation] = [:]
    private var activeId: String?

    public init(store: LocalStore) {
        self.store = store
    }

    public nonisolated func observeConversations() -> AsyncStream<[Conversation]> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.register(id: id, continuation: continuation)
                if let list = try? await self.conversations() {
                    continuation.yield(list)
                }
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregister(id: id) }
            }
        }
    }

    public func conversations() async throws -> [Conversation] {
        do {
            return try await store.allConversations()
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func upsertConversation(from message: Message, title: String?, incrementUnread: Bool) async throws {
        let existing = (try? await store.allConversations())?.first(where: { $0.id == message.conversationId })
        let peer = message.chatType == .group
            ? message.toUID
            : (message.isOutgoing ? message.toUID : message.fromUID)
        var conv = existing ?? Conversation(
            id: message.conversationId,
            chatType: message.chatType,
            title: title ?? peer,
            peerOrGroupId: peer
        )
        if let title, !title.isEmpty {
            conv.title = title
        } else if message.chatType == .group {
            if let name = try? await store.group(id: peer)?.name, !name.isEmpty {
                conv.title = name
            } else if existing == nil {
                conv.title = peer
            }
        } else if existing == nil {
            conv.title = peer
        }
        conv.lastMessagePreview = message.listPreview
        conv.lastMessageAt = message.timestampMs
        if incrementUnread, !message.isOutgoing {
            conv.unreadCount = (existing?.unreadCount ?? 0) + 1
        } else if existing != nil {
            conv.unreadCount = existing!.unreadCount
        }
        do {
            try await store.upsertConversation(conv)
            await notify()
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func setUnread(conversationId: String, count: Int) async throws {
        do {
            try await store.setUnread(conversationId: conversationId, count: count)
            await notify()
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func deleteConversation(id: String) async throws {
        do {
            try await store.deleteConversation(id: id)
            await notify()
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func syncGroupTitles() async throws {
        do {
            let groups = try await store.groups()
            var changed = false
            for g in groups where !g.name.isEmpty {
                let cid = ConversationID.group(g.groupId)
                guard var conv = try await store.conversation(id: cid) else { continue }
                if conv.title != g.name {
                    conv.title = g.name
                    try await store.upsertConversation(conv)
                    changed = true
                }
            }
            if changed { await notify() }
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func setActiveConversationId(_ id: String?) async {
        activeId = id
    }

    public func activeConversationId() async -> String? {
        activeId
    }

    private func register(id: UUID, continuation: AsyncStream<[Conversation]>.Continuation) {
        continuations[id] = continuation
    }

    private func unregister(id: UUID) {
        continuations[id] = nil
    }

    private func notify() async {
        guard let list = try? await store.allConversations() else { return }
        for cont in continuations.values {
            cont.yield(list)
        }
    }
}
