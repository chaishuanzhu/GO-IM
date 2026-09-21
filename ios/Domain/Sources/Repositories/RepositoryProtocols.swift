import Foundation

public enum DomainError: Error, Sendable, Equatable, LocalizedError {
    case notAuthenticated
    case network(String)
    case server(Int, String)
    case persistence(String)
    case invalidState(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .notAuthenticated: return "未登录"
        case let .network(msg): return msg
        case let .server(code, body): return "服务器错误 (\(code)): \(body)"
        case let .persistence(msg): return "本地存储错误: \(msg)"
        case let .invalidState(msg): return msg
        case .cancelled: return "已取消"
        }
    }
}

public protocol AuthRepository: Sendable {
    func login(uid: String, username: String, password: String) async throws -> User
    func register(uid: String, username: String, password: String) async throws -> User
    func currentUser() -> User?
    func logout() async
    func saveSession(_ user: User) async
}

public protocol ConnectionRepository: Sendable {
    var state: AsyncStream<ConnectionState> { get }
    var inboundEvents: AsyncStream<InboundEvent> { get }
    func connect(user: User, transport: TransportKind) async throws
    func disconnect() async
    func send(_ envelope: OutboundEnvelope) async throws
    func preferredTransport() -> TransportKind
    func setPreferredTransport(_ kind: TransportKind) async
}

public enum InboundEvent: Sendable {
    case message(Message)
    case ack(seq: Int64, msgId: Int64)
    case kick
    case friendRequest(FriendRequest)
    case friendResponse(FriendRequest)
    case groupUpdated(Group)
    case searchHits([SearchHit], finished: Bool)
    case unread([String: Int])
    /// CmdHistory completion frame; `delivered` is the page size returned by the server.
    case historyFinished(delivered: Int)
}

public struct OutboundEnvelope: Sendable {
    public enum Kind: Sendable {
        case chat(Message)
        case file(Message)
        case offline
        case history(peer: String, before: Int64?, limit: Int, chatType: ChatType)
        case readReceipt(to: String, chatType: ChatType)
        case unreadCount
        case heartbeat
        case groupCreate(name: String, members: [String])
        case groupJoin(groupId: String)
        case groupLeave(groupId: String)
        case groupInfo(groupId: String)
        case groupList
        case groupInvite(groupId: String, uid: String)
        case friendRequest(to: String)
        case friendResponse(to: String, accept: Bool)
        case search(query: String, peer: String?, chatType: ChatType?, limit: Int)
    }

    public let kind: Kind
    public init(kind: Kind) { self.kind = kind }
}

public protocol MessageRepository: Sendable {
    func observeMessages(conversationId: String) -> AsyncStream<[Message]>
    func messages(conversationId: String, before: Int64?, limit: Int) async throws -> [Message]
    func upsert(_ message: Message) async throws
    func markStatus(clientSeq: Int64, status: MessageStatus, serverMsgId: Int64?) async throws
    func sendText(to: String, chatType: ChatType, text: String, from: User) async throws -> Message
    func sendFile(to: String, chatType: ChatType, meta: FileMeta, from: User) async throws -> Message
    /// Insert an outgoing file/image bubble immediately (`sending`) without uploading/wiring yet.
    func enqueueOutgoingFile(to: String, chatType: ChatType, meta: FileMeta, from: User) async throws -> Message
    /// Replace placeholder meta, deliver over the wire, and mark sent/failed.
    func deliverOutgoingFile(_ message: Message, meta: FileMeta) async throws -> Message
    /// Resend a locally failed outgoing message (same clientSeq / content).
    func retry(_ message: Message) async throws
    /// Request a page of history; returns server `delivered` count when the finish frame arrives.
    func loadHistory(
        conversationId: String,
        peer: String,
        before: Int64?,
        limit: Int,
        chatType: ChatType
    ) async throws -> Int
    /// Called when the gateway emits the CmdHistory completion signal.
    func completeHistory(delivered: Int) async
    /// True while a CmdHistory request is in flight (history rows must not bump unread).
    func isHistoryInFlight() async -> Bool
    func syncOffline() async throws
    func markRead(conversationId: String, peer: String, chatType: ChatType) async throws
    /// Send a sticker by pack reference (CmdFile + msg_type=6); no upload.
    func sendSticker(to: String, chatType: ChatType, sticker: StickerRef, from: User) async throws -> Message
}

public protocol StickerRepository: Sendable {
    /// Installed / catalog packs available in the sticker panel.
    func installedPacks() async -> [StickerPack]
    func stickers(in packId: String) async -> [StickerItem]
    /// Resolve image bytes for display (local pack first, then url / CDN fallback).
    func imageData(for ref: StickerRef) async -> Data?
    func localFileURL(packId: String, stickerId: String) async -> URL?
    func recordRecent(_ ref: StickerRef) async
    func recentStickers(limit: Int) async -> [StickerRef]
    /// Pull remote catalog and ensure pack manifests are available.
    func syncCatalog(from catalogURL: URL?) async throws
    /// Build a sendable ref with absolute CDN `url` for receivers without the pack.
    func enrichedRef(_ item: StickerItem) async -> StickerRef
}

public protocol ConversationRepository: Sendable {
    func observeConversations() -> AsyncStream<[Conversation]>
    func conversations() async throws -> [Conversation]
    func upsertConversation(from message: Message, title: String?, incrementUnread: Bool) async throws
    func setUnread(conversationId: String, count: Int) async throws
    func deleteConversation(id: String) async throws
    /// Apply cached group names onto existing group conversations.
    func syncGroupTitles() async throws
    /// Conversation currently open in chat UI (skips unread increments for that id).
    func setActiveConversationId(_ id: String?) async
    func activeConversationId() async -> String?
}

public protocol GroupRepository: Sendable {
    func create(name: String, members: [String]) async throws -> Group
    func join(groupId: String) async throws
    func leave(groupId: String) async throws
    func invite(groupId: String, uid: String) async throws
    func list() async throws -> [Group]
    func members(groupId: String) async throws -> [GroupMember]
    func observeGroups() -> AsyncStream<[Group]>
}

public protocol FriendRepository: Sendable {
    func listFriends() async throws -> [Friend]
    func pendingRequests() async throws -> [FriendRequest]
    func sendRequest(to uid: String) async throws
    func respond(to uid: String, accept: Bool) async throws
    func observeFriends() -> AsyncStream<[Friend]>
    func observeRequests() -> AsyncStream<[FriendRequest]>
    func refresh() async throws
}

public protocol FileRepository: Sendable {
    func upload(data: Data, fileName: String, mime: String) async throws -> FileMeta
    func fileURL(fileId: String, thumb: Bool) -> URL?
    /// Persist bytes under a `local:` id so the chat list can show the bubble before upload.
    func stageLocalFile(data: Data, fileName: String) throws -> String
    func replaceStaged(fileId: String, data: Data) throws
    func stagedData(fileId: String) -> Data?
    func removeStaged(fileId: String)
}

public protocol SearchRepository: Sendable {
    func search(query: String, peer: String?, chatType: ChatType?, limit: Int) async throws -> [SearchHit]
}
