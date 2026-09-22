import Foundation
import Domain

enum MessageBubbleMapper {
    static func map(
        _ message: Message,
        fileURL: (String, Bool) -> URL?
    ) -> MessageBubbleViewModel {
        let outgoing = message.isOutgoing

        if message.msgType == .text, let notice = Message.systemNotice(from: message.content) {
            return MessageBubbleViewModel(
                alignment: .center,
                metaText: nil,
                metaTextAlignment: .center,
                status: message.status,
                isOutgoing: outgoing,
                bubbleStyle: .system,
                content: .system(notice)
            )
        }

        let metaText: String = outgoing
            ? GOIMFormat.messageTime(message.timestampMs)
            : "\(message.fromUID) · \(GOIMFormat.messageTime(message.timestampMs))"

        let content = mapContent(message, fileURL: fileURL)
        let bubbleStyle: BubbleStyle
        switch content {
        case .sticker:
            bubbleStyle = .clear
        default:
            bubbleStyle = outgoing ? .outgoing : .incoming
        }

        return MessageBubbleViewModel(
            alignment: outgoing ? .trailing : .leading,
            metaText: metaText,
            metaTextAlignment: outgoing ? .right : .left,
            status: message.status,
            isOutgoing: outgoing,
            bubbleStyle: bubbleStyle,
            content: content
        )
    }

    private static func mapContent(
        _ message: Message,
        fileURL: (String, Bool) -> URL?
    ) -> MessageContentModel {
        switch message.msgType {
        case .text:
            return .text(message.content)
        case .unsupported:
            return .unsupported(MsgType.unsupportedPlaceholder)
        case .image:
            return mapImage(message.content, fileURL: fileURL)
        case .sticker:
            return mapSticker(message.content)
        case .voice:
            return mapVoice(message.content, fileURL: fileURL)
        case .video:
            return mapVideo(message.content, fileURL: fileURL)
        case .file:
            return mapFile(message.content, fileURL: fileURL)
        }
    }

    private static func mapImage(
        _ content: String,
        fileURL: (String, Bool) -> URL?
    ) -> MessageContentModel {
        guard let meta = Message.decodeFileMeta(content) else {
            return .text("[图片]")
        }
        let thumb = fileURL(meta.fileId, true)
        let full = fileURL(meta.fileId, false)
        return .image(ImageContent(
            fileId: meta.fileId,
            displaySize: MessageBubbleLayout.imageSize(width: meta.width, height: meta.height),
            thumbURL: thumb,
            fullURL: full
        ))
    }

    private static func mapSticker(_ content: String) -> MessageContentModel {
        guard let ref = StickerRef.decode(from: content) else {
            return .text("[表情]")
        }
        let side = MessageBubbleLayout.stickerSide
        return .sticker(StickerContent(
            ref: ref,
            displaySize: CGSize(width: side, height: side),
            bindKey: "\(ref.packId)/\(ref.stickerId)"
        ))
    }

    private static func mapVoice(
        _ content: String,
        fileURL: (String, Bool) -> URL?
    ) -> MessageContentModel {
        let meta = Message.decodeFileMeta(content)
        let url = meta.flatMap { fileURL($0.fileId, false) }
        return .voice(VoiceContent(duration: meta?.duration ?? 0, playURL: url))
    }

    private static func mapVideo(
        _ content: String,
        fileURL: (String, Bool) -> URL?
    ) -> MessageContentModel {
        let meta = Message.decodeFileMeta(content)
        var parts: [String] = ["视频"]
        if let d = meta?.duration, d > 0 { parts.append("\(d)\"") }
        if let name = meta?.name, !name.isEmpty, name != "file" { parts.append(name) }
        let url = meta.flatMap { fileURL($0.fileId, false) }
        return .video(VideoContent(
            title: parts.joined(separator: " · "),
            url: url,
            name: meta?.name,
            duration: meta?.duration
        ))
    }

    private static func mapFile(
        _ content: String,
        fileURL: (String, Bool) -> URL?
    ) -> MessageContentModel {
        let meta = Message.decodeFileMeta(content)
        if let mime = meta?.mime {
            if mime.hasPrefix("image/") {
                return mapImage(content, fileURL: fileURL)
            }
            if mime.hasPrefix("audio/") {
                return mapVoice(content, fileURL: fileURL)
            }
            if mime.hasPrefix("video/") {
                return mapVideo(content, fileURL: fileURL)
            }
        }
        let name = meta?.name ?? "文件"
        let sizeText = (meta?.size).map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? ""
        let title = sizeText.isEmpty ? name : "\(name)\n\(sizeText)"
        let url = meta.flatMap { fileURL($0.fileId, false) }
        return .file(FileContent(
            title: title,
            name: name,
            mime: meta?.mime,
            url: url,
            fileId: meta?.fileId
        ))
    }
}
