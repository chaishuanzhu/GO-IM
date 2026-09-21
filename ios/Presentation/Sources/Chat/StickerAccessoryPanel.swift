import UIKit
import Kingfisher
import Domain

/// Sticker grid for the chat composer accessory (pack tabs + recent).
@MainActor
final class StickerAccessoryPanel: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    var onSelect: ((StickerRef) -> Void)?

    private let packControl = UISegmentedControl(items: [])
    private let collection: UICollectionView
    private var packs: [StickerPack] = []
    private var items: [StickerItem] = []
    private var recent: [StickerRef] = []
    private var showingRecent = false
    private let stickers: StickerRepository
    private let emptyLabel = UILabel()

    init(stickers: StickerRepository) {
        self.stickers = stickers
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 8
        layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 8, left: 16, bottom: 12, right: 16)
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init(frame: .zero)
        setup()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    func reload() {
        Task { await reloadAsync() }
    }

    private func setup() {
        backgroundColor = .clear
        packControl.translatesAutoresizingMaskIntoConstraints = false
        packControl.addTarget(self, action: #selector(packChanged), for: .valueChanged)

        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.delegate = self
        collection.register(StickerCell.self, forCellWithReuseIdentifier: StickerCell.reuseID)
        collection.translatesAutoresizingMaskIntoConstraints = false

        emptyLabel.text = "暂无表情包"
        emptyLabel.textColor = .secondaryLabel
        emptyLabel.textAlignment = .center
        emptyLabel.font = .preferredFont(forTextStyle: .footnote)
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        emptyLabel.isHidden = true

        addSubview(packControl)
        addSubview(collection)
        addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            packControl.topAnchor.constraint(equalTo: topAnchor, constant: 8),
            packControl.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            packControl.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),

            collection.topAnchor.constraint(equalTo: packControl.bottomAnchor, constant: 8),
            collection.leadingAnchor.constraint(equalTo: leadingAnchor),
            collection.trailingAnchor.constraint(equalTo: trailingAnchor),
            collection.bottomAnchor.constraint(equalTo: bottomAnchor),

            emptyLabel.centerXAnchor.constraint(equalTo: centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: collection.centerYAnchor),
        ])
    }

    private func reloadAsync() async {
        packs = await stickers.installedPacks()
        recent = await stickers.recentStickers(limit: 20)

        packControl.removeAllSegments()
        var idx = 0
        if !recent.isEmpty {
            packControl.insertSegment(withTitle: "最近", at: idx, animated: false)
            idx += 1
        }
        for pack in packs {
            packControl.insertSegment(withTitle: pack.name, at: idx, animated: false)
            idx += 1
        }
        if packControl.numberOfSegments > 0 {
            packControl.selectedSegmentIndex = 0
        }
        await applySelection()
    }

    @objc private func packChanged() {
        Task { await applySelection() }
    }

    private func applySelection() async {
        let index = packControl.selectedSegmentIndex
        guard index >= 0 else {
            items = []
            showingRecent = false
            collection.reloadData()
            emptyLabel.isHidden = false
            return
        }
        let recentOffset = recent.isEmpty ? 0 : 1
        if !recent.isEmpty, index == 0 {
            showingRecent = true
            items = []
        } else {
            showingRecent = false
            let packIndex = index - recentOffset
            guard packIndex >= 0, packIndex < packs.count else {
                items = []
                collection.reloadData()
                return
            }
            let packId = packs[packIndex].packId
            items = await stickers.stickers(in: packId)
            // Refresh pack entry with full sticker list for CDN urls.
            if let updated = (await stickers.installedPacks()).first(where: { $0.packId == packId }) {
                packs[packIndex] = updated
            }
        }
        emptyLabel.isHidden = showingRecent ? !recent.isEmpty : !items.isEmpty
        emptyLabel.text = items.isEmpty && !showingRecent ? "正在加载表情…" : "暂无表情包"
        collection.reloadData()
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        showingRecent ? recent.count : items.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: StickerCell.reuseID, for: indexPath) as! StickerCell
        let ref: StickerRef
        if showingRecent {
            ref = recent[indexPath.item]
        } else {
            ref = items[indexPath.item].asRef()
        }
        let token = "\(ref.packId)/\(ref.stickerId)/\(indexPath.item)"
        cell.bindToken = token
        cell.imageView.image = nil
        Task {
            let data = await stickers.imageData(for: ref)
            await MainActor.run {
                guard cell.bindToken == token else { return }
                if let data, let image = KingfisherWrapper<UIImage>.image(data: data, options: ImageCreatingOptions()) {
                    cell.imageView.image = image
                    cell.imageView.startAnimating()
                }
            }
        }
        return cell
    }

    func collectionView(
        _ collectionView: UICollectionView,
        layout collectionViewLayout: UICollectionViewLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> CGSize {
        CGSize(width: 64, height: 64)
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if showingRecent {
            onSelect?(recent[indexPath.item])
            return
        }
        let item = items[indexPath.item]
        Task {
            let ref = await stickers.enrichedRef(item)
            await MainActor.run { onSelect?(ref) }
        }
    }
}

private final class StickerCell: UICollectionViewCell {
    static let reuseID = "StickerCell"
    let imageView = AnimatedImageView()
    var bindToken: String = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFit
        imageView.autoPlayAnimatedImage = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
        ])
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        bindToken = ""
        imageView.stopAnimating()
        imageView.image = nil
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }
}
