import UIKit
import Domain

@MainActor
public final class NewFriendsViewController: UITableViewController {
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
        title = "添加好友"
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            image: UIImage(systemName: "person.badge.plus"),
            style: .plain,
            target: self,
            action: #selector(addFriend)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "发送好友请求"
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

    public override func numberOfSections(in tableView: UITableView) -> Int { 2 }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? nil : (viewModel.requests.isEmpty ? nil : "好友请求")
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 1, viewModel.requests.isEmpty {
            return "暂无待处理请求"
        }
        return nil
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        section == 0 ? 1 : viewModel.requests.count
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var config = cell.defaultContentConfiguration()
        if indexPath.section == 0 {
            config.text = "搜索用户 ID 添加"
            config.secondaryText = "输入对方用户 ID 发送好友请求"
            config.secondaryTextProperties.color = .secondaryLabel
            config.image = UIImage(systemName: "magnifyingglass")
            config.imageProperties.tintColor = .systemBlue
            cell.accessoryType = .disclosureIndicator
        } else {
            let r = viewModel.requests[indexPath.row]
            let title = (r.username?.isEmpty == false) ? r.username! : r.fromUID
            config.text = title
            config.secondaryText = r.fromUID == title ? "等待处理" : "\(r.fromUID) · 等待处理"
            config.secondaryTextProperties.color = .systemOrange
            config.image = .goimAvatar(monogram: GOIMFormat.monogram(from: title), size: 36, color: .systemOrange)
            config.imageProperties.cornerRadius = 18
            cell.accessoryType = .disclosureIndicator
        }
        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 {
            addFriend()
            return
        }
        let r = viewModel.requests[indexPath.row]
        let alert = UIAlertController(title: "好友请求", message: r.fromUID, preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "接受", style: .default) { [weak self] _ in
            Task { await self?.viewModel.respond(to: r.fromUID, accept: true) }
        })
        alert.addAction(UIAlertAction(title: "拒绝", style: .destructive) { [weak self] _ in
            Task { await self?.viewModel.respond(to: r.fromUID, accept: false) }
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        goimPresentActionSheet(alert, sourceView: tableView.cellForRow(at: indexPath))
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
