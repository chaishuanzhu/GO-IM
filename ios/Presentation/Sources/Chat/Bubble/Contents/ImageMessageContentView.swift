import UIKit
import Kingfisher

@MainActor
final class ImageMessageContentView: UIView, MessageContentView {
    static let reuseKey = "image"

    private let imageView = AnimatedImageView()
    private var widthConstraint: NSLayoutConstraint!
    private var heightConstraint: NSLayoutConstraint!
    private var boundFileId: String?
    private var actions = MessageContentActions()
    private var fullURL: URL?
    private var thumbURL: URL?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.layer.cornerRadius = 0
        imageView.backgroundColor = .tertiarySystemFill
        imageView.autoPlayAnimatedImage = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.isUserInteractionEnabled = true
        imageView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        addSubview(imageView)
        widthConstraint = imageView.widthAnchor.constraint(equalToConstant: 180)
        heightConstraint = imageView.heightAnchor.constraint(equalToConstant: 180)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: topAnchor),
            imageView.leadingAnchor.constraint(equalTo: leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthConstraint,
            heightConstraint,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForReuse() {
        imageView.kf.cancelDownloadTask()
        actions = MessageContentActions()
        fullURL = nil
        thumbURL = nil
    }

    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    ) {
        self.actions = actions
        guard case let .image(content) = model else { return }
        fullURL = content.fullURL
        thumbURL = content.thumbURL
        widthConstraint.constant = content.displaySize.width
        heightConstraint.constant = content.displaySize.height
        imageView.contentMode = .scaleAspectFill
        imageView.backgroundColor = .tertiarySystemFill

        if boundFileId == content.fileId, imageView.image != nil {
            return
        }

        let url = content.thumbURL ?? content.fullURL
        if let url {
            if let cached = ImageCache.default.retrieveImageInMemoryCache(forKey: url.cacheKey) {
                imageView.image = cached
            } else if url.isFileURL, let data = try? Data(contentsOf: url), let img = UIImage(data: data) {
                imageView.image = img
            } else if boundFileId != nil {
                imageView.image = nil
            }
            imageView.kf.setImage(
                with: url,
                options: [
                    .keepCurrentImageWhileLoading,
                    .transition(.none),
                ]
            )
            boundFileId = content.fileId
        } else {
            imageView.image = nil
            boundFileId = nil
        }
    }

    @objc private func tapped() {
        actions.onPreview?(.image(fullURL: fullURL ?? thumbURL, placeholder: imageView.image))
    }
}
