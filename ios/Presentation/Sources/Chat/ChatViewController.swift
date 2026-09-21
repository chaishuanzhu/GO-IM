import UIKit
import Photos
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation
import Domain

@MainActor
public final class ChatViewController: UIViewController, UITableViewDataSource, UITableViewDelegate,
    PHPickerViewControllerDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate,
    UIDocumentPickerDelegate, ChatComposerBarDelegate
{
    private let env: AppEnvironment
    private let viewModel: ChatViewModel

    private let tableView = UITableView(frame: .zero, style: .plain)
    private let composer = ChatComposerBar()
    /// Docked to the physical bottom; keyboard lifts via constant (avoids safe-area gap).
    private var composerBottomConstraint: NSLayoutConstraint!

    private var pickerMode: PickerMode = .album
    private enum PickerMode { case album, video }

    /// First open / first data fill should land on the latest message after layout.
    private var pendingScrollToBottom = false
    private var hasScrolledToBottomOnce = false

    public init(env: AppEnvironment, conversation: Conversation) {
        self.env = env
        viewModel = ChatViewModel(env: env, conversation: conversation)
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = viewModel.conversation.title
        navigationItem.largeTitleDisplayMode = .never
        view.backgroundColor = .systemGroupedBackground
        configureLayout()
        configureBindings()
        composer.configureStickers(env.stickers)
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        viewModel.start()
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        viewModel.stop()
        composer.dismissAccessory()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !hasScrolledToBottomOnce, viewModel.messages.count > 0 {
            scrollToBottom(animated: false, force: true)
        } else {
            flushPendingScrollToBottom()
        }
    }

    private func configureBindings() {
        viewModel.onChange = { [weak self] in
            guard let self else { return }
            let previousCount = self.tableView.numberOfRows(inSection: 0)
            let newCount = self.viewModel.messages.count
            let prepended = self.viewModel.didPrependHistory
            let oldOffset = self.tableView.contentOffset.y
            let oldHeight = self.tableView.contentSize.height
            let isInitialFill = previousCount == 0 && newCount > 0

            self.tableView.reloadData()

            if prepended, newCount > previousCount, previousCount > 0 {
                self.tableView.layoutIfNeeded()
                let delta = self.tableView.contentSize.height - oldHeight
                self.tableView.contentOffset.y = max(0, oldOffset + delta)
            } else if isInitialFill {
                // Entering chat: always pin to latest once layout is ready.
                self.scrollToBottom(animated: false, force: true)
            } else if newCount > previousCount {
                self.scrollToBottom(animated: true, force: false)
            } else if self.isNearBottom {
                self.scrollToBottom(animated: false, force: false)
            }

            if let err = self.viewModel.errorMessage {
                self.presentError(title: "发送失败", message: err)
                self.viewModel.errorMessage = nil
            }
        }
    }

    private var isNearBottom: Bool {
        let visible = tableView.bounds.height
        guard visible > 0 else { return true }
        let offsetY = tableView.contentOffset.y
        let contentH = tableView.contentSize.height
        return offsetY + visible >= contentH - 120
    }

    private func configureLayout() {
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(MessageBubbleCell.self, forCellReuseIdentifier: MessageBubbleCell.reuseID)
        tableView.separatorStyle = .none
        tableView.keyboardDismissMode = .interactive
        tableView.backgroundColor = .systemGroupedBackground
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 120
        tableView.translatesAutoresizingMaskIntoConstraints = false
        // Stable table frame: composer overlays; inset keeps last messages visible.
        // Animating tableView.bottom with the accessory stretches self-sizing image cells.
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.contentInset = UIEdgeInsets(top: 6, left: 0, bottom: 12, right: 0)

        composer.delegate = self
        composer.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(tableView)
        view.addSubview(composer)

        // Always pin to the physical bottom so home-indicator strip is the same
        // chrome material as the toolbar (no separate fill / color mismatch).
        composerBottomConstraint = composer.bottomAnchor.constraint(equalTo: view.bottomAnchor)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            composer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            composer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            composerBottomConstraint,
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissInputs))
        tap.cancelsTouchesInView = false
        tableView.addGestureRecognizer(tap)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardFrameWillChange(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
    }

    @objc private func keyboardFrameWillChange(_ note: Notification) {
        guard
            let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
            let duration = note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double
        else { return }
        let converted = view.convert(frame, from: nil)
        let overlap = max(0, view.bounds.maxY - converted.minY)
        // Don't lift when accessory panel is open (composer already fills to bottom).
        let lift = composer.accessory == .none ? overlap : 0
        composerBottomConstraint.constant = -lift
        let curveRaw = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? Int) ?? 7
        let options = UIView.AnimationOptions(rawValue: UInt(curveRaw << 16))
        UIView.animate(withDuration: duration, delay: 0, options: options) {
            self.view.layoutIfNeeded()
            self.updateTableInsetsForComposer()
        }
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateTableInsetsForComposer()
        flushPendingScrollToBottom()
    }

    private func updateTableInsetsForComposer() {
        let cover = max(0, view.bounds.maxY - composer.frame.minY)
        let bottom = cover + 8
        var inset = tableView.contentInset
        guard abs(inset.bottom - bottom) > 0.5 else { return }
        inset.bottom = bottom
        tableView.contentInset = inset
        tableView.verticalScrollIndicatorInsets.bottom = bottom
        // Inset change can leave the latest message covered — re-pin if we intended to be at bottom.
        if hasScrolledToBottomOnce, isNearBottom || pendingScrollToBottom {
            pendingScrollToBottom = true
        }
    }

    @objc private func dismissInputs() {
        view.endEditing(true)
        composer.dismissAccessory()
    }

    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        viewModel.messages.count
    }

    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: MessageBubbleCell.reuseID, for: indexPath) as! MessageBubbleCell
        let m = viewModel.messages[indexPath.row]
        cell.configure(message: m, fileURL: { [weak self] fileId, thumb in
            self?.env.files.fileURL(fileId: fileId, thumb: thumb)
        }, stickerImage: { [weak self] ref in
            await self?.env.stickers.imageData(for: ref)
        }, onOpen: { [weak self] url in
            UIApplication.shared.open(url)
        })
        cell.onRetry = { [weak self] in
            let messageId = m.id
            Task { await self?.viewModel.retryMessage(id: messageId) }
        }
        return cell
    }

    public func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === tableView else { return }
        if scrollView.contentOffset.y < 48 {
            Task { await viewModel.loadOlderIfNeeded() }
        }
    }

    // MARK: - ChatComposerBarDelegate

    func composerBar(_ bar: ChatComposerBar, didSendText text: String) {
        viewModel.draft = text
        Task { await viewModel.sendText() }
    }

    func composerBar(_ bar: ChatComposerBar, didSelectSticker sticker: StickerRef) {
        Task { await viewModel.sendSticker(sticker) }
    }

    func composerBarDidTapMention(_ bar: ChatComposerBar) {
        let picker = MentionPickerViewController(env: env, conversation: viewModel.conversation)
        picker.onPick = { [weak self] uid in
            self?.composer.insertMention(uid)
        }
        let nav = UINavigationController(rootViewController: picker)
        present(nav, animated: true)
    }

    func composerBar(_ bar: ChatComposerBar, didFinishVoice data: Data, duration: Int) {
        Task {
            await viewModel.sendAttachment(data: data, fileName: "voice.m4a", mime: "audio/mp4", duration: duration)
        }
    }

    func composerBarDidRequestCamera(_ bar: ChatComposerBar) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            presentError(title: "相机", message: "当前设备无法拍照")
            return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = self
        picker.allowsEditing = false
        present(picker, animated: true)
    }

    func composerBarDidRequestAlbum(_ bar: ChatComposerBar) {
        presentMediaPicker(mode: .album)
    }

    func composerBarDidRequestVideo(_ bar: ChatComposerBar) {
        presentMediaPicker(mode: .video)
    }

    func composerBarDidRequestFile(_ bar: ChatComposerBar) {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        present(picker, animated: true)
    }

    func composerBar(
        _ bar: ChatComposerBar,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    ) {
        composer.dismissAccessory()
        // Fire each image as soon as it's ready — don't wait for all loads + full compress.
        Task {
            var index = 0
            func nextName() -> String {
                index += 1
                return "photo-\(index).jpg"
            }

            for image in extraImages {
                await viewModel.sendImage(image, fileName: nextName(), original: sendOriginal)
            }

            var hadAsset = false
            for asset in assets {
                if let image = await loadUIImage(from: asset) {
                    hadAsset = true
                    await viewModel.sendImage(image, fileName: nextName(), original: sendOriginal)
                }
            }

            if extraImages.isEmpty && !hadAsset {
                presentError(title: "发送图片失败", message: "未能读取所选图片")
            }
        }
    }

    func composerBar(_ bar: ChatComposerBar, voiceFailed message: String) {
        presentError(title: "语音", message: message)
    }

    func composerBar(_ bar: ChatComposerBar, accessoryChanged mode: ChatComposerAccessory) {
        // Composer stays pinned to the physical bottom; accessory grows upward inside it.
        if mode != .none {
            composerBottomConstraint.constant = 0
        }
        UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseInOut]) {
            self.composer.layoutIfNeeded()
            self.view.layoutIfNeeded()
            self.updateTableInsetsForComposer()
        } completion: { _ in
            self.updateTableInsetsForComposer()
            if mode != .none {
                self.scrollToBottom(animated: true, force: false)
            }
        }
    }

    // MARK: - Pickers

    private func presentMediaPicker(mode: PickerMode) {
        pickerMode = mode
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = mode == .album ? .images : .videos
        config.selectionLimit = mode == .album ? 9 : 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return }
        switch pickerMode {
        case .album:
            Task {
                for result in results {
                    if let image = try? await loadUIImage(from: result.itemProvider) {
                        composer.appendPickedImage(image)
                    }
                }
            }
        case .video:
            guard let provider = results.first?.itemProvider else { return }
            composer.dismissAccessory()
            Task {
                do {
                    let (data, name, mime, duration) = try await loadVideo(from: provider)
                    await viewModel.sendAttachment(data: data, fileName: name, mime: mime, duration: duration)
                } catch {
                    presentError(title: "选媒体失败", message: error.localizedDescription)
                }
            }
        }
    }

    public func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        picker.dismiss(animated: true)
        if let image = info[.originalImage] as? UIImage {
            composer.appendPickedImage(image)
        }
    }

    public func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }

    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else { return }
        composer.dismissAccessory()
        Task {
            do {
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                let data = try Data(contentsOf: url)
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
                await viewModel.sendAttachment(data: data, fileName: url.lastPathComponent, mime: mime)
            } catch {
                presentError(title: "读取文件失败", message: error.localizedDescription)
            }
        }
    }

    // MARK: - Loaders

    private func loadUIImage(from asset: PHAsset) async -> UIImage? {
        await withCheckedContinuation { cont in
            let opts = PHImageRequestOptions()
            opts.deliveryMode = .highQualityFormat
            opts.resizeMode = .none
            opts.isNetworkAccessAllowed = true
            var resumed = false
            PHImageManager.default().requestImage(
                for: asset,
                targetSize: PHImageManagerMaximumSize,
                contentMode: .aspectFit,
                options: opts
            ) { image, info in
                let cancelled = (info?[PHImageCancelledKey] as? Bool) ?? false
                let failed = info?[PHImageErrorKey] != nil
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if cancelled || failed {
                    guard !resumed else { return }
                    resumed = true
                    cont.resume(returning: nil)
                    return
                }
                if degraded { return }
                guard !resumed else { return }
                resumed = true
                cont.resume(returning: image)
            }
        }
    }

    private func loadUIImage(from provider: NSItemProvider) async throws -> UIImage {
        if provider.canLoadObject(ofClass: UIImage.self) {
            return try await withCheckedThrowingContinuation { cont in
                provider.loadObject(ofClass: UIImage.self) { object, error in
                    if let error { cont.resume(throwing: error) }
                    else if let image = object as? UIImage { cont.resume(returning: image) }
                    else { cont.resume(throwing: DomainError.invalidState("无法读取图片对象")) }
                }
            }
        }
        let data = try await loadImageData(from: provider)
        guard let image = UIImage(data: data) else {
            throw DomainError.invalidState("图片解码失败")
        }
        return image
    }

    private func loadImageData(from provider: NSItemProvider) async throws -> Data {
        if provider.canLoadObject(ofClass: UIImage.self) {
            let image: UIImage = try await withCheckedThrowingContinuation { cont in
                provider.loadObject(ofClass: UIImage.self) { object, error in
                    if let error { cont.resume(throwing: error) }
                    else if let image = object as? UIImage { cont.resume(returning: image) }
                    else { cont.resume(throwing: DomainError.invalidState("无法读取图片对象")) }
                }
            }
            if let data = image.jpegData(compressionQuality: 0.8) { return data }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return try await withCheckedThrowingContinuation { cont in
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, error in
                    if let error { cont.resume(throwing: error) }
                    else if let data, !data.isEmpty { cont.resume(returning: data) }
                    else { cont.resume(throwing: DomainError.invalidState("图片数据为空")) }
                }
            }
        }
        throw DomainError.invalidState("当前照片无法加载，请换一张重试")
    }

    private func loadVideo(from provider: NSItemProvider) async throws -> (Data, String, String, Int) {
        let typeId = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            ? UTType.movie.identifier : "public.movie"
        let url: URL = try await withCheckedThrowingContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: typeId) { url, error in
                if let error { cont.resume(throwing: error); return }
                guard let url else {
                    cont.resume(throwing: DomainError.invalidState("视频数据为空"))
                    return
                }
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent("goim-video-\(UUID().uuidString)-\(url.lastPathComponent)")
                do {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.copyItem(at: url, to: dest)
                    cont.resume(returning: dest)
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
        defer { try? FileManager.default.removeItem(at: url) }
        let data = try Data(contentsOf: url)
        let duration = Int(ceil(CMTimeGetSeconds(AVURLAsset(url: url).duration)))
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/mp4"
        return (data, url.lastPathComponent, mime, max(duration, 0))
    }

    private func presentError(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    private func scrollToBottom(animated: Bool, force: Bool) {
        let count = viewModel.messages.count
        guard count > 0 else {
            if force { pendingScrollToBottom = true }
            return
        }
        tableView.layoutIfNeeded()
        guard tableView.bounds.height > 1, tableView.window != nil else {
            pendingScrollToBottom = true
            return
        }
        let indexPath = IndexPath(row: count - 1, section: 0)
        guard tableView.numberOfRows(inSection: 0) > indexPath.row else {
            pendingScrollToBottom = true
            return
        }
        tableView.scrollToRow(at: indexPath, at: .bottom, animated: animated)
        hasScrolledToBottomOnce = true
        pendingScrollToBottom = false
    }

    private func flushPendingScrollToBottom() {
        guard pendingScrollToBottom else { return }
        scrollToBottom(animated: false, force: true)
    }
}
