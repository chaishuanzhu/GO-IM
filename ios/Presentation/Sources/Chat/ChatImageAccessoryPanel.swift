import UIKit
import Photos

@MainActor
protocol ChatImageAccessoryPanelDelegate: AnyObject {
    func imagePanelDidTapCamera(_ panel: ChatImageAccessoryPanel)
    func imagePanelDidTapAlbum(_ panel: ChatImageAccessoryPanel)
    func imagePanel(
        _ panel: ChatImageAccessoryPanel,
        didConfirmAssets assets: [PHAsset],
        extraImages: [UIImage],
        sendOriginal: Bool
    )
}

/// WeChat-style image dock: optional yellow photo-access tip, camera/album, photo strip, send.
@MainActor
final class ChatImageAccessoryPanel: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    weak var delegate: ChatImageAccessoryPanelDelegate?

    private let tipBanner = UIControl()
    private let tipLabel = UILabel()
    private let tipChevron = UIImageView()
    private var tipHeightConstraint: NSLayoutConstraint!
    private var sideTopToTip: NSLayoutConstraint!
    private var sideTopToSelf: NSLayoutConstraint!
    private var collectionTopToTip: NSLayoutConstraint!
    private var collectionTopToSelf: NSLayoutConstraint!

    private let sideStack = UIStackView()
    private let cameraButton = UIButton(type: .system)
    private let albumButton = UIButton(type: .system)
    private let collectionView: UICollectionView
    private let bottomBar = UIView()
    private let originalButton = UIButton(type: .system)
    private let sendButton = UIButton(type: .system)

    private var assets: [PHAsset] = []
    private var selectedIds = Set<String>()
    private var extraImages: [UIImage] = []
    private var sendOriginal = false
    private let maxSelection = 9
    private let imageManager = PHCachingImageManager()
    private var thumbSize = CGSize(width: 160, height: 160)
    private nonisolated(unsafe) var becomeActiveObserver: NSObjectProtocol?

    override init(frame: CGRect) {
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 6
        layout.minimumInteritemSpacing = 6
        layout.sectionInset = UIEdgeInsets(top: 0, left: 8, bottom: 0, right: 12)
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: frame)
        setup()
        becomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.refreshTipAndLibrary()
            }
        }
    }

    deinit {
        if let becomeActiveObserver {
            NotificationCenter.default.removeObserver(becomeActiveObserver)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func reloadLibrary() {
        requestAndFetch()
    }

    func clearSelection() {
        selectedIds.removeAll()
        extraImages.removeAll()
        sendOriginal = false
        updateOriginalChrome()
        updateSendChrome()
        collectionView.reloadData()
    }

    func appendExtraImage(_ image: UIImage) {
        guard extraImages.count + selectedIds.count < maxSelection else { return }
        extraImages.insert(image, at: 0)
        updateSendChrome()
        collectionView.reloadData()
        collectionView.setContentOffset(.zero, animated: true)
    }

    // MARK: - Setup

    private func setup() {
        tipBanner.backgroundColor = UIColor.systemYellow.withAlphaComponent(0.32)
        tipBanner.isHidden = true
        tipBanner.translatesAutoresizingMaskIntoConstraints = false
        tipBanner.addTarget(self, action: #selector(tipBannerTapped), for: .touchUpInside)
        tipBanner.accessibilityTraits = .button

        tipLabel.font = .systemFont(ofSize: 12, weight: .medium)
        tipLabel.textColor = UIColor(red: 0.42, green: 0.30, blue: 0.02, alpha: 1)
        tipLabel.numberOfLines = 1
        tipLabel.lineBreakMode = .byTruncatingTail
        tipLabel.translatesAutoresizingMaskIntoConstraints = false
        tipLabel.isUserInteractionEnabled = false

        tipChevron.image = UIImage(systemName: "chevron.right")
        tipChevron.tintColor = tipLabel.textColor
        tipChevron.contentMode = .scaleAspectFit
        tipChevron.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 11, weight: .semibold)
        tipChevron.translatesAutoresizingMaskIntoConstraints = false
        tipChevron.isUserInteractionEnabled = false

        tipBanner.addSubview(tipLabel)
        tipBanner.addSubview(tipChevron)

        configureSideButton(cameraButton, title: "拍照", symbol: "camera.fill", action: #selector(cameraTapped))
        configureSideButton(albumButton, title: "相册", symbol: "photo.on.rectangle", action: #selector(albumTapped))

        sideStack.axis = .vertical
        sideStack.spacing = 8
        sideStack.distribution = .fillEqually
        sideStack.addArrangedSubview(cameraButton)
        sideStack.addArrangedSubview(albumButton)
        sideStack.translatesAutoresizingMaskIntoConstraints = false

        collectionView.backgroundColor = .clear
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(PhotoThumbCell.self, forCellWithReuseIdentifier: PhotoThumbCell.reuseID)
        collectionView.translatesAutoresizingMaskIntoConstraints = false

        bottomBar.translatesAutoresizingMaskIntoConstraints = false

        var origCfg = UIButton.Configuration.plain()
        origCfg.image = UIImage(systemName: "circle")
        origCfg.title = "原图"
        origCfg.imagePadding = 6
        origCfg.baseForegroundColor = .label
        originalButton.configuration = origCfg
        originalButton.addTarget(self, action: #selector(originalTapped), for: .touchUpInside)
        originalButton.translatesAutoresizingMaskIntoConstraints = false

        var sendCfg = UIButton.Configuration.filled()
        sendCfg.cornerStyle = .capsule
        sendCfg.baseBackgroundColor = .systemBlue
        sendCfg.baseForegroundColor = .white
        sendCfg.title = "发送"
        sendButton.configuration = sendCfg
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        sendButton.translatesAutoresizingMaskIntoConstraints = false
        sendButton.isEnabled = false
        sendButton.alpha = 0.4

        addSubview(tipBanner)
        addSubview(sideStack)
        addSubview(collectionView)
        addSubview(bottomBar)
        bottomBar.addSubview(originalButton)
        bottomBar.addSubview(sendButton)

        tipHeightConstraint = tipBanner.heightAnchor.constraint(equalToConstant: 0)
        sideTopToTip = sideStack.topAnchor.constraint(equalTo: tipBanner.bottomAnchor, constant: 8)
        sideTopToSelf = sideStack.topAnchor.constraint(equalTo: topAnchor, constant: 10)
        collectionTopToTip = collectionView.topAnchor.constraint(equalTo: tipBanner.bottomAnchor, constant: 8)
        collectionTopToSelf = collectionView.topAnchor.constraint(equalTo: topAnchor, constant: 10)
        sideTopToTip.isActive = false
        collectionTopToTip.isActive = false

        NSLayoutConstraint.activate([
            tipBanner.topAnchor.constraint(equalTo: topAnchor),
            tipBanner.leadingAnchor.constraint(equalTo: leadingAnchor),
            tipBanner.trailingAnchor.constraint(equalTo: trailingAnchor),
            tipHeightConstraint,

            tipLabel.leadingAnchor.constraint(equalTo: tipBanner.leadingAnchor, constant: 12),
            tipLabel.centerYAnchor.constraint(equalTo: tipBanner.centerYAnchor),
            tipLabel.trailingAnchor.constraint(lessThanOrEqualTo: tipChevron.leadingAnchor, constant: -6),

            tipChevron.trailingAnchor.constraint(equalTo: tipBanner.trailingAnchor, constant: -10),
            tipChevron.centerYAnchor.constraint(equalTo: tipBanner.centerYAnchor),
            tipChevron.widthAnchor.constraint(equalToConstant: 12),
            tipChevron.heightAnchor.constraint(equalToConstant: 12),

            sideStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            sideTopToSelf,
            sideStack.bottomAnchor.constraint(equalTo: bottomBar.topAnchor, constant: -10),
            sideStack.widthAnchor.constraint(equalToConstant: 72),

            collectionView.leadingAnchor.constraint(equalTo: sideStack.trailingAnchor, constant: 8),
            collectionView.trailingAnchor.constraint(equalTo: trailingAnchor),
            collectionTopToSelf,
            collectionView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor, constant: -10),

            bottomBar.leadingAnchor.constraint(equalTo: leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: 48),

            originalButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 14),
            originalButton.centerYAnchor.constraint(equalTo: bottomBar.centerYAnchor),

            sendButton.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -14),
            sendButton.centerYAnchor.constraint(equalTo: bottomBar.centerYAnchor),
            sendButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 88),
            sendButton.heightAnchor.constraint(equalToConstant: 34),
        ])
    }

    private func configureSideButton(_ button: UIButton, title: String, symbol: String, action: Selector) {
        var cfg = UIButton.Configuration.gray()
        cfg.cornerStyle = .medium
        cfg.image = UIImage(systemName: symbol)
        cfg.title = title
        cfg.imagePlacement = .top
        cfg.imagePadding = 6
        cfg.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)
        cfg.baseForegroundColor = .label
        button.configuration = cfg
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    // MARK: - Library + tip

    private func refreshTipAndLibrary() {
        updatePhotoAccessTip()
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if status == .authorized || status == .limited {
            fetchAssets()
        }
    }

    private func requestAndFetch() {
        updatePhotoAccessTip()
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        switch status {
        case .authorized, .limited:
            fetchAssets()
        case .notDetermined:
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] newStatus in
                Task { @MainActor in
                    self?.updatePhotoAccessTip()
                    if newStatus == .authorized || newStatus == .limited {
                        self?.fetchAssets()
                    } else {
                        self?.assets = []
                        self?.collectionView.reloadData()
                    }
                }
            }
        default:
            assets = []
            collectionView.reloadData()
        }
    }

    private func updatePhotoAccessTip() {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        switch status {
        case .limited:
            setTipVisible(
                true,
                text: "只能访问相册部分照片，点击去设置选择更多"
            )
        case .denied, .restricted:
            setTipVisible(
                true,
                text: "未获得相册权限，点击去设置开启"
            )
        default:
            setTipVisible(false, text: nil)
        }
    }

    private func setTipVisible(_ visible: Bool, text: String?) {
        tipLabel.text = text
        tipBanner.isHidden = !visible
        tipBanner.isAccessibilityElement = visible
        tipBanner.accessibilityLabel = text
        tipHeightConstraint.constant = visible ? 32 : 0
        sideTopToTip.isActive = visible
        sideTopToSelf.isActive = !visible
        collectionTopToTip.isActive = visible
        collectionTopToSelf.isActive = !visible
        setNeedsLayout()
    }

    private func fetchAssets() {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 80
        options.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        let result = PHAsset.fetchAssets(with: options)
        var list: [PHAsset] = []
        list.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in list.append(asset) }
        assets = list
        collectionView.reloadData()
    }

    // MARK: - Actions

    @objc private func tipBannerTapped() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    @objc private func cameraTapped() { delegate?.imagePanelDidTapCamera(self) }
    @objc private func albumTapped() { delegate?.imagePanelDidTapAlbum(self) }

    @objc private func originalTapped() {
        sendOriginal.toggle()
        updateOriginalChrome()
    }

    @objc private func sendTapped() {
        let selected = assets.filter { selectedIds.contains($0.localIdentifier) }
        guard !selected.isEmpty || !extraImages.isEmpty else { return }
        delegate?.imagePanel(self, didConfirmAssets: selected, extraImages: extraImages, sendOriginal: sendOriginal)
    }

    private func updateOriginalChrome() {
        var cfg = originalButton.configuration ?? .plain()
        cfg.image = UIImage(systemName: sendOriginal ? "checkmark.circle.fill" : "circle")
        cfg.baseForegroundColor = sendOriginal ? .systemBlue : .label
        originalButton.configuration = cfg
    }

    private func updateSendChrome() {
        let count = selectedIds.count + extraImages.count
        var cfg = sendButton.configuration ?? .filled()
        cfg.title = count > 0 ? "发送(\(count))" : "发送"
        sendButton.configuration = cfg
        sendButton.isEnabled = count > 0
        sendButton.alpha = count > 0 ? 1 : 0.4
    }

    private func toggleAsset(_ asset: PHAsset) {
        let id = asset.localIdentifier
        if selectedIds.contains(id) {
            selectedIds.remove(id)
        } else {
            guard selectedIds.count + extraImages.count < maxSelection else { return }
            selectedIds.insert(id)
        }
        updateSendChrome()
        if let idx = assets.firstIndex(where: { $0.localIdentifier == id }) {
            collectionView.reloadItems(at: [IndexPath(item: idx + extraImages.count, section: 0)])
        } else {
            collectionView.reloadData()
        }
    }

    private func removeExtra(at index: Int) {
        guard extraImages.indices.contains(index) else { return }
        extraImages.remove(at: index)
        updateSendChrome()
        collectionView.reloadData()
    }

    // MARK: - UICollectionView

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        extraImages.count + assets.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PhotoThumbCell.reuseID, for: indexPath) as! PhotoThumbCell
        if indexPath.item < extraImages.count {
            let image = extraImages[indexPath.item]
            cell.configure(image: image, selected: true, order: indexPath.item + 1)
        } else {
            let asset = assets[indexPath.item - extraImages.count]
            let selected = selectedIds.contains(asset.localIdentifier)
            let order = selected ? selectionOrder(for: asset) : nil
            cell.configurePlaceholder()
            let scale = UIScreen.main.scale
            let target = CGSize(width: thumbSize.width * scale, height: thumbSize.height * scale)
            imageManager.requestImage(for: asset, targetSize: target, contentMode: .aspectFill, options: nil) { image, _ in
                guard let image else { return }
                cell.configure(image: image, selected: selected, order: order)
            }
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if indexPath.item < extraImages.count {
            removeExtra(at: indexPath.item)
        } else {
            toggleAsset(assets[indexPath.item - extraImages.count])
        }
    }

    func collectionView(
        _ collectionView: UICollectionView,
        layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        let h = max(collectionView.bounds.height, 96)
        thumbSize = CGSize(width: h, height: h)
        return thumbSize
    }

    private func selectionOrder(for asset: PHAsset) -> Int? {
        let stripSelected = assets.filter { selectedIds.contains($0.localIdentifier) }
        guard let idx = stripSelected.firstIndex(where: { $0.localIdentifier == asset.localIdentifier }) else {
            return nil
        }
        return extraImages.count + idx + 1
    }
}

