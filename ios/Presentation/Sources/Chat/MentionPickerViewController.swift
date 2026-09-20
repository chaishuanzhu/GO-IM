import UIKit
import Domain

/// Secondary page to pick a user for @ mention.
@MainActor
public final class MentionPickerViewController: UITableViewController {
    public var onPick: ((String) -> Void)?

    private let env: AppEnvironment
    private let conversation: Conversation
    private var candidates: [(uid: String, title: String)] = []

    public init(env: AppEnvironment, conversation: Conversation) {
        self.env = env
        self.conversation = conversation
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = "选择提醒的人"
        navigationItem.largeTitleDisplayMode = .never
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close,
            target: self,
            action: #selector(close)
        )
        Task { await loadCandidates() }
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    private func loadCandidates() async {
        var list: [(String, String)] = []
        if conversation.chatType == .group {
            if let members = try? await env.groups.members(groupId: conversation.peerOrGroupId) {
                let me = env.auth.currentUser()?.uid
                for m in members where m.uid != me {
                    list.append((m.uid, m.uid))
                }
            }
        } else {
            // DM: peer + friends (excluding self)
            let peer = conversation.peerOrGroupId
            if !peer.isEmpty {
                list.append((peer, conversation.title.isEmpty ? peer : conversation.title))
            }
            if let friends = try? await env.friends.listFriends() {
                let me = env.auth.currentUser()?.uid
                for f in friends where f.uid != me && f.uid != peer {
                    let title = f.username.isEmpty ? f.uid : f.username
                    list.append((f.uid, title))
                }
            }
        }
        // De-dupe by uid
        var seen = Set<String>()
        candidates = list.filter { seen.insert($0.0).inserted }.map { ($0.0, $0.1) }
        tableView.reloadData()
        if candidates.isEmpty {
            let label = UILabel()
            label.text = "暂无可选用户"
            label.textAlignment = .center
            label.textColor = .secondaryLabel
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
    }

    public override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        candidates.count
    }

    public override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let c = candidates[indexPath.row]
        var config = cell.defaultContentConfiguration()
        config.text = c.title
        config.secondaryText = c.uid
        config.image = .goimAvatar(monogram: GOIMFormat.monogram(from: c.title), size: 40, color: .systemBlue)
        config.imageProperties.cornerRadius = 20
        cell.contentConfiguration = config
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    public override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        let uid = candidates[indexPath.row].uid
        onPick?(uid)
        dismiss(animated: true)
    }
}
