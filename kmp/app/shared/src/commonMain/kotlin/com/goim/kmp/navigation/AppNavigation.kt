package com.goim.kmp.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import androidx.compose.runtime.DisposableEffect
import com.goim.kmp.LocalTitleBarInset
import com.goim.kmp.common.LogBuffer
import com.goim.kmp.common.platformDescription
import androidx.compose.foundation.layout.Spacer
import com.goim.kmp.designsystem.EmptyState
import com.goim.kmp.designsystem.GoImDimens
import com.goim.kmp.designsystem.PaneSurface
import com.goim.kmp.designsystem.GoImFormat
import com.goim.kmp.designsystem.GoImIcons
import com.goim.kmp.domain.LoginUseCase
import com.goim.kmp.domain.MarkReadUseCase
import com.goim.kmp.domain.SendFileMessageUseCase
import com.goim.kmp.domain.SendStickerUseCase
import com.goim.kmp.domain.SendTextMessageUseCase
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.feature.auth.LoginScreen
import com.goim.kmp.feature.auth.LoginViewModel
import com.goim.kmp.feature.chat.ChatMediaDeps
import com.goim.kmp.feature.chat.ChatScreen
import com.goim.kmp.feature.chat.ChatViewModel
import com.goim.kmp.feature.contacts.ContactsScreen
import com.goim.kmp.feature.contacts.ContactsViewModel
import com.goim.kmp.feature.contacts.FriendInfoScreen
import com.goim.kmp.feature.contacts.FriendInfoViewModel
import com.goim.kmp.feature.contacts.GroupInfoScreen
import com.goim.kmp.feature.contacts.GroupInfoViewModel
import com.goim.kmp.feature.contacts.GroupsScreen
import com.goim.kmp.feature.contacts.GroupsViewModel
import com.goim.kmp.feature.contacts.AddFriendScreen
import com.goim.kmp.feature.contacts.AddFriendViewModel
import com.goim.kmp.feature.contacts.NewFriendsScreen
import com.goim.kmp.feature.contacts.NewFriendsViewModel
import com.goim.kmp.feature.conversations.ConversationListScreen
import com.goim.kmp.feature.conversations.ConversationListViewModel
import com.goim.kmp.feature.search.SearchScreen
import com.goim.kmp.feature.search.SearchViewModel
import com.goim.kmp.feature.settings.AccountActions
import com.goim.kmp.feature.settings.DeveloperDeps
import com.goim.kmp.feature.settings.DeveloperScreen
import com.goim.kmp.feature.settings.DeveloperViewModel
import com.goim.kmp.feature.settings.ServerSettingsScreen
import com.goim.kmp.feature.settings.SettingsScreen
import com.goim.kmp.feature.settings.ProfileScreen
import com.goim.kmp.feature.settings.ProfileViewModel
import com.goim.kmp.designsystem.LocalProfileLookup
import com.goim.kmp.designsystem.ProfileLookup
import androidx.compose.runtime.CompositionLocalProvider
import com.goim.kmp.feature.settings.SettingsViewModel
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.Friend
import com.goim.kmp.repository.SessionManager
import com.goim.kmp.repository.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.compose.koinInject

