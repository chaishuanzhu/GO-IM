package com.goim.kmp.database

import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.UserProfile

fun MessageEntity.toDomain() = Message(
    id = id,
    serverMsgId = serverMsgId,
    clientSeq = clientSeq,
    conversationId = conversationId,
    fromUid = fromUid,
    toUid = toUid,
    chatType = ChatType.from(chatType),
    msgType = MsgType.from(msgType),
    content = content,
    timestampMs = timestampMs,
    status = MessageStatus.from(status),
    isOutgoing = isOutgoing,
)

fun Message.toEntity() = MessageEntity(
    id = id,
    serverMsgId = serverMsgId,
    clientSeq = clientSeq,
    conversationId = conversationId,
    fromUid = fromUid,
    toUid = toUid,
    chatType = chatType.value,
    msgType = msgType.value,
    content = content,
    timestampMs = timestampMs,
    status = status.value,
    isOutgoing = isOutgoing,
)

fun ConversationEntity.toDomain() = Conversation(
    id = id,
    chatType = ChatType.from(chatType),
    title = title,
    peerOrGroupId = peerOrGroupId,
    lastMessagePreview = lastMsgPreview,
    lastMessageAt = lastMsgAt,
    unreadCount = unreadCount,
)

fun Conversation.toEntity() = ConversationEntity(
    id = id,
    chatType = chatType.value,
    title = title,
    peerOrGroupId = peerOrGroupId,
    lastMsgPreview = lastMessagePreview,
    lastMsgAt = lastMessageAt,
    unreadCount = unreadCount,
)

fun GroupEntity.toDomain() = Group(groupId = groupId, name = name, ownerUid = ownerUid, memberCount = memberCount)

fun Group.toEntity() = GroupEntity(groupId = groupId, name = name, ownerUid = ownerUid, memberCount = memberCount)

fun GroupMemberEntity.toDomain() = GroupMember(groupId = groupId, uid = uid, role = role, alias = alias)

fun FriendEntity.toDomain() = Friend(uid = uid, nickname = nickname, avatarUrl = avatarUrl, signature = signature, remark = remark)

fun Friend.toEntity() = FriendEntity(uid = uid, nickname = nickname, avatarUrl = avatarUrl, signature = signature, remark = remark)

fun ProfileEntity.toDomain() = UserProfile(uid = uid, nickname = nickname, avatarUrl = avatarUrl, signature = signature)

fun FriendRequestEntity.toDomain() = FriendRequest(
    fromUid = fromUid,
    toUid = toUid,
    status = FriendRequestStatus.fromRaw(status),
    createdAt = createdAt,
    nickname = nickname,
    avatarUrl = avatarUrl,
)

fun FriendRequest.toEntity() = FriendRequestEntity(
    fromUid = fromUid,
    toUid = toUid,
    status = status.raw,
    createdAt = createdAt,
    nickname = nickname,
    avatarUrl = avatarUrl,
)
