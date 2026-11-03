package gateway

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/im/api/proto"
)

// SystemSenderPrefix 是接入方以系统身份发消息时的发送方前缀（"sys:<app_id>"），不对应真实用户。
const SystemSenderPrefix = "sys:"

// ErrBlocked 表示接收方屏蔽了发送方。
var ErrBlocked = errors.New("blocked by recipient")

// IsSystemSender 报告 uid 是否为系统发送方。
func IsSystemSender(uid string) bool {
	return strings.HasPrefix(uid, SystemSenderPrefix)
}

// SendMessage 以 from 的身份发出一条持久消息（服务端 API 使用），与客户端 CmdChat 走同一条投递链路：
// 在线推送 / 跨网关转发 / 离线存储、未读计数、持久化，并同步给 from 自己在本节点的在线设备。
// from 为真实用户时群聊要求是成员、单聊遵守屏蔽；系统发送方（sys:...）不受这两项限制。
func (r *Router) SendMessage(ctx context.Context, from string, chatType int32, to string, msgType int32, content string) (*proto.Message, error) {
	if from == "" || to == "" {
		return nil, proto.ErrMissingTarget
	}
	if msgType == 0 {
		msgType = proto.MsgTypeText
	}
	msg := &proto.Message{
		Cmd:       proto.CmdChat,
		MsgId:     r.snow.Next(),
		From:      from,
		To:        to,
		ChatType:  chatType,
		MsgType:   msgType,
		Content:   content,
		Timestamp: time.Now().UnixMilli(),
	}
	system := IsSystemSender(from)
	sender := &Client{UID: from}
	if chatType == proto.ChatTypeGroup {
		if r.groupStore == nil {
			return nil, errors.New("group chat not enabled")
		}
		if _, err := r.groupStore.Get(ctx, to); err != nil {
			return nil, err
		}
		if !system && !r.groupStore.IsMember(ctx, to, from) {
			return nil, ErrNotMember
		}
		r.fanoutGroup(ctx, sender, msg, !system)
	} else {
		msg.ChatType = proto.ChatTypeSingle
		if !system && r.blockedBy(ctx, to, from) {
			return nil, ErrBlocked
		}
		r.deliverToUser(ctx, to, msg)
		if !system && to != from {
			r.syncToOwnDevices(ctx, sender, msg)
		}
	}
	r.incrementUnread(ctx, from, msg)
	r.persistAsync(ctx, msg)
	return msg, nil
}

// NotifyGroup 向群成员发出群通知（member_joined / member_left / group_dismissed 等），与客户端触发的通知格式相同。
func (r *Router) NotifyGroup(ctx context.Context, uid, groupID, notifType string, members []string) {
	r.sendGroupNotificationWithMembers(ctx, uid, groupID, notifType, members)
}