/** 未登录：登录 ⇄ 服务器设置；登录页列出本机已保存的账号可直接切换。 */
@Composable
fun AuthNavHost() {
    val nav = rememberNavController()
    val manager = koinInject<SessionManager>()
    val settings = koinInject<ServerSettingsRepository>()
    val login = koinInject<LoginUseCase>()
    val accounts by manager.accounts.collectAsState()
    val scope = rememberCoroutineScope()

    NavHost(nav, startDestination = LoginRoute) {
        composable<LoginRoute> {
            val vm = viewModel {
                LoginViewModel(login, onLoggedIn = { user -> withContext(Dispatchers.Default) { manager.mount(user) } })
            }
            LoginScreen(
                vm,
                onOpenServerSettings = { nav.navigate(ServerSettingsRoute) },
                savedAccounts = accounts,
                onUseSavedAccount = { uid -> scope.launch(Dispatchers.Default) { manager.switchTo(uid) } },
            )
        }
        composable<ServerSettingsRoute> {
            val vm = viewModel {
                SettingsViewModel(
                    user = null,
                    settings = settings,
                    connectionState = null,
                    switchTransport = { settings.setPreferredTransport(it) },
                )
            }
            ServerSettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}

/** 窗口宽度档位（Material 3 window size class）。 */
private enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

private val ChatRouteSaver = Saver<ChatRoute?, String>(
    save = { route -> route?.let { Json.encodeToString(ChatRoute.serializer(), it) } },
    restore = { Json.decodeFromString(ChatRoute.serializer(), it) },
)

/**
 * 已登录：首页（聊天 / 通讯录 / 搜索 / 设置）→ 聊天、群组、好友等二级页。整个图随 [session] 重建。
 * 宽屏（≥ 840dp）首页为「导航栏 + 列表 + 聊天」三栏，聊天不入栈；窄屏时聊天全屏入栈。
 */
@Composable
fun MainNavHost(session: UserSession) {
    val lookup = remember(session) { ProfileLookup(session.profiles::observe) }
    CompositionLocalProvider(LocalProfileLookup provides lookup) {
        MainNavContent(session)
    }
}

@Composable
private fun MainNavContent(session: UserSession) {
    val nav = rememberNavController()
    val selfUid = session.user.uid

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mode = when {
            maxWidth >= 840.dp -> LayoutMode.EXPANDED
            maxWidth >= 600.dp -> LayoutMode.MEDIUM
            else -> LayoutMode.COMPACT
        }
        val twoPane = mode == LayoutMode.EXPANDED
        var selected by rememberSaveable(stateSaver = ChatRouteSaver) { mutableStateOf<ChatRoute?>(null) }

        fun openChat(route: ChatRoute) {
            if (twoPane) {
                nav.popBackStack(HomeRoute, inclusive = false)
                selected = route
            } else {
                nav.navigate(route)
            }
        }

        fun openInfo(route: ChatRoute) = if (route.chatType == ChatType.GROUP.value) {
            nav.navigate(GroupInfoRoute(route.peer, route.title))
        } else {
            nav.navigate(FriendInfoRoute(route.peer, route.title, fromChat = true))
        }

        // 窗口跨档位时在「右栏」与「全屏入栈」之间迁移当前聊天
        LaunchedEffect(twoPane) {
            if (twoPane) {
                val top = nav.currentBackStackEntry
                if (top != null && top.destination.hasRoute<ChatRoute>()) {
                    val route = top.toRoute<ChatRoute>()
                    nav.popBackStack()
                    selected = route
                }
            } else {
                selected?.let {
                    selected = null
                    nav.navigate(it)
                }
            }
        }

        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    session = session,
                    mode = mode,
                    selected = selected,
                    onOpenChat = ::openChat,
                    onCloseChat = { selected = null },
                    onOpenInfo = ::openInfo,
                    onOpenFriend = { friend -> nav.navigate(FriendInfoRoute(friend.uid, friend.displayName)) },
                    onOpenNewFriends = { nav.navigate(NewFriendsRoute) },
                    onOpenGroups = { nav.navigate(GroupsRoute) },
                    onOpenDeveloper = { nav.navigate(DeveloperRoute) },
                    onOpenProfile = { nav.navigate(ProfileRoute) },
                )
            }
            composable<ChatRoute> { entry ->
                val route = entry.toRoute<ChatRoute>()
                ChatPane(session, route, onBack = { nav.popBackStack() }, onOpenInfo = { openInfo(route) })
            }
            composable<FriendInfoRoute> { entry ->
                val route = entry.toRoute<FriendInfoRoute>()
                val vm = viewModel { FriendInfoViewModel(route.uid, route.name, session.friends) }
                FriendInfoScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onSendMessage = {
                        nav.popBackStack()
                        if (!route.fromChat) {
                            openChat(ChatRoute.of(Conversation(ConversationId.dm(selfUid, route.uid), ChatType.SINGLE, vm.displayName, route.uid)))
                        }
                    },
                    onRemoved = { nav.popBackStack() },
                )
            }
            composable<NewFriendsRoute> {
                val vm = viewModel { NewFriendsViewModel(session.friends) }
                NewFriendsScreen(vm, onAddFriend = { nav.navigate(AddFriendRoute) }, onBack = { nav.popBackStack() })
            }
            composable<AddFriendRoute> {
                val vm = viewModel { AddFriendViewModel(session.friends, session.profiles) }
                AddFriendScreen(vm, selfUid, onBack = { nav.popBackStack() })
            }
            composable<GroupsRoute> {
                val vm = viewModel { GroupsViewModel(session.groups, session.friends) }
                GroupsScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenGroup = { group ->
                        openChat(ChatRoute.of(Conversation(ConversationId.group(group.groupId), ChatType.GROUP, group.displayName, group.groupId)))
                    },
                )
            }
            composable<GroupInfoRoute> { entry ->
                val route = entry.toRoute<GroupInfoRoute>()
                val vm = viewModel { GroupInfoViewModel(route.groupId, route.name, selfUid, session.groups, session.friends) }
                GroupInfoScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    // 退群后回到进入群聊之前的页面（同 iOS）；双栏时关闭右侧群聊
                    onLeft = {
                        if (selected?.peer == route.groupId) selected = null
                        if (!nav.popBackStack<ChatRoute>(inclusive = true)) nav.popBackStack()
                    },
                )
            }
            composable<ProfileRoute> {
                val vm = viewModel { ProfileViewModel(session.profiles) }
                ProfileScreen(vm, onBack = { nav.popBackStack() })
            }
            composable<DeveloperRoute> {
                DeveloperDestination(session, onBack = { nav.popBackStack() })
            }
        }
    }
}

