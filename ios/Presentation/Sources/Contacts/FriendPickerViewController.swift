import UIKit
import Domain

/// Multi-select friend list used when creating a group.
@MainActor
public final class FriendPickerViewController: UITableViewController {
    public var onDone: (([Friend]) -> Void)?

    private let friends: [Friend]
    private var selectedUIDs: Set<String> = []

    public init(friends: [Friend]) {
        self.friends = friends.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
        super.init(style: .plain)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "选择群成员"
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.allowsMultipleSelection = true
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "跳过",
            style: .plain,
            target: self,
            action: #selector(skip)
        )
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "完成",
            style: .done,
            target: self,
            action: #selector(done)
        )
        updateDoneTitle()
    }

    private func updateDoneTitle() {
        let count = selectedUIDs.count
        navigationItem.rightBarButtonItem?.title = count == 0 ? "完成" : "完成(\(count))"
    }

    @objc private func skip() {
        onDone?([])
        dismissOrPop()
    }

    @objc private func done() {
        let picked = friends.filter { selectedUIDs.contains($0.uid) }
        onDone?(picked)
        dismissOrPop()
    }

    private func dismissOrPop() {
        if let nav = navigationController, nav.presentingViewController != nil, nav.viewControllers.first === self {
            dismiss(animated: true)
        } else {
            navigationController?.popViewController(animated: true)
        }
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        friends.count
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        friends.isEmpty ? "暂无好友，可跳过并稍后再邀请" : nil
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let f = friends[indexPath.row]
        var config = cell.defaultContentConfiguration()
        config.text = f.displayName
        config.secondaryText = f.uid == f.displayName ? nil : f.uid
        config.secondaryTextProperties.color = .secondaryLabel
        config.image = .goimAvatar(monogram: GOIMFormat.monogram(from: f.displayName), size: 36, color: .systemTeal)
        config.imageProperties.cornerRadius = 18
        cell.contentConfiguration = config
        cell.accessoryType = selectedUIDs.contains(f.uid) ? .checkmark : .none
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let uid = friends[indexPath.row].uid
        if selectedUIDs.contains(uid) {
            selectedUIDs.remove(uid)
        } else {
            selectedUIDs.insert(uid)
        }
        tableView.reloadRows(at: [indexPath], with: .none)
        updateDoneTitle()
    }
}
