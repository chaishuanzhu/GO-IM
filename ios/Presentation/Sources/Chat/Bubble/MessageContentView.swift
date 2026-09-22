import UIKit

/// Pluggable bubble body.
///
/// ## Extending a new message type
/// 1. Domain `MsgType` case
/// 2. Mapper → new `MessageContentModel` case
/// 3. New `XxxMessageContentView`
/// 4. Register in `MessageContentRegistry.make`
/// 5. Extend `MediaPreviewItem` if preview is needed
///
/// Do not change `MessageBubbleCell` chrome for content-only work.
@MainActor
protocol MessageContentView: UIView {
    static var reuseKey: String { get }
    func prepareForReuse()
    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    )
}

@MainActor
enum MessageContentRegistry {
    static func make(_ model: MessageContentModel) -> any MessageContentView {
        switch model {
        case .text, .unsupported:
            return TextMessageContentView()
        case .system:
            return SystemMessageContentView()
        case .image:
            return ImageMessageContentView()
        case .sticker:
            return StickerMessageContentView()
        case .voice:
            return VoiceMessageContentView()
        case .video:
            return VideoMessageContentView()
        case .file:
            return FileMessageContentView()
        }
    }
}
