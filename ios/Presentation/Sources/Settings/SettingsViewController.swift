import UIKit
import Domain

@MainActor
public final class SettingsViewController: UITableViewController {
    private let env: AppEnvironment
    public var onLoggedOut: (() -> Void)?

    private enum Section: Int, CaseIterable {
        case account
        case connection
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
    }

    public override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch Section(rawValue: section)! {
        case .account: return 1
        case .connection: return 2
        case .session: return 1
        }
    }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .account: return "账号"
        case .connection: return "连接"
        case .session: return nil
        }
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch Section(rawValue: section)! {
        case .connection:
            return "API 地址用于登录与文件上传；传输协议用于实时消息。"
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
            if indexPath.row == 0 {
                config.text = "API 地址"
                config.secondaryText = env.apiBaseURL.absoluteString
                config.image = UIImage(systemName: "link")
                cell.accessoryType = .disclosureIndicator
            } else {
                config.text = "传输方式"
                config.secondaryText = transportTitle(env.connection.preferredTransport())
                config.image = UIImage(systemName: "antenna.radiowaves.left.and.right")
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
            if indexPath.row == 0 {
                editBaseURL()
            } else {
                pickTransport(source: tableView.cellForRow(at: indexPath))
            }
        case .session:
            confirmLogout(source: tableView.cellForRow(at: indexPath))
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
