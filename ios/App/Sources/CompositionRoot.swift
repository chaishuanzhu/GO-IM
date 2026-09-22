import UIKit
import Domain
import Data
import Presentation
import Moya
import Kingfisher

@MainActor
public enum CompositionRoot {
    public private(set) static var environment: AppEnvironment!
    private static var window: UIWindow?

    private static var authPlugin = GOIMAuthPlugin()
    private static var sharedProvider: SharedMoyaProvider!
    private static let keychain = KeychainStore.shared
    private static var stickers = StickerRepositoryImpl()
    private static var pumpTasks: [_Concurrency.Task<Void, Never>] = []
    private static var mountedUID: String?

    public static func bootstrap(window: UIWindow) {
        self.window = window
        GOIMAppearance.apply()

        try? FileManager.default.createDirectory(
            at: UserHome.applicationSupportGOIM,
            withIntermediateDirectories: true
        )
        try? FileManager.default.createDirectory(
            at: UserHome.sharedDirectory,
            withIntermediateDirectories: true
        )
        try? FileManager.default.createDirectory(
            at: UserHome.stickerCatalogDirectory,
            withIntermediateDirectories: true
        )

        // Touch Keychain so legacy session/transport migrate before DevicePreferences reads.
        _ = keychain.loadActiveSession()
        keychain.preferredTransport = keychain.preferredTransport

        var prefs = DevicePreferences.load()
        let baseURLString = prefs.apiBaseURL
            ?? UserDefaults.standard.string(forKey: "goim.apiBaseURL")
            ?? Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String
            ?? "https://im.chaisz.com"
        let baseURL = URL(string: baseURLString) ?? URL(string: "https://im.chaisz.com")!
        if prefs.apiBaseURL == nil {
            prefs.apiBaseURL = baseURL.absoluteString
            prefs.save()
        }
        ServerConfigHolder.shared.baseURL = baseURL

        sharedProvider = SharedMoyaProvider(MoyaProvider<GOIMAPI>(plugins: [authPlugin]))

        if let user = keychain.loadActiveSession() {
            LegacyUserDataMigrator.migrateIfNeeded(activeUID: user.uid)
            mountSession(uid: user.uid, apiBaseURL: baseURL)
            showMain()
            _Concurrency.Task {
                try? await environment.connection.connect(
                    user: user,
                    transport: environment.connection.preferredTransport()
                )
            }
        } else {
            // Lightweight env for login / server settings before any UserHome is open.
            mountAnonymousEnvironment(apiBaseURL: baseURL)
            showLogin()
        }

        _Concurrency.Task {
            await stickers.loadPresetCatalog()
            try? await stickers.syncCatalog(from: environment.stickerCatalogURL)
        }

        // FLEX defaults off; only show if the developer toggle was previously enabled.
        FLEXSupport.applySavedPreference()
    }

    /// After login: remount UserHome, rebuild Data layer, then connect.
    public static func activateLoggedInSession() async {
        guard let user = keychain.loadActiveSession() else {
            showLogin()
            return
        }
        LegacyUserDataMigrator.migrateIfNeeded(activeUID: user.uid)
        let base = ServerConfigHolder.shared.baseURL
        mountSession(uid: user.uid, apiBaseURL: base)
        showMain()
        try? await environment.connection.connect(
            user: user,
            transport: environment.connection.preferredTransport()
        )
    }

    public static func handleLoggedOut() {
        tearDownMountedSession()
        let base = ServerConfigHolder.shared.baseURL
        mountAnonymousEnvironment(apiBaseURL: base)
        showLogin()
    }

    public static func showLogin() {
        guard let window, let env = environment else { return }
        let login = LoginViewController(env: env)
        login.onLoggedIn = {
            _Concurrency.Task {
                await activateLoggedInSession()
            }
        }
        window.rootViewController = UINavigationController(rootViewController: login)
        window.makeKeyAndVisible()
    }

    public static func showMain() {
        guard let window, let env = environment else { return }
        let tabs = MainTabBarController(env: env)
        if let settingsNav = tabs.viewControllers?.last as? UINavigationController,
           let settings = settingsNav.viewControllers.first as? SettingsViewController {
            settings.onLoggedOut = {
                handleLoggedOut()
            }
        }
        window.rootViewController = tabs
        window.makeKeyAndVisible()
    }

    // MARK: - Mount / tear-down

