import UIKit
import Kingfisher
import Domain

/// Sticker grid for the chat composer accessory (pack tabs + recent).
@MainActor
final class StickerAccessoryPanel: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    var onSelect: ((StickerRef) -> Void)?

    private let packTabs: UICollectionView
    private let collection: UICollectionView
    private var tabTitles: [String] = []
    private var selectedTabIndex = 0
    private var packs: [StickerPack] = []
    private var items: [StickerItem] = []
    private var recent: [StickerRef] = []
    private var showingRecent = false
    private let stickers: StickerRepository
    private let emptyLabel = UILabel()

    init(stickers: StickerRepository) {
        self.stickers = stickers

        let tabLayout = UICollectionViewFlowLayout()
        tabLayout.scrollDirection = .horizontal
        tabLayout.minimumInteritemSpacing = 8
        tabLayout.minimumLineSpacing = 8
        tabLayout.sectionInset = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
        packTabs = UICollectionView(frame: .zero, collectionViewLayout: tabLayout)

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

    func setBottomContentInset(_ inset: CGFloat) {
        collection.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: inset, right: 0)
        collection.scrollIndicatorInsets = collection.contentInset
    }

    private func setup() {
        backgroundColor = .clear

        packTabs.backgroundColor = .clear
        packTabs.showsHorizontalScrollIndicator = false
        packTabs.alwaysBounceHorizontal = true
        packTabs.dataSource = self
        packTabs.delegate = self
        packTabs.register(PackTabCell.self, forCellWithReuseIdentifier: PackTabCell.reuseID)
        packTabs.translatesAutoresizingMaskIntoConstraints = false

        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.delegate = self
        collection.register(StickerCell.self, forCellWithReuseIdentifier: StickerCell.reuseID)
        collection.translatesAutoresizingMaskIntoConstraints = false
        collection.contentInsetAdjustmentBehavior = .never

        emptyLabel.text = "暂无表情包"
        emptyLabel.textColor = .secondaryLabel
        emptyLabel.textAlignment = .center
        emptyLabel.font = .preferredFont(forTextStyle: .footnote)
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        emptyLabel.isHidden = true

        addSubview(packTabs)
        addSubview(collection)
        addSubview(emptyLabel)
        NSLayoutConstraint.activate([
            packTabs.topAnchor.constraint(equalTo: topAnchor, constant: 4),
            packTabs.leadingAnchor.constraint(equalTo: leadingAnchor),
            packTabs.trailingAnchor.constraint(equalTo: trailingAnchor),
            packTabs.heightAnchor.constraint(equalToConstant: 36),

            collection.topAnchor.constraint(equalTo: packTabs.bottomAnchor, constant: 4),
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

        var titles: [String] = []
        if !recent.isEmpty {
            titles.append("最近")
        }
        titles.append(contentsOf: packs.map(\.name))
        tabTitles = titles
        if selectedTabIndex >= tabTitles.count {
            selectedTabIndex = 0
        }
        packTabs.reloadData()
        await applySelection()
    }

    private func applySelection() async {
        guard !tabTitles.isEmpty, selectedTabIndex >= 0, selectedTabIndex < tabTitles.count else {
            items = []
            showingRecent = false
            collection.reloadData()
            emptyLabel.isHidden = false
            return
        }
        let recentOffset = recent.isEmpty ? 0 : 1
        if !recent.isEmpty, selectedTabIndex == 0 {
            showingRecent = true
            items = []
        } else {
            showingRecent = false
            let packIndex = selectedTabIndex - recentOffset
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

    // MARK: - UICollectionView

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        if collectionView === packTabs {
            return tabTitles.count
        }
        return showingRecent ? recent.count : items.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        if collectionView === packTabs {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PackTabCell.reuseID, for: indexPath) as! PackTabCell
            cell.configure(title: tabTitles[indexPath.item], selected: indexPath.item == selectedTabIndex)
            return cell
        }

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
        if collectionView === packTabs {
            let title = tabTitles[indexPath.item]
            let font = UIFont.systemFont(ofSize: 14, weight: .medium)
            let width = (title as NSString).size(withAttributes: [.font: font]).width
            return CGSize(width: ceil(width) + 24, height: 32)
        }
        return CGSize(width: 64, height: 64)
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if collectionView === packTabs {
            guard indexPath.item != selectedTabIndex else { return }
            selectedTabIndex = indexPath.item
            packTabs.reloadData()
            packTabs.scrollToItem(at: indexPath, at: .centeredHorizontally, animated: true)
            Task { await applySelection() }
            return
        }
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

private final class PackTabCell: UICollectionViewCell {
    static let reuseID = "PackTabCell"
    private let titleLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 16
        contentView.layer.cornerCurve = .continuous
        contentView.clipsToBounds = true

        titleLabel.font = .systemFont(ofSize: 14, weight: .medium)
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(titleLabel)
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            titleLabel.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            titleLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
        ])
    }

    func configure(title: String, selected: Bool) {
        titleLabel.text = title
        titleLabel.textColor = selected ? .white : .label
        contentView.backgroundColor = selected ? .systemBlue : UIColor.tertiarySystemFill
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }
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
