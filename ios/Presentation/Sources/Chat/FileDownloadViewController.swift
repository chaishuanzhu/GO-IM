import UIKit
import QuickLook
import Domain

/// Shows download progress for a chat file. Dismissing keeps the download running;
/// reopening attaches to the same job and does not restart.
@MainActor
final class FileDownloadViewController: UIViewController {
    private let fileId: String
    private let fileName: String
    private let mime: String?
    private let files: FileRepository

    private let iconView = UIImageView()
    private let nameLabel = UILabel()
    private let statusLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .default)
    private let percentLabel = UILabel()
    private var observeTask: Task<Void, Never>?
    private var didPresentPreview = false

    init(fileId: String, fileName: String, mime: String?, files: FileRepository) {
        self.fileId = fileId
        self.fileName = fileName
        self.mime = mime
        self.files = files
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .pageSheet
        if let sheet = sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    deinit {
        observeTask?.cancel()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        title = "下载文件"
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            systemItem: .close,
            primaryAction: UIAction { [weak self] _ in self?.dismiss(animated: true) }
        )

        iconView.image = UIImage(systemName: "arrow.down.doc.fill")
        iconView.tintColor = .systemBlue
        iconView.contentMode = .scaleAspectFit
        iconView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 48, weight: .medium)
        iconView.translatesAutoresizingMaskIntoConstraints = false

        nameLabel.text = fileName.isEmpty ? "文件" : fileName
        nameLabel.font = .preferredFont(forTextStyle: .headline)
        nameLabel.textAlignment = .center
        nameLabel.numberOfLines = 3
        nameLabel.translatesAutoresizingMaskIntoConstraints = false

        statusLabel.text = "准备下载…"
        statusLabel.font = .preferredFont(forTextStyle: .subheadline)
        statusLabel.textColor = .secondaryLabel
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false

        progressView.translatesAutoresizingMaskIntoConstraints = false
        progressView.progress = 0

        percentLabel.text = "0%"
        percentLabel.font = .monospacedDigitSystemFont(ofSize: 15, weight: .medium)
        percentLabel.textAlignment = .center
        percentLabel.translatesAutoresizingMaskIntoConstraints = false

        let stack = UIStackView(arrangedSubviews: [
            iconView, nameLabel, statusLabel, progressView, percentLabel,
        ])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = 16
        stack.setCustomSpacing(8, after: nameLabel)
        stack.setCustomSpacing(24, after: statusLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            iconView.heightAnchor.constraint(equalToConstant: 64),
            progressView.heightAnchor.constraint(equalToConstant: 4),
            stack.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -20),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let local = files.localFileIfPresent(fileId: fileId) {
            openPreview(local)
            return
        }
        startObservingIfNeeded()
    }

    private func startObservingIfNeeded() {
        guard observeTask == nil else { return }
        statusLabel.text = "正在下载…"
        observeTask = Task { [weak self] in
            guard let self else { return }
            let stream = self.files.observeFileDownload(
                fileId: self.fileId,
                suggestedName: self.fileName
            )
            for await event in stream {
                if Task.isCancelled { return }
                await MainActor.run {
                    self.handle(event)
                }
            }
        }
    }

    private func handle(_ event: FileDownloadEvent) {
        switch event {
        case let .progress(value):
            progressView.setProgress(Float(value), animated: true)
            percentLabel.text = "\(Int((value * 100).rounded()))%"
            statusLabel.text = "正在下载…"
            statusLabel.textColor = .secondaryLabel
        case let .completed(url):
            progressView.setProgress(1, animated: true)
            percentLabel.text = "100%"
            statusLabel.text = "下载完成"
            openPreview(url)
        case let .failed(message):
            statusLabel.text = message
            statusLabel.textColor = .systemRed
            percentLabel.text = "失败"
        }
    }

    private func openPreview(_ url: URL) {
        guard !didPresentPreview else { return }
        didPresentPreview = true
        observeTask?.cancel()
        observeTask = nil

        var previewURL = url
        if url.pathExtension.isEmpty,
           !QLPreviewController.canPreview(url as NSURL),
           let renamed = try? Self.copyForQuickLook(url, displayName: fileName),
           QLPreviewController.canPreview(renamed as NSURL) {
            previewURL = renamed
        }

        if QLPreviewController.canPreview(previewURL as NSURL) {
            // Replace download sheet with Quick Look.
            dismiss(animated: true) {
                let presenter = FilePreviewPresenter(fileURL: previewURL)
                MediaPreview.retainFilePresenter(presenter)
                if let host = Self.topPresenter() {
                    presenter.present(from: host)
                }
            }
        } else {
            let page = UnsupportedFileViewController(fileName: fileName, mime: mime, localURL: url)
            navigationController?.setViewControllers([page], animated: true)
        }
    }

    private static func copyForQuickLook(_ source: URL, displayName: String) throws -> URL {
        let safe = displayName.isEmpty ? source.lastPathComponent : displayName
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-ql-\(UUID().uuidString)-\(safe)", isDirectory: false)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: source, to: dest)
        return dest
    }

    private static func topPresenter() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let presented = top?.presentedViewController {
            top = presented
        }
        return top
    }
}
