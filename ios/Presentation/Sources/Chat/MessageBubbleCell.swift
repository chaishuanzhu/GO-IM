import UIKit
import Kingfisher
import Domain

/// Messages-style bubble: text / image / voice / video / file + group system notices.
final class MessageBubbleCell: UITableViewCell {
    static let reuseID = "MessageBubbleCell"

    private let bubbleView = UIView()
    private let bodyLabel = UILabel()
    private let metaLabel = UILabel()
    private let imageViewBubble = UIImageView()
    private let mediaIcon = UIImageView()
    private let statusLabel = UILabel()
    private let stack = UIStackView()
    private let bubbleRow = UIStackView()
    private let statusAccessory = UIView()
    private let activityView = UIActivityIndicatorView(style: .medium)
    private let failButton = UIButton(type: .system)
    private let contentStack = UIStackView()
    private let mediaRow = UIStackView()

    private var leadingConstraint: NSLayoutConstraint!
    private var trailingConstraint: NSLayoutConstraint!
    private var bubbleMaxWidthConstraint: NSLayoutConstraint!

    /// Image is NOT in a UIStackView — fixed W×H pinned to bubble, so table
    /// frame animations cannot stretch it via stack `.fill`.
    private var imageWidthConstraint: NSLayoutConstraint!
    private var imageHeightConstraint: NSLayoutConstraint!
    private var imageTopConstraint: NSLayoutConstraint!
    private var imageLeadingConstraint: NSLayoutConstraint!
    private var imageBottomConstraint: NSLayoutConstraint!
    private var imageTrailingConstraint: NSLayoutConstraint!

    private var textStackConstraints: [NSLayoutConstraint] = []

