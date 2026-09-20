import Foundation
import Moya
import Domain

// MARK: - Server config

public final class ServerConfig: @unchecked Sendable {
    public var baseURL: URL

    public init(baseURL: URL) {
        self.baseURL = baseURL
    }

    public var host: String { baseURL.host ?? "im.chaisz.com" }
    public var httpPort: UInt16 { UInt16(baseURL.port ?? (baseURL.scheme == "https" ? 443 : 80)) }
    public var tcpPort: UInt16 { 8081 }
    public var useTLS: Bool { baseURL.scheme == "https" }
}

// MARK: - DTOs

public struct LoginResponseDTO: Decodable, Sendable {
    public let uid: String
    public let username: String
    public let token: String
}

public struct HealthResponseDTO: Decodable, Sendable {
    public let status: String
    public let connections: Int?
}

public struct GroupDTO: Decodable, Sendable {
    public let id: String
    public let name: String
    public let owner_uid: String
    public let members: [String]?
    public let member_count: Int?
    public let created_at: Int64?

    private enum CodingKeys: String, CodingKey {
        case id, group_id, name, owner_uid, members, member_count, created_at
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let id = try c.decodeIfPresent(String.self, forKey: .id), !id.isEmpty {
            self.id = id
        } else if let groupId = try c.decodeIfPresent(String.self, forKey: .group_id), !groupId.isEmpty {
            // POST /group/create returns `group_id`; list uses `id`.
            self.id = groupId
        } else {
            throw DecodingError.keyNotFound(
                CodingKeys.id,
                .init(codingPath: c.codingPath, debugDescription: "Missing group id/group_id")
            )
        }
        name = try c.decode(String.self, forKey: .name)
        owner_uid = try c.decode(String.self, forKey: .owner_uid)
        members = try c.decodeIfPresent([String].self, forKey: .members)
        member_count = try c.decodeIfPresent(Int.self, forKey: .member_count)
        created_at = try c.decodeIfPresent(Int64.self, forKey: .created_at)
    }

    public func toDomain() -> Group {
        Group(
            groupId: id,
            name: name,
            ownerUID: owner_uid,
            memberCount: member_count ?? members?.count ?? 0
        )
    }
}

public struct GroupListResponseDTO: Decodable, Sendable {
    public let groups: [GroupDTO]
}

public struct GroupMembersResponseDTO: Decodable, Sendable {
    public let group_id: String
    public let members: [String]
}

public struct FriendRowDTO: Decodable, Sendable {
    public let uid: String?
    public let friend_uid: String
    public let status: Int?
    public let created_at: Int64?
}

public struct PendingFriendRequestDTO: Decodable, Sendable {
    public let from_uid: String
    public let username: String?
    public let created_at: Int64?
}

public struct FriendListResponseDTO: Decodable, Sendable {
    public let uid: String?
    public let friends: [FriendRowDTO]
    public let pending_requests: [PendingFriendRequestDTO]?
}

public struct StatusResponseDTO: Decodable, Sendable {
    public let status: String?
    public let ok: String?
}

public struct UploadResponseDTO: Decodable, Sendable {
    public let fileId: String
    public let name: String
    public let size: Int64
    public let mime: String
    public let width: Int?
    public let height: Int?
    public let thumbWidth: Int?
    public let thumbHeight: Int?

    enum CodingKeys: String, CodingKey {
        case fileId = "file_id"
        case name, size, mime, width, height
        case thumbWidth = "thumb_width"
        case thumbHeight = "thumb_height"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let s = try? c.decode(String.self, forKey: .fileId) {
            fileId = s
        } else if let n = try? c.decode(Int64.self, forKey: .fileId) {
            fileId = String(n)
        } else {
            throw DecodingError.dataCorruptedError(forKey: .fileId, in: c, debugDescription: "file_id missing")
        }
        name = (try? c.decode(String.self, forKey: .name)) ?? ""
        if let s = try? c.decode(Int64.self, forKey: .size) {
            size = s
        } else if let s = try? c.decode(Int.self, forKey: .size) {
            size = Int64(s)
        } else {
            size = 0
        }
        mime = (try? c.decode(String.self, forKey: .mime)) ?? "application/octet-stream"
        // Server returns 0 when DecodeConfig fails — treat as missing.
        width = Self.positiveInt(try? c.decode(Int.self, forKey: .width))
        height = Self.positiveInt(try? c.decode(Int.self, forKey: .height))
        thumbWidth = Self.positiveInt(try? c.decode(Int.self, forKey: .thumbWidth))
        thumbHeight = Self.positiveInt(try? c.decode(Int.self, forKey: .thumbHeight))
    }

