import Foundation

public enum TransportKind: String, Sendable, Codable, CaseIterable {
    case webSocket
    case tcp
}

public enum ChatType: Int32, Sendable, Codable {
    case single = 1
    case group = 2
}

public enum MsgType: Sendable, Codable, Equatable {
    case text
    case image
    case voice
    case video
    case file
    case sticker
    /// Wire value not recognized by this client build; keep original code for round-trip.
    case unsupported(Int32)

    public static let unsupportedPlaceholder = "暂不支持的消息类型，请升级到最新版本"

    public var rawValue: Int32 {
        switch self {
        case .text: return 1
        case .image: return 2
        case .voice: return 3
        case .video: return 4
        case .file: return 5
        case .sticker: return 6
        case let .unsupported(code): return code
        }
    }

    public init(rawValue: Int32) {
        switch rawValue {
        case 1: self = .text
        case 2: self = .image
        case 3: self = .voice
        case 4: self = .video
        case 5: self = .file
        case 6: self = .sticker
        default: self = .unsupported(rawValue)
        }
    }

    /// Map upload MIME to protocol msg_type (docs/07-api-reference.md §10.2).
    public static func from(mime: String) -> MsgType {
        let m = mime.lowercased()
        if m.hasPrefix("image/") { return .image }
        if m.hasPrefix("audio/") { return .voice }
        if m.hasPrefix("video/") { return .video }
        return .file
    }
}

public enum MessageStatus: Int, Sendable, Codable {
    case sending = 0
    case sent = 1
    case failed = 2
    case recalled = 3
}

public enum ConnectionState: Sendable, Equatable {
    case disconnected
    case connecting
    case connected
    case reconnecting
}

public struct User: Sendable, Equatable, Identifiable {
    public var id: String { uid }
    public let uid: String
    public let username: String
    public let token: String

    public init(uid: String, username: String, token: String) {
        self.uid = uid
        self.username = username
        self.token = token
    }
}

public struct Message: Sendable, Equatable, Identifiable {
    public let id: String
    public var serverMsgId: Int64?
    public var clientSeq: Int64
    public var conversationId: String
    public var fromUID: String
    public var toUID: String
    public var chatType: ChatType
    public var msgType: MsgType
    public var content: String
    public var timestampMs: Int64
    public var status: MessageStatus
    public var isOutgoing: Bool

    public init(
        id: String = UUID().uuidString,
        serverMsgId: Int64? = nil,
        clientSeq: Int64,
        conversationId: String,
        fromUID: String,
        toUID: String,
        chatType: ChatType,
        msgType: MsgType,
        content: String,
        timestampMs: Int64,
        status: MessageStatus,
        isOutgoing: Bool
    ) {
        self.id = id
        self.serverMsgId = serverMsgId
        self.clientSeq = clientSeq
        self.conversationId = conversationId
        self.fromUID = fromUID
        self.toUID = toUID
        self.chatType = chatType
        self.msgType = msgType
        self.content = content
        self.timestampMs = timestampMs
        self.status = status
        self.isOutgoing = isOutgoing
    }
}

public struct Conversation: Sendable, Equatable, Identifiable {
    public let id: String
    public var chatType: ChatType
    public var title: String
    public var peerOrGroupId: String
    public var lastMessagePreview: String
    public var lastMessageAt: Int64
    public var unreadCount: Int

    public init(
        id: String,
        chatType: ChatType,
        title: String,
        peerOrGroupId: String,
        lastMessagePreview: String = "",
        lastMessageAt: Int64 = 0,
        unreadCount: Int = 0
    ) {
        self.id = id
        self.chatType = chatType
        self.title = title
        self.peerOrGroupId = peerOrGroupId
        self.lastMessagePreview = lastMessagePreview
        self.lastMessageAt = lastMessageAt
        self.unreadCount = unreadCount
    }
}

public struct Group: Sendable, Equatable, Identifiable {
    public var id: String { groupId }
    public let groupId: String
    public var name: String
    public var ownerUID: String
    public var memberCount: Int

    public init(groupId: String, name: String, ownerUID: String, memberCount: Int) {
        self.groupId = groupId
        self.name = name
        self.ownerUID = ownerUID
        self.memberCount = memberCount
    }
}

public struct GroupMember: Sendable, Equatable {
    public let groupId: String
    public let uid: String
    public var role: String

    public init(groupId: String, uid: String, role: String = "member") {
        self.groupId = groupId
        self.uid = uid
        self.role = role
    }
}

public struct Friend: Sendable, Equatable, Identifiable {
    public var id: String { uid }
    public let uid: String
    public var username: String

    public init(uid: String, username: String) {
        self.uid = uid
        self.username = username
    }
}

public enum FriendRequestStatus: String, Sendable, Codable {
    case pending
    case accepted
    case rejected
}

