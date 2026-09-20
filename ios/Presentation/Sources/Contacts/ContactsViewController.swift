import UIKit
import Domain

@MainActor
public final class ContactsViewController: UITableViewController {
    private enum Shortcut: Int, CaseIterable {
        case newFriends = 0
        case groups = 1

        var title: String {
            switch self {
            case .newFriends: return "添加好友"
            case .groups: return "群组"
            }
        }

        var systemImage: String {
            switch self {
            case .newFriends: return "person.badge.plus"
            case .groups: return "person.3"
            }
        }

        var tint: UIColor {
            switch self {
            case .newFriends: return .systemOrange
            case .groups: return .systemIndigo
            }
        }
    }

    private let env: AppEnvironment
    private let viewModel: ContactsViewModel

    public init(env: AppEnvironment) {
        self.env = env
        viewModel = ContactsViewModel(env: env)
        super.init(style: .plain)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "通讯录"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 56
        tableView.sectionIndexColor = .secondaryLabel
        tableView.sectionIndexBackgroundColor = .clear
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "person.badge.plus"),
            style: .plain,
            target: self,
            action: #selector(addFriend)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "添加好友"
        viewModel.onChange = { [weak self] in
            self?.tableView.reloadData()
            self?.presentErrorIfNeeded()
        }
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        viewModel.start()
        if let selected = tableView.indexPathForSelectedRow {
            tableView.deselectRow(at: selected, animated: animated)
        }
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Keep observing while alerts/action sheets are presented.
        if isMovingFromParent || isBeingDismissed {
            viewModel.stop()
        }
    }

    private func presentErrorIfNeeded() {
        guard let message = viewModel.errorMessage, !message.isEmpty else { return }
        viewModel.errorMessage = nil
        let alert = UIAlertController(title: "操作失败", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    // MARK: - Data source

    public override func numberOfSections(in tableView: UITableView) -> Int {
        1 + viewModel.friendSections.count
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if section == 0 { return Shortcut.allCases.count }
        return viewModel.friendSections[section - 1].friends.count
    }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        if section == 0 { return nil }
        return viewModel.friendSections[section - 1].title
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0, viewModel.friends.isEmpty {
            return "暂无好友，点「添加好友」发送请求"
        }
        return nil
    }

    public override func sectionIndexTitles(for tableView: UITableView) -> [String]? {
        viewModel.sectionIndexTitles.isEmpty ? nil : viewModel.sectionIndexTitles
    }

    public override func tableView(_ tableView: UITableView, sectionForSectionIndexTitle title: String, at index: Int) -> Int {
        viewModel.sectionForIndexTitle(title) ?? (index + 1)
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var config = cell.defaultContentConfiguration()
        cell.accessoryView = nil

        if indexPath.section == 0 {
            let shortcut = Shortcut(rawValue: indexPath.row) ?? .newFriends
            config.text = shortcut.title
            config.textProperties.font = .preferredFont(forTextStyle: .body)
            config.image = .goimAvatar(
                monogram: shortcut == .groups ? "群" : "+",
                size: 36,
                color: shortcut.tint
            )
            config.imageProperties.cornerRadius = 8
            config.secondaryText = nil
            cell.accessoryType = .disclosureIndicator

            if shortcut == .newFriends, !viewModel.requests.isEmpty {
                let badge = UILabel()
                badge.text = viewModel.requests.count > 99 ? "99+" : "\(viewModel.requests.count)"
                badge.font = .systemFont(ofSize: 12, weight: .semibold)
                badge.textColor = .white
                badge.backgroundColor = .systemRed
                badge.textAlignment = .center
                badge.clipsToBounds = true
                badge.sizeToFit()
                let w = max(20, ceil(badge.bounds.width) + 10)
                badge.bounds = CGRect(x: 0, y: 0, width: w, height: 20)
                badge.layer.cornerRadius = 10
                cell.accessoryView = badge
                cell.accessoryType = .none
            }
        } else if let friend = viewModel.friend(at: indexPath) {
            let title = friend.displayName
            config.text = title
            config.textProperties.font = .preferredFont(forTextStyle: .body)
            config.secondaryText = friend.uid == title ? nil : friend.uid
            config.secondaryTextProperties.color = .secondaryLabel
            config.image = .goimAvatar(monogram: GOIMFormat.monogram(from: title), size: 36, color: .systemTeal)
            config.imageProperties.cornerRadius = 18
            cell.accessoryType = .disclosureIndicator
        }

        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 {
            switch Shortcut(rawValue: indexPath.row) {
            case .newFriends:
                navigationController?.pushViewController(NewFriendsViewController(env: env), animated: true)
            case .groups:
                navigationController?.pushViewController(GroupsViewController(env: env), animated: true)
            case .none:
                break
            }
            return
        }
        guard let friend = viewModel.friend(at: indexPath),
              let me = env.auth.currentUser() else { return }
        let conv = Conversation(
            id: ConversationID.dm(uidA: me.uid, uidB: friend.uid),
            chatType: .single,
            title: friend.displayName,
            peerOrGroupId: friend.uid
        )
        navigationController?.pushViewController(ChatViewController(env: env, conversation: conv), animated: true)
    }

    @objc private func addFriend() {
        let alert = UIAlertController(title: "添加好友", message: "输入对方用户 ID", preferredStyle: .alert)
        alert.addTextField {
            $0.placeholder = "用户 ID"
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "发送请求", style: .default) { [weak self] _ in
            guard let uid = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !uid.isEmpty else { return }
            Task { await self?.viewModel.addFriend(uid: uid) }
        })
        present(alert, animated: true)
    }
}