    private static func positiveInt(_ value: Int?) -> Int? {
        guard let value, value > 0 else { return nil }
        return value
    }

    public func toDomain() -> FileMeta {
        FileMeta(
            fileId: fileId,
            name: name,
            size: size,
            mime: mime,
            width: width,
            height: height,
            thumbWidth: thumbWidth,
            thumbHeight: thumbHeight
        )
    }
}

public struct SearchMessageDTO: Decodable, Sendable {
    public let msg_id: String?
    public let cmd: Int32?
    public let from: String
    public let to: String
    public let chat_type: Int32
    public let msg_type: Int32?
    public let content: String
    public let timestamp: Int64
    public let need_ack: Bool?
}

public struct SearchHTTPResponse: Decodable, Sendable {
    public let query: String?
    public let messages: [SearchMessageDTO]
    public let total: Int?
    public let next_cursor: Int64?
}

// MARK: - Auth plugin

public final class GOIMAuthPlugin: PluginType, @unchecked Sendable {
    public var uid: String = ""
    public var token: String = ""

    public init() {}

    public func prepare(_ request: URLRequest, target: TargetType) -> URLRequest {
        var req = request
        guard !uid.isEmpty, !token.isEmpty else { return req }

        if let target = target as? GOIMAPI {
            switch target {
            case .login, .register, .health, .upload:
                return req
            default:
                break
            }
        }

        // Always put uid/token on the query string. Gateway authenticateRequest uses
        // ParseForm(), which merges query + body — so POST form endpoints stay authorized
        // even when httpBody is nil/unavailable during Moya plugin prepare.
        if let url = req.url, var components = URLComponents(url: url, resolvingAgainstBaseURL: false) {
            var items = components.queryItems ?? []
            if !items.contains(where: { $0.name == "uid" }) {
                items.append(URLQueryItem(name: "uid", value: uid))
            }
            if !items.contains(where: { $0.name == "token" }) {
                items.append(URLQueryItem(name: "token", value: token))
            }
            components.queryItems = items
            req.url = components.url
        }

        // Best-effort: also append into x-www-form-urlencoded bodies when present.
        if req.httpMethod != "GET", req.httpMethod != "HEAD",
           let body = req.httpBody,
           req.value(forHTTPHeaderField: "Content-Type")?.contains("application/x-www-form-urlencoded") == true,
           var form = String(data: body, encoding: .utf8) {
            let encodedUID = uid.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? uid
            let encodedToken = token.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? token
            if !form.contains("uid=") {
                form += (form.isEmpty ? "" : "&") + "uid=\(encodedUID)"
            }
            if !form.contains("token=") {
                form += (form.isEmpty ? "" : "&") + "token=\(encodedToken)"
            }
            req.httpBody = Data(form.utf8)
        }
        return req
    }
}

// MARK: - TargetType

public enum GOIMAPI {
    case login(uid: String, username: String, password: String)
    case register(uid: String, username: String, password: String)
    case health
    case groupCreate(name: String, members: [String])
    case groupJoin(groupId: String)
    case groupLeave(groupId: String)
    case groupMembers(groupId: String)
    case groupList
    case friendList
    case friendRequest(toUID: String)
    case friendAccept(fromUID: String)
    case friendReject(fromUID: String)
    case upload(data: Data, fileName: String, mime: String, uid: String, token: String)
    case search(query: String, peer: String?, chatType: Int32?, limit: Int)
}

extension GOIMAPI: TargetType {
    public var baseURL: URL {
        ServerConfigHolder.shared.baseURL
    }

    public var path: String {
        switch self {
        case .login: return "/login"
        case .register: return "/register"
        case .health: return "/health"
        case .groupCreate: return "/group/create"
        case .groupJoin: return "/group/join"
        case .groupLeave: return "/group/leave"
        case .groupMembers: return "/group/members"
        case .groupList: return "/group/list"
        case .friendList: return "/friend/list"
        case .friendRequest: return "/friend/request"
        case .friendAccept: return "/friend/accept"
        case .friendReject: return "/friend/reject"
        case .upload: return "/upload"
        case .search: return "/search"
        }
    }

    public var method: Moya.Method {
        switch self {
        case .health, .groupMembers, .groupList, .friendList, .search:
            return .get
        default:
            return .post
        }
    }

