import UIKit

@MainActor
final class TextMessageContentView: UIView, MessageContentView {
    static let reuseKey = "text"

    private let bodyLabel = UILabel()
    /// Max width available for the label itself (bubble max minus horizontal padding).
    private var maxTextWidth: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        setContentHuggingPriority(.required, for: .horizontal)
        setContentHuggingPriority(.required, for: .vertical)
        setContentCompressionResistancePriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .vertical)

        bodyLabel.numberOfLines = 0
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.setContentHuggingPriority(.required, for: .horizontal)
        bodyLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        bodyLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bodyLabel)

        // Leading/top/bottom pin; trailing hugs label so short text keeps a tight bubble.
        let trailing = trailingAnchor.constraint(equalTo: bodyLabel.trailingAnchor, constant: 12)
        trailing.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            bodyLabel.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            bodyLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            bodyLabel.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
            bodyLabel.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -12),
            trailing,
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForReuse() {
        bodyLabel.text = nil
    }

    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    ) {
        let text: String
        switch model {
        case let .text(t), let .unsupported(t):
            text = t
        default:
            text = ""
        }
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.textColor = chrome.bodyColor
        applyPreferredMaxLayoutWidth(resolvedMaxTextWidth())
        bodyLabel.text = text
        invalidateIntrinsicContentSize()
    }

    /// `bubbleMax` is the max bubble width (already × ratio); converts to label text width.
    func setMaxBubbleWidth(_ bubbleMax: CGFloat) {
        maxTextWidth = max(0, bubbleMax - 24)
        applyPreferredMaxLayoutWidth(resolvedMaxTextWidth())
    }

    /// Direct label max width (padding already subtracted), matching pre-refactor layoutSubviews.
    func updatePreferredMaxLayoutWidth(_ textMax: CGFloat) {
        maxTextWidth = max(0, textMax)
        applyPreferredMaxLayoutWidth(maxTextWidth)
    }

    private func resolvedMaxTextWidth() -> CGFloat {
        if maxTextWidth > 1 { return maxTextWidth }
        return max(0, UIScreen.main.bounds.width * GOIMStyle.bubbleMaxWidthRatio - 24)
    }

    private func applyPreferredMaxLayoutWidth(_ width: CGFloat) {
        guard width > 1, abs(bodyLabel.preferredMaxLayoutWidth - width) > 0.5 else { return }
        bodyLabel.preferredMaxLayoutWidth = width
        invalidateIntrinsicContentSize()
    }
}
