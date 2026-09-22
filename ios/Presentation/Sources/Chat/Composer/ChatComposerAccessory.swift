import UIKit
import Photos
import Domain

/// Accessory mode for the iOS-26 style chat dock.
enum ChatComposerAccessory: Equatable {
    case none
    case emoji
    case voice
    case image
    case more
}

@MainActor
protocol ChatComposerBarDelegate: AnyObject {
    func composerBar(_ bar: ChatComposerBar, didSendText text: String)
    func composerBarDidTapMention(_ bar: ChatComposerBar)
    func composerBar(_ bar: ChatComposerBar, didFinishVoice data: Data, duration: Int)
    func composerBarDidRequestCamera(_ bar: ChatComposerBar)
    func composerBarDidRequestAlbum(_ bar: ChatComposerBar)
    func composerBarDidRequestVideo(_ bar: ChatComposerBar)
    func composerBarDidRequestFile(_ bar: ChatComposerBar)
    func composerBar(
        _ bar: ChatComposerBar,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    )
    func composerBar(_ bar: ChatComposerBar, voiceFailed message: String)
    func composerBar(_ bar: ChatComposerBar, accessoryChanged mode: ChatComposerAccessory)
    func composerBar(_ bar: ChatComposerBar, didSelectSticker sticker: StickerRef)
}

/// Pluggable composer accessory body.
///
/// ## Extending a mode
/// 1. Add `ChatComposerAccessory` case
/// 2. Implement `ChatComposerAccessoryPanel`
/// 3. Register in `ChatComposerAccessoryRegistry.make`
/// 4. Wire a tool button in `ChatComposerBar` if needed
///
/// Do not put mode UI into the shell.
@MainActor
protocol ChatComposerAccessoryPanel: UIView {
    static var mode: ChatComposerAccessory { get }
    func prepareForDisplay()
    func prepareForHide()
    func applyBottomSafeInset(_ inset: CGFloat)
}

extension ChatComposerAccessoryPanel {
    func applyBottomSafeInset(_ inset: CGFloat) {}
}

/// Actions panels use to talk back to the shell (shell then fans out to the VC).
@MainActor
struct ChatComposerPanelActions {
    var insertEmoji: ((String) -> Void)?
    var sendText: (() -> Void)?
    var textHasContent: (() -> Bool)?
    var selectSticker: ((StickerRef) -> Void)?
    var voiceFinished: ((Data, Int) -> Void)?
    var voiceFailed: ((String) -> Void)?
    var voiceDidSend: (() -> Void)?
    var requestCamera: (() -> Void)?
    var requestAlbum: (() -> Void)?
    var confirmAssets: (([PHAsset], [UIImage], Bool) -> Void)?
    var requestVideo: (() -> Void)?
    var requestFile: (() -> Void)?
}

@MainActor
enum ChatComposerAccessoryRegistry {
    static func make(
        _ mode: ChatComposerAccessory,
        actions: ChatComposerPanelActions
    ) -> (any ChatComposerAccessoryPanel)? {
        switch mode {
        case .none:
            return nil
        case .emoji:
            return EmojiAccessoryPanel(actions: actions)
        case .voice:
            return VoiceAccessoryPanel(actions: actions)
        case .image:
            return ChatImageAccessoryPanel(actions: actions)
        case .more:
            return MoreAccessoryPanel(actions: actions)
        }
    }
}
