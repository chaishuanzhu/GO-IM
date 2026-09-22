import UIKit
import Photos
import PhotosUI
import UniformTypeIdentifiers
import Domain

/// Routes chat page intents: composer, pickers, bubble actions, preview.
@MainActor
final class ChatEventRouter: NSObject {
    private let env: AppEnvironment
    private let viewModel: ChatViewModel
    private weak var composerHost: ChatComposerHosting?
    private weak var presentationHost: ChatPresentationHosting?

    private var pickerMode: PickerMode = .album
    private enum PickerMode { case album, video }

    /// Stable bubble actions — retry looks up by id at tap time via `retryHandler`.
    private(set) lazy var bubbleActions: MessageContentActions = makeBubbleActions()

    init(env: AppEnvironment, viewModel: ChatViewModel) {
        self.env = env
        self.viewModel = viewModel
        super.init()
    }

    func bindHosts(composerHost: ChatComposerHosting, presentationHost: ChatPresentationHosting) {
        self.composerHost = composerHost
        self.presentationHost = presentationHost
    }

    func handle(_ action: ChatUIAction) {
        switch action {
        case let .sendText(text):
            viewModel.draft = text
            Task { await viewModel.sendText() }

        case let .sendSticker(sticker):
            Task { await viewModel.sendSticker(sticker) }

        case let .sendVoice(data, duration):
            Task {
                await viewModel.sendAttachment(
                    data: data,
                    fileName: "voice.m4a",
                    mime: "audio/mp4",
                    duration: duration
                )
            }

        case let .retry(messageId):
            Task { await viewModel.retryMessage(id: messageId) }

        case let .preview(item):
            guard let host = presentationHost as? UIViewController else { return }
            MediaPreview.present(
                item,
                from: host,
                loadSticker: { [weak self] ref in
                    await self?.env.stickers.imageData(for: ref)
                },
                files: env.files
            )

        case let .playVoice(url, duration):
            VoicePlayer.shared.toggle(url: url, estimatedDuration: duration)

        case .openMentionPicker:
            openMentionPicker()

        case .requestCamera:
            requestCamera()

        case .requestAlbum:
            presentMediaPicker(mode: .album)

        case .requestVideo:
            presentMediaPicker(mode: .video)

        case .requestFile:
            requestFile()

        case let .confirmImages(assets, extras, original):
            confirmImages(assets: assets, extras: extras, original: original)

        case let .voiceFailed(message):
            presentationHost?.presentError(title: "语音", message: message)

        case let .accessoryChanged(mode):
            presentationHost?.composerAccessoryChanged(mode)

        case let .presentError(title, message):
            presentationHost?.presentError(title: title, message: message)

        case .openGroupInfo:
            let conv = viewModel.conversation
            guard conv.chatType == .group else { return }
            presentationHost?.pushGroupInfo(groupId: conv.peerOrGroupId, groupName: conv.title)
        }
    }

    func bubbleActions(for messageId: String) -> MessageContentActions {
        var actions = bubbleActions
        actions.onRetry = { [weak self] in
            self?.handle(.retry(messageId: messageId))
        }
        return actions
    }

    // MARK: - Private

    private func makeBubbleActions() -> MessageContentActions {
        MessageContentActions(
            onRetry: nil,
            onPreview: { [weak self] item in
                self?.handle(.preview(item))
            },
            onPlayVoice: { [weak self] url, duration in
                self?.handle(.playVoice(url, duration: duration))
            },
            loadSticker: { [weak self] ref in
                await self?.env.stickers.imageData(for: ref)
            }
        )
    }

    private func openMentionPicker() {
        let picker = MentionPickerViewController(env: env, conversation: viewModel.conversation)
        picker.onPick = { [weak self] uid in
            self?.composerHost?.insertMention(uid)
        }
        let nav = UINavigationController(rootViewController: picker)
        presentationHost?.presentHosted(nav, animated: true)
    }