    public var task: Moya.Task {
        switch self {
        case let .login(uid, username, password):
            return .requestParameters(
                parameters: form(["uid": uid, "username": username, "password": password]),
                encoding: URLEncoding.httpBody
            )
        case let .register(uid, username, password):
            return .requestParameters(
                parameters: form(["uid": uid, "username": username, "password": password]),
                encoding: URLEncoding.httpBody
            )
        case .health:
            return .requestPlain
        case let .groupCreate(name, members):
            var params: [String: Any] = ["name": name]
            if !members.isEmpty {
                params["members"] = members.joined(separator: ",")
            }
            return .requestParameters(parameters: params, encoding: URLEncoding.httpBody)
        case let .groupJoin(groupId):
            return .requestParameters(parameters: form(["group_id": groupId]), encoding: URLEncoding.httpBody)
        case let .groupLeave(groupId):
            return .requestParameters(parameters: form(["group_id": groupId]), encoding: URLEncoding.httpBody)
        case let .groupMembers(groupId):
            return .requestParameters(parameters: ["group_id": groupId], encoding: URLEncoding.queryString)
        case .groupList, .friendList:
            return .requestPlain
        case let .friendRequest(toUID):
            return .requestParameters(parameters: form(["to_uid": toUID]), encoding: URLEncoding.httpBody)
        case let .friendAccept(fromUID):
            return .requestParameters(parameters: form(["from_uid": fromUID]), encoding: URLEncoding.httpBody)
        case let .friendReject(fromUID):
            return .requestParameters(parameters: form(["from_uid": fromUID]), encoding: URLEncoding.httpBody)
        case let .upload(data, fileName, mime, uid, token):
            let formData: [MultipartFormData] = [
                MultipartFormData(provider: .data(Data(uid.utf8)), name: "uid"),
                MultipartFormData(provider: .data(Data(token.utf8)), name: "token"),
                MultipartFormData(provider: .data(data), name: "file", fileName: fileName, mimeType: mime),
            ]
            return .uploadMultipart(formData)
        case let .search(query, peer, chatType, limit):
            var params: [String: Any] = ["q": query, "limit": limit]
            if let peer { params["peer"] = peer }
            if let chatType { params["chat_type"] = chatType }
            return .requestParameters(parameters: params, encoding: URLEncoding.queryString)
        }
    }

    public var headers: [String: String]? {
        switch self {
        case .upload:
            return nil
        case .login, .register, .groupCreate, .groupJoin, .groupLeave,
             .friendRequest, .friendAccept, .friendReject:
            return ["Content-Type": "application/x-www-form-urlencoded"]
        default:
            return nil
        }
    }

    private func form(_ dict: [String: String]) -> [String: Any] {
        dict
    }
}

/// Mutable holder so TargetType can resolve baseURL without capturing ServerConfig per request.
public enum ServerConfigHolder {
    public static let shared = ServerConfig(baseURL: URL(string: "https://im.chaisz.com")!)
}

// MARK: - Moya helpers

/// Unchecked Sendable box for MoyaProvider (Moya is not Sendable under Swift 6).
public final class SharedMoyaProvider: @unchecked Sendable {
    public let provider: MoyaProvider<GOIMAPI>
    public init(_ provider: MoyaProvider<GOIMAPI>) {
        self.provider = provider
    }

    public func requestDecodable<T: Decodable>(_ target: GOIMAPI, as type: T.Type = T.self) async throws -> T {
        try await provider.requestDecodable(target, as: type)
    }
}


public extension MoyaProvider where Target == GOIMAPI {
    func requestDecodable<T: Decodable>(_ target: GOIMAPI, as type: T.Type = T.self) async throws -> T {
        let response = try await asyncRequest(target)
        guard (200..<300).contains(response.statusCode) else {
            let body = String(data: response.data, encoding: .utf8) ?? ""
            throw DomainError.server(response.statusCode, body)
        }
        do {
            return try JSONDecoder().decode(T.self, from: response.data)
        } catch {
            throw DomainError.network("decode: \(error.localizedDescription)")
        }
    }

    func asyncRequest(_ target: GOIMAPI) async throws -> Response {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Response, Error>) in
            self.request(target) { result in
                switch result {
                case .success(let response):
                    // Moya.Response is not Sendable; hand off via unchecked transfer.
                    nonisolated(unsafe) let boxed = response
                    cont.resume(returning: boxed)
                case .failure(let error):
                    cont.resume(throwing: DomainError.network(error.localizedDescription))
                }
            }
        }
    }
}