    private static func mountAnonymousEnvironment(apiBaseURL: URL) {
        cancelPumps()
        LocalMediaStore.shared.reset()
        mountedUID = nil
        authPlugin.uid = ""
        authPlugin.token = ""

        let auth = AuthRepositoryImpl(provider: sharedProvider, keychain: keychain, authPlugin: authPlugin)
        let connection = ConnectionRepositoryImpl(serverConfig: ServerConfigHolder.shared, keychain: keychain)
        // In-memory DB while logged out (login screen only).
        let database = try! AppDatabase(path: nil)
        let store = LocalStore(db: database)
        let messages = MessageRepositoryImpl(store: store, connection: connection)
        let conversations = ConversationRepositoryImpl(store: store)
        let groups = GroupRepositoryImpl(provider: sharedProvider, connection: connection, store: store)
        let friends = FriendRepositoryImpl(
            provider: sharedProvider,
            connection: connection,
            store: store,
            selfUIDProvider: { auth.currentUser()?.uid }
        )
        let files = FileRepositoryImpl(provider: sharedProvider, serverConfig: ServerConfigHolder.shared, auth: auth)
        let search = SearchRepositoryImpl(provider: sharedProvider, auth: auth)

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
            apiBaseURL: apiBaseURL
        )
        wireEnvironmentCallbacks()
    }

    private static func mountSession(uid: String, apiBaseURL: URL) {
        tearDownMountedSession(preservingAuthPlugin: true)

        let home = UserHome(uid: uid)
        try? home.ensureDirectories()
        LocalMediaStore.shared.configure(home: home)
        mountedUID = uid

        _Concurrency.Task {
            await stickers.setActiveUID(uid)
        }

        let dbPath = home.databaseURL.path
        let database = try! AppDatabase(path: dbPath)
        let store = LocalStore(db: database)

        let auth = AuthRepositoryImpl(provider: sharedProvider, keychain: keychain, authPlugin: authPlugin)
        if let user = auth.currentUser() {
            authPlugin.uid = user.uid
            authPlugin.token = user.token
        }
        let connection = ConnectionRepositoryImpl(serverConfig: ServerConfigHolder.shared, keychain: keychain)
        let messages = MessageRepositoryImpl(store: store, connection: connection)
        let conversations = ConversationRepositoryImpl(store: store)
        let groups = GroupRepositoryImpl(provider: sharedProvider, connection: connection, store: store)
        let friends = FriendRepositoryImpl(
            provider: sharedProvider,
            connection: connection,
            store: store,
            selfUIDProvider: { auth.currentUser()?.uid }
        )
        let files = FileRepositoryImpl(provider: sharedProvider, serverConfig: ServerConfigHolder.shared, auth: auth)
        let search = SearchRepositoryImpl(provider: sharedProvider, auth: auth)

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
            apiBaseURL: apiBaseURL
        )
        wireEnvironmentCallbacks()

        let inbound = _Concurrency.Task {
            await pumpInboundEvents(
                connection: connection,
                messages: messages,
                conversations: conversations,
                friends: friends
            )
        }
        let statePump = _Concurrency.Task {
            await pumpConnectionState(
                connection: connection,
                messages: messages,
                conversations: conversations
            )
        }
        pumpTasks = [inbound, statePump]
    }

    private static func tearDownMountedSession(preservingAuthPlugin: Bool = false) {
        cancelPumps()
        VoicePlayer.shared.stop()
        MediaPreview.clearPresenter()
        FileDownloadCenter.shared.reset()
        ImageCache.default.clearMemoryCache()
        ImageCache.default.clearDiskCache()
        URLCache.shared.removeAllCachedResponses()
        LocalMediaStore.shared.reset()
        mountedUID = nil
        _Concurrency.Task {
            await stickers.setActiveUID(nil)
        }
        if !preservingAuthPlugin {
            authPlugin.uid = ""
            authPlugin.token = ""
        }
    }

    private static func cancelPumps() {
        for task in pumpTasks {
            task.cancel()
        }
        pumpTasks = []
    }

    private static func wireEnvironmentCallbacks() {
        environment.onAPIBaseURLChange = { url in
            ServerConfigHolder.shared.baseURL = url
            DevicePreferences.update { $0.apiBaseURL = url.absoluteString }
            UserDefaults.standard.set(url.absoluteString, forKey: "goim.apiBaseURL")
        }
        environment.onFLEXEnabledChange = { enabled in
            FLEXSupport.isEnabled = enabled
        }
        environment.onShowFLEXExplorer = {
            FLEXSupport.showExplorer()
        }
    }

    // MARK: - Pumps

    private static func pumpConnectionState(
        connection: ConnectionRepository,
        messages: MessageRepository,
        conversations: ConversationRepository
    ) async {
        for await state in connection.observeState() {
            if _Concurrency.Task.isCancelled { return }
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
                        handleLoggedOut()
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
            if _Concurrency.Task.isCancelled { return }
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
                        handleLoggedOut()
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
