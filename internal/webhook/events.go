package webhook

import (
	"context"
	"errors"

	"github.com/im/api/proto"
	"github.com/im/internal/gateway"
	"github.com/im/internal/moderation"
)

// MessageData 是 message.sent 的 data。
type MessageData struct {
	MsgID     int64  `json:"msg_id"`
	From      string `json:"from"`
	To        string `json:"to"`
	ChatType  int32  `json:"chat_type"`
	MsgType   int32  `json:"msg_type"`
	Content   string `json:"content"`
	Timestamp int64  `json:"timestamp"`
}

// MessageEvents 实现 gateway.MessageEvents。
type MessageEvents struct{ P *Publisher }

// MessageSent 实现 gateway.MessageEvents。
func (e MessageEvents) MessageSent(_ context.Context, m *proto.Message) {
	e.P.Publish(EventMessageSent, MessageData{
		MsgID: m.MsgId, From: m.From, To: m.To, ChatType: m.ChatType,
		MsgType: m.MsgType, Content: m.Content, Timestamp: m.Timestamp,
	})
}

// MessageRecalled 实现 gateway.MessageEvents。
func (e MessageEvents) MessageRecalled(_ context.Context, msgID int64, by, peer string, chatType int32) {
	e.P.Publish(EventMessageRecalled, map[string]any{"msg_id": msgID, "by": by, "to": peer, "chat_type": chatType})
}

// groupStore 在成员变化成功后发布群事件；客户端、管理接口和服务端 API 的改动都经过它。
type groupStore struct {
	gateway.GroupStore
	p *Publisher
}

// WrapGroupStore 返回发布群事件的 GroupStore。
func WrapGroupStore(inner gateway.GroupStore, p *Publisher) gateway.GroupStore {
	return &groupStore{GroupStore: inner, p: p}
}

func (s *groupStore) joined(groupID, uid string) {
	s.p.Publish(EventGroupMemberJoined, map[string]string{"group_id": groupID, "uid": uid})
}

func (s *groupStore) Create(ctx context.Context, name, ownerUID string, members []string) (*gateway.Group, error) {
	g, err := s.GroupStore.Create(ctx, name, ownerUID, members)
	if err == nil {
		for uid := range g.Members {
			s.joined(g.ID, uid)
		}
	}
	return g, err
}

func (s *groupStore) AddMember(ctx context.Context, groupID, uid string) error {
	err := s.GroupStore.AddMember(ctx, groupID, uid)
	if err == nil {
		s.joined(groupID, uid)
	}
	return err
}

// RemoveMember 发布 group.member_left；最后一人离开导致群被删除时再发 group.dismissed。
func (s *groupStore) RemoveMember(ctx context.Context, groupID, uid string) error {
	err := s.GroupStore.RemoveMember(ctx, groupID, uid)
	if err != nil {
		return err
	}
	s.p.Publish(EventGroupMemberLeft, map[string]string{"group_id": groupID, "uid": uid})
	if _, gerr := s.GroupStore.Get(ctx, groupID); errors.Is(gerr, gateway.ErrGroupNotFound) {
		s.p.Publish(EventGroupDismissed, map[string]string{"group_id": groupID})
	}
	return nil
}

// PresenceHook 返回 Hub 上线 / 下线回调：用户在本网关首个连接建立时发 user.online，最后一个断开时发 user.offline。
// 多网关时同一用户可能在不同网关各触发一次，接收方应以最新状态为准。
func PresenceHook(p *Publisher, clients gateway.ClientRegistry) func(uid string) {
	return func(uid string) {
		typ := EventUserOffline
		if clients.IsOnline(context.Background(), uid) {
			typ = EventUserOnline
		}
		p.Publish(typ, map[string]string{"uid": uid})
	}
}

// ReportHook 返回 moderation.Service.OnReport 回调。
func ReportHook(p *Publisher) func(context.Context, moderation.Report) {
	return func(_ context.Context, r moderation.Report) {
		p.Publish(EventReportCreated, r)
	}
}
