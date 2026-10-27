package com.goim.kmp.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
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
import kotlinx.coroutines.flow.update
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
                val first = friend.sortName.firstOrNull()?.uppercaseChar()
                if (first != null && first in 'A'..'Z') first.toString() else "#"
            }
            .map { (letter, items) -> FriendSection(letter, items.sortedBy { it.sortName.lowercase() }) }
            .sortedWith(compareBy({ it.letter == "#" }, { it.letter }))
    }
}

/** [pending]：发给我、待处理的请求；[history]：其余记录（我发出的、已处理的）。 */
data class NewFriendsUiState(
    val pending: List<FriendRequest> = emptyList(),
    val history: List<FriendRequest> = emptyList(),
)

class NewFriendsViewModel(private val friends: FriendRepository, val selfUid: String) : ActionViewModel() {
    val state: StateFlow<NewFriendsUiState> = friends.observeRequestHistory()
        .map { records ->
            val (pending, history) = records.partition { it.status == FriendRequestStatus.PENDING && it.toUid == selfUid }
            NewFriendsUiState(pending, history)
        }
        .catch { emit(NewFriendsUiState()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewFriendsUiState())

    init {
        action(block = { friends.refresh() })
    }

    fun respond(request: FriendRequest, accept: Boolean) = action(
        success = if (accept) "已添加 ${request.displayName} 为好友" else "已拒绝",
        block = { friends.respond(request.fromUid, accept) },
    )
}

data class AddFriendUiState(
    val query: String = "",
    val results: List<UserProfile> = emptyList(),
    val searching: Boolean = false,
    /** 已完成至少一次搜索（区分「未搜索」与「无结果」）。 */
    val searched: Boolean = false,
    val error: String? = null,
    val sending: Set<String> = emptySet(),
    /** 服务端已确认的请求；失败的不会进入这里，可以重试。 */
    val sent: Set<String> = emptySet(),
)

class AddFriendViewModel(
    private val friends: FriendRepository,
    private val profiles: ProfileRepository,
) : ActionViewModel() {
    val friendUids: StateFlow<Set<String>> = friends.observeFriends()
        .map<List<Friend>, Set<String>> { list -> list.mapTo(HashSet()) { it.uid } }
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _state = MutableStateFlow(AddFriendUiState())
    val state: StateFlow<AddFriendUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, error = null) }
    }

    /** 按昵称（包含）、邮箱或用户 ID（精确）搜索。 */
    fun search() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val results = profiles.search(q)
                _state.update { it.copy(results = results, searched = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage(), searched = true) }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }

    fun sendRequest(uid: String) {
        if (uid in _state.value.sending) return
        _state.update { it.copy(sending = it.sending + uid) }
        viewModelScope.launch {
            try {
                friends.sendRequest(uid)
                _state.update { it.copy(sent = it.sent + uid) }
                notify("好友请求已发送")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify(e.userMessage())
            } finally {
                _state.update { it.copy(sending = it.sending - uid) }
            }
        }
    }
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

    fun setRemark(remark: String) = action(
        success = if (remark.isBlank()) "已清除备注" else "备注已保存",
        block = { friends.setRemark(uid, remark) },
    )

    fun copied() = notify("用户 ID 已复制")
}

class GroupsViewModel(private val groups: GroupRepository) : ActionViewModel() {
    val groupList: StateFlow<List<Group>> = groups.observeGroups()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() = action(block = { groups.refresh() })

    fun leave(group: Group) = action(success = "已退出「${group.displayName}」", block = { groups.leave(group.groupId) })
}

class CreateGroupViewModel(
    private val groups: GroupRepository,
    friends: FriendRepository,
) : ActionViewModel() {
    val friendList: StateFlow<List<Friend>> = friends.observeFriends()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, members: List<String>, onCreated: (Group) -> Unit) {
        var created: Group? = null
        action(block = { created = groups.create(name, members) }, then = { created?.let(onCreated) })
    }
}

class JoinGroupViewModel(private val groups: GroupRepository) : ActionViewModel() {
    fun join(groupId: String, onJoined: (Group) -> Unit) {
        var joined: Group? = null
        action(
            block = { joined = groups.join(groupId) ?: Group(groupId, groupId, ownerUid = "") },
            then = { joined?.let(onJoined) },
        )
    }
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
