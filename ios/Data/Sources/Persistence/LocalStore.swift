import Foundation
import GRDB
import Domain

public final class AppDatabase: Sendable {
    public let dbQueue: DatabaseQueue

    public init(path: String? = nil) throws {
        if let path {
            dbQueue = try DatabaseQueue(path: path)
        } else {
            dbQueue = try DatabaseQueue()
        }
        try migrator.migrate(dbQueue)
    }

    private var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1") { db in
            try db.create(table: "messages") { t in
                t.column("id", .text).primaryKey()
                t.column("server_msg_id", .integer).unique()
                t.column("client_seq", .integer).notNull()
                t.column("conversation_id", .text).notNull().indexed()
                t.column("from_uid", .text).notNull()
                t.column("to_uid", .text).notNull()
                t.column("chat_type", .integer).notNull()
                t.column("msg_type", .integer).notNull()
                t.column("content", .text).notNull()
                t.column("timestamp_ms", .integer).notNull().indexed()
                t.column("status", .integer).notNull()
                t.column("is_outgoing", .boolean).notNull()
            }
            try db.create(table: "conversations") { t in
                t.column("id", .text).primaryKey()
                t.column("chat_type", .integer).notNull()
                t.column("title", .text).notNull()
                t.column("peer_or_group_id", .text).notNull()
                t.column("last_msg_preview", .text).notNull().defaults(to: "")
                t.column("last_msg_at", .integer).notNull().defaults(to: 0)
                t.column("unread_count", .integer).notNull().defaults(to: 0)
            }
            try db.create(table: "groups") { t in
                t.column("group_id", .text).primaryKey()
                t.column("name", .text).notNull()
                t.column("owner_uid", .text).notNull()
                t.column("member_count", .integer).notNull().defaults(to: 0)
            }
            try db.create(table: "group_members") { t in
                t.column("group_id", .text).notNull()
                t.column("uid", .text).notNull()
                t.column("role", .text).notNull().defaults(to: "member")
                t.primaryKey(["group_id", "uid"])
            }
            try db.create(table: "friends") { t in
                t.column("uid", .text).primaryKey()
                t.column("username", .text).notNull()
            }
            try db.create(table: "friend_requests") { t in
                t.column("from_uid", .text).notNull()
                t.column("to_uid", .text).notNull()
                t.column("status", .text).notNull()
                t.column("created_at", .integer).notNull()
                t.primaryKey(["from_uid", "to_uid"])
            }
            try db.create(table: "meta") { t in
                t.column("key", .text).primaryKey()
                t.column("value", .text).notNull()
            }
        }
        migrator.registerMigration("v2_friend_request_username") { db in
            try db.alter(table: "friend_requests") { t in
                t.add(column: "username", .text)
            }
        }
        return migrator
    }
}

public struct MessageRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    public static let databaseTableName = "messages"
    public var id: String
    public var serverMsgId: Int64?
    public var clientSeq: Int64
    public var conversationId: String
    public var fromUid: String
    public var toUid: String
    public var chatType: Int
    public var msgType: Int
    public var content: String
    public var timestampMs: Int64
    public var status: Int
    public var isOutgoing: Bool

    enum CodingKeys: String, CodingKey {
        case id
        case serverMsgId = "server_msg_id"
        case clientSeq = "client_seq"
        case conversationId = "conversation_id"
        case fromUid = "from_uid"
        case toUid = "to_uid"
        case chatType = "chat_type"
        case msgType = "msg_type"
        case content
        case timestampMs = "timestamp_ms"
        case status
        case isOutgoing = "is_outgoing"
    }

    public func toDomain() -> Message {
        Message(
            id: id,
            serverMsgId: serverMsgId,
            clientSeq: clientSeq,
            conversationId: conversationId,
            fromUID: fromUid,
            toUID: toUid,
            chatType: ChatType(rawValue: Int32(chatType)) ?? .single,
            msgType: MsgType(rawValue: Int32(msgType)) ?? .text,
            content: content,
            timestampMs: timestampMs,
            status: MessageStatus(rawValue: status) ?? .sent,
            isOutgoing: isOutgoing
        )
    }

    public static func from(_ m: Message) -> MessageRecord {
        MessageRecord(
            id: m.id,
            serverMsgId: m.serverMsgId,
            clientSeq: m.clientSeq,
            conversationId: m.conversationId,
            fromUid: m.fromUID,
            toUid: m.toUID,
            chatType: Int(m.chatType.rawValue),
            msgType: Int(m.msgType.rawValue),
            content: m.content,
            timestampMs: m.timestampMs,
            status: m.status.rawValue,
            isOutgoing: m.isOutgoing
        )
    }
}