    private func requestCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            presentationHost?.presentError(title: "相机", message: "当前设备无法拍照")
            return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = self
        picker.allowsEditing = false
        presentationHost?.presentHosted(picker, animated: true)
    }

    private func requestFile() {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        presentationHost?.presentHosted(picker, animated: true)
    }

    private func presentMediaPicker(mode: PickerMode) {
        pickerMode = mode
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = mode == .album ? .images : .videos
        config.selectionLimit = mode == .album ? 9 : 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        presentationHost?.presentHosted(picker, animated: true)
    }

    private func confirmImages(assets: [PHAsset], extras: [UIImage], original: Bool) {
        composerHost?.dismissAccessory()
        Task {
            var index = 0
            func nextName() -> String {
                index += 1
                return "photo-\(index).jpg"
            }

            for image in extras {
                await viewModel.sendImage(image, fileName: nextName(), original: original)
            }

            var hadAsset = false
            for asset in assets {
                if let image = await ChatMediaLoader.loadUIImage(from: asset) {
                    hadAsset = true
                    await viewModel.sendImage(image, fileName: nextName(), original: original)
                }
            }

            if extras.isEmpty && !hadAsset {
                presentationHost?.presentError(title: "发送图片失败", message: "未能读取所选图片")
            }
        }
    }
}

// MARK: - ChatComposerBarDelegate

extension ChatEventRouter: ChatComposerBarDelegate {
    func composerBar(_ bar: ChatComposerBar, didSendText text: String) {
        handle(.sendText(text))
    }

    func composerBar(_ bar: ChatComposerBar, didSelectSticker sticker: StickerRef) {
        handle(.sendSticker(sticker))
    }

    func composerBarDidTapMention(_ bar: ChatComposerBar) {
        handle(.openMentionPicker)
    }

    func composerBar(_ bar: ChatComposerBar, didFinishVoice data: Data, duration: Int) {
        handle(.sendVoice(data, duration: duration))
    }

    func composerBarDidRequestCamera(_ bar: ChatComposerBar) {
        handle(.requestCamera)
    }

    func composerBarDidRequestAlbum(_ bar: ChatComposerBar) {
        handle(.requestAlbum)
    }

    func composerBarDidRequestVideo(_ bar: ChatComposerBar) {
        handle(.requestVideo)
    }

    func composerBarDidRequestFile(_ bar: ChatComposerBar) {
        handle(.requestFile)
    }

    func composerBar(
        _ bar: ChatComposerBar,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    ) {
        handle(.confirmImages(assets: assets, extras: extraImages, original: sendOriginal))
    }

    func composerBar(_ bar: ChatComposerBar, voiceFailed message: String) {
        handle(.voiceFailed(message))
    }

    func composerBar(_ bar: ChatComposerBar, accessoryChanged mode: ChatComposerAccessory) {
        handle(.accessoryChanged(mode))
    }
}

// MARK: - System pickers

extension ChatEventRouter: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return }
        switch pickerMode {
        case .album:
            Task {
                for result in results {
                    if let image = try? await ChatMediaLoader.loadUIImage(from: result.itemProvider) {
                        composerHost?.appendPickedImage(image)
                    }
                }
            }
        case .video:
            guard let provider = results.first?.itemProvider else { return }
            composerHost?.dismissAccessory()
            Task {
                do {
                    let (data, name, mime, duration) = try await ChatMediaLoader.loadVideo(from: provider)
                    await viewModel.sendAttachment(data: data, fileName: name, mime: mime, duration: duration)
                } catch {
                    presentationHost?.presentError(title: "选媒体失败", message: error.localizedDescription)
                }
            }
        }
    }
}

extension ChatEventRouter: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        picker.dismiss(animated: true)
        if let image = info[.originalImage] as? UIImage {
            composerHost?.appendPickedImage(image)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
}

extension ChatEventRouter: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        composerHost?.dismissAccessory()
        Task {
            do {
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                    ?? "application/octet-stream"
                await viewModel.sendAttachment(data: data, fileName: url.lastPathComponent, mime: mime)
            } catch {
                presentationHost?.presentError(title: "读取文件失败", message: error.localizedDescription)
            }
        }
    }
}
