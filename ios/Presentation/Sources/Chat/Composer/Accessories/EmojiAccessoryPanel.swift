import UIKit
import Domain

@MainActor
final class EmojiAccessoryPanel: UIView, ChatComposerAccessoryPanel, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    static var mode: ChatComposerAccessory { .emoji }

    private var actions: ChatComposerPanelActions
    private let modeControl = UISegmentedControl(items: ["表情", "贴纸"])
    private let emojiCollection: UICollectionView
    private let sendButton = UIButton(type: .system)
    private var stickerPanel: StickerAccessoryPanel?

    private static let emojis: [String] = [
        "😀", "😂", "🥰", "😍", "🤔", "😎", "😭", "😡",
        "👍", "👎", "👏", "🙏", "🔥", "✨", "🎉", "❤️",
        "🥰", "😊", "🤗", "😴", "🤝", "💪", "🌟", "💯",
        "😅", "🤣", "😘", "😜", "🥺", "😱", "🙄", "😇",
    ]
    private var lastCollectionWidth: CGFloat = 0

    init(actions: ChatComposerPanelActions) {
        self.actions = actions
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 6
        layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 12, left: 16, bottom: 12, right: 16)
        emojiCollection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: .zero)
        setup()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func prepareForDisplay() {
        stickerPanel?.reload()
        updateTabVisibility()
        updateSendEnabled()
    }

    func prepareForHide() {}

    func applyBottomSafeInset(_ inset: CGFloat) {
        let sendClearance: CGFloat = 46
        emojiCollection.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: inset + sendClearance, right: 0)
        emojiCollection.scrollIndicatorInsets = emojiCollection.contentInset
        stickerPanel?.setBottomContentInset(inset + 8)
    }

    func configureStickers(_ repository: StickerRepository) {
        if stickerPanel == nil {
            let panel = StickerAccessoryPanel(stickers: repository)
            panel.translatesAutoresizingMaskIntoConstraints = false
            panel.isHidden = true
            panel.onSelect = { [weak self] ref in
                self?.actions.selectSticker?(ref)
            }
            addSubview(panel)
            NSLayoutConstraint.activate([
                panel.topAnchor.constraint(equalTo: modeControl.bottomAnchor, constant: 4),
                panel.leadingAnchor.constraint(equalTo: leadingAnchor),
                panel.trailingAnchor.constraint(equalTo: trailingAnchor),
                panel.bottomAnchor.constraint(equalTo: bottomAnchor),
            ])
            stickerPanel = panel
            bringSubviewToFront(sendButton)
        }
        stickerPanel?.reload()
        updateTabVisibility()
    }

    func updateSendEnabled() {
        let hasText = actions.textHasContent?() ?? false
        sendButton.isEnabled = hasText
        sendButton.alpha = hasText ? 1 : 0.45
    }

    private func setup() {
        modeControl.selectedSegmentIndex = 0
        modeControl.translatesAutoresizingMaskIntoConstraints = false
        modeControl.addTarget(self, action: #selector(modeChanged), for: .valueChanged)
        addSubview(modeControl)

        emojiCollection.backgroundColor = .clear
        emojiCollection.dataSource = self
        emojiCollection.delegate = self
        emojiCollection.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.reuseID)
        emojiCollection.translatesAutoresizingMaskIntoConstraints = false
        emojiCollection.contentInsetAdjustmentBehavior = .never
        addSubview(emojiCollection)

        var sendCfg = UIButton.Configuration.filled()
        sendCfg.cornerStyle = .capsule
        sendCfg.baseBackgroundColor = .systemBlue
        sendCfg.baseForegroundColor = .white
        sendCfg.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)
        sendCfg.title = "发送"
        sendCfg.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { incoming in
            var out = incoming
            out.font = .systemFont(ofSize: 15, weight: .semibold)
            return out
        }
        sendButton.configuration = sendCfg
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        sendButton.isEnabled = false
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        addSubview(sendButton)

        NSLayoutConstraint.activate([
            modeControl.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            modeControl.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            modeControl.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),

            emojiCollection.topAnchor.constraint(equalTo: modeControl.bottomAnchor, constant: 4),
            emojiCollection.leadingAnchor.constraint(equalTo: leadingAnchor),
            emojiCollection.trailingAnchor.constraint(equalTo: trailingAnchor),
            emojiCollection.bottomAnchor.constraint(equalTo: bottomAnchor),

            sendButton.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            sendButton.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor, constant: -10),
            sendButton.heightAnchor.constraint(equalToConstant: 36),
        ])
    }

    @objc private func modeChanged() {
        updateTabVisibility()
        if modeControl.selectedSegmentIndex == 1 {
            stickerPanel?.reload()
        }
    }

    private func updateTabVisibility() {
        let showStickers = modeControl.selectedSegmentIndex == 1
        emojiCollection.isHidden = showStickers
        stickerPanel?.isHidden = !showStickers
        sendButton.isHidden = showStickers
        if !showStickers {
            updateSendEnabled()
        }
    }

    @objc private func sendTapped() {
        actions.sendText?()
        updateSendEnabled()
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        Self.emojis.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.reuseID, for: indexPath) as! EmojiCell
        cell.label.text = Self.emojis[indexPath.item]
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        actions.insertEmoji?(Self.emojis[indexPath.item])
        updateSendEnabled()
        UISelectionFeedbackGenerator().selectionChanged()
    }

    func collectionView(
        _ collectionView: UICollectionView,
        layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        let cols: CGFloat = 8
        let inset: CGFloat = 32
        let spacing: CGFloat = 6 * (cols - 1)
        var available = collectionView.bounds.width
        if available < 1 {
            available = bounds.width
        }
        if available < 1 {
            available = window?.bounds.width ?? UIScreen.main.bounds.width
        }
        let w = floor((available - inset - spacing) / cols)
        return CGSize(width: max(w, 32), height: max(w, 32))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = emojiCollection.bounds.width
        guard !emojiCollection.isHidden, w > 1, abs(w - lastCollectionWidth) > 0.5 else { return }
        lastCollectionWidth = w
        emojiCollection.collectionViewLayout.invalidateLayout()
    }
}

private final class EmojiCell: UICollectionViewCell {
    static let reuseID = "EmojiCell"
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.font = .systemFont(ofSize: 28)
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }
}