public struct ConversationRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    public static let databaseTableName = "conversations"
    public var id: String
    public var chatType: Int
    public var title: String
    public var peerOrGroupId: String
    public var lastMsgPreview: String
    public var lastMsgAt: Int64
    public var unreadCount: Int

    enum CodingKeys: String, CodingKey {
        case id, title
        case chatType = "chat_type"
        case peerOrGroupId = "peer_or_group_id"
        case lastMsgPreview = "last_msg_preview"
        case lastMsgAt = "last_msg_at"
        case unreadCount = "unread_count"
    }

    public func toDomain() -> Conversation {
        Conversation(
            id: id,
            chatType: ChatType(rawValue: Int32(chatType)) ?? .single,
            title: title,
            peerOrGroupId: peerOrGroupId,
            lastMessagePreview: lastMsgPreview,
            lastMessageAt: lastMsgAt,
            unreadCount: unreadCount
        )
    }
}

public actor LocalStore {
    private let db: AppDatabase

    public init(db: AppDatabase) {
        self.db = db
    }

    public func upsertMessage(_ message: Message) throws {
        try db.dbQueue.write { db in
            try MessageRecord.from(message).save(db)
        }
    }

    public func markStatus(clientSeq: Int64, status: MessageStatus, serverMsgId: Int64?) throws {
        try db.dbQueue.write { db in
            try db.execute(
                sql: """
                UPDATE messages SET status = ?, server_msg_id = COALESCE(?, server_msg_id)
                WHERE client_seq = ?
                """,
                arguments: [status.rawValue, serverMsgId, clientSeq]
            )
        }
    }

    public func messages(conversationId: String, before: Int64?, limit: Int) throws -> [Message] {
        try db.dbQueue.read { db in
            var sql = "SELECT * FROM messages WHERE conversation_id = ?"
            var args: [any DatabaseValueConvertible] = [conversationId]
            if let before {
                sql += " AND timestamp_ms < ?"
                args.append(before)
            }
            sql += " ORDER BY timestamp_ms DESC LIMIT ?"
            args.append(limit)
            return try MessageRecord.fetchAll(db, sql: sql, arguments: StatementArguments(args))
                .map { $0.toDomain() }
                .reversed()
        }
    }

    public func upsertConversation(_ c: Conversation) throws {
        try db.dbQueue.write { db in
            try ConversationRecord(
                id: c.id,
                chatType: Int(c.chatType.rawValue),
                title: c.title,
                peerOrGroupId: c.peerOrGroupId,
                lastMsgPreview: c.lastMessagePreview,
                lastMsgAt: c.lastMessageAt,
                unreadCount: c.unreadCount
            ).save(db)
        }
    }

    public func allConversations() throws -> [Conversation] {
        try db.dbQueue.read { db in
            try ConversationRecord.order(sql: "last_msg_at DESC").fetchAll(db).map { $0.toDomain() }
        }
    }

    public func conversation(id: String) throws -> Conversation? {
        try db.dbQueue.read { db in
            try ConversationRecord.fetchOne(db, key: id)?.toDomain()
        }
    }

    public func setUnread(conversationId: String, count: Int) throws {
        try db.dbQueue.write { db in
            try db.execute(
                sql: "UPDATE conversations SET unread_count = ? WHERE id = ?",
                arguments: [count, conversationId]
            )
        }
    }

    public func deleteConversation(id: String) throws {
        try db.dbQueue.write { db in
            try db.execute(sql: "DELETE FROM messages WHERE conversation_id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM conversations WHERE id = ?", arguments: [id])
        }
    }

    public func saveFriends(_ friends: [Friend]) throws {
        try db.dbQueue.write { db in
            try db.execute(sql: "DELETE FROM friends")
            for f in friends {
                try db.execute(sql: "INSERT INTO friends (uid, username) VALUES (?, ?)", arguments: [f.uid, f.username])
            }
        }
    }

    public func friends() throws -> [Friend] {
        try db.dbQueue.read { db in
            try Row.fetchAll(db, sql: "SELECT uid, username FROM friends").map {
                Friend(uid: $0["uid"], username: $0["username"])
            }
        }
    }

    public func saveFriendRequest(_ r: FriendRequest) throws {
        try db.dbQueue.write { db in
            try db.execute(
                sql: """
                INSERT OR REPLACE INTO friend_requests (from_uid, to_uid, status, created_at, username)
                VALUES (?, ?, ?, ?, ?)
                """,
                arguments: [r.fromUID, r.toUID, r.status.rawValue, r.createdAt, r.username]
            )
        }
    }

    public func replacePendingRequests(_ requests: [FriendRequest], for toUID: String) throws {
        try db.dbQueue.write { db in
            try db.execute(
                sql: "DELETE FROM friend_requests WHERE to_uid = ? AND status = ?",
                arguments: [toUID, FriendRequestStatus.pending.rawValue]
            )
            for r in requests {
                try db.execute(
                    sql: """
                    INSERT OR REPLACE INTO friend_requests (from_uid, to_uid, status, created_at, username)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    arguments: [r.fromUID, r.toUID, r.status.rawValue, r.createdAt, r.username]
                )
            }
        }
    }

    public func pendingRequests(for uid: String) throws -> [FriendRequest] {
        try db.dbQueue.read { db in
            try Row.fetchAll(
                db,
                sql: "SELECT * FROM friend_requests WHERE to_uid = ? AND status = ?",
                arguments: [uid, FriendRequestStatus.pending.rawValue]
            ).map {
                let username: String? = $0["username"]
                return FriendRequest(
                    fromUID: $0["from_uid"],
                    toUID: $0["to_uid"],
                    status: FriendRequestStatus(rawValue: $0["status"]) ?? .pending,
                    createdAt: $0["created_at"],
                    username: username
                )
            }
        }
    }

    public func saveGroup(_ g: Group) throws {
        try db.dbQueue.write { db in
            try db.execute(
                sql: """
                INSERT OR REPLACE INTO groups (group_id, name, owner_uid, member_count)
                VALUES (?, ?, ?, ?)
                """,
                arguments: [g.groupId, g.name, g.ownerUID, g.memberCount]
            )
        }
    }

    public func group(id: String) throws -> Group? {
        try db.dbQueue.read { db in
            try Row.fetchOne(db, sql: "SELECT * FROM groups WHERE group_id = ?", arguments: [id]).map {
                Group(groupId: $0["group_id"], name: $0["name"], ownerUID: $0["owner_uid"], memberCount: $0["member_count"])
            }
        }
    }

    public func groups() throws -> [Group] {
        try db.dbQueue.read { db in
            try Row.fetchAll(db, sql: "SELECT * FROM groups").map {
                Group(groupId: $0["group_id"], name: $0["name"], ownerUID: $0["owner_uid"], memberCount: $0["member_count"])
            }
        }
    }

    public func meta(_ key: String) throws -> String? {
        try db.dbQueue.read { db in
            try String.fetchOne(db, sql: "SELECT value FROM meta WHERE key = ?", arguments: [key])
        }
    }

    public func setMeta(_ key: String, value: String) throws {
        try db.dbQueue.write { db in
            try db.execute(sql: "INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arguments: [key, value])
        }
    }
}