// MARK: - Cell

private final class PhotoThumbCell: UICollectionViewCell {
    static let reuseID = "PhotoThumbCell"
    private let imageView = UIImageView()
    private let badge = UILabel()
    private let dim = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 10
        contentView.layer.cornerCurve = .continuous
        contentView.clipsToBounds = true
        contentView.backgroundColor = .tertiarySystemFill

        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false

        dim.backgroundColor = UIColor.black.withAlphaComponent(0.28)
        dim.isHidden = true
        dim.translatesAutoresizingMaskIntoConstraints = false

        badge.font = .systemFont(ofSize: 12, weight: .bold)
        badge.textColor = .white
        badge.textAlignment = .center
        badge.backgroundColor = .systemBlue
        badge.layer.cornerRadius = 11
        badge.clipsToBounds = true
        badge.isHidden = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(imageView)
        contentView.addSubview(dim)
        contentView.addSubview(badge)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            dim.topAnchor.constraint(equalTo: contentView.topAnchor),
            dim.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            dim.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            dim.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            badge.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            badge.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -6),
            badge.widthAnchor.constraint(equalToConstant: 22),
            badge.heightAnchor.constraint(equalToConstant: 22),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func configurePlaceholder() {
        imageView.image = nil
        dim.isHidden = true
        badge.isHidden = true
    }

    func configure(image: UIImage, selected: Bool, order: Int?) {
        imageView.image = image
        dim.isHidden = !selected
        if let order, selected {
            badge.isHidden = false
            badge.text = "\(order)"
        } else {
            badge.isHidden = true
            badge.text = nil
        }
    }
}