    private var openURL: URL?
    private var boundImageFileId: String?
    var onOpenURL: ((URL) -> Void)?
    var onRetry: (() -> Void)?

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
        bubbleView.isUserInteractionEnabled = true
        bubbleView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(bubbleTapped)))

        bodyLabel.numberOfLines = 0
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.setContentHuggingPriority(.required, for: .horizontal)
        bodyLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

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

        imageViewBubble.contentMode = .scaleAspectFill
        imageViewBubble.clipsToBounds = true
        // Bubble already clips with cornerRadius — no inset, no second radius on the image.
        imageViewBubble.layer.cornerRadius = 0
        imageViewBubble.isHidden = true
        imageViewBubble.backgroundColor = .tertiarySystemFill
        imageViewBubble.translatesAutoresizingMaskIntoConstraints = false

        mediaIcon.contentMode = .scaleAspectFit
        mediaIcon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)
        mediaIcon.setContentHuggingPriority(.required, for: .horizontal)

        mediaRow.axis = .horizontal
        mediaRow.spacing = 10
        mediaRow.alignment = .center
        mediaRow.addArrangedSubview(mediaIcon)
        mediaRow.addArrangedSubview(bodyLabel)

        contentStack.axis = .vertical
        contentStack.spacing = 6
        contentStack.alignment = .leading
        contentStack.addArrangedSubview(mediaRow)
        contentStack.translatesAutoresizingMaskIntoConstraints = false

        // Image is a direct subview of bubble — never an arrangedSubview.
        bubbleView.addSubview(imageViewBubble)
        bubbleView.addSubview(contentStack)

        imageTopConstraint = imageViewBubble.topAnchor.constraint(equalTo: bubbleView.topAnchor)
        imageLeadingConstraint = imageViewBubble.leadingAnchor.constraint(equalTo: bubbleView.leadingAnchor)
        imageWidthConstraint = imageViewBubble.widthAnchor.constraint(equalToConstant: 180)
        imageHeightConstraint = imageViewBubble.heightAnchor.constraint(equalToConstant: 180)
        imageBottomConstraint = bubbleView.bottomAnchor.constraint(equalTo: imageViewBubble.bottomAnchor)
        imageTrailingConstraint = bubbleView.trailingAnchor.constraint(equalTo: imageViewBubble.trailingAnchor)

        textStackConstraints = [
            contentStack.topAnchor.constraint(equalTo: bubbleView.topAnchor, constant: 10),
            contentStack.leadingAnchor.constraint(equalTo: bubbleView.leadingAnchor, constant: 12),
            contentStack.trailingAnchor.constraint(equalTo: bubbleView.trailingAnchor, constant: -12),
            contentStack.bottomAnchor.constraint(equalTo: bubbleView.bottomAnchor, constant: -10),
        ]
        NSLayoutConstraint.activate(textStackConstraints)

        bubbleRow.axis = .horizontal
        bubbleRow.spacing = 6
        bubbleRow.alignment = .bottom
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

        // Bottom pin is high but not required — if UITableView forces a taller
        // frame during composer animation, we prefer not crushing bubble content.
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
            mediaIcon.widthAnchor.constraint(equalToConstant: 28),
            mediaIcon.heightAnchor.constraint(equalToConstant: 28),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard imageViewBubble.isHidden else { return }
        let marginWidth = contentView.layoutMarginsGuide.layoutFrame.width
        guard marginWidth > 1 else { return }
        let maxText = max(0, marginWidth * GOIMStyle.bubbleMaxWidthRatio - 24 - 38)
        if abs(bodyLabel.preferredMaxLayoutWidth - maxText) > 0.5 {
            bodyLabel.preferredMaxLayoutWidth = maxText
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        imageViewBubble.kf.cancelDownloadTask()
        // Keep bitmap until configure replaces it (avoids blank frame on local→remote handoff).
        boundImageFileId = nil
        mediaIcon.image = nil
        bodyLabel.text = nil
        statusLabel.text = nil
        statusLabel.isHidden = true
        metaLabel.text = nil
        openURL = nil
        onOpenURL = nil
        onRetry = nil
        hideSendAccessory()
    }

    func configure(message: Message, fileURL: ((String, Bool) -> URL?)?, onOpen: ((URL) -> Void)? = nil) {
        onOpenURL = onOpen
        openURL = nil
        let outgoing = message.isOutgoing

        stack.alignment = outgoing ? .trailing : .leading
        leadingConstraint.isActive = !outgoing
        trailingConstraint.isActive = outgoing

        if let notice = parseSystemNotice(message.content), message.msgType == .text {
            configureSystemNotice(notice, outgoing: outgoing)
            return
        }

        bubbleView.backgroundColor = outgoing ? GOIMStyle.outgoingBubble : GOIMStyle.incomingBubble
        bodyLabel.textColor = outgoing ? GOIMStyle.outgoingText : GOIMStyle.incomingText
        mediaIcon.tintColor = outgoing ? .white : .label

        metaLabel.isHidden = false
        metaLabel.text = outgoing
            ? GOIMFormat.messageTime(message.timestampMs)
            : "\(message.fromUID) · \(GOIMFormat.messageTime(message.timestampMs))"
        metaLabel.textAlignment = outgoing ? .right : .left

        applyStatus(message.status, outgoing: outgoing)

        switch message.msgType {
        case .image:
            configureImage(message.content, fileURL: fileURL)
        case .voice:
            configureVoice(message.content, fileURL: fileURL, outgoing: outgoing)
        case .video:
            configureVideo(message.content, fileURL: fileURL, outgoing: outgoing)
        case .file:
            configureFile(message.content, fileURL: fileURL, outgoing: outgoing)
        case .text:
            configureText(message.content)
        case .unsupported:
            configureText(MsgType.unsupportedPlaceholder)
        }
    }

    // MARK: - Mode switching

    private func showImageContent(size: CGSize) {
        NSLayoutConstraint.deactivate(textStackConstraints)
        contentStack.isHidden = true

        imageViewBubble.isHidden = false
        imageWidthConstraint.constant = size.width
        imageHeightConstraint.constant = size.height
        NSLayoutConstraint.activate([
            imageTopConstraint,
            imageLeadingConstraint,
            imageWidthConstraint,
            imageHeightConstraint,
            imageBottomConstraint,
            imageTrailingConstraint,
        ])
    }

    private func showTextContent() {
        NSLayoutConstraint.deactivate([
            imageTopConstraint,
            imageLeadingConstraint,
            imageWidthConstraint,
            imageHeightConstraint,
            imageBottomConstraint,
            imageTrailingConstraint,
        ])
        imageViewBubble.isHidden = true
        // Don't nil image here during mode switch mid-configure; clear when leaving image msgs.
        contentStack.isHidden = false
        NSLayoutConstraint.activate(textStackConstraints)
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

    private func configureSystemNotice(_ text: String, outgoing: Bool) {
        metaLabel.isHidden = true
        statusLabel.isHidden = true
        hideSendAccessory()
        showTextContent()
        mediaIcon.isHidden = true
        mediaIcon.image = nil
        bodyLabel.text = text
        bodyLabel.textColor = .secondaryLabel
        bodyLabel.font = .preferredFont(forTextStyle: .footnote)
        bubbleView.backgroundColor = .tertiarySystemFill
        stack.alignment = .center
        leadingConstraint.isActive = true
        trailingConstraint.isActive = true
        openURL = nil
    }

    private func configureText(_ text: String) {
        imageViewBubble.image = nil
        boundImageFileId = nil
        showTextContent()
        mediaIcon.isHidden = true
        mediaIcon.image = nil
        bodyLabel.isHidden = false
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        bodyLabel.text = text
        openURL = nil
    }

    private func configureImage(_ content: String, fileURL: ((String, Bool) -> URL?)?) {
        guard let meta = parseFileMeta(content) else {
            imageViewBubble.image = nil
            boundImageFileId = nil
            configureText("[图片]")
            return
        }
        // Prefer message JSON width/height; scale into bubble box.
        let size = bubbleImageSize(width: meta.width, height: meta.height)
        showImageContent(size: size)
        let thumb = fileURL?(meta.fileId, true)
        let full = fileURL?(meta.fileId, false)
        openURL = full

        // Same file already on screen (e.g. status-only refresh) — skip reload.
        if boundImageFileId == meta.fileId, imageViewBubble.image != nil {
            return
        }

        let url = thumb ?? full
        if let url {
            // Sync paint from memory cache / local file before any async fetch.
            if let cached = ImageCache.default.retrieveImageInMemoryCache(forKey: url.cacheKey) {
                imageViewBubble.image = cached
            } else if url.isFileURL, let data = try? Data(contentsOf: url), let img = UIImage(data: data) {
                imageViewBubble.image = img
            } else if boundImageFileId != nil {
                // Different remote image while scrolling — clear stale bitmap.
                imageViewBubble.image = nil
            }
            imageViewBubble.kf.setImage(
                with: url,
                options: [
                    .keepCurrentImageWhileLoading,
                    .transition(.none),
                ]
            )
            boundImageFileId = meta.fileId
        } else {
            imageViewBubble.image = nil
            boundImageFileId = nil
        }
    }

    private func configureVoice(_ content: String, fileURL: ((String, Bool) -> URL?)?, outgoing: Bool) {
        let meta = parseFileMeta(content)
        showTextContent()
        mediaIcon.isHidden = false
        mediaIcon.image = UIImage(systemName: "waveform")
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        let sec = meta?.duration ?? 0
        bodyLabel.text = sec > 0 ? "语音 \(sec)\"" : "语音消息"
        openURL = meta.flatMap { fileURL?($0.fileId, false) }
    }

    private func configureVideo(_ content: String, fileURL: ((String, Bool) -> URL?)?, outgoing: Bool) {
        let meta = parseFileMeta(content)
        showTextContent()
        mediaIcon.isHidden = false
        mediaIcon.image = UIImage(systemName: "play.rectangle.fill")
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        var parts: [String] = ["视频"]
        if let d = meta?.duration, d > 0 { parts.append("\(d)\"") }
        if let name = meta?.name, !name.isEmpty { parts.append(name) }
        bodyLabel.text = parts.joined(separator: " · ")
        openURL = meta.flatMap { fileURL?($0.fileId, false) }
    }

    private func configureFile(_ content: String, fileURL: ((String, Bool) -> URL?)?, outgoing: Bool) {
        let meta = parseFileMeta(content)
        if let mime = meta?.mime, mime.hasPrefix("image/") {
            configureImage(content, fileURL: fileURL)
            return
        }
        if let mime = meta?.mime, mime.hasPrefix("audio/") {
            configureVoice(content, fileURL: fileURL, outgoing: outgoing)
            return
        }
        if let mime = meta?.mime, mime.hasPrefix("video/") {
            configureVideo(content, fileURL: fileURL, outgoing: outgoing)
            return
        }
        showTextContent()
        mediaIcon.isHidden = false
        mediaIcon.image = UIImage(systemName: "doc.fill")
        bodyLabel.font = .preferredFont(forTextStyle: .body)
        let name = meta?.name ?? "文件"
        let sizeText = meta?.size.map { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) } ?? ""
        bodyLabel.text = sizeText.isEmpty ? name : "\(name)\n\(sizeText)"
        openURL = meta.flatMap { fileURL?($0.fileId, false) }
    }

    @objc private func bubbleTapped() {
        guard let openURL else { return }
        onOpenURL?(openURL)
    }

    @objc private func retryTapped() {
        onRetry?()
    }

    /// Display size from message `width`/`height` (pixels), fitted into max box.
    private func bubbleImageSize(width: Int?, height: Int?) -> CGSize {
        let maxW: CGFloat = 220
        let maxH: CGFloat = 260
        let minSide: CGFloat = 120
        guard let w = width, let h = height, w > 0, h > 0 else {
            return CGSize(width: 180, height: 180)
        }
        let ratio = CGFloat(w) / CGFloat(h)
        var outW = min(maxW, CGFloat(w))
        var outH = outW / ratio
        if outH > maxH {
            outH = maxH
            outW = outH * ratio
        }
        if outW < minSide, outH < minSide {
            if ratio >= 1 {
                outW = minSide
                outH = minSide / ratio
            } else {
                outH = minSide
                outW = minSide * ratio
            }
        }
        return CGSize(width: outW.rounded(), height: outH.rounded())
    }

    private struct FileMetaJSON: Decodable {
        let fileId: String
        let name: String?
        let size: Int64?
        let mime: String?
        let width: Int?
        let height: Int?
        let duration: Int?

        enum CodingKeys: String, CodingKey {
            case fileId = "file_id"
            case name, size, mime, width, height, duration
            case thumbWidth = "thumb_width"
            case thumbHeight = "thumb_height"
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            if let s = try? c.decode(String.self, forKey: .fileId) {
                fileId = s
            } else if let n = try? c.decode(Int64.self, forKey: .fileId) {
                fileId = String(n)
            } else {
                throw DecodingError.dataCorruptedError(forKey: .fileId, in: c, debugDescription: "file_id missing")
            }
            name = try? c.decode(String.self, forKey: .name)
            if let s = try? c.decode(Int64.self, forKey: .size) {
                size = s
            } else if let s = try? c.decode(Int.self, forKey: .size) {
                size = Int64(s)
            } else {
                size = nil
            }
            mime = try? c.decode(String.self, forKey: .mime)
            // Prefer full width/height; fall back to thumb_* from upload response.
            let w = (try? c.decode(Int.self, forKey: .width))
                ?? (try? c.decode(Int.self, forKey: .thumbWidth))
            let h = (try? c.decode(Int.self, forKey: .height))
                ?? (try? c.decode(Int.self, forKey: .thumbHeight))
            width = (w ?? 0) > 0 ? w : nil
            height = (h ?? 0) > 0 ? h : nil
            if let d = try? c.decode(Int.self, forKey: .duration) {
                duration = d
            } else if let d = try? c.decode(Double.self, forKey: .duration) {
                duration = Int(d.rounded())
            } else {
                duration = nil
            }
        }
    }

    private func parseFileMeta(_ content: String) -> FileMetaJSON? {
        guard let data = content.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(FileMetaJSON.self, from: data)
    }

    private func parseSystemNotice(_ content: String) -> String? {
        guard let data = content.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = obj["type"] as? String else { return nil }
        let uid = obj["uid"] as? String ?? ""
        switch type {
        case "member_joined": return "\(uid) 加入了群聊"
        case "member_left": return "\(uid) 离开了群聊"
        default: return nil
        }
    }
}
