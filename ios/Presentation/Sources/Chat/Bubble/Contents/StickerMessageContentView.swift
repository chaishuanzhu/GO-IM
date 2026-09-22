import UIKit
import Domain
import Kingfisher

@MainActor
final class StickerMessageContentView: UIView, MessageContentView {
    static let reuseKey = "sticker"

    private let imageView = AnimatedImageView()
    private var widthConstraint: NSLayoutConstraint!
    private var heightConstraint: NSLayoutConstraint!
    private var boundKey: String?
    private var actions = MessageContentActions()
    private var ref: StickerRef?
    private var onLoadFailed: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.backgroundColor = .clear
        imageView.autoPlayAnimatedImage = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.isUserInteractionEnabled = true
        imageView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        addSubview(imageView)
        widthConstraint = imageView.widthAnchor.constraint(equalToConstant: 140)
        heightConstraint = imageView.heightAnchor.constraint(equalToConstant: 140)
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
        actions = MessageContentActions()
        ref = nil
        onLoadFailed = nil
    }

    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    ) {
        self.actions = actions
        guard case let .sticker(content) = model else { return }
        ref = content.ref
        widthConstraint.constant = content.displaySize.width
        heightConstraint.constant = content.displaySize.height
        imageView.contentMode = .scaleAspectFit
        imageView.backgroundColor = .clear

        if boundKey == content.bindKey, imageView.image != nil {
            if !imageView.isAnimating {
                imageView.startAnimating()
            }
            return
        }

        let switching = boundKey != content.bindKey
        boundKey = content.bindKey
        if switching {
            imageView.image = nil
        }

        if let cached = StickerImageCache.shared.image(for: content.bindKey) {
            imageView.image = cached
            imageView.startAnimating()
            return
        }

        guard let loadImage = actions.loadSticker else { return }
        let bindKey = content.bindKey
        let stickerRef = content.ref
        Task {
            let data = await loadImage(stickerRef)
            await MainActor.run {
                guard self.boundKey == bindKey else { return }
                if let data, let image = StickerImageCache.shared.image(for: bindKey, data: data) {
                    self.imageView.image = image
                    self.imageView.startAnimating()
                } else if self.imageView.image == nil {
                    self.onLoadFailed?()
                }
            }
        }
    }

    /// Shell can swap to a text fallback when sticker bytes fail to load.
    func setLoadFailedHandler(_ handler: (() -> Void)?) {
        onLoadFailed = handler
    }

    @objc private func tapped() {
        guard let ref else { return }
        actions.onPreview?(.sticker(ref: ref, placeholder: imageView.image))
    }
}
