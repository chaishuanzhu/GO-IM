import UIKit
import QuickLook
import AVKit
import Kingfisher
import Domain

/// What to show when the user taps a media bubble.
enum MediaPreviewItem {
    case image(fullURL: URL?, placeholder: UIImage?)
    case sticker(ref: StickerRef, placeholder: UIImage?)
    case video(url: URL?, name: String?, duration: Int?)
    case file(name: String, mime: String?, url: URL?)
}

// MARK: - Image / sticker fullscreen

@MainActor
final class ImagePreviewViewController: UIViewController, UIScrollViewDelegate {
    private let scrollView = UIScrollView()
    private let imageView = AnimatedImageView()
    private let closeButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .large)

    private let fullURL: URL?
    private let placeholder: UIImage?
    private let loadSticker: (() async -> Data?)?

    init(
        fullURL: URL?,
        placeholder: UIImage?,
        loadSticker: (() async -> Data?)? = nil
    ) {
        self.fullURL = fullURL
        self.placeholder = placeholder
        self.loadSticker = loadSticker
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .overFullScreen
        modalTransitionStyle = .crossDissolve
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.96)

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.bouncesZoom = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.contentInsetAdjustmentBehavior = .never
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.autoPlayAnimatedImage = true
        imageView.isUserInteractionEnabled = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(imageView)

        spinner.color = .white
        spinner.hidesWhenStopped = true
        spinner.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(spinner)

        var closeCfg = UIButton.Configuration.plain()
        closeCfg.image = UIImage(systemName: "xmark.circle.fill")
        closeCfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 28, weight: .medium)
        closeCfg.baseForegroundColor = UIColor.white.withAlphaComponent(0.9)
        closeButton.configuration = closeCfg
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        closeButton.accessibilityLabel = "关闭预览"
        view.addSubview(closeButton)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        let singleTap = UITapGestureRecognizer(target: self, action: #selector(close))
        singleTap.require(toFail: doubleTap)
        scrollView.addGestureRecognizer(singleTap)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            imageView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.frameLayoutGuide.heightAnchor),

            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),

            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
        ])

        loadContent()
    }

    private func loadContent() {
        if let placeholder {
            imageView.image = placeholder
            imageView.startAnimating()
        }

        if let loadSticker {
            if imageView.image == nil { spinner.startAnimating() }
            Task {
                let data = await loadSticker()
                await MainActor.run {
                    self.spinner.stopAnimating()
                    if let data,
                       let img = KingfisherWrapper<UIImage>.image(data: data, options: ImageCreatingOptions())
                        ?? UIImage(data: data)
                    {
                        self.imageView.image = img
                        self.imageView.startAnimating()
                    } else if self.imageView.image == nil {
                        self.showLoadFailed()
                    }
                }
            }
            return
        }

        guard let fullURL else {
            if imageView.image == nil { showLoadFailed() }
            return
        }

        if fullURL.isFileURL {
            if let data = try? Data(contentsOf: fullURL),
               let img = KingfisherWrapper<UIImage>.image(data: data, options: ImageCreatingOptions())
                ?? UIImage(data: data)
            {
                imageView.image = img
                imageView.startAnimating()
            } else if imageView.image == nil {
                showLoadFailed()
            }
            return
        }

        if imageView.image == nil { spinner.startAnimating() }
        imageView.kf.setImage(
            with: fullURL,
            placeholder: placeholder,
            options: [.transition(.fade(0.15))]
        ) { [weak self] result in
            self?.spinner.stopAnimating()
            if case .failure = result, self?.imageView.image == nil {
                self?.showLoadFailed()
            } else {
                self?.imageView.startAnimating()
            }
        }
    }

    private func showLoadFailed() {
        let label = UILabel()
        label.text = "无法加载预览"
        label.textColor = .secondaryLabel
        label.font = .preferredFont(forTextStyle: .body)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    @objc private func handleDoubleTap(_ gr: UITapGestureRecognizer) {
        if scrollView.zoomScale > scrollView.minimumZoomScale + 0.01 {
            scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
        } else {
            let point = gr.location(in: imageView)
            let zoom: CGFloat = 2.5
            let size = scrollView.bounds.size
            let w = size.width / zoom
            let h = size.height / zoom
            let rect = CGRect(x: point.x - w / 2, y: point.y - h / 2, width: w, height: h)
            scrollView.zoom(to: rect, animated: true)
        }
    }

    @objc private func close() {
        dismiss(animated: true)
    }
}