public struct FriendRequest: Sendable, Equatable, Identifiable {
    public var id: String { "\(fromUID)->\(toUID)" }
    public let fromUID: String
    public let toUID: String
    public var status: FriendRequestStatus
    public var createdAt: Int64
    /// Display name from `/friend/list` pending_requests when available.
    public var username: String?

    public init(
        fromUID: String,
        toUID: String,
        status: FriendRequestStatus,
        createdAt: Int64,
        username: String? = nil
    ) {
        self.fromUID = fromUID
        self.toUID = toUID
        self.status = status
        self.createdAt = createdAt
        self.username = username
    }
}

public struct FileMeta: Sendable, Equatable {
    public let fileId: String
    public let name: String
    public let size: Int64
    public let mime: String
    public var width: Int?
    public var height: Int?
    public var thumbWidth: Int?
    public var thumbHeight: Int?
    /// Seconds; used for voice / video messages.
    public var duration: Int?

    public init(
        fileId: String,
        name: String,
        size: Int64,
        mime: String,
        width: Int? = nil,
        height: Int? = nil,
        thumbWidth: Int? = nil,
        thumbHeight: Int? = nil,
        duration: Int? = nil
    ) {
        self.fileId = fileId
        self.name = name
        self.size = size
        self.mime = mime
        self.width = width
        self.height = height
        self.thumbWidth = thumbWidth
        self.thumbHeight = thumbHeight
        self.duration = duration
    }
}

/// Sticker wire payload (`msg_type=6`); authority is pack_id + sticker_id.
public struct StickerRef: Sendable, Equatable, Codable {
    public var packId: String
    public var stickerId: String
    public var format: String
    public var width: Int
    public var height: Int
    /// Optional CDN / custom-scheme fallback when the pack is not installed locally.
    public var url: String?

    public enum CodingKeys: String, CodingKey {
        case packId = "pack_id"
        case stickerId = "sticker_id"
        case format, width, height, url
    }

    public init(
        packId: String,
        stickerId: String,
        format: String = "png",
        width: Int = 240,
        height: Int = 240,
        url: String? = nil
    ) {
        self.packId = packId
        self.stickerId = stickerId
        self.format = format
        self.width = width
        self.height = height
        self.url = url
    }

    public func encodeContent() throws -> String {
        let data = try JSONEncoder().encode(self)
        guard let s = String(data: data, encoding: .utf8) else {
            throw DomainError.invalidState("sticker json encode failed")
        }
        return s
    }

    public static func decode(from content: String) -> StickerRef? {
        guard let data = content.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(StickerRef.self, from: data)
    }
}

public struct StickerItem: Sendable, Equatable, Identifiable {
    public var id: String { stickerId }
    public let packId: String
    public let stickerId: String
    public let fileName: String
    public let width: Int
    public let height: Int

    public init(packId: String, stickerId: String, fileName: String, width: Int = 240, height: Int = 240) {
        self.packId = packId
        self.stickerId = stickerId
        self.fileName = fileName
        self.width = width
        self.height = height
    }

    public func asRef(url: String? = nil) -> StickerRef {
        let ext = fileName.split(separator: ".").last.map(String.init)?.lowercased() ?? "png"
        return StickerRef(
            packId: packId,
            stickerId: stickerId,
            format: ext.isEmpty ? "png" : ext,
            width: width,
            height: height,
            url: url
        )
    }
}
public struct StickerPack: Sendable, Equatable, Identifiable {
    public var id: String { packId }
    public let packId: String
    public let name: String
    public let version: Int
    public let coverFileName: String?
    public let stickers: [StickerItem]
    public let baseURL: String?

    public init(
        packId: String,
        name: String,
        version: Int,
        coverFileName: String? = nil,
        stickers: [StickerItem],
        baseURL: String? = nil
    ) {
        self.packId = packId
        self.name = name
        self.version = version
        self.coverFileName = coverFileName
        self.stickers = stickers
        self.baseURL = baseURL
    }
}

public struct SearchHit: Sendable, Equatable, Identifiable {
    public var id: String { "\(serverMsgId)" }
    public let serverMsgId: Int64
    public let conversationId: String
    public let fromUID: String
    public let content: String
    public let timestampMs: Int64

    public init(
        serverMsgId: Int64,
        conversationId: String,
        fromUID: String,
        content: String,
        timestampMs: Int64
    ) {
        self.serverMsgId = serverMsgId
        self.conversationId = conversationId
        self.fromUID = fromUID
        self.content = content
        self.timestampMs = timestampMs
    }
}

public enum ConversationID {
    public static func dm(uidA: String, uidB: String) -> String {
        let sorted = [uidA, uidB].sorted()
        return "dm:\(sorted[0])_\(sorted[1])"
    }

    public static func group(_ groupId: String) -> String {
        "group:\(groupId)"
    }
}
