import Foundation
import Moya
import Domain

public actor GroupRepositoryImpl: GroupRepository {
    private let provider: SharedMoyaProvider
    private let connection: ConnectionRepository
    private let store: LocalStore
    private var continuations: [UUID: AsyncStream<[Group]>.Continuation] = [:]

    public init(provider: SharedMoyaProvider, connection: ConnectionRepository, store: LocalStore) {
        self.provider = provider
        self.connection = connection
        self.store = store
    }

    public func create(name: String, members: [String]) async throws -> Group {
        // Prefer HTTP: returns the created group synchronously. Do not also send
        // CmdGroupCreate over WS — that would create a second group.
        let dto: GroupDTO = try await provider.requestDecodable(.groupCreate(name: name, members: members))
        let group = dto.toDomain()
        try? await store.saveGroup(group)
        // Seed chat list row with the real group name.
        var conv = Conversation(
            id: ConversationID.group(group.groupId),
            chatType: .group,
            title: group.name.isEmpty ? group.groupId : group.name,
            peerOrGroupId: group.groupId
        )
        if let existing = try? await store.conversation(id: conv.id) {
            conv.lastMessagePreview = existing.lastMessagePreview
            conv.lastMessageAt = existing.lastMessageAt
            conv.unreadCount = existing.unreadCount
            conv.title = group.name.isEmpty ? existing.title : group.name
        }
        try? await store.upsertConversation(conv)
        await notify()
        return group
    }

    public func join(groupId: String) async throws {
        do {
            try await connection.send(OutboundEnvelope(kind: .groupJoin(groupId: groupId)))
        } catch {
            let _: StatusResponseDTO = try await provider.requestDecodable(.groupJoin(groupId: groupId))
        }
        // Refresh membership so local group name cache is available for chat titles.
        _ = try? await list()
        await notify()
    }

    public func leave(groupId: String) async throws {
        do {
            try await connection.send(OutboundEnvelope(kind: .groupLeave(groupId: groupId)))
            return
        } catch {
            let _: StatusResponseDTO = try await provider.requestDecodable(.groupLeave(groupId: groupId))
        }
        await notify()
    }

    public func invite(groupId: String, uid: String) async throws {
        try await connection.send(OutboundEnvelope(kind: .groupInvite(groupId: groupId, uid: uid)))
    }

    public func list() async throws -> [Group] {
        do {
            try await connection.send(OutboundEnvelope(kind: .groupList))
        } catch {
            // HTTP fallback below
        }
        let dto: GroupListResponseDTO = try await provider.requestDecodable(.groupList)
        let groups = dto.groups.map { $0.toDomain() }
        for g in groups {
            try? await store.saveGroup(g)
        }
        await notify()
        return groups
    }

    public func members(groupId: String) async throws -> [GroupMember] {
        let dto: GroupMembersResponseDTO = try await provider.requestDecodable(.groupMembers(groupId: groupId))
        return dto.members.map { GroupMember(groupId: dto.group_id, uid: $0) }
    }

    public nonisolated func observeGroups() -> AsyncStream<[Group]> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.register(id: id, continuation: continuation)
                if let list = try? await self.store.groups() {
                    continuation.yield(list)
                }
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregister(id: id) }
            }
        }
    }

    private func register(id: UUID, continuation: AsyncStream<[Group]>.Continuation) {
        continuations[id] = continuation
    }

    private func unregister(id: UUID) {
        continuations[id] = nil
    }

    private func notify() async {
        guard let list = try? await store.groups() else { return }
        for cont in continuations.values {
            cont.yield(list)
        }
    }
}