// MARK: - Video (AVPlayer)

@MainActor
final class VideoPreviewViewController: UIViewController {
    private let url: URL
    private let titleName: String?
    private let playerVC = AVPlayerViewController()
    private var player: AVPlayer?

    init(url: URL, name: String?) {
        self.url = url
        self.titleName = name
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        title = titleName

        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)

        let p = AVPlayer(url: url)
        player = p
        playerVC.player = p
        playerVC.showsPlaybackControls = true

        addChild(playerVC)
        playerVC.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(playerVC.view)
        playerVC.didMove(toParent: self)

        var closeCfg = UIButton.Configuration.plain()
        closeCfg.image = UIImage(systemName: "xmark.circle.fill")
        closeCfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 28, weight: .medium)
        closeCfg.baseForegroundColor = UIColor.white.withAlphaComponent(0.9)
        let closeButton = UIButton(type: .system)
        closeButton.configuration = closeCfg
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        closeButton.accessibilityLabel = "关闭"
        view.addSubview(closeButton)

        NSLayoutConstraint.activate([
            playerVC.view.topAnchor.constraint(equalTo: view.topAnchor),
            playerVC.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            playerVC.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            playerVC.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -12),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        player?.play()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        player?.pause()
    }

    @objc private func close() {
        player?.pause()
        dismiss(animated: true)
    }
}

// MARK: - Unsupported file page

@MainActor
final class UnsupportedFileViewController: UIViewController {
    private let fileName: String
    private let mime: String?
    private let localURL: URL?

    init(fileName: String, mime: String?, localURL: URL?) {
        self.fileName = fileName
        self.mime = mime
        self.localURL = localURL
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .pageSheet
        if let sheet = sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "文件"

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .close,
            primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) }
        )

        let icon = UIImageView(image: UIImage(systemName: "doc.fill"))
        icon.tintColor = .secondaryLabel
        icon.contentMode = .scaleAspectFit
        icon.translatesAutoresizingMaskIntoConstraints = false
        icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 56, weight: .medium)

        let titleLabel = UILabel()
        titleLabel.text = fileName.isEmpty ? "未知文件" : fileName
        titleLabel.font = .preferredFont(forTextStyle: .title3)
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 3
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        let hint = UILabel()
        hint.text = "不支持应用内打开"
        hint.font = .preferredFont(forTextStyle: .subheadline)
        hint.textColor = .secondaryLabel
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false

        var shareCfg = UIButton.Configuration.filled()
        shareCfg.title = "用其他应用打开"
        shareCfg.image = UIImage(systemName: "square.and.arrow.up")
        shareCfg.imagePadding = 8
        shareCfg.cornerStyle = .large
        let shareButton = UIButton(configuration: shareCfg)
        shareButton.translatesAutoresizingMaskIntoConstraints = false
        shareButton.isEnabled = localURL != nil
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [icon, titleLabel, hint, shareButton])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 16
        stack.setCustomSpacing(8, after: titleLabel)
        stack.setCustomSpacing(28, after: hint)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            icon.widthAnchor.constraint(equalToConstant: 72),
            icon.heightAnchor.constraint(equalToConstant: 72),
            shareButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 200),
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -24),
        ])
    }

    @objc private func shareTapped() {
        guard let localURL else { return }
        let share = UIActivityViewController(activityItems: [localURL], applicationActivities: nil)
        if let pop = share.popoverPresentationController {
            pop.sourceView = view
            pop.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        }
        present(share, animated: true)
    }
}

// MARK: - File Quick Look

final class FilePreviewPresenter: NSObject, QLPreviewControllerDataSource {
    private let fileURL: URL
    private let titleName: String

    init(fileURL: URL, titleName: String) {
        self.fileURL = fileURL
        self.titleName = titleName
    }

    @MainActor
    func present(from host: UIViewController) {
        let ql = QLPreviewController()
        ql.dataSource = self
        ql.title = titleName
        host.present(ql, animated: true)
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> any QLPreviewItem {
        fileURL as NSURL
    }
}

// MARK: - Chat helper

@MainActor
enum MediaPreview {
    /// Retained while a Quick Look sheet is visible (replaced on next preview).
    private static var activeFilePresenter: FilePreviewPresenter?

