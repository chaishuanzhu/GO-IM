import UIKit
import Domain

enum BubbleStyle: Equatable {
    case outgoing
    case incoming
    case clear
    case system
}

struct MessageChromeTokens {
    var bodyColor: UIColor
    var iconTint: UIColor
}

struct ImageContent: Equatable {
    var fileId: String
    var displaySize: CGSize
    var thumbURL: URL?
    var fullURL: URL?
}

struct StickerContent: Equatable {
    var ref: StickerRef
    var displaySize: CGSize
    var bindKey: String
}

struct VoiceContent: Equatable {
    var duration: Int
    var playURL: URL?
}

struct VideoContent: Equatable {
    var title: String
    var url: URL?
    var name: String?
    var duration: Int?
}

struct FileContent: Equatable {
    var title: String
    var name: String
    var mime: String?
    var url: URL?
    var fileId: String?
}

enum MessageContentModel: Equatable {
    case text(String)
    case unsupported(String)
    case system(String)
    case image(ImageContent)
    case sticker(StickerContent)
    case voice(VoiceContent)
    case video(VideoContent)
    case file(FileContent)

    var reuseKey: String {
        switch self {
        case .text, .unsupported: return "text"
        case .system: return "system"
        case .image: return "image"
        case .sticker: return "sticker"
        case .voice: return "voice"
        case .video: return "video"
        case .file: return "file"
        }
    }
}

struct MessageBubbleViewModel: Equatable {
    enum Alignment: Equatable {
        case leading
        case trailing
        case center
    }

    var alignment: Alignment
    var metaText: String?
    var metaTextAlignment: NSTextAlignment
    var status: MessageStatus
    var isOutgoing: Bool
    var bubbleStyle: BubbleStyle
    var content: MessageContentModel
}

struct MessageContentActions {
    var onRetry: (() -> Void)?
    var onPreview: ((MediaPreviewItem) -> Void)?
    var onPlayVoice: ((URL, Int) -> Void)?
    var loadSticker: ((StickerRef) async -> Data?)?
}