/** 聊天页：全屏入栈时用导航条目的 ViewModel 作用域，右栏时由 [ScopedViewModelStore] 提供。 */
@Composable
private fun ChatPane(session: UserSession, route: ChatRoute, onBack: (() -> Unit)?, onOpenInfo: () -> Unit) {
    val conversation = route.toConversation()
    val vm = viewModel {
        ChatViewModel(
            conversation = conversation,
            self = session.user,
            messages = session.messages,
            conversations = session.conversations,
            sendText = SendTextMessageUseCase(session.messages, session.conversations),
            markRead = MarkReadUseCase(session.messages, session.conversations),
            connectionState = session.connection.state,
            focus = route.focus(),
            mediaDeps = ChatMediaDeps(
                files = session.files,
                stickers = session.stickers,
                sendFile = SendFileMessageUseCase(session.files, session.messages, session.conversations),
                sendSticker = SendStickerUseCase(session.messages, session.conversations, session.stickers),
                voiceDir = session.home.tmp.toString(),
                // 群聊：群成员；单聊：对方 + 好友（同 iOS）
                mentionCandidates = {
                    if (conversation.chatType == ChatType.GROUP) {
                        session.groups.members(conversation.peerOrGroupId).map { it.uid }
                    } else {
                        listOf(conversation.peerOrGroupId) + session.friends.observeFriends().first().map { it.uid }
                    }
                },
            ),
        )
    }
    ChatScreen(
        vm,
        onBack = onBack,
        onOpenInfo = onOpenInfo,
    )
}

@Composable
private fun DeveloperDestination(session: UserSession, onBack: () -> Unit) {
    val settings = koinInject<ServerSettingsRepository>()
    val imageLoader = SingletonImageLoader.get(LocalPlatformContext.current)
    val vm = viewModel {
        DeveloperViewModel(
            DeveloperDeps(
                logs = LogBuffer.shared.entries,
                clearLogs = LogBuffer.shared::clear,
                connectionState = session.connection.state,
                reconnect = {
                    session.connection.disconnect()
                    session.connection.connect(session.user, settings.preferredTransport.value)
                },
                info = {
                    listOf(
                        "账号" to "${session.profiles.me.value.displayName} (${session.user.uid})",
                        "API" to settings.apiBaseUrl.value,
                        "传输" to settings.preferredTransport.value.title,
                        "平台" to platformDescription,
                        "数据目录" to session.home.root.toString(),
                    )
                },
                databaseStats = session::databaseStats,
                storageUsage = session::storageUsage,
                exportDatabase = session::exportDatabase,
                discardExport = session::deleteExport,
                clearImageCache = {
                    imageLoader.memoryCache?.clear()
                    imageLoader.diskCache?.clear()
                },
            ),
        )
    }
    DeveloperScreen(vm, onBack)
}

private enum class HomeTab(val label: String) { CHATS("聊天"), CONTACTS("通讯录"), SEARCH("搜索"), SETTINGS("设置") }