    static func present(
        _ item: MediaPreviewItem,
        from host: UIViewController,
        loadSticker: ((StickerRef) async -> Data?)? = nil
    ) {
        switch item {
        case let .image(fullURL, placeholder):
            let vc = ImagePreviewViewController(
                fullURL: fullURL,
                placeholder: placeholder
            )
            host.present(vc, animated: true)

        case let .sticker(ref, placeholder):
            let vc = ImagePreviewViewController(
                fullURL: ref.url.flatMap(URL.init(string:)),
                placeholder: placeholder,
                loadSticker: {
                    if let loadSticker { return await loadSticker(ref) }
                    return nil
                }
            )
            host.present(vc, animated: true)

        case let .video(url, name, _):
            guard let url else {
                presentUnsupported(name: name ?? "视频", mime: "video/*", localURL: nil, from: host)
                return
            }
            let vc = VideoPreviewViewController(url: url, name: name)
            host.present(vc, animated: true)

        case let .file(name, mime, url):
            guard let url else {
                presentUnsupported(name: name, mime: mime, localURL: nil, from: host)
                return
            }
            Task { await presentFile(name: name, mime: mime, url: url, from: host) }
        }
    }

    private static func presentFile(name: String, mime: String?, url: URL, from host: UIViewController) async {
        let safeName = name.isEmpty ? "file" : name
        do {
            let local = try await resolveLocalFile(url: url, safeName: safeName, on: host)
            await MainActor.run {
                if QLPreviewController.canPreview(local as NSURL) {
                    let presenter = FilePreviewPresenter(fileURL: local, titleName: safeName)
                    activeFilePresenter = presenter
                    presenter.present(from: host)
                } else {
                    presentUnsupported(name: safeName, mime: mime, localURL: local, from: host)
                }
            }
        } catch {
            await MainActor.run {
                let alert = UIAlertController(
                    title: "加载失败",
                    message: error.localizedDescription,
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: "好", style: .default))
                host.present(alert, animated: true)
            }
        }
    }

    private static func presentUnsupported(name: String, mime: String?, localURL: URL?, from host: UIViewController) {
        let page = UnsupportedFileViewController(fileName: name, mime: mime, localURL: localURL)
        let nav = UINavigationController(rootViewController: page)
        host.present(nav, animated: true)
    }

    private static func resolveLocalFile(url: URL, safeName: String, on host: UIViewController) async throws -> URL {
        if url.isFileURL { return url }

        let overlay = BlockingOverlay(message: "正在加载…")
        await MainActor.run { overlay.show(on: host.view) }
        defer { Task { @MainActor in overlay.hide() } }

        let (temp, response) = try await URLSession.shared.download(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200..<300).contains(status) else {
            throw DomainError.server(status, "download failed")
        }
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-preview-\(UUID().uuidString)-\(safeName)")
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: temp, to: dest)
        return dest
    }
}

@MainActor
private final class BlockingOverlay {
    private let dim = UIView()
    private let spinner = UIActivityIndicatorView(style: .large)
    private let label = UILabel()

    init(message: String) {
        dim.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        dim.translatesAutoresizingMaskIntoConstraints = false
        spinner.color = .white
        spinner.translatesAutoresizingMaskIntoConstraints = false
        label.text = message
        label.textColor = .white
        label.font = .preferredFont(forTextStyle: .footnote)
        label.translatesAutoresizingMaskIntoConstraints = false
    }

    func show(on view: UIView) {
        view.addSubview(dim)
        dim.addSubview(spinner)
        dim.addSubview(label)
        NSLayoutConstraint.activate([
            dim.topAnchor.constraint(equalTo: view.topAnchor),
            dim.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dim.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dim.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            spinner.centerXAnchor.constraint(equalTo: dim.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: dim.centerYAnchor, constant: -12),
            label.topAnchor.constraint(equalTo: spinner.bottomAnchor, constant: 12),
            label.centerXAnchor.constraint(equalTo: dim.centerXAnchor),
        ])
        spinner.startAnimating()
    }

    func hide() {
        spinner.stopAnimating()
        dim.removeFromSuperview()
    }
}
