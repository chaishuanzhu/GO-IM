import Foundation
import Domain

@MainActor
public final class ChatViewModel {
    public let conversation: Conversation
    public private(set) var messages: [Message] = []
    public var draft: String = ""
    public var onChange: (() -> Void)?
    public var errorMessage: String?

    private let env: AppEnvironment
    private var observeTask: Task<Void, Never>?

    public init(env: AppEnvironment, conversation: Conversation) {
        self.env = env
        self.conversation = conversation
    }

    public func start() {
        observeTask?.cancel()
        observeTask = Task {
            for await list in env.observeMessages.execute(conversationId: conversation.id) {
                messages = list
                onChange?()
            }
        }
        Task {
            try? await env.markRead.execute(
                conversationId: conversation.id,
                peer: conversation.peerOrGroupId,
                chatType: conversation.chatType
            )
        }
    }

    public func stop() {
        observeTask?.cancel()
        observeTask = nil
    }

    public func sendText() async {
        guard let user = env.auth.currentUser(), !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }
        let text = draft
        draft = ""
        do {
            _ = try await env.sendText.execute(
                to: conversation.peerOrGroupId,
                chatType: conversation.chatType,
                text: text,
                from: user
            )
            try? await env.conversations.upsertConversation(
                from: Message(
                    clientSeq: 0,
                    conversationId: conversation.id,
                    fromUID: user.uid,
                    toUID: conversation.peerOrGroupId,
                    chatType: conversation.chatType,
                    msgType: .text,
                    content: text,
                    timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
                    status: .sent,
                    isOutgoing: true
                ),
                title: conversation.title
            )
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    public func sendImage(data: Data, fileName: String, width: Int = 0, height: Int = 0) async {
        await sendAttachment(
            data: data,
            fileName: fileName,
            mime: "image/jpeg",
            width: width,
            height: height
        )
    }

    public func sendAttachment(
        data: Data,
        fileName: String,
        mime: String,
        width: Int = 0,
        height: Int = 0,
        duration: Int = 0
    ) async {
        guard let user = env.auth.currentUser() else {
            errorMessage = "未登录"
            onChange?()
            return
        }
        guard !data.isEmpty else {
            errorMessage = "附件数据为空"
            onChange?()
            return
        }
        if data.count > ImageCompressor.serverMaxBytes {
            errorMessage = "附件过大 (\(data.count / 1024)KB)，上限 \(ImageCompressor.serverMaxBytes / 1024 / 1024)MB"
            onChange?()
            return
        }
        errorMessage = nil
        do {
            let sent = try await env.sendFile.execute(
                to: conversation.peerOrGroupId,
                chatType: conversation.chatType,
                data: data,
                fileName: fileName,
                mime: mime,
                from: user,
                localWidth: width > 0 ? width : nil,
                localHeight: height > 0 ? height : nil,
                localDuration: duration > 0 ? duration : nil
            )
            try? await env.conversations.upsertConversation(from: sent, title: conversation.title)
            onChange?()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            onChange?()
        }
    }
}