@Composable
private fun HomeScreen(
    session: UserSession,
    mode: LayoutMode,
    selected: ChatRoute?,
    onOpenChat: (ChatRoute) -> Unit,
    onCloseChat: () -> Unit,
    onOpenInfo: (ChatRoute) -> Unit,
    onOpenFriend: (Friend) -> Unit,
    onOpenNewFriends: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val manager = koinInject<SessionManager>()
    val settings = koinInject<ServerSettingsRepository>()
    var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
    val selfUid = session.user.uid
    val twoPane = mode == LayoutMode.EXPANDED

    val totalUnread by remember(session) { session.conversations.observeTotalUnread() }.collectAsState(0)
    val pendingRequests by remember(session) { session.friends.observeRequests().map { it.size } }.collectAsState(0)
    val me by session.profiles.me.collectAsState()

    val conversationsVm = viewModel {
        ConversationListViewModel(session.user.uid, session.conversations, session.connection.state)
    }
    val contactsVm = viewModel { ContactsViewModel(session.friends) }
    val searchVm = viewModel { SearchViewModel(session.search, session.conversations) }
    val settingsVm = viewModel {
        SettingsViewModel(
            user = session.user,
            settings = settings,
            connectionState = session.connection.state,
            switchTransport = { kind ->
                settings.setPreferredTransport(kind)
                session.connection.disconnect()
                session.connection.connect(session.user, kind)
            },
            logout = { manager.logout() },
            accountActions = AccountActions(
                accounts = manager.accounts,
                switchTo = { uid -> withContext(Dispatchers.Default) { manager.switchTo(uid) } },
                addAccount = { withContext(Dispatchers.Default) { manager.addAccount() } },
                forget = manager::forget,
            ),
        )
    }

    fun badge(t: HomeTab) = when (t) {
        HomeTab.CHATS -> totalUnread
        HomeTab.CONTACTS -> pendingRequests
        else -> 0
    }

    val list: @Composable () -> Unit = {
        when (tab) {
            HomeTab.CHATS -> ConversationListScreen(
                conversationsVm,
                onOpenChat = { onOpenChat(ChatRoute.of(it)) },
                selectedId = selected?.conversationId?.takeIf { twoPane },
            )
            HomeTab.CONTACTS -> ContactsScreen(contactsVm, onOpenNewFriends, onOpenGroups, onOpenFriend)
            HomeTab.SEARCH -> SearchScreen(searchVm) { hit, title -> onOpenChat(ChatRoute.of(hit, title)) }
            HomeTab.SETTINGS -> SettingsScreen(settingsVm, onOpenDeveloper = onOpenDeveloper, profile = me, onOpenProfile = onOpenProfile)
        }
    }

    // 桌面 / 硬件键盘：⌘/Ctrl+1…4 切 Tab，⌘/Ctrl+F 搜索，Esc 关闭右栏聊天
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shortcuts = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val command = event.isMetaPressed || event.isCtrlPressed
            val target = when {
                command && event.key == Key.One -> HomeTab.CHATS
                command && event.key == Key.Two -> HomeTab.CONTACTS
                command && (event.key == Key.Three || event.key == Key.F) -> HomeTab.SEARCH
                command && event.key == Key.Four -> HomeTab.SETTINGS
                else -> null
            }
            when {
                target != null -> {
                    tab = target
                    true
                }
                event.key == Key.Escape && twoPane && selected != null -> {
                    onCloseChat()
                    true
                }
                else -> false
            }
        }

    val railed = mode != LayoutMode.COMPACT
    val titleBar = LocalTitleBarInset.current?.takeIf { twoPane }
    if (titleBar != null) {
        DisposableEffect(titleBar) {
            titleBar.claimed = true
            onDispose { titleBar.claimed = false }
        }
    }
    Scaffold(
        modifier = shortcuts,
        containerColor = if (twoPane) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        // 外层只让出底部导航栏；状态栏 / 刘海由各 Tab 自己的 TopAppBar 处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (mode == LayoutMode.COMPACT) {
                NavigationBar {
                    HomeTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, badge(t)) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                // 三栏的圆角面板整体放在状态栏之下，面板内的 TopAppBar 不再重复让位
                .then(if (twoPane) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)) else Modifier),
        ) {
            if (railed) {
                NavigationRail(
                    containerColor = if (twoPane) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
                    // 面板顶到窗口顶部，导航栏单独避开 macOS 红绿灯
                    modifier = Modifier.padding(top = 8.dp + (titleBar?.height ?: 0.dp)),
                ) {
                    HomeTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { TabIcon(t, badge(t)) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
            if (twoPane) {
                val paneModifier = Modifier.fillMaxHeight().padding(vertical = 8.dp)
                if (tab != HomeTab.SETTINGS) {
                    PaneSurface(paneModifier.width(GoImDimens.listPaneWidth)) { list() }
                    Spacer(Modifier.width(8.dp))
                    PaneSurface(paneModifier.weight(1f).padding(end = 8.dp)) {
                        val route = selected
                        if (route == null) {
                            EmptyState(GoImIcons.Chat, "选择一个会话开始聊天", "或按 ⌘/Ctrl+F 搜索消息")
                        } else {
                            ScopedViewModelStore(route) {
                                ChatPane(session, route, onBack = null, onOpenInfo = { onOpenInfo(route) })
                            }
                        }
                    }
                } else {
                    PaneSurface(paneModifier.weight(1f).padding(end = 8.dp)) { list() }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxHeight()) { list() }
            }
        }
    }
}

@Composable
private fun TabIcon(tab: HomeTab, badge: Int) {
    val icon: ImageVector = when (tab) {
        HomeTab.CHATS -> GoImIcons.Chat
        HomeTab.CONTACTS -> GoImIcons.Contacts
        HomeTab.SEARCH -> Icons.Filled.Search
        HomeTab.SETTINGS -> Icons.Filled.Settings
    }
    BadgedBox(
        badge = {
            if (badge > 0) {
                Badge(Modifier.semantics { contentDescription = "$badge 条未处理" }) { Text(GoImFormat.unreadBadge(badge)) }
            }
        },
    ) {
        Icon(icon, contentDescription = null)
    }
}