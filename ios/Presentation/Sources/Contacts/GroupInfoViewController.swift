import UIKit
import Domain

/// Secondary page: group profile, members, invite / leave.
@MainActor
public final class GroupInfoViewController: UITableViewController {
    private enum Section: Int, CaseIterable {
        case info
        case members
        case actions
    }

    private let env: AppEnvironment
    private let groupId: String
    private var groupName: String
    private var group: Group?
    private var members: [GroupMember] = []
    private var friendNames: [String: String] = [:]
    private var isLoading = true

    public init(env: AppEnvironment, groupId: String, groupName: String) {
        self.env = env
        self.groupId = groupId
        self.groupName = groupName
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "群聊信息"
        navigationItem.largeTitleDisplayMode = .never
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 52
        refresh()
    }

    private func refresh() {
        Task { await load() }
    }

    private func load() async {
        isLoading = true
        tableView.reloadData()

        let groups = (try? await env.groups.list()) ?? []
        group = groups.first(where: { $0.groupId == groupId })
        if let name = group?.name, !name.isEmpty {
            groupName = name
        }

        members = (try? await env.groups.members(groupId: groupId)) ?? []
        let friends = (try? await env.friends.listFriends()) ?? []
        friendNames = Dictionary(uniqueKeysWithValues: friends.map { ($0.uid, $0.displayName) })

        isLoading = false
        tableView.reloadData()
    }

    private func displayName(for uid: String) -> String {
        if let name = friendNames[uid], !name.isEmpty, name != uid {
            return name
        }
        if uid == env.auth.currentUser()?.uid {
            return "我"
        }
        return uid
    }

    // MARK: - Table

    public override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .info:
            return 4
        case .members:
            if isLoading { return 1 }
            return max(members.count, 1)
        case .actions:
            return 3
        }
    }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .info:
            return nil
        case .members:
            let count = group?.memberCount ?? members.count
            return count > 0 ? "群成员 (\(count))" : "群成员"
        case .actions:
            return nil
        }
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        cell.accessoryType = .none
        cell.selectionStyle = .default
        var config = cell.defaultContentConfiguration()
        config.image = nil
        config.secondaryText = nil

        switch Section(rawValue: indexPath.section)! {
        case .info:
            cell.selectionStyle = indexPath.row == 1 ? .default : .none
            switch indexPath.row {
            case 0:
                let title = groupName.isEmpty ? groupId : groupName
                config.text = title
                config.textProperties.font = .preferredFont(forTextStyle: .headline)
                config.secondaryText = "群名称"
                config.image = .goimAvatar(
                    monogram: GOIMFormat.monogram(from: title),
                    size: GOIMStyle.listAvatarSize,
                    color: .systemIndigo
                )
                config.imageProperties.cornerRadius = GOIMStyle.listAvatarSize / 2
            case 1:
                config.text = groupId
                config.secondaryText = "群 ID（点按复制）"
                config.secondaryTextProperties.color = .secondaryLabel
            case 2:
                let owner = group?.ownerUID ?? "—"
                config.text = displayName(for: owner)
                config.secondaryText = owner == displayName(for: owner) ? "群主" : "群主 · \(owner)"
                config.secondaryTextProperties.color = .secondaryLabel
            default:
                let count = group?.memberCount ?? members.count
                config.text = count > 0 ? "\(count) 人" : (isLoading ? "加载中…" : "—")
                config.secondaryText = "成员数"
                config.secondaryTextProperties.color = .secondaryLabel
            }

        case .members:
            if isLoading {
                config.text = "加载中…"
                config.textProperties.color = .secondaryLabel
                cell.selectionStyle = .none
            } else if members.isEmpty {
                config.text = "暂无成员信息"
                config.textProperties.color = .secondaryLabel
                cell.selectionStyle = .none
            } else {
                let m = members[indexPath.row]
                let name = displayName(for: m.uid)
                config.text = name
                var parts: [String] = []
                if name != m.uid { parts.append(m.uid) }
                if m.uid == group?.ownerUID { parts.append("群主") }
                else if m.role == "owner" { parts.append("群主") }
                config.secondaryText = parts.isEmpty ? nil : parts.joined(separator: " · ")
                config.secondaryTextProperties.color = .secondaryLabel
                config.image = .goimAvatar(
                    monogram: GOIMFormat.monogram(from: name),
                    size: 36,
                    color: .systemBlue
                )
                config.imageProperties.cornerRadius = 18
                cell.selectionStyle = .none
            }

        case .actions:
            switch indexPath.row {
            case 0:
                config.text = "邀请成员"
                config.image = UIImage(systemName: "person.badge.plus")
                config.imageProperties.tintColor = .systemBlue
                cell.accessoryType = .disclosureIndicator
            case 1:
                config.text = "复制群 ID"
                config.image = UIImage(systemName: "doc.on.doc")
                config.imageProperties.tintColor = .systemBlue
            default:
                config.text = "退出群聊"
                config.textProperties.color = .systemRed
                config.image = UIImage(systemName: "rectangle.portrait.and.arrow.right")
                config.imageProperties.tintColor = .systemRed
            }
        }

        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch Section(rawValue: indexPath.section)! {
        case .info:
            if indexPath.row == 1 { copyGroupId() }
        case .members:
            break
        case .actions:
            switch indexPath.row {
            case 0: inviteMembers()
            case 1: copyGroupId()
            default: confirmLeave()
            }
        }
    }

    // MARK: - Actions

    private func copyGroupId() {
        UIPasteboard.general.string = groupId
        let alert = UIAlertController(title: "已复制", message: groupId, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    private func inviteMembers() {
        Task {
            let friends = (try? await env.friends.listFriends()) ?? []
            let memberUIDs = Set(members.map(\.uid))
            let candidates = friends.filter { !memberUIDs.contains($0.uid) }
            guard !candidates.isEmpty else {
                presentMessage(title: "邀请成员", message: "没有可邀请的好友")
                return
            }
            let picker = FriendPickerViewController(friends: candidates)
            picker.onDone = { [weak self] selected in
                guard let self, !selected.isEmpty else { return }
                Task {
                    do {
                        for friend in selected {
                            try await self.env.groups.invite(groupId: self.groupId, uid: friend.uid)
                        }
                        await self.load()
                    } catch {
                        self.presentMessage(title: "邀请失败", message: error.localizedDescription)
                    }
                }
            }
            navigationController?.pushViewController(picker, animated: true)
        }
    }

    private func confirmLeave() {
        let alert = UIAlertController(
            title: "退出群聊",
            message: "确定退出「\(groupName.isEmpty ? groupId : groupName)」？",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "退出", style: .destructive) { [weak self] _ in
            guard let self else { return }
            Task { await self.performLeave() }
        })
        present(alert, animated: true)
    }

    private func performLeave() async {
        do {
            try await env.leaveGroup.execute(groupId: groupId)
            guard let nav = navigationController else { return }
            // Pop group info and the underlying chat.
            let stack = nav.viewControllers
            if let chatIndex = stack.firstIndex(where: { $0 is ChatViewController }), chatIndex > 0 {
                nav.popToViewController(stack[chatIndex - 1], animated: true)
            } else {
                nav.popToRootViewController(animated: true)
            }
        } catch {
            presentMessage(title: "退出失败", message: error.localizedDescription)
        }
    }

    private func presentMessage(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }
}
