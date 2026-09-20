import Foundation
import Moya
import Domain

public final class SearchRepositoryImpl: SearchRepository, @unchecked Sendable {
    private let provider: SharedMoyaProvider
    private let auth: AuthRepository

    public init(provider: SharedMoyaProvider, auth: AuthRepository) {
        self.provider = provider
        self.auth = auth
    }

    public func search(query: String, peer: String?, chatType: ChatType?, limit: Int) async throws -> [SearchHit] {
        guard auth.currentUser() != nil else { throw DomainError.notAuthenticated }
        let dto: SearchHTTPResponse = try await provider.requestDecodable(
            .search(query: query, peer: peer, chatType: chatType?.rawValue, limit: limit)
        )
        let selfUID = auth.currentUser()?.uid ?? ""
        return dto.messages.map { m in
            let conversationId: String
            if m.chat_type == ChatType.group.rawValue {
                conversationId = ConversationID.group(m.to)
            } else {
                let peerUID = m.from == selfUID ? m.to : m.from
                conversationId = ConversationID.dm(uidA: selfUID, uidB: peerUID)
            }
            let serverId = Int64(m.msg_id ?? "") ?? 0
            return SearchHit(
                serverMsgId: serverId,
                conversationId: conversationId,
                fromUID: m.from,
                content: m.content,
                timestampMs: m.timestamp
            )
        }
    }
}
