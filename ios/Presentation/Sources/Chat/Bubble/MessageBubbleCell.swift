import UIKit
import Domain

/// Thin bubble chrome: alignment, meta, send status, and a pluggable content host.
///
/// Content types live under `Bubble/Contents/`. See `MessageContentView` for how to
/// add a new message type without changing this shell.
@MainActor
final class MessageBubbleCell: UITableViewCell {
    static let reuseID = "MessageBubbleCell"

    private let bubbleView = UIView()
    private let contentHost = UIView()
    private let metaLabel = UILabel()
    private let statusLabel = UILabel()
    private let stack = UIStackView()
    private let bubbleRow = UIStackView()
    private let statusAccessory = UIView()
    private let activityView = UIActivityIndicatorView(style: .medium)
    private let failButton = UIButton(type: .system)

    private var leadingConstraint: NSLayoutConstraint!
    private var trailingConstraint: NSLayoutConstraint!
    private var bubbleMaxWidthConstraint: NSLayoutConstraint!

    private var currentContent: (any MessageContentView)?
    private var currentKey: String?
    private var actions = MessageContentActions()

    var onRetry: (() -> Void)? {
        didSet { actions.onRetry = onRetry }
    }

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        bubbleView.layer.cornerRadius = GOIMStyle.bubbleCorner
        bubbleView.layer.cornerCurve = .continuous
        bubbleView.clipsToBounds = true
        bubbleView.translatesAutoresizingMaskIntoConstraints = false
        bubbleView.setContentHuggingPriority(.required, for: .horizontal)
        bubbleView.setContentHuggingPriority(.required, for: .vertical)
        bubbleView.setContentCompressionResistancePriority(.required, for: .horizontal)
        bubbleView.setContentCompressionResistancePriority(.required, for: .vertical)

        contentHost.translatesAutoresizingMaskIntoConstraints = false
        contentHost.setContentHuggingPriority(.required, for: .horizontal)
        contentHost.setContentHuggingPriority(.required, for: .vertical)
        contentHost.setContentCompressionResistancePriority(.required, for: .horizontal)
        contentHost.setContentCompressionResistancePriority(.required, for: .vertical)
        bubbleView.addSubview(contentHost)
        NSLayoutConstraint.activate([
            contentHost.topAnchor.constraint(equalTo: bubbleView.topAnchor),
            contentHost.leadingAnchor.constraint(equalTo: bubbleView.leadingAnchor),
            contentHost.trailingAnchor.constraint(equalTo: bubbleView.trailingAnchor),
            contentHost.bottomAnchor.constraint(equalTo: bubbleView.bottomAnchor),
        ])

