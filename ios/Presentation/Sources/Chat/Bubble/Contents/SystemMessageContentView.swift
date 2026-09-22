import UIKit

@MainActor
final class SystemMessageContentView: UIView, MessageContentView {
    static let reuseKey = "system"

    private let bodyLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        bodyLabel.numberOfLines = 0
        bodyLabel.font = .preferredFont(forTextStyle: .footnote)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.textAlignment = .center
        bodyLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(bodyLabel)
        NSLayoutConstraint.activate([
            bodyLabel.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            bodyLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            bodyLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            bodyLabel.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
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
        if case let .system(text) = model {
            bodyLabel.text = text
        } else {
            bodyLabel.text = nil
        }
    }
}
