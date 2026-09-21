import UIKit
import QuickLook
import Kingfisher
import Domain

/// What to show when the user taps a media bubble.
enum MediaPreviewItem {
    case image(fullURL: URL?, placeholder: UIImage?)
    case sticker(ref: StickerRef, placeholder: UIImage?)
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

        case let .file(name, _, url):
            guard let url else {
                let alert = UIAlertController(title: "无法预览", message: "文件地址无效", preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "好", style: .default))
                host.present(alert, animated: true)
                return
            }
            Task { await presentFile(name: name, url: url, from: host) }
        }
    }

    private static func presentFile(name: String, url: URL, from host: UIViewController) async {
        let safeName = name.isEmpty ? "file" : name
        do {
            let local: URL
            if url.isFileURL {
                local = url
            } else {
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
                local = dest
            }

            await MainActor.run {
                if QLPreviewController.canPreview(local as NSURL) {
                    let presenter = FilePreviewPresenter(fileURL: local, titleName: safeName)
                    activeFilePresenter = presenter
                    presenter.present(from: host)
                } else {
                    let share = UIActivityViewController(activityItems: [local], applicationActivities: nil)
                    if let pop = share.popoverPresentationController {
                        pop.sourceView = host.view
                        pop.sourceRect = CGRect(x: host.view.bounds.midX, y: host.view.bounds.midY, width: 1, height: 1)
                    }
                    host.present(share, animated: true)
                }
            }
        } catch {
            await MainActor.run {
                let alert = UIAlertController(
                    title: "预览失败",
                    message: error.localizedDescription,
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: "好", style: .default))
                host.present(alert, animated: true)
            }
        }
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