        metaLabel.font = .preferredFont(forTextStyle: .caption2)
        metaLabel.adjustsFontForContentSizeCategory = true
        metaLabel.textColor = .secondaryLabel
        metaLabel.numberOfLines = 1
        metaLabel.lineBreakMode = .byTruncatingTail
        metaLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)

        statusLabel.font = .preferredFont(forTextStyle: .caption2)
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = .secondaryLabel
        statusLabel.isHidden = true

        activityView.hidesWhenStopped = true
        activityView.translatesAutoresizingMaskIntoConstraints = false

        var failConfig = UIButton.Configuration.plain()
        failConfig.image = UIImage(systemName: "exclamationmark.circle.fill")
        failConfig.baseForegroundColor = .systemRed
        failConfig.contentInsets = .zero
        failButton.configuration = failConfig
        failButton.accessibilityLabel = "发送失败，点击重试"
        failButton.addTarget(self, action: #selector(retryTapped), for: .touchUpInside)
        failButton.translatesAutoresizingMaskIntoConstraints = false
        failButton.isHidden = true

        statusAccessory.translatesAutoresizingMaskIntoConstraints = false
        statusAccessory.addSubview(activityView)
        statusAccessory.addSubview(failButton)
        NSLayoutConstraint.activate([
            statusAccessory.widthAnchor.constraint(equalToConstant: 22),
            statusAccessory.heightAnchor.constraint(equalToConstant: 22),
            activityView.centerXAnchor.constraint(equalTo: statusAccessory.centerXAnchor),
            activityView.centerYAnchor.constraint(equalTo: statusAccessory.centerYAnchor),
            failButton.centerXAnchor.constraint(equalTo: statusAccessory.centerXAnchor),
            failButton.centerYAnchor.constraint(equalTo: statusAccessory.centerYAnchor),
            failButton.widthAnchor.constraint(equalToConstant: 22),
            failButton.heightAnchor.constraint(equalToConstant: 22),
        ])

        bubbleRow.axis = .horizontal
        bubbleRow.spacing = 6
        bubbleRow.alignment = .bottom
        bubbleRow.distribution = .fill
        bubbleRow.setContentHuggingPriority(.required, for: .horizontal)
        bubbleRow.addArrangedSubview(statusAccessory)
        bubbleRow.addArrangedSubview(bubbleView)

        stack.axis = .vertical
        stack.spacing = 4
        stack.alignment = .leading
        stack.addArrangedSubview(metaLabel)
        stack.addArrangedSubview(bubbleRow)
        stack.addArrangedSubview(statusLabel)
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.setContentHuggingPriority(.required, for: .horizontal)
        stack.setContentHuggingPriority(.required, for: .vertical)
        contentView.addSubview(stack)

        leadingConstraint = stack.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor)
        trailingConstraint = stack.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor)
        trailingConstraint.isActive = false
        bubbleMaxWidthConstraint = bubbleView.widthAnchor.constraint(
            lessThanOrEqualTo: contentView.layoutMarginsGuide.widthAnchor,
            multiplier: GOIMStyle.bubbleMaxWidthRatio
        )

        let bottomPin = stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4)
        bottomPin.priority = UILayoutPriority(999)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4),
            bottomPin,
            leadingConstraint,
            bubbleMaxWidthConstraint,
            metaLabel.widthAnchor.constraint(
                lessThanOrEqualTo: contentView.layoutMarginsGuide.widthAnchor,
                multiplier: GOIMStyle.bubbleMaxWidthRatio
            ),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        refreshTextPreferredMaxWidth()
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        currentContent?.prepareForReuse()
        metaLabel.text = nil
        statusLabel.text = nil
        statusLabel.isHidden = true
        hideSendAccessory()
        actions = MessageContentActions()
        onRetry = nil
    }

    func configure(vm: MessageBubbleViewModel, actions: MessageContentActions) {
        var merged = actions
        merged.onRetry = onRetry ?? actions.onRetry
        self.actions = merged

        switch vm.alignment {
        case .leading:
            stack.alignment = .leading
            leadingConstraint.isActive = true
            trailingConstraint.isActive = false
        case .trailing:
            stack.alignment = .trailing
            leadingConstraint.isActive = false
            trailingConstraint.isActive = true
        case .center:
            stack.alignment = .center
            leadingConstraint.isActive = true
            trailingConstraint.isActive = true
        }

        if let meta = vm.metaText {
            metaLabel.isHidden = false
            metaLabel.text = meta
            metaLabel.textAlignment = vm.metaTextAlignment
        } else {
            metaLabel.isHidden = true
            metaLabel.text = nil
        }

        applyBubbleStyle(vm.bubbleStyle)
        applyStatus(vm.status, outgoing: vm.isOutgoing)
        installContent(vm.content)
        applyContent(vm)
    }

    private func applyBubbleStyle(_ style: BubbleStyle) {
        switch style {
        case .outgoing:
            bubbleView.backgroundColor = GOIMStyle.outgoingBubble
        case .incoming:
            bubbleView.backgroundColor = GOIMStyle.incomingBubble
        case .clear:
            bubbleView.backgroundColor = .clear
        case .system:
            bubbleView.backgroundColor = .tertiarySystemFill
        }
    }

    private func chromeTokens(for vm: MessageBubbleViewModel) -> MessageChromeTokens {
        switch vm.bubbleStyle {
        case .outgoing:
            return MessageChromeTokens(bodyColor: GOIMStyle.outgoingText, iconTint: .white)
        case .incoming, .clear:
            return MessageChromeTokens(bodyColor: GOIMStyle.incomingText, iconTint: .label)
        case .system:
            return MessageChromeTokens(bodyColor: .secondaryLabel, iconTint: .secondaryLabel)
        }
    }

    private func installContent(_ model: MessageContentModel) {
        let key = model.reuseKey
        if currentKey == key, currentContent != nil {
            return
        }
        currentContent?.prepareForReuse()
        currentContent?.removeFromSuperview()
        let view = MessageContentRegistry.make(model)
        view.translatesAutoresizingMaskIntoConstraints = false
        contentHost.addSubview(view)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: contentHost.topAnchor),
            view.leadingAnchor.constraint(equalTo: contentHost.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: contentHost.trailingAnchor),
            view.bottomAnchor.constraint(equalTo: contentHost.bottomAnchor),
        ])
        currentContent = view
        currentKey = key
    }

    private func applyContent(_ vm: MessageBubbleViewModel) {
        let chrome = chromeTokens(for: vm)
        refreshTextPreferredMaxWidth()
        if let sticker = currentContent as? StickerMessageContentView {
            sticker.setLoadFailedHandler { [weak self] in
                self?.fallbackStickerFailure(outgoing: vm.isOutgoing)
            }
        }
        currentContent?.apply(vm.content, chrome: chrome, actions: actions)
    }

    /// Keep multiline label wrapping in sync with the cell's real margin width.
    private func refreshTextPreferredMaxWidth() {
        guard let text = currentContent as? TextMessageContentView else { return }
        let marginWidth = contentView.layoutMarginsGuide.layoutFrame.width
        let bubbleMax: CGFloat
        if marginWidth > 1 {
            bubbleMax = marginWidth * GOIMStyle.bubbleMaxWidthRatio
        } else {
            bubbleMax = UIScreen.main.bounds.width * GOIMStyle.bubbleMaxWidthRatio
        }
        // Pre-refactor used `- 24 - 38` (padding + icon column). Text-only has no icon.
        text.updatePreferredMaxLayoutWidth(max(0, bubbleMax - 24))
    }

    private func fallbackStickerFailure(outgoing: Bool) {
        applyBubbleStyle(outgoing ? .outgoing : .incoming)
        let model = MessageContentModel.text("[表情]")
        installContent(model)
        let chrome = MessageChromeTokens(
            bodyColor: outgoing ? GOIMStyle.outgoingText : GOIMStyle.incomingText,
            iconTint: outgoing ? .white : .label
        )
        currentContent?.apply(model, chrome: chrome, actions: actions)
    }

    private func applyStatus(_ status: MessageStatus, outgoing: Bool) {
        statusLabel.isHidden = true
        statusLabel.text = nil
        guard outgoing else {
            hideSendAccessory()
            return
        }
        switch status {
        case .sending:
            statusAccessory.isHidden = false
            failButton.isHidden = true
            activityView.startAnimating()
        case .failed:
            statusAccessory.isHidden = false
            activityView.stopAnimating()
            failButton.isHidden = false
        case .recalled:
            hideSendAccessory()
            statusLabel.isHidden = false
            statusLabel.text = "已撤回"
        case .sent:
            hideSendAccessory()
        }
    }

    private func hideSendAccessory() {
        activityView.stopAnimating()
        failButton.isHidden = true
        statusAccessory.isHidden = true
    }

    @objc private func retryTapped() {
        (onRetry ?? actions.onRetry)?()
    }
}
