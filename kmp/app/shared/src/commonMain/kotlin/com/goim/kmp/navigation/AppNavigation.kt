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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
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
import com.goim.kmp.feature.chat.MessageBubble
import com.goim.kmp.feature.chat.ReadOnlyBubbles
import com.goim.kmp.feature.contacts.ContactsScreen
import com.goim.kmp.feature.contacts.ContactsViewModel
import com.goim.kmp.feature.contacts.FriendInfoScreen
import com.goim.kmp.feature.contacts.FriendInfoViewModel
import com.goim.kmp.feature.contacts.GroupInfoScreen
import com.goim.kmp.feature.contacts.GroupInfoViewModel
import com.goim.kmp.feature.contacts.GroupsScreen
import com.goim.kmp.feature.contacts.GroupsViewModel
import com.goim.kmp.feature.contacts.CreateGroupScreen
import com.goim.kmp.feature.contacts.CreateGroupViewModel
import com.goim.kmp.feature.contacts.JoinGroupScreen
import com.goim.kmp.feature.contacts.JoinGroupViewModel
import com.goim.kmp.feature.contacts.AddFriendScreen
import com.goim.kmp.feature.contacts.AddFriendViewModel
import com.goim.kmp.feature.contacts.NewFriendsScreen
import com.goim.kmp.feature.contacts.NewFriendsViewModel
import com.goim.kmp.feature.conversations.ConversationListScreen
import com.goim.kmp.feature.conversations.ConversationListViewModel
import com.goim.kmp.feature.search.SearchScreen
import com.goim.kmp.feature.search.SearchViewModel
import com.goim.kmp.feature.settings.AccountActions
import com.goim.kmp.feature.settings.AdminChatScreen
import com.goim.kmp.feature.settings.AdminChatViewModel
import com.goim.kmp.feature.settings.AdminConversationsScreen
import com.goim.kmp.feature.settings.AdminConversationsViewModel
import com.goim.kmp.feature.settings.AdminDeps
import com.goim.kmp.feature.settings.AdminScreen
import com.goim.kmp.feature.settings.AdminUserDetailScreen
import com.goim.kmp.feature.settings.AdminViewModel
import com.goim.kmp.network.GoImUserApi
import com.goim.kmp.feature.settings.DeveloperDeps
import com.goim.kmp.feature.settings.DeveloperScreen
import com.goim.kmp.feature.settings.DeveloperViewModel
import com.goim.kmp.feature.settings.ServerSettingsScreen
import com.goim.kmp.feature.settings.SettingsScreen
import com.goim.kmp.feature.settings.ProfileScreen
import com.goim.kmp.feature.settings.ProfileViewModel
import com.goim.kmp.designsystem.LocalProfileLookup
import com.goim.kmp.designsystem.ProfileLookup
import com.goim.kmp.designsystem.GoImSpacing
import androidx.compose.runtime.CompositionLocalProvider
import com.goim.kmp.feature.settings.SettingsViewModel
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.Group
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
import com.goim.kmp.feature.contacts.Presentation
import com.goim.kmp.feature.contacts.ContactsSelection
import com.goim.kmp.designsystem.GoImElevation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog

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

private val ContactDetailSaver = Saver<ContactDetail?, String>(
    save = { detail -> detail?.let { Json.encodeToString(ContactDetail.serializer(), it) } },
    restore = { Json.decodeFromString(ContactDetail.serializer(), it) },
)

