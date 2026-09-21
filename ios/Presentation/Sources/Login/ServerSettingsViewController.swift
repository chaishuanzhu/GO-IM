import UIKit
import Domain

/// Pre-login / shared server connection settings: API base URL + transport kind.
@MainActor
public final class ServerSettingsViewController: UITableViewController {
    private let env: AppEnvironment

    private enum Row: Int, CaseIterable {
        case apiURL
        case transport
    }

    public init(env: AppEnvironment) {
        self.env = env
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "服务器设置"
        navigationItem.largeTitleDisplayMode = .never
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        tableView.reloadData()
    }

    public override func numberOfSections(in tableView: UITableView) -> Int { 1 }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        Row.allCases.count
    }

    public override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        "连接"
    }

    public override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        "API 地址用于登录与文件上传；传输方式用于实时消息（登录后生效）。"
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        var config = cell.defaultContentConfiguration()
        cell.accessoryType = .disclosureIndicator
        cell.selectionStyle = .default
        switch Row(rawValue: indexPath.row)! {
        case .apiURL:
            config.text = "服务器地址"
            config.secondaryText = env.apiBaseURL.absoluteString
            config.image = UIImage(systemName: "link")
        case .transport:
            config.text = "连接方式"
            config.secondaryText = Self.transportTitle(env.connection.preferredTransport())
            config.image = UIImage(systemName: "antenna.radiowaves.left.and.right")
        }
        cell.contentConfiguration = config
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch Row(rawValue: indexPath.row)! {
        case .apiURL:
            editBaseURL()
        case .transport:
            pickTransport(source: tableView.cellForRow(at: indexPath))
        }
    }

    static func transportTitle(_ kind: TransportKind) -> String {
        switch kind {
        case .webSocket: return "WebSocket"
        case .tcp: return "TCP"
        }
    }

    private func editBaseURL() {
        let alert = UIAlertController(
            title: "服务器地址",
            message: "例如 https://im.chaisz.com",
            preferredStyle: .alert
        )
        alert.addTextField {
            $0.text = self.env.apiBaseURL.absoluteString
            $0.autocapitalizationType = .none
            $0.autocorrectionType = .no
            $0.keyboardType = .URL
            $0.textContentType = .URL
            $0.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存", style: .default) { [weak self] _ in
            guard let self,
                  let text = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let url = URL(string: text),
                  url.scheme != nil,
                  url.host != nil
            else {
                self?.presentInvalidURL()
                return
            }
            self.env.updateAPIBaseURL(url)
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func presentInvalidURL() {
        let alert = UIAlertController(
            title: "地址无效",
            message: "请输入完整 URL，例如 https://im.chaisz.com",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    private func pickTransport(source: UIView?) {
        let alert = UIAlertController(title: "连接方式", message: nil, preferredStyle: .actionSheet)
        for kind in TransportKind.allCases {
            let title = Self.transportTitle(kind)
            let current = env.connection.preferredTransport() == kind
            alert.addAction(UIAlertAction(title: current ? "✓ \(title)" : title, style: .default) { [weak self] _ in
                Task {
                    try? await self?.env.switchTransport.execute(kind)
                    self?.tableView.reloadData()
                }
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        goimPresentActionSheet(alert, sourceView: source)
    }
}
