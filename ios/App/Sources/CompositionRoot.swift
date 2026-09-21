import UIKit
import Domain
import Data
import Presentation
import Moya

@MainActor
public enum CompositionRoot {
    public private(set) static var environment: AppEnvironment!
    private static var window: UIWindow?

    public static func bootstrap(window: UIWindow) {
        self.window = window
        GOIMAppearance.apply()

        let baseURLString = UserDefaults.standard.string(forKey: "goim.apiBaseURL")
            ?? Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
            ?? "https://im.chaisz.com"
        let baseURL = URL(string: baseURLString) ?? URL(string: "https://im.chaisz.com")!
        ServerConfigHolder.shared.baseURL = baseURL
        let serverConfig = ServerConfigHolder.shared

        let authPlugin = GOIMAuthPlugin()
        let sharedProvider = SharedMoyaProvider(MoyaProvider<GOIMAPI>(plugins: [authPlugin]))
        let keychain = KeychainStore.shared

        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dbDir = support.appendingPathComponent("GOIM", isDirectory: true)
        try? FileManager.default.createDirectory(at: dbDir, withIntermediateDirectories: true)
        let dbPath = dbDir.appendingPathComponent("goim.sqlite").path
        let database = try! AppDatabase(path: dbPath)
        let store = LocalStore(db: database)

        let auth = AuthRepositoryImpl(provider: sharedProvider, keychain: keychain, authPlugin: authPlugin)
        let connection = ConnectionRepositoryImpl(serverConfig: serverConfig, keychain: keychain)
        let messages = MessageRepositoryImpl(store: store, connection: connection)
        let conversations = ConversationRepositoryImpl(store: store)
        let groups = GroupRepositoryImpl(provider: sharedProvider, connection: connection, store: store)
        let friends = FriendRepositoryImpl(
            provider: sharedProvider,
            connection: connection,
            store: store,
            selfUIDProvider: { auth.currentUser()?.uid }
        )
        let files = FileRepositoryImpl(provider: sharedProvider, serverConfig: serverConfig, auth: auth)
        let search = SearchRepositoryImpl(provider: sharedProvider, auth: auth)
        let stickers = StickerRepositoryImpl()

        environment = AppEnvironment(
            auth: auth,
            connection: connection,
            messages: messages,
            conversations: conversations,
            groups: groups,
            friends: friends,
            files: files,
            search: search,
            stickers: stickers,
            apiBaseURL: baseURL
        )
        environment.onAPIBaseURLChange = { url in
            ServerConfigHolder.shared.baseURL = url
            UserDefaults.standard.set(url.absoluteString, forKey: "goim.apiBaseURL")
        }

        if let saved = UserDefaults.standard.string(forKey: "goim.apiBaseURL"),
           let savedURL = URL(string: saved), savedURL.host != nil {
            environment.updateAPIBaseURL(savedURL)
        }

        _Concurrency.Task {
            await stickers.loadPresetCatalog()
            try? await stickers.syncCatalog(from: environment.stickerCatalogURL)
        }

        _Concurrency.Task {
            await pumpInboundEvents(
                connection: connection,
                messages: messages,
                conversations: conversations,
                friends: friends
            )
        }

        _Concurrency.Task {
            await pumpConnectionState(
                connection: connection,
                messages: messages,
                conversations: conversations
            )
        }

        if let user = auth.currentUser() {
            showMain()
            _Concurrency.Task {
                try? await connection.connect(user: user, transport: connection.preferredTransport())
            }
        } else {
            showLogin()
        }
    }

    public static func showLogin() {
        guard let window, let env = environment else { return }
        let login = LoginViewController(env: env)
        login.onLoggedIn = { showMain() }
        window.rootViewController = UINavigationController(rootViewController: login)
        window.makeKeyAndVisible()
    }

    public static func showMain() {
        guard let window, let env = environment else { return }
        let tabs = MainTabBarController(env: env)
        if let settingsNav = tabs.viewControllers?.last as? UINavigationController,
           let settings = settingsNav.viewControllers.first as? SettingsViewController {
            settings.onLoggedOut = { showLogin() }
        }
        window.rootViewController = tabs
        window.makeKeyAndVisible()
    }

    private static func pumpConnectionState(
        connection: ConnectionRepository,
        messages: MessageRepository,
        conversations: ConversationRepository
    ) async {
        for await state in connection.observeState() {
            switch state {
            case .connected:
                await postReconnectCatchup(
                    connection: connection,
                    messages: messages,
                    conversations: conversations
                )
            case .authExpired:
                await MainActor.run {
                    _Concurrency.Task {
                        await environment.logoutUseCase.execute()
                        showLogin()
                    }
                }
            default:
                break
            }
        }
    }

    /// Unread + active-chat history + resend `.sending` (CmdOffline already sent in connect).
    private static func postReconnectCatchup(
        connection: ConnectionRepository,
        messages: MessageRepository,
        conversations: ConversationRepository
    ) async {
        try? await connection.send(OutboundEnvelope(kind: .unreadCount))

        if let activeId = await conversations.activeConversationId(),
           let conv = try? await conversations.conversations().first(where: { $0.id == activeId }) {
            _ = try? await messages.loadHistory(
                conversationId: activeId,
                peer: conv.peerOrGroupId,
                before: nil,
                limit: 50,
                chatType: conv.chatType
            )
        }

        if let pending = try? await messages.messages(status: .sending) {
            for msg in pending {
                try? await messages.retry(msg)
            }
        }
    }

    private static func pumpInboundEvents(
        connection: ConnectionRepository,
        messages: MessageRepository,
        conversations: ConversationRepository,
        friends: FriendRepository
    ) async {
        for await event in connection.inboundEvents {
            switch event {
            case let .message(msg):
                try? await messages.upsert(msg)
                let historyInFlight = await messages.isHistoryInFlight()
                let activeId = await conversations.activeConversationId()
                let incrementUnread = !msg.isOutgoing
                    && !historyInFlight
                    && activeId != msg.conversationId
                try? await conversations.upsertConversation(
                    from: msg,
                    title: nil,
                    incrementUnread: incrementUnread
                )
            case let .ack(seq, msgId):
                try? await messages.markStatus(clientSeq: seq, status: .sent, serverMsgId: msgId)
            case let .historyFinished(delivered):
                await messages.completeHistory(delivered: delivered)
            case .kick:
                await MainActor.run {
                    _Concurrency.Task {
                        await environment.logoutUseCase.execute()
                        showLogin()
                    }
                }
            case let .friendRequest(req):
                _ = req
                try? await friends.refresh()
            case .friendResponse:
                try? await friends.refresh()
            case let .unread(map):
                let selfUID = await MainActor.run { environment.auth.currentUser()?.uid ?? "" }
                for (peer, count) in map {
                    let cid: String
                    if peer.hasPrefix("g_") || peer.hasPrefix("group:") {
                        let gid = peer.hasPrefix("group:") ? String(peer.dropFirst("group:".count)) : peer
                        cid = ConversationID.group(gid)
                    } else {
                        cid = ConversationID.dm(uidA: selfUID, uidB: peer)
                    }
                    try? await conversations.setUnread(conversationId: cid, count: count)
                }
            default:
                break
            }
        }
    }
}