/**
 * 已登录：首页（聊天 / 通讯录 / 搜索 / 设置）→ 聊天、群组、好友等二级页。整个图随 [session] 重建。
 * 宽屏（≥ 840dp）首页为「导航栏 + 列表 + 详情」三栏：聊天、通讯录详情显示在右栏，群 / 好友信息是聊天旁的侧边面板，
 * 添加好友、创建 / 加入群聊是弹窗；窄屏时它们都是全屏入栈的二级页。
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
            maxWidth >= GoImDimens.expandedBreakpoint -> LayoutMode.EXPANDED
            maxWidth >= GoImDimens.mediumBreakpoint -> LayoutMode.MEDIUM
            else -> LayoutMode.COMPACT
        }
        val twoPane = mode == LayoutMode.EXPANDED
        var tab by rememberSaveable { mutableStateOf(HomeTab.CHATS) }
        var selected by rememberSaveable(stateSaver = ChatRouteSaver) { mutableStateOf<ChatRoute?>(null) }
        var chatInfoOpen by rememberSaveable { mutableStateOf(false) }
        var contactDetail by rememberSaveable(stateSaver = ContactDetailSaver) { mutableStateOf<ContactDetail?>(null) }
        var dialog by rememberSaveable { mutableStateOf<ContactDialog?>(null) }

        fun openChat(route: ChatRoute) {
            if (twoPane) {
                nav.popBackStack(HomeRoute, inclusive = false)
                if (selected?.conversationId != route.conversationId) chatInfoOpen = false
                selected = route
                if (tab == HomeTab.CONTACTS) tab = HomeTab.CHATS
            } else {
                nav.navigate(route)
            }
        }

        fun openGroupChat(group: Group) =
            openChat(ChatRoute.of(Conversation(ConversationId.group(group.groupId), ChatType.GROUP, group.displayName, group.groupId)))

        fun openDirectChat(uid: String, name: String) =
            openChat(ChatRoute.of(Conversation(ConversationId.dm(selfUid, uid), ChatType.SINGLE, name, uid)))

        fun navigateInfo(route: ChatRoute) = if (route.chatType == ChatType.GROUP.value) {
            nav.navigate(GroupInfoRoute(route.peer, route.title))
        } else {
            nav.navigate(FriendInfoRoute(route.peer, route.title, fromChat = true))
        }

        fun openInfo(route: ChatRoute) = if (twoPane) chatInfoOpen = !chatInfoOpen else navigateInfo(route)

        fun navigateContact(detail: ContactDetail) = when (detail) {
            ContactDetail.NewFriends -> nav.navigate(NewFriendsRoute)
            ContactDetail.Groups -> nav.navigate(GroupsRoute)
            is ContactDetail.Person -> nav.navigate(FriendInfoRoute(detail.uid, detail.name))
        }

        fun openContact(detail: ContactDetail) = if (twoPane) contactDetail = detail else navigateContact(detail)

        fun navigateDialog(form: ContactDialog) = when (form) {
            ContactDialog.ADD_FRIEND -> nav.navigate(AddFriendRoute)
            ContactDialog.CREATE_GROUP -> nav.navigate(CreateGroupRoute)
            ContactDialog.JOIN_GROUP -> nav.navigate(JoinGroupRoute)
        }

        fun openDialog(form: ContactDialog) = if (twoPane) dialog = form else navigateDialog(form)

        // 窗口跨档位时在「右栏 / 侧边面板 / 弹窗」与「全屏入栈」之间迁移当前页面
        LaunchedEffect(twoPane) {
            if (twoPane) {
                val top = nav.currentBackStackEntry ?: return@LaunchedEffect
                val destination = top.destination
                when {
                    destination.hasRoute<ChatRoute>() -> selected = top.toRoute<ChatRoute>()
                    destination.hasRoute<GroupInfoRoute>() ||
                        (destination.hasRoute<FriendInfoRoute>() && top.toRoute<FriendInfoRoute>().fromChat) -> {
                        val chat = nav.previousBackStackEntry?.takeIf { it.destination.hasRoute<ChatRoute>() }
                        selected = chat?.toRoute<ChatRoute>() ?: return@LaunchedEffect
                        chatInfoOpen = true
                    }
                    destination.hasRoute<FriendInfoRoute>() -> top.toRoute<FriendInfoRoute>().let {
                        contactDetail = ContactDetail.Person(it.uid, it.name)
                    }
                    destination.hasRoute<NewFriendsRoute>() -> contactDetail = ContactDetail.NewFriends
                    destination.hasRoute<AddFriendRoute>() -> {
                        contactDetail = ContactDetail.NewFriends
                        dialog = ContactDialog.ADD_FRIEND
                    }
                    destination.hasRoute<GroupsRoute>() -> contactDetail = ContactDetail.Groups
                    destination.hasRoute<CreateGroupRoute>() || destination.hasRoute<JoinGroupRoute>() -> {
                        contactDetail = ContactDetail.Groups
                        dialog = if (destination.hasRoute<CreateGroupRoute>()) ContactDialog.CREATE_GROUP else ContactDialog.JOIN_GROUP
                    }
                    else -> return@LaunchedEffect
                }
                nav.popBackStack(HomeRoute, inclusive = false)
            } else if (tab == HomeTab.CONTACTS) {
                contactDetail?.let(::navigateContact)
                dialog?.let(::navigateDialog)
                contactDetail = null
                dialog = null
            } else {
                selected?.let {
                    selected = null
                    nav.navigate(it)
                    if (chatInfoOpen) navigateInfo(it)
                }
                chatInfoOpen = false
            }
        }

        val chatDetail: @Composable () -> Unit = {
            val route = selected
            if (route == null) {
                EmptyState(GoImIcons.Chat, "选择一个会话开始聊天", "或按 ⌘/Ctrl+F 搜索消息")
            } else {
                ChatWithInfoPanel(
                    session = session,
                    route = route,
                    infoOpen = chatInfoOpen,
                    onToggleInfo = { chatInfoOpen = !chatInfoOpen },
                    onLeftGroup = {
                        chatInfoOpen = false
                        selected = null
                    },
                )
            }
        }

        val contactsDetail: @Composable () -> Unit = {
            when (val detail = contactDetail) {
                null -> EmptyState(GoImIcons.Contacts, "选择联系人查看详情", "也可以打开「新的朋友」或「群组」")
                else -> ScopedViewModelStore(detail) {
                    ContactDetailPane(
                        session = session,
                        detail = detail,
                        onClose = { contactDetail = null },
                        onOpenDialog = ::openDialog,
                        onOpenGroupChat = ::openGroupChat,
                        onOpenDirectChat = ::openDirectChat,
                    )
                }
            }
        }

        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    session = session,
                    mode = mode,
                    tab = tab,
                    onTabChange = { tab = it },
                    selectedChatId = selected?.conversationId,
                    contactsSelection = contactDetail?.toSelection(),
                    onOpenChat = ::openChat,
                    onOpenFriend = { friend -> openContact(ContactDetail.Person(friend.uid, friend.displayName)) },
                    onOpenNewFriends = { openContact(ContactDetail.NewFriends) },
                    onOpenGroups = { openContact(ContactDetail.Groups) },
                    onOpenDeveloper = { nav.navigate(DeveloperRoute) },
                    onOpenProfile = { nav.navigate(ProfileRoute) },
                    onOpenAdmin = if (session.adminApi != null) {
                        { nav.navigate(AdminRoute) }
                    } else {
                        null
                    },
                    onEscape = {
                        when {
                            tab == HomeTab.CONTACTS && contactDetail != null -> contactDetail = null
                            tab != HomeTab.CONTACTS && chatInfoOpen -> chatInfoOpen = false
                            tab != HomeTab.CONTACTS && selected != null -> selected = null
                            else -> return@HomeScreen false
                        }
                        true
                    },
                    detailPane = if (tab == HomeTab.CONTACTS) contactsDetail else chatDetail,
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
                        if (!route.fromChat) openDirectChat(route.uid, vm.displayName)
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
                val vm = viewModel { GroupsViewModel(session.groups) }
                GroupsScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onOpenGroup = ::openGroupChat,
                    onCreateGroup = { nav.navigate(CreateGroupRoute) },
                    onJoinGroup = { nav.navigate(JoinGroupRoute) },
                )
            }
            composable<CreateGroupRoute> {
                val vm = viewModel { CreateGroupViewModel(session.groups, session.friends) }
                CreateGroupScreen(vm, onBack = { nav.popBackStack() }, onCreated = { group ->
                    nav.popBackStack()
                    openGroupChat(group)
                })
            }
            composable<JoinGroupRoute> {
                val vm = viewModel { JoinGroupViewModel(session.groups) }
                JoinGroupScreen(vm, onBack = { nav.popBackStack() }, onJoined = { group ->
                    nav.popBackStack()
                    openGroupChat(group)
                })
            }
            composable<GroupInfoRoute> { entry ->
                val route = entry.toRoute<GroupInfoRoute>()
                val vm = viewModel { GroupInfoViewModel(route.groupId, route.name, selfUid, session.groups, session.friends) }
                GroupInfoScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    // 退群后回到进入群聊之前的页面（同 iOS）
                    onLeft = { if (!nav.popBackStack<ChatRoute>(inclusive = true)) nav.popBackStack() },
                )
            }
            composable<ProfileRoute> {
                val vm = viewModel { ProfileViewModel(session.profiles) }
                ProfileScreen(vm, onBack = { nav.popBackStack() })
            }
            composable<DeveloperRoute> {
                DeveloperDestination(session, onBack = { nav.popBackStack() })
            }
            composable<AdminRoute> {
                val api = session.adminApi
                if (api == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    val vm = viewModel { AdminViewModel(adminDeps(session.user.uid, api)) }
                    AdminScreen(
                        vm,
                        onBack = { nav.popBackStack() },
                        onOpenUser = { user -> nav.navigate(AdminUserRoute(user.uid)) },
                    )
                }
            }
            composable<AdminUserRoute> { entry ->
                val route = entry.toRoute<AdminUserRoute>()
                val api = session.adminApi
                if (api == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    val console = remember(entry) { nav.getBackStackEntry<AdminRoute>() }
                    val vm = viewModel(console) { AdminViewModel(adminDeps(session.user.uid, api)) }
                    AdminUserDetailScreen(
                        vm,
                        uid = route.uid,
                        onBack = { nav.popBackStack() },
                        onOpenConversations = { user -> nav.navigate(AdminUserConversationsRoute(user.uid, user.displayName)) },
                    )
                }
            }
            composable<AdminUserConversationsRoute> { entry ->
                val route = entry.toRoute<AdminUserConversationsRoute>()
                val api = session.adminApi
                if (api == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    val vm = viewModel { AdminConversationsViewModel { api.adminConversations(route.uid) } }
                    AdminConversationsScreen(
                        vm,
                        userName = route.name,
                        onBack = { nav.popBackStack() },
                        onOpen = { c ->
                            nav.navigate(AdminConversationRoute(route.uid, route.name, c.peer, c.chatType.value, c.displayTitle))
                        },
                    )
                }
            }
            composable<AdminConversationRoute> { entry ->
                val route = entry.toRoute<AdminConversationRoute>()
                val api = session.adminApi
                if (api == null) {
                    LaunchedEffect(Unit) { nav.popBackStack() }
                } else {
                    val chatType = ChatType.from(route.chatType)
                    val vm = viewModel {
                        AdminChatViewModel(
                            load = { before, limit -> api.adminConversationMessages(route.uid, route.peer, chatType, before, limit) },
                            deleteMessage = api::adminDeleteMessage,
                        )
                    }
                    ReadOnlyBubbles(session.files, session.stickers, onError = vm::showNotice) {
                        AdminChatScreen(
                            vm,
                            title = route.title,
                            subtitle = if (chatType == ChatType.GROUP) "群聊 · 全部成员的消息" else "${route.name} 的单聊",
                            selfUid = route.uid,
                            onBack = { nav.popBackStack() },
                            bubble = { message, sender, outgoing, maxWidth, onLongPress ->
                                MessageBubble(
                                    message.toChatMessage(outgoing),
                                    senderName = sender?.nickname?.takeIf { it.isNotBlank() } ?: message.fromUid,
                                    avatarUrl = sender?.avatarUrl,
                                    maxBubbleWidth = maxWidth,
                                    onLongPress = onLongPress,
                                )
                            },
                        )
                    }
                }
            }
        }

        val form = dialog
        if (twoPane && form != null) {
            FormDialog(form, onDismiss = { dialog = null }) {
                ScopedViewModelStore(form) {
                    ContactFormDialogContent(
                        session = session,
                        form = form,
                        onDismiss = { dialog = null },
                        onOpenGroupChat = { group ->
                            dialog = null
                            openGroupChat(group)
                        },
                    )
                }
            }
        }
    }
}

private fun ContactDetail.toSelection(): ContactsSelection = when (this) {
    ContactDetail.NewFriends -> ContactsSelection.NewFriends
    ContactDetail.Groups -> ContactsSelection.Groups
    is ContactDetail.Person -> ContactsSelection.Person(uid)
}

/** 宽屏右栏的聊天；群 / 好友信息作为侧边面板，聊天栏够宽时并排，否则浮在聊天右侧。 */
@Composable
private fun ChatWithInfoPanel(
    session: UserSession,
    route: ChatRoute,
    infoOpen: Boolean,
    onToggleInfo: () -> Unit,
    onLeftGroup: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inline = maxWidth >= GoImDimens.chatWithPanelMinWidth
        val panel: @Composable () -> Unit = {
            ScopedViewModelStore(route.conversationId to "info") {
                ChatInfoPanel(session, route, onClose = onToggleInfo, onLeftGroup = onLeftGroup)
            }
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                ScopedViewModelStore(route) {
                    ChatPane(session, route, onBack = null, onOpenInfo = onToggleInfo)
                }
            }
            if (infoOpen && inline) {
                VerticalDivider()
                Box(Modifier.width(GoImDimens.sidePanelWidth).fillMaxHeight()) { panel() }
            }
        }
        if (infoOpen && !inline) {
            Surface(
                Modifier.align(Alignment.CenterEnd).width(GoImDimens.sidePanelWidth).fillMaxHeight(),
                shadowElevation = GoImElevation.level3,
            ) { panel() }
        }
    }
}

