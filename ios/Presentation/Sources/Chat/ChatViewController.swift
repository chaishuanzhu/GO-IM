import UIKit
import Domain

/// Chat chrome: layout, table, keyboard lift / insets. User intents go through `ChatEventRouter`.
@MainActor
public final class ChatViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let env: AppEnvironment
    private let viewModel: ChatViewModel
    private let router: ChatEventRouter

    private let tableView = UITableView(frame: .zero, style: .plain)
    private let composer = ChatComposerBar()
    /// Docked to the physical bottom; keyboard lifts via constant (avoids safe-area gap).
    private var composerBottomConstraint: NSLayoutConstraint!

    /// First open / first data fill should land on the latest message after layout.
    private var pendingScrollToBottom = false
    private var hasScrolledToBottomOnce = false

    public init(env: AppEnvironment, conversation: Conversation) {
        self.env = env
        viewModel = ChatViewModel(env: env, conversation: conversation)
        router = ChatEventRouter(env: env, viewModel: viewModel)
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        router.bindHosts(composerHost: self, presentationHost: self)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    public override func viewDidLoad() {
        super.viewDidLoad()
        title = viewModel.conversation.title
        navigationItem.largeTitleDisplayMode = .never
        view.backgroundColor = .systemGroupedBackground
        if viewModel.conversation.chatType == .group {
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                image: UIImage(systemName: "ellipsis.circle"),
                style: .plain,
                target: self,
                action: #selector(openGroupInfo)
            )
            navigationItem.rightBarButtonItem?.accessibilityLabel = "更多"
        }
        configureLayout()
        configureBindings()
        composer.configureStickers(env.stickers)
    }

    public override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        viewModel.start()
    }

    public override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        viewModel.stop()
        composer.dismissAccessory()
        VoicePlayer.shared.stop()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !hasScrolledToBottomOnce, viewModel.messages.count > 0 {
            scrollToBottom(animated: false, force: true)
        } else {
            flushPendingScrollToBottom()
        }
    }

    private func configureBindings() {
        viewModel.onChange = { [weak self] in
            guard let self else { return }
            let previousCount = self.tableView.numberOfRows(inSection: 0)
            let newCount = self.viewModel.messages.count
            let prepended = self.viewModel.didPrependHistory
            let oldOffset = self.tableView.contentOffset.y
            let oldHeight = self.tableView.contentSize.height
            let isInitialFill = previousCount == 0 && newCount > 0

            self.tableView.reloadData()

            if prepended, newCount > previousCount, previousCount > 0 {
                self.tableView.layoutIfNeeded()
                let delta = self.tableView.contentSize.height - oldHeight
                self.tableView.contentOffset.y = max(0, oldOffset + delta)
            } else if isInitialFill || !self.hasScrolledToBottomOnce {
                self.scrollToBottom(animated: false, force: true)
            } else if newCount > previousCount {
                self.scrollToBottom(animated: true, force: false)
            } else if self.isNearBottom {
                self.scrollToBottom(animated: false, force: false)
            }

            if let err = self.viewModel.errorMessage {
                self.presentError(title: "发送失败", message: err)
                self.viewModel.errorMessage = nil
            }
        }
    }

    private var isNearBottom: Bool {
        let visible = tableView.bounds.height
        guard visible > 0 else { return true }
        let offsetY = tableView.contentOffset.y
        let contentH = tableView.contentSize.height
        return offsetY + visible >= contentH - 120
    }

    private func configureLayout() {
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(MessageBubbleCell.self, forCellReuseIdentifier: MessageBubbleCell.reuseID)
        tableView.separatorStyle = .none
        tableView.keyboardDismissMode = .interactive
        tableView.backgroundColor = .systemGroupedBackground
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 120
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.contentInsetAdjustmentBehavior = .never
        tableView.contentInset = UIEdgeInsets(top: 6, left: 0, bottom: 12, right: 0)

        composer.delegate = router
        composer.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(tableView)
        view.addSubview(composer)

        composerBottomConstraint = composer.bottomAnchor.constraint(equalTo: view.bottomAnchor)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            composer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            composer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            composerBottomConstraint,
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissInputs))
        tap.cancelsTouchesInView = false
        tableView.addGestureRecognizer(tap)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardFrameWillChange(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
    }

    @objc private func keyboardFrameWillChange(_ note: Notification) {
        guard
            let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect,
            let duration = note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double
        else { return }
        let converted = view.convert(frame, from: nil)
        let overlap = max(0, view.bounds.maxY - converted.minY)
        let lift = composer.accessory == .none ? overlap : 0
        composerBottomConstraint.constant = -lift
        let curveRaw = (note.userInfo?[UIResponder.keyboardAnimationCurveUserInfoKey] as? Int) ?? 7
        let options = UIView.AnimationOptions(rawValue: UInt(curveRaw << 16))
        UIView.animate(withDuration: duration, delay: 0, options: options) {
            self.view.layoutIfNeeded()
            self.updateTableInsetsForComposer()
        }
    }

    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateTableInsetsForComposer()
        flushPendingScrollToBottom()
    }

    private func updateTableInsetsForComposer() {
        let cover = max(0, view.bounds.maxY - composer.frame.minY)
        let bottom = cover + 8
        var inset = tableView.contentInset
        guard abs(inset.bottom - bottom) > 0.5 else { return }
        inset.bottom = bottom
        tableView.contentInset = inset
        tableView.verticalScrollIndicatorInsets.bottom = bottom
        if hasScrolledToBottomOnce, isNearBottom || pendingScrollToBottom {
            pendingScrollToBottom = true
        }
    }

    @objc private func openGroupInfo() {
        router.handle(.openGroupInfo)
    }

    @objc private func dismissInputs() {
        view.endEditing(true)
        composer.dismissAccessory()
    }

    public func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        viewModel.messages.count
    }

    public func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: MessageBubbleCell.reuseID, for: indexPath) as! MessageBubbleCell
        let m = viewModel.messages[indexPath.row]
        let vm = MessageBubbleMapper.map(m) { [weak self] fileId, thumb in
            self?.env.files.fileURL(fileId: fileId, thumb: thumb)
        }
        cell.configure(vm: vm, actions: router.bubbleActions(for: m.id))
        return cell
    }

    public func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === tableView else { return }
        if scrollView.contentOffset.y < 48 {
            Task { await viewModel.loadOlderIfNeeded() }
        }
    }

    private func scrollToBottom(animated: Bool, force: Bool) {
        let count = viewModel.messages.count
        guard count > 0 else {
            if force { pendingScrollToBottom = true }
            return
        }
        tableView.layoutIfNeeded()
        guard tableView.bounds.height > 1, tableView.window != nil else {
            pendingScrollToBottom = true
            return
        }
        let indexPath = IndexPath(row: count - 1, section: 0)
        guard tableView.numberOfRows(inSection: 0) > indexPath.row else {
            pendingScrollToBottom = true
            return
        }
        tableView.scrollToRow(at: indexPath, at: .bottom, animated: animated)
        hasScrolledToBottomOnce = true
        pendingScrollToBottom = false
    }

    private func flushPendingScrollToBottom() {
        guard pendingScrollToBottom else { return }
        scrollToBottom(animated: false, force: true)
    }
}

// MARK: - Hosts

extension ChatViewController: ChatComposerHosting {
    var accessory: ChatComposerAccessory { composer.accessory }

    func dismissAccessory() {
        composer.dismissAccessory()
    }

    func appendPickedImage(_ image: UIImage) {
        composer.appendPickedImage(image)
    }

    func insertMention(_ uid: String) {
        composer.insertMention(uid)
    }
}

extension ChatViewController: ChatPresentationHosting {
    func presentHosted(_ viewController: UIViewController, animated: Bool) {
        present(viewController, animated: animated)
    }

    func presentError(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "好", style: .default))
        present(alert, animated: true)
    }

    func composerAccessoryChanged(_ mode: ChatComposerAccessory) {
        if mode != .none {
            composerBottomConstraint.constant = 0
        }
        UIView.animate(withDuration: 0.28, delay: 0, options: [.curveEaseInOut]) {
            self.composer.layoutIfNeeded()
            self.view.layoutIfNeeded()
            self.updateTableInsetsForComposer()
        } completion: { _ in
            self.updateTableInsetsForComposer()
            if mode != .none {
                self.scrollToBottom(animated: true, force: false)
            }
        }
    }

    func pushGroupInfo(groupId: String, groupName: String) {
        let info = GroupInfoViewController(env: env, groupId: groupId, groupName: groupName)
        navigationController?.pushViewController(info, animated: true)
    }
}
