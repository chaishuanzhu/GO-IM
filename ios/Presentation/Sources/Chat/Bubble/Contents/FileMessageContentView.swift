import UIKit

@MainActor
final class FileMessageContentView: UIView, MessageContentView {
    static let reuseKey = "file"

    private let mediaRow = UIStackView()
    private let mediaIcon = UIImageView()
    private let bodyLabel = UILabel()
    private var content: FileContent?
    private var actions = MessageContentActions()

    override init(frame: CGRect) {
        super.init(frame: frame)
        mediaIcon.contentMode = .scaleAspectFit
        mediaIcon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)
        mediaIcon.setContentHuggingPriority(.required, for: .horizontal)
        mediaIcon.image = UIImage(systemName: "doc.fill")

        bodyLabel.numberOfLines = 0
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true

        mediaRow.axis = .horizontal
        mediaRow.spacing = 10
        mediaRow.alignment = .center
        mediaRow.addArrangedSubview(mediaIcon)
        mediaRow.addArrangedSubview(bodyLabel)
        mediaRow.translatesAutoresizingMaskIntoConstraints = false
        addSubview(mediaRow)

        NSLayoutConstraint.activate([
            mediaRow.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            mediaRow.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            mediaRow.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            mediaRow.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
            mediaIcon.widthAnchor.constraint(equalToConstant: 28),
            mediaIcon.heightAnchor.constraint(equalToConstant: 28),
        ])

        isUserInteractionEnabled = true
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForReuse() {
        content = nil
        bodyLabel.text = nil
        actions = MessageContentActions()
    }

    func apply(
        _ model: MessageContentModel,
        chrome: MessageChromeTokens,
        actions: MessageContentActions
    ) {
        self.actions = actions
        guard case let .file(file) = model else { return }
        content = file
        mediaIcon.tintColor = chrome.iconTint
        bodyLabel.textColor = chrome.bodyColor
        bodyLabel.text = file.title
    }

    @objc private func tapped() {
        guard let content else { return }
        actions.onPreview?(.file(
            name: content.name,
            mime: content.mime,
            url: content.url,
            fileId: content.fileId
        ))
    }
}