@Composable
private fun ChatInfoPanel(session: UserSession, route: ChatRoute, onClose: () -> Unit, onLeftGroup: () -> Unit) {
    if (route.chatType == ChatType.GROUP.value) {
        val vm = viewModel {
            GroupInfoViewModel(route.peer, route.title, session.user.uid, session.groups, session.friends)
        }
        GroupInfoScreen(vm, onBack = onClose, onLeft = onLeftGroup, presentation = Presentation.Overlay)
    } else {
        val vm = viewModel { FriendInfoViewModel(route.peer, route.title, session.friends) }
        FriendInfoScreen(vm, onBack = onClose, onSendMessage = null, onRemoved = onClose, presentation = Presentation.Overlay)
    }
}

/** 宽屏通讯录右栏：新的朋友、群组、好友信息。 */
@Composable
private fun ContactDetailPane(
    session: UserSession,
    detail: ContactDetail,
    onClose: () -> Unit,
    onOpenDialog: (ContactDialog) -> Unit,
    onOpenGroupChat: (Group) -> Unit,
    onOpenDirectChat: (uid: String, name: String) -> Unit,
) {
    when (detail) {
        ContactDetail.NewFriends -> {
            val vm = viewModel { NewFriendsViewModel(session.friends) }
            NewFriendsScreen(
                vm,
                onAddFriend = { onOpenDialog(ContactDialog.ADD_FRIEND) },
                onBack = onClose,
                presentation = Presentation.Pane,
            )
        }
        ContactDetail.Groups -> {
            val vm = viewModel { GroupsViewModel(session.groups) }
            GroupsScreen(
                vm,
                onBack = onClose,
                onOpenGroup = onOpenGroupChat,
                onCreateGroup = { onOpenDialog(ContactDialog.CREATE_GROUP) },
                onJoinGroup = { onOpenDialog(ContactDialog.JOIN_GROUP) },
                presentation = Presentation.Pane,
            )
        }
        is ContactDetail.Person -> {
            val vm = viewModel { FriendInfoViewModel(detail.uid, detail.name, session.friends) }
            FriendInfoScreen(
                vm,
                onBack = onClose,
                onSendMessage = { onOpenDirectChat(detail.uid, vm.displayName) },
                onRemoved = onClose,
                presentation = Presentation.Pane,
            )
        }
    }
}

