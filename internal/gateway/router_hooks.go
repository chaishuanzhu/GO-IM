package gateway

import (
	"context"
	"encoding/json"
	"time"

	"github.com/im/api/proto"
)

// MessageEvents 接收已投递的持久消息与撤回（webhook 发布用）。实现必须快速返回、不阻塞热路径。
type MessageEvents interface {
	MessageSent(ctx context.Context, msg *proto.Message)
	MessageRecalled(ctx context.Context, msgID int64, byUID, peer string, chatType int32)
}

// BeforeSendHook 在用户消息投递前同步调用，可拒绝消息；实现负责超时并在出错时放行。
type BeforeSendHook interface {
	BeforeSend(ctx context.Context, msg *proto.Message) (allow bool, reason string)
}

// SetMessageEvents 设置消息事件接收方(nil = 不发布)。
func (r *Router) SetMessageEvents(e MessageEvents) { r.events = e }

// SetBeforeSend 设置发送前回调(nil = 不回调)。
func (r *Router) SetBeforeSend(h BeforeSendHook) { r.beforeSend = h }

// rejectedByHook 调用发送前回调；被拒绝时回 ACK {"error":"rejected","reason":...} 并返回 true。
func (r *Router) rejectedByHook(ctx context.Context, sender *Client, msg *proto.Message) bool {
	if r.beforeSend == nil {
		return false
	}
	allow, reason := r.beforeSend.BeforeSend(ctx, msg)
	if allow {
		return false
	}
	if msg.Seq > 0 {
		r.dedup.Mark(dedupKey(sender), msg.Seq, msg.MsgId)
	}
	if msg.NeedAck {
		content, _ := json.Marshal(map[string]string{"error": "rejected", "reason": reason})
		sender.Send(&proto.Message{
			Cmd:       proto.CmdAck,
			MsgId:     msg.MsgId,
			Seq:       msg.Seq,
			To:        sender.UID,
			Content:   string(content),
			Timestamp: time.Now().UnixMilli(),
		})
	}
	return true
}
