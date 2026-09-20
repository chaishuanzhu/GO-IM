import UIKit
import Domain

@MainActor
public final class ConversationListViewController: UITableViewController {
    private let env: AppEnvironment
    private let viewModel: ConversationListViewModel

    public init(env: AppEnvironment) {
        self.env = env
        viewModel = ConversationListViewModel(env: env)
        super.init(style: .plain)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "聊天"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 72
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "square.and.pencil"),
            style: .plain,
            target: self,
            action: #selector(newChat)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "新建对话"
        viewModel.onChange = { [weak self] in
            self?.tableView.reloadData()
            self?.updateEmpty()
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
        viewModel.stop()
    }

    private func updateEmpty() {
        if viewModel.conversations.isEmpty {
            let label = UILabel()
            label.text = "暂无会话\n点右上角开始聊天"
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            label.numberOfLines = 0
            label.font = .preferredFont(forTextStyle: .body)
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        viewModel.conversations.count
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let c = viewModel.conversations[indexPath.row]
        var config = cell.defaultContentConfiguration()
        config.text = c.title
        config.textProperties.font = .preferredFont(forTextStyle: .headline)
        let preview = Message.sanitizedListPreview(c.lastMessagePreview)
        config.secondaryText = preview.isEmpty ? "暂无消息" : preview
        config.secondaryTextProperties.color = .secondaryLabel
        config.secondaryTextProperties.numberOfLines = 1
        config.image = .goimAvatar(
            monogram: GOIMFormat.monogram(from: c.title),
            size: GOIMStyle.listAvatarSize,
            color: c.chatType == .group ? .systemIndigo : .systemBlue
        )
        config.imageProperties.cornerRadius = GOIMStyle.listAvatarSize / 2
        config.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 10, leading: 0, bottom: 10, trailing: 0)

        let time = GOIMFormat.conversationTime(c.lastMessageAt)
        if c.unreadCount > 0 {
            config.secondaryText = preview.isEmpty
                ? "\(c.unreadCount) 条未读"
                : preview

            let timeLabel = UILabel()
            timeLabel.text = time
            timeLabel.font = .preferredFont(forTextStyle: .caption1)
            timeLabel.textColor = .secondaryLabel
            timeLabel.textAlignment = .right
            timeLabel.sizeToFit()

            let badge = UILabel()
            badge.text = c.unreadCount > 99 ? "99+" : "\(c.unreadCount)"
            badge.font = .systemFont(ofSize: 12, weight: .semibold)
            badge.textColor = .white
            badge.backgroundColor = .systemRed
            badge.textAlignment = .center
            badge.clipsToBounds = true
            badge.sizeToFit()
            let badgeW = max(20, ceil(badge.bounds.width) + 10)
            let badgeH: CGFloat = 20
            badge.bounds = CGRect(x: 0, y: 0, width: badgeW, height: badgeH)
            badge.layer.cornerRadius = badgeH / 2

            let gap: CGFloat = 6
            let width = max(ceil(timeLabel.bounds.width), badgeW, 36)
            let height = ceil(timeLabel.bounds.height) + gap + badgeH
            let container = UIView(frame: CGRect(x: 0, y: 0, width: width, height: height))
            timeLabel.frame = CGRect(x: 0, y: 0, width: width, height: timeLabel.bounds.height)
            badge.frame = CGRect(x: width - badgeW, y: timeLabel.bounds.height + gap, width: badgeW, height: badgeH)
            container.addSubview(timeLabel)
            container.addSubview(badge)
            cell.accessoryView = container
        } else {
            let t = UILabel()
            t.text = time
            t.font = .preferredFont(forTextStyle: .caption1)
            t.textColor = .secondaryLabel
            t.sizeToFit()
            cell.accessoryView = t
        }

        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let c = viewModel.conversations[indexPath.row]
        let chat = ChatViewController(env: env, conversation: c)
        navigationController?.pushViewController(chat, animated: true)
    }

    public override func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        let delete = UIContextualAction(style: .destructive, title: "删除") { [weak self] _, _, done in
            guard let self else {
                done(false)
                return
            }
            Task {
                await self.viewModel.deleteConversation(at: indexPath.row)
                done(true)
            }
        }
        delete.image = UIImage(systemName: "trash")
        let config = UISwipeActionsConfiguration(actions: [delete])
        config.performsFirstActionWithFullSwipe = true
        return config
    }

    @objc private func newChat() {
        let alert = UIAlertController(title: "新建对话", message: "输入对方用户 ID", preferredStyle: .alert)
        alert.addTextField {
            $0.placeholder = "用户 ID"
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "打开", style: .default) { [weak self] _ in
            guard let self, let peer = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !peer.isEmpty,
                  let me = self.env.auth.currentUser() else { return }
            let conv = Conversation(
                id: ConversationID.dm(uidA: me.uid, uidB: peer),
                chatType: .single,
                title: peer,
                peerOrGroupId: peer
            )
            let chat = ChatViewController(env: self.env, conversation: conv)
            self.navigationController?.pushViewController(chat, animated: true)
        })
        present(alert, animated: true)
    }
}
