package com.goim.kmp.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import com.goim.kmp.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 提示信息 + 进行中状态，各页面共用。 */
abstract class ActionViewModel : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    protected fun notify(text: String) {
        _message.value = text
    }

    /** 执行写操作；失败转成中文提示，成功时回调。 */
    protected fun action(success: String? = null, block: suspend () -> Unit, then: () -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                success?.let(::notify)
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.userMessage())
            } finally {
                _busy.value = false
            }
        }
    }
}

data class FriendSection(val letter: String, val friends: List<Friend>)

data class ContactsUiState(
    val sections: List<FriendSection> = emptyList(),
    val pendingCount: Int = 0,
    val loaded: Boolean = false,
)

class ContactsViewModel(private val friends: FriendRepository) : ActionViewModel() {
    val state: StateFlow<ContactsUiState> = combine(
        friends.observeFriends().catch { emit(emptyList()) },
        friends.observeRequests().catch { emit(emptyList()) },
    ) { list, requests -> ContactsUiState(sectionize(list), requests.size, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    init {
        refresh()
    }

    fun refresh() = action(block = { friends.refresh() })

    companion object {
        /** 按首字母分组（同 iOS 通讯录），非拉丁字母归入 "#" 并排在最后。 */
        fun sectionize(list: List<Friend>): List<FriendSection> = list
            .groupBy { friend ->
                val first = friend.displayName.firstOrNull()?.uppercaseChar()
                if (first != null && first in 'A'..'Z') first.toString() else "#"
            }
            .map { (letter, items) -> FriendSection(letter, items.sortedBy { it.displayName.lowercase() }) }
            .sortedWith(compareBy({ it.letter == "#" }, { it.letter }))
    }
}

data class UserSearchState(
    val query: String = "",
    val results: List<UserProfile> = emptyList(),
    val searching: Boolean = false,
    /** 已完成至少一次搜索（区分「未搜索」与「无结果」）。 */
    val searched: Boolean = false,
    val error: String? = null,
)

class NewFriendsViewModel(
    private val friends: FriendRepository,
    private val profiles: ProfileRepository? = null,
) : ActionViewModel() {
    val requests: StateFlow<List<FriendRequest>> = friends.observeRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val friendUids: StateFlow<Set<String>> = friends.observeFriends()
        .map<List<Friend>, Set<String>> { list -> list.mapTo(HashSet()) { it.uid } }
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _search = MutableStateFlow(UserSearchState())
    val search: StateFlow<UserSearchState> = _search.asStateFlow()
    private var searchJob: Job? = null

    init {
        action(block = { friends.refresh() })
    }

    fun sendRequest(uid: String) = action(success = "好友请求已发送", block = { friends.sendRequest(uid) })

    fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query, error = null)
    }

    fun resetSearch() {
        searchJob?.cancel()
        _search.value = UserSearchState()
    }

    /** 按昵称、邮箱或用户 ID 搜索；服务端不支持搜索时按用户 ID 精确返回一条。 */
    fun searchUsers() {
        val q = _search.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _search.value = _search.value.copy(searching = true, error = null)
        searchJob = viewModelScope.launch {
            try {
                val results = profiles?.search(q) ?: listOf(UserProfile(q, q))
                _search.value = _search.value.copy(results = results, searched = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.value = _search.value.copy(error = e.userMessage(), searched = true)
            } finally {
                _search.value = _search.value.copy(searching = false)
            }
        }
    }

    fun respond(request: FriendRequest, accept: Boolean) = action(
        success = if (accept) "已添加 ${request.displayName} 为好友" else "已拒绝",
        block = { friends.respond(request.fromUid, accept) },
    )
}

/** [friend] 为 null 表示已不是好友（被对方删除或尚未同步）。 */
data class FriendInfoUiState(
    val friend: Friend? = null,
    val loaded: Boolean = false,
)

class FriendInfoViewModel(
    val uid: String,
    private val fallbackName: String,
    private val friends: FriendRepository,
) : ActionViewModel() {
    val state: StateFlow<FriendInfoUiState> = friends.observeFriends()
        .map { list -> FriendInfoUiState(list.firstOrNull { it.uid == uid }, loaded = true) }
        .catch { emit(FriendInfoUiState(loaded = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FriendInfoUiState())

    val displayName: String get() = state.value.friend?.displayName ?: fallbackName.ifBlank { uid }

    fun remove(onRemoved: () -> Unit) = action(block = { friends.remove(uid) }, then = onRemoved)

    fun copied() = notify("用户 ID 已复制")
}

class GroupsViewModel(
    private val groups: GroupRepository,
    friends: FriendRepository,
) : ActionViewModel() {
    val groupList: StateFlow<List<Group>> = groups.observeGroups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val friendList: StateFlow<List<Friend>> = friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() = action(block = { groups.refresh() })

    fun create(name: String, members: List<String>, onCreated: (Group) -> Unit) {
        var created: Group? = null
        action(block = { created = groups.create(name, members) }, then = { created?.let(onCreated) })
    }

    fun join(groupId: String, onJoined: (Group) -> Unit) {
        var joined: Group? = null
        action(
            block = { joined = groups.join(groupId) ?: Group(groupId, groupId, ownerUid = "") },
            then = { joined?.let(onJoined) },
        )
    }

    fun leave(group: Group) = action(success = "已退出「${group.displayName}」", block = { groups.leave(group.groupId) })
}

data class GroupInfoUiState(
    val group: Group? = null,
    val members: List<GroupMember> = emptyList(),
    val isOwner: Boolean = false,
)

class GroupInfoViewModel(
    val groupId: String,
    private val fallbackName: String,
    private val selfUid: String,
    private val groups: GroupRepository,
    friends: FriendRepository,
) : ActionViewModel() {
    private val members = MutableStateFlow<List<GroupMember>>(emptyList())

    val state: StateFlow<GroupInfoUiState> = combine(
        groups.observeGroups().map { list -> list.firstOrNull { it.groupId == groupId } }.catch { emit(null) },
        members,
    ) { group, list ->
        val shown = group ?: Group(groupId, fallbackName, ownerUid = "", memberCount = list.size)
        GroupInfoUiState(shown, list, isOwner = shown.ownerUid == selfUid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupInfoUiState())

    val friendList: StateFlow<List<Friend>> = friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        reload()
    }

    fun reload() = action(block = {
        members.value = groups.members(groupId)
        groups.refresh()
    })

    fun invite(uids: List<String>) {
        if (uids.isEmpty()) return
        action(
            success = "已邀请 ${uids.size} 人",
            block = {
                uids.forEach { groups.invite(groupId, it) }
                members.value = groups.members(groupId)
            },
        )
    }

    fun leave(onLeft: () -> Unit) = action(block = { groups.leave(groupId) }, then = onLeft)

    fun copied() = notify("群 ID 已复制")
}
