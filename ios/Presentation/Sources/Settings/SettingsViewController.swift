import UIKit
import Domain

@MainActor
public final class SettingsViewController: UITableViewController {
    private let env: AppEnvironment
    public var onLoggedOut: (() -> Void)?
    private var connectionState: ConnectionState = .disconnected
    private var stateTask: Task<Void, Never>?

    private enum Section: Int, CaseIterable {
        case account
        case connection
        case developer
        case session
    }

    public init(env: AppEnvironment) {
        self.env = env
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "设置"
        navigationController?.navigationBar.prefersLargeTitles = true
        navigationItem.largeTitleDisplayMode = .always
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        Task {
            connectionState = await env.connection.currentConnectionState()
            tableView.reloadSections(IndexSet(integer: Section.connection.rawValue), with: .none)
        }
        stateTask = Task { [weak self] in
            guard let self else { return }
            for await state in self.env.connection.observeState() {
                self.connectionState = state
                self.tableView.reloadSections(IndexSet(integer: Section.connection.rawValue), with: .none)
            }
        }
    }

    deinit {
        stateTask?.cancel()
    }

    public override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .account: return 1
        case .connection: return 3
        case .developer: return DeveloperSettings.isFLEXEnabled ? 2 : 1
        case .session: return 1
        }
    }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .account: return "账号"
        case .connection: return "连接"
        case .developer: return "开发者选项"
        case .session: return nil
        }
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .connection:
            return "API 地址用于登录与文件上传；传输协议用于实时消息。"
        case .developer:
            return "FLEX 调试面板默认关闭。开启后可在 App 内查看视图层级、网络与对象状态。"
        default:
            return nil
        }
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var config = cell.defaultContentConfiguration()
        cell.accessoryType = .none
        cell.selectionStyle = .default
        cell.accessoryView = nil

        switch Section(rawValue: indexPath.section)! {
        case .account:
            let uid = env.auth.currentUser()?.uid ?? "—"
            let name = env.auth.currentUser()?.username ?? ""
            config.text = name.isEmpty ? uid : name
            config.secondaryText = uid
            config.image = .goimAvatar(monogram: GOIMFormat.monogram(from: name.isEmpty ? uid : name), size: 40)
            config.imageProperties.cornerRadius = 20
            cell.selectionStyle = .none
        case .connection:
            switch indexPath.row {
            case 0:
                config.text = "连接状态"
                config.secondaryText = connectionStateTitle(connectionState)
                config.image = UIImage(systemName: connectionStateIcon(connectionState))
                cell.selectionStyle = .none
            case 1:
                config.text = "API 地址"
                config.secondaryText = env.apiBaseURL.absoluteString
                config.image = UIImage(systemName: "link")
                cell.accessoryType = .disclosureIndicator
            default:
                config.text = "传输方式"
                config.secondaryText = transportTitle(env.connection.preferredTransport())
                config.image = UIImage(systemName: "antenna.radiowaves.left.and.right")
                cell.accessoryType = .disclosureIndicator
            }
        case .developer:
            if indexPath.row == 0 {
                config.text = "FLEX 调试工具"
                config.secondaryText = "网络 / 视图 / 对象浏览器"
                config.image = UIImage(systemName: "hammer.fill")
                cell.selectionStyle = .none
                let toggle = UISwitch()
                toggle.isOn = DeveloperSettings.isFLEXEnabled
                toggle.addTarget(self, action: #selector(flexToggleChanged(_:)), for: .valueChanged)
                cell.accessoryView = toggle
            } else {
                config.text = "打开 FLEX 面板"
                config.secondaryText = "若工具条被关闭可由此重新打开"
                config.image = UIImage(systemName: "rectangle.and.hand.point.up.left.fill")
                cell.accessoryType = .disclosureIndicator
            }
        case .session:
            config.text = "退出登录"
            config.textProperties.color = .systemRed
            config.textProperties.alignment = .center
            config.image = nil
        }
        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch Section(rawValue: indexPath.section)! {
        case .account:
            break
        case .connection:
            if indexPath.row == 1 {
                editBaseURL()
            } else if indexPath.row == 2 {
                pickTransport(source: tableView.cellForRow(at: indexPath))
            }
        case .developer:
            if indexPath.row == 1, DeveloperSettings.isFLEXEnabled {
                env.onShowFLEXExplorer?()
            }
        case .session:
            confirmLogout(source: tableView.cellForRow(at: indexPath))
        }
    }

    @objc private func flexToggleChanged(_ sender: UISwitch) {
        let enabled = sender.isOn
        DeveloperSettings.isFLEXEnabled = enabled
        env.onFLEXEnabledChange?(enabled)
        tableView.reloadSections(IndexSet(integer: Section.developer.rawValue), with: .automatic)
    }

    private func connectionStateTitle(_ state: ConnectionState) -> String {
        switch state {
        case .disconnected: return "已断开"
        case .connecting: return "连接中…"
        case .connected: return "已连接"
        case .reconnecting: return "重连中…"
        case .authExpired: return "登录已失效"
        }
    }

    private func connectionStateIcon(_ state: ConnectionState) -> String {
        switch state {
        case .disconnected: return "wifi.slash"
        case .connecting, .reconnecting: return "arrow.triangle.2.circlepath"
        case .connected: return "wifi"
        case .authExpired: return "exclamationmark.triangle"
        }
    }

    private func transportTitle(_ kind: TransportKind) -> String {
        switch kind {
        case .webSocket: return "WebSocket"
        case .tcp: return "TCP"
        }
    }

    private func editBaseURL() {
        let alert = UIAlertController(title: "API 地址", message: "例如 https://im.chaisz.com", preferredStyle: .alert)
        alert.addTextField {
            $0.text = self.env.apiBaseURL.absoluteString
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
            $0.keyboardType = .URL
            $0.textContentType = .URL
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存", style: .default) { [weak self] _ in
            guard let self,
                  let text = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let url = URL(string: text),
                  url.host != nil else { return }
            self.env.updateAPIBaseURL(url)
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func pickTransport(source: UIView?) {
        let alert = UIAlertController(title: "传输方式", message: nil, preferredStyle: .actionSheet)
        for kind in TransportKind.allCases {
            alert.addAction(UIAlertAction(title: transportTitle(kind), style: .default) { [weak self] _ in
                Task {
                    try? await self?.env.switchTransport.execute(kind)
                    self?.tableView.reloadData()
                }
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        goimPresentActionSheet(alert, sourceView: source)
    }

    private func confirmLogout(source: UIView?) {
        let alert = UIAlertController(title: "退出登录？", message: nil, preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "退出登录", style: .destructive) { [weak self] _ in
            Task {
                await self?.env.logoutUseCase.execute()
                self?.onLoggedOut?()
            }
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        goimPresentActionSheet(alert, sourceView: source)
    }
}
