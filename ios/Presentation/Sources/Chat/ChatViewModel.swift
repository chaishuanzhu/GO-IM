import Foundation
import UIKit
import Kingfisher
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

    /// Show a bubble immediately with a quick preview, then compress + upload in the background.
    public func sendImage(_ image: UIImage, fileName: String, original: Bool) async {
        guard let user = env.auth.currentUser() else {
            errorMessage = "未登录"
            onChange?()
            return
        }
        errorMessage = nil

        // Capture for concurrent work (UIImage is not Sendable; detach with unchecked transfer).
        nonisolated(unsafe) let imageRef = image

        guard let preview = await Task.detached(priority: .userInitiated, operation: {
            ImageCompressor.quickPreviewJPEG(from: imageRef)
        }).value else {
            errorMessage = "图片预览生成失败"
            onChange?()
            return
        }

        let localId: String
        let pending: Message
        do {
            localId = try env.files.stageLocalFile(data: preview.data, fileName: fileName)
            let placeholder = FileMeta(
                fileId: localId,
                name: fileName,
                size: Int64(preview.data.count),
                mime: "image/jpeg",
                width: preview.width,
                height: preview.height
            )
            pending = try await env.messages.enqueueOutgoingFile(
                to: conversation.peerOrGroupId,
                chatType: conversation.chatType,
                meta: placeholder,
                from: user
            )
            try? await env.conversations.upsertConversation(from: pending, title: conversation.title)
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
            return
        }

        do {
            let uploadPayload = await Task.detached(priority: .utility, operation: {
                ImageCompressor.jpegDataForUpload(from: imageRef, original: original)
                    ?? preview
            }).value

            if uploadPayload.data != preview.data {
                try? env.files.replaceStaged(fileId: localId, data: uploadPayload.data)
            }

            var meta = try await env.files.upload(
                data: uploadPayload.data,
                fileName: fileName,
                mime: "image/jpeg"
            )
            if (meta.width ?? 0) <= 0 { meta.width = uploadPayload.width }
            if (meta.height ?? 0) <= 0 { meta.height = uploadPayload.height }

            // Seed Kingfisher so the local→remote handoff hits memory cache (no blank flash).
            if let previewImage = UIImage(data: uploadPayload.data) ?? UIImage(data: preview.data) {
                warmRemoteImageCache(image: previewImage, fileId: meta.fileId)
            }

            let sent = try await env.messages.deliverOutgoingFile(pending, meta: meta)
            env.files.removeStaged(fileId: localId)
            try? await env.conversations.upsertConversation(from: sent, title: conversation.title)
        } catch {
            try? await env.messages.markStatus(
                clientSeq: pending.clientSeq,
                status: .failed,
                serverMsgId: nil
            )
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            onChange?()
        }
    }

    private func warmRemoteImageCache(image: UIImage, fileId: String) {
        for thumb in [true, false] {
            guard let url = env.files.fileURL(fileId: fileId, thumb: thumb) else { continue }
            ImageCache.default.store(image, forKey: url.cacheKey)
        }
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

    public func retryMessage(id: String) async {
        guard let message = messages.first(where: { $0.id == id }),
              message.isOutgoing,
              message.status == .failed else { return }
        do {
            switch message.msgType {
            case .text:
                try await env.messages.retry(message)
            case .image, .voice, .video, .file:
                _ = try await env.sendFile.retry(message)
            }
        } catch {
            errorMessage = error.localizedDescription
            onChange?()
        }
    }
}
