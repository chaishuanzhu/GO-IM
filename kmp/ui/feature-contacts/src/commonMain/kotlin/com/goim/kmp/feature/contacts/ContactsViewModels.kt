package com.goim.kmp.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goim.kmp.common.userMessage
import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import kotlinx.coroutines.CancellationException
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

class NewFriendsViewModel(private val friends: FriendRepository) : ActionViewModel() {
    val requests: StateFlow<List<FriendRequest>> = friends.observeRequests()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        action(block = { friends.refresh() })
    }

    fun sendRequest(uid: String) = action(success = "好友请求已发送", block = { friends.sendRequest(uid) })

    fun respond(request: FriendRequest, accept: Boolean) = action(
        success = if (accept) "已添加 ${request.displayName} 为好友" else "已拒绝",
        block = { friends.respond(request.fromUid, accept) },
    )
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
