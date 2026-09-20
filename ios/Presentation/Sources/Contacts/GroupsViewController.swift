import UIKit
import Domain

@MainActor
public final class GroupsViewController: UITableViewController {
    private let env: AppEnvironment
    private let viewModel: GroupsViewModel

    public init(env: AppEnvironment) {
        self.env = env
        viewModel = GroupsViewModel(env: env)
        super.init(style: .plain)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "群组"
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(
                image: UIImage(systemName: "plus"),
                style: .plain,
                target: self,
                action: #selector(createGroup)
            ),
            UIBarButtonItem(
                image: UIImage(systemName: "person.badge.plus"),
                style: .plain,
                target: self,
                action: #selector(joinGroup)
            ),
        ]
        navigationItem.rightBarButtonItems?[0].accessibilityLabel = "创建群组"
        navigationItem.rightBarButtonItems?[1].accessibilityLabel = "加入群组"
        viewModel.onChange = { [weak self] in
            self?.tableView.reloadData()
            self?.updateEmpty()
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
        if isMovingFromParent || isBeingDismissed {
            viewModel.stop()
        }
    }

    private func updateEmpty() {
        if viewModel.groups.isEmpty {
            let label = UILabel()
            label.text = "暂无群组\n点右上角创建或加入"
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            label.numberOfLines = 0
            label.font = .preferredFont(forTextStyle: .body)
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
    }

    private func presentErrorIfNeeded() {
        guard let message = viewModel.errorMessage, !message.isEmpty else { return }
        viewModel.errorMessage = nil
        let alert = UIAlertController(title: "操作失败", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        viewModel.groups.count
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let g = viewModel.groups[indexPath.row]
        var config = cell.defaultContentConfiguration()
        let title = g.name.isEmpty ? g.groupId : g.name
        config.text = title
        config.textProperties.font = .preferredFont(forTextStyle: .headline)
        config.secondaryText = g.memberCount > 0
            ? "\(g.groupId) · \(g.memberCount) 人"
            : g.groupId
        config.secondaryTextProperties.color = .secondaryLabel
        config.image = .goimAvatar(
            monogram: GOIMFormat.monogram(from: title),
            size: GOIMStyle.listAvatarSize,
            color: .systemIndigo
        )
        config.imageProperties.cornerRadius = GOIMStyle.listAvatarSize / 2
        config.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0)
        cell.accessoryType = .disclosureIndicator
        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let g = viewModel.groups[indexPath.row]
        let title = g.name.isEmpty ? g.groupId : g.name
        let conv = Conversation(
            id: ConversationID.group(g.groupId),
            chatType: .group,
            title: title,
            peerOrGroupId: g.groupId
        )
        navigationController?.pushViewController(ChatViewController(env: env, conversation: conv), animated: true)
    }

    public override func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        let leave = UIContextualAction(style: .destructive, title: "退出") { [weak self] _, _, done in
            guard let self else {
                done(false)
                return
            }
            Task {
                await self.viewModel.leaveGroup(at: indexPath.row)
                done(true)
            }
        }
        leave.image = UIImage(systemName: "rectangle.portrait.and.arrow.right")
        let config = UISwipeActionsConfiguration(actions: [leave])
        config.performsFirstActionWithFullSwipe = false
        return config
    }

    @objc private func createGroup() {
        let alert = UIAlertController(title: "创建群组", message: "输入群名称，下一步选择成员", preferredStyle: .alert)
        alert.addTextField {
            $0.placeholder = "群名称"
            $0.autocapitalizationType = .words
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "下一步", style: .default) { [weak self] _ in
            guard let self,
                  let name = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !name.isEmpty else { return }
            Task { await self.pickMembersAndCreate(name: name) }
        })
        present(alert, animated: true)
    }

    private func pickMembersAndCreate(name: String) async {
        let friends = await viewModel.loadFriends()
        let picker = FriendPickerViewController(friends: friends)
        picker.onDone = { [weak self] selected in
            guard let self else { return }
            Task {
                let memberUIDs = selected.map(\.uid)
                if let group = await self.viewModel.createGroup(name: name, members: memberUIDs) {
                    let title = group.name.isEmpty ? group.groupId : group.name
                    let conv = Conversation(
                        id: ConversationID.group(group.groupId),
                        chatType: .group,
                        title: title,
                        peerOrGroupId: group.groupId
                    )
                    self.navigationController?.pushViewController(
                        ChatViewController(env: self.env, conversation: conv),
                        animated: true
                    )
                }
            }
        }
        navigationController?.pushViewController(picker, animated: true)
    }

    @objc private func joinGroup() {
        let alert = UIAlertController(title: "加入群组", message: "输入群组 ID", preferredStyle: .alert)
        alert.addTextField {
            $0.placeholder = "群组 ID"
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "加入", style: .default) { [weak self] _ in
            guard let self,
                  let groupId = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !groupId.isEmpty else { return }
            Task {
                let ok = await self.viewModel.joinGroup(groupId: groupId)
                guard ok else { return }
                let name = self.viewModel.groups.first(where: { $0.groupId == groupId })?.name
                let title = (name?.isEmpty == false) ? name! : groupId
                let conv = Conversation(
                    id: ConversationID.group(groupId),
                    chatType: .group,
                    title: title,
                    peerOrGroupId: groupId
                )
                self.navigationController?.pushViewController(
                    ChatViewController(env: self.env, conversation: conv),
                    animated: true
                )
            }
        })
        present(alert, animated: true)
    }
}
