import Foundation
import Moya
import Domain

public actor FriendRepositoryImpl: FriendRepository {
    private let provider: SharedMoyaProvider
    private let connection: ConnectionRepository
    private let store: LocalStore
    private let selfUIDProvider: @Sendable () -> String?
    private var friendContinuations: [UUID: AsyncStream<[Friend]>.Continuation] = [:]
    private var requestContinuations: [UUID: AsyncStream<[FriendRequest]>.Continuation] = [:]

    public init(
        provider: SharedMoyaProvider,
        connection: ConnectionRepository,
        store: LocalStore,
        selfUIDProvider: @escaping @Sendable () -> String?
    ) {
        self.provider = provider
        self.connection = connection
        self.store = store
        self.selfUIDProvider = selfUIDProvider
    }

    public func listFriends() async throws -> [Friend] {
        do {
            return try await store.friends()
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func pendingRequests() async throws -> [FriendRequest] {
        guard let uid = selfUIDProvider() else { return [] }
        do {
            return try await store.pendingRequests(for: uid)
        } catch {
            throw DomainError.persistence(error.localizedDescription)
        }
    }

    public func sendRequest(to uid: String) async throws {
        do {
            try await connection.send(OutboundEnvelope(kind: .friendRequest(to: uid)))
        } catch {
            let _: StatusResponseDTO = try await provider.requestDecodable(.friendRequest(toUID: uid))
        }
    }

    public func respond(to uid: String, accept: Bool) async throws {
        // HTTP is synchronous and authoritative; WS alone races with an immediate refresh.
        if accept {
            let _: StatusResponseDTO = try await provider.requestDecodable(.friendAccept(fromUID: uid))
        } else {
            let _: StatusResponseDTO = try await provider.requestDecodable(.friendReject(fromUID: uid))
        }
        // Best-effort notify the peer over the realtime channel.
        try? await connection.send(OutboundEnvelope(kind: .friendResponse(to: uid, accept: accept)))
        try await refresh()
    }

    public nonisolated func observeFriends() -> AsyncStream<[Friend]> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.registerFriends(id: id, continuation: continuation)
                if let list = try? await self.listFriends() {
                    continuation.yield(list)
                }
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregisterFriends(id: id) }
            }
        }
    }

    public nonisolated func observeRequests() -> AsyncStream<[FriendRequest]> {
        AsyncStream { continuation in
            let id = UUID()
            _Concurrency.Task {
                await self.registerRequests(id: id, continuation: continuation)
                if let list = try? await self.pendingRequests() {
                    continuation.yield(list)
                }
            }
            continuation.onTermination = { _ in
                _Concurrency.Task { await self.unregisterRequests(id: id) }
            }
        }
    }

    public func refresh() async throws {
        let dto: FriendListResponseDTO = try await provider.requestDecodable(.friendList)
        let me = selfUIDProvider()
        // Server GetFriends normalizes rows so `uid` is the peer and `friend_uid` is self.
        let friends: [Friend] = dto.friends.compactMap { row in
            let peer = (row.uid?.isEmpty == false) ? row.uid! : row.friend_uid
            guard !peer.isEmpty, peer != me else { return nil }
            return Friend(uid: peer, username: peer)
        }
        try await store.saveFriends(friends)

        let toUID = me ?? ""
        let pending = (dto.pending_requests ?? []).map {
            FriendRequest(
                fromUID: $0.from_uid,
                toUID: toUID,
                status: .pending,
                createdAt: $0.created_at ?? 0,
                username: $0.username
            )
        }
        if !toUID.isEmpty {
            try await store.replacePendingRequests(pending, for: toUID)
        }

        await notifyFriends()
        await notifyRequests()
    }

    private func registerFriends(id: UUID, continuation: AsyncStream<[Friend]>.Continuation) {
        friendContinuations[id] = continuation
    }

    private func unregisterFriends(id: UUID) {
        friendContinuations[id] = nil
    }

    private func registerRequests(id: UUID, continuation: AsyncStream<[FriendRequest]>.Continuation) {
        requestContinuations[id] = continuation
    }

    private func unregisterRequests(id: UUID) {
        requestContinuations[id] = nil
    }

    private func notifyFriends() async {
        guard let list = try? await store.friends() else { return }
        for cont in friendContinuations.values { cont.yield(list) }
    }

    private func notifyRequests() async {
        guard let uid = selfUIDProvider(),
              let list = try? await store.pendingRequests(for: uid) else { return }
        for cont in requestContinuations.values { cont.yield(list) }
    }
}
