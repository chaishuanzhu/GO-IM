import UIKit
import Photos
import Domain

/// Page-level user intents for the chat screen.
enum ChatUIAction {
    case sendText(String)
    case sendSticker(StickerRef)
    case sendVoice(Data, duration: Int)
    case retry(messageId: String)
    case preview(MediaPreviewItem)
    case playVoice(URL, duration: Int)
    case openMentionPicker
    case requestCamera
    case requestAlbum
    case requestVideo
    case requestFile
    case confirmImages(assets: [PHAsset], extras: [UIImage], original: Bool)
    case voiceFailed(String)
    case accessoryChanged(ChatComposerAccessory)
    case presentError(title: String, message: String)
    case openGroupInfo
}

@MainActor
protocol ChatComposerHosting: AnyObject {
    var accessory: ChatComposerAccessory { get }
    func dismissAccessory()
    func appendPickedImage(_ image: UIImage)
    func insertMention(_ uid: String)
}

@MainActor
protocol ChatPresentationHosting: AnyObject {
    func presentHosted(_ viewController: UIViewController, animated: Bool)
    func presentError(title: String, message: String)
    func composerAccessoryChanged(_ mode: ChatComposerAccessory)
    func pushGroupInfo(groupId: String, groupName: String)
}