@Composable
private fun ContactFormDialogContent(
    session: UserSession,
    form: ContactDialog,
    onDismiss: () -> Unit,
    onOpenGroupChat: (Group) -> Unit,
) {
    when (form) {
        ContactDialog.ADD_FRIEND -> {
            val vm = viewModel { AddFriendViewModel(session.friends, session.profiles) }
            AddFriendScreen(vm, session.user.uid, onBack = onDismiss, presentation = Presentation.Overlay)
        }
        ContactDialog.CREATE_GROUP -> {
            val vm = viewModel { CreateGroupViewModel(session.groups, session.friends) }
            CreateGroupScreen(vm, onBack = onDismiss, onCreated = onOpenGroupChat, presentation = Presentation.Overlay)
        }
        ContactDialog.JOIN_GROUP -> {
            val vm = viewModel { JoinGroupViewModel(session.groups) }
            JoinGroupScreen(vm, onBack = onDismiss, onJoined = onOpenGroupChat, presentation = Presentation.Overlay)
        }
    }
}

/** 宽屏表单弹窗：固定宽度，高度随表单类型；窗口太小时贴边留 s6。 */
@Composable
private fun FormDialog(form: ContactDialog, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val height = if (form == ContactDialog.JOIN_GROUP) GoImDimens.compactDialogHeight else GoImDimens.dialogMaxHeight
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .padding(GoImSpacing.s6)
                .widthIn(max = GoImDimens.dialogWidth)
                .heightIn(max = height)
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = GoImElevation.level3,
            content = content,
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    session: UserSession,
    mode: LayoutMode,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    selectedChatId: String?,
    contactsSelection: ContactsSelection?,
    onOpenChat: (ChatRoute) -> Unit,
    onOpenFriend: (Friend) -> Unit,
    onOpenNewFriends: () -> Unit,
    onOpenGroups: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAdmin: (() -> Unit)?,
    onEscape: () -> Boolean,
    detailPane: @Composable () -> Unit,
) {
    val manager = koinInject<SessionManager>()
    val settings = koinInject<ServerSettingsRepository>()
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
                selectedId = selectedChatId?.takeIf { twoPane },
            )
            HomeTab.CONTACTS -> ContactsScreen(
                contactsVm,
                onOpenNewFriends,
                onOpenGroups,
                onOpenFriend,
                selection = contactsSelection.takeIf { twoPane },
            )
            HomeTab.SEARCH -> SearchScreen(searchVm) { hit, title -> onOpenChat(ChatRoute.of(hit, title)) }
            HomeTab.SETTINGS -> SettingsScreen(
                settingsVm,
                onOpenDeveloper = onOpenDeveloper,
                profile = me,
                onOpenProfile = onOpenProfile,
                onOpenAdmin = onOpenAdmin,
            )
        }
    }

    // 桌面 / 硬件键盘：⌘/Ctrl+1…4 切 Tab，⌘/Ctrl+F 搜索，Esc 逐层关闭侧边面板与右栏
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
                    onTabChange(target)
                    true
                }
                event.key == Key.Escape && twoPane -> onEscape()
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
                Column {
                    HorizontalDivider()
                    // iOS TabBar 风格：不画选中指示器、点击无水波纹，只用图标与文字颜色区分
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = GoImElevation.level0) {
                            val itemColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HomeTab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { onTabChange(t) },
                                    icon = { TabIcon(t, badge(t)) },
                                    label = { Text(t.label) },
                                    colors = itemColors,
                                )
                            }
                        }
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
                    modifier = Modifier.padding(top = GoImSpacing.s2 + (titleBar?.height ?: 0.dp)),
                ) {
                    HomeTab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { onTabChange(t) },
                            icon = { TabIcon(t, badge(t)) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
            if (twoPane) {
                val paneModifier = Modifier.fillMaxHeight().padding(vertical = GoImSpacing.s2)
                if (tab != HomeTab.SETTINGS) {
                    PaneSurface(paneModifier.width(GoImDimens.listPaneWidth)) { list() }
                    Spacer(Modifier.width(GoImSpacing.s2))
                    PaneSurface(paneModifier.weight(1f).padding(end = GoImSpacing.s2)) { detailPane() }
                } else {
                    PaneSurface(paneModifier.weight(1f).padding(end = GoImSpacing.s2)) { list() }
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

private fun adminDeps(selfUid: String, api: GoImUserApi) = AdminDeps(
    selfUid = selfUid,
    stats = api::adminStats,
    users = api::adminUsers,
    deleteUser = api::adminDeleteUser,
    setUserDisabled = api::adminSetUserDisabled,
    messages = api::adminMessages,
    deleteMessage = api::adminDeleteMessage,
)

/** 管理后台的服务端消息转成聊天气泡使用的模型；[outgoing] 即是否为被查看用户发出。 */
private fun AdminMessage.toChatMessage(outgoing: Boolean) = Message(
    id = msgId.toString(),
    serverMsgId = msgId,
    clientSeq = 0,
    conversationId = "",
    fromUid = fromUid,
    toUid = toUid,
    chatType = chatType,
    msgType = msgType,
    content = content,
    timestampMs = timestampMs,
    status = if (recalled) MessageStatus.RECALLED else MessageStatus.SENT,
    isOutgoing = outgoing,
)