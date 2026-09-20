import Foundation

public struct LoginUseCase: Sendable {
    private let auth: AuthRepository
    private let connection: ConnectionRepository

    public init(auth: AuthRepository, connection: ConnectionRepository) {
        self.auth = auth
        self.connection = connection
    }

    public func execute(uid: String, username: String, password: String, register: Bool) async throws -> User {
        let user: User
        if register {
            user = try await auth.register(uid: uid, username: username, password: password)
        } else {
            user = try await auth.login(uid: uid, username: username, password: password)
        }
        await auth.saveSession(user)
        try await connection.connect(user: user, transport: connection.preferredTransport())
        return user
    }
}

public struct LogoutUseCase: Sendable {
    private let auth: AuthRepository
    private let connection: ConnectionRepository

    public init(auth: AuthRepository, connection: ConnectionRepository) {
        self.auth = auth
        self.connection = connection
    }

    public func execute() async {
        await connection.disconnect()
        await auth.logout()
    }
}

public struct SendTextMessageUseCase: Sendable {
    private let messages: MessageRepository

    public init(messages: MessageRepository) {
        self.messages = messages
    }

    public func execute(to: String, chatType: ChatType, text: String, from: User) async throws -> Message {
        try await messages.sendText(to: to, chatType: chatType, text: text, from: from)
    }
}

public struct SendFileMessageUseCase: Sendable {
    private let files: FileRepository
    private let messages: MessageRepository

    public init(files: FileRepository, messages: MessageRepository) {
        self.files = files
        self.messages = messages
    }

    public func execute(
        to: String,
        chatType: ChatType,
        data: Data,
        fileName: String,
        mime: String,
        from: User,
        localWidth: Int? = nil,
        localHeight: Int? = nil,
        localDuration: Int? = nil
    ) async throws -> Message {
        var meta = try await files.upload(data: data, fileName: fileName, mime: mime)
        // Prefer server dims; fall back to client-measured pixels when server returns 0.
        if (meta.width ?? 0) <= 0, let localWidth, localWidth > 0 {
            meta.width = localWidth
        }
        if (meta.height ?? 0) <= 0, let localHeight, localHeight > 0 {
            meta.height = localHeight
        }
        if (meta.duration ?? 0) <= 0, let localDuration, localDuration > 0 {
            meta.duration = localDuration
        }
        return try await messages.sendFile(to: to, chatType: chatType, meta: meta, from: from)
    }
}

public struct ObserveConversationsUseCase: Sendable {
    private let conversations: ConversationRepository

    public init(conversations: ConversationRepository) {
        self.conversations = conversations
    }

    public func execute() -> AsyncStream<[Conversation]> {
        conversations.observeConversations()
    }
}

public struct ObserveMessagesUseCase: Sendable {
    private let messages: MessageRepository

    public init(messages: MessageRepository) {
        self.messages = messages
    }

    public func execute(conversationId: String) -> AsyncStream<[Message]> {
        messages.observeMessages(conversationId: conversationId)
    }
}

public struct MarkReadUseCase: Sendable {
    private let messages: MessageRepository

    public init(messages: MessageRepository) {
        self.messages = messages
    }

    public func execute(conversationId: String, peer: String, chatType: ChatType) async throws {
        try await messages.markRead(conversationId: conversationId, peer: peer, chatType: chatType)
    }
}

public struct CreateGroupUseCase: Sendable {
    private let groups: GroupRepository

    public init(groups: GroupRepository) {
        self.groups = groups
    }

    public func execute(name: String, members: [String] = []) async throws -> Group {
        try await groups.create(name: name, members: members)
    }
}

public struct JoinGroupUseCase: Sendable {
    private let groups: GroupRepository

    public init(groups: GroupRepository) {
        self.groups = groups
    }

    public func execute(groupId: String) async throws {
        try await groups.join(groupId: groupId)
    }
}

public struct LeaveGroupUseCase: Sendable {
    private let groups: GroupRepository

    public init(groups: GroupRepository) {
        self.groups = groups
    }

    public func execute(groupId: String) async throws {
        try await groups.leave(groupId: groupId)
    }
}

public struct FriendUseCases: Sendable {
    private let friends: FriendRepository

    public init(friends: FriendRepository) {
        self.friends = friends
    }

    public func refresh() async throws { try await friends.refresh() }
    public func sendRequest(to uid: String) async throws { try await friends.sendRequest(to: uid) }
    public func respond(to uid: String, accept: Bool) async throws {
        try await friends.respond(to: uid, accept: accept)
    }
    public func observeFriends() -> AsyncStream<[Friend]> { friends.observeFriends() }
    public func observeRequests() -> AsyncStream<[FriendRequest]> { friends.observeRequests() }
}

public struct SearchMessagesUseCase: Sendable {
    private let search: SearchRepository

    public init(search: SearchRepository) {
        self.search = search
    }

    public func execute(query: String, peer: String? = nil, chatType: ChatType? = nil) async throws -> [SearchHit] {
        try await search.search(query: query, peer: peer, chatType: chatType, limit: 50)
    }
}

public struct SwitchTransportUseCase: Sendable {
    private let auth: AuthRepository
    private let connection: ConnectionRepository

    public init(auth: AuthRepository, connection: ConnectionRepository) {
        self.auth = auth
        self.connection = connection
    }

    public func execute(_ kind: TransportKind) async throws {
        await connection.setPreferredTransport(kind)
        guard let user = auth.currentUser() else { return }
        await connection.disconnect()
        try await connection.connect(user: user, transport: kind)
    }
}
