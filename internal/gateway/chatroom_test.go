package gateway

import (
	"context"
	"testing"

	"github.com/im/api/proto"
	"github.com/im/internal/pkg/snowflake"
)

func newChatroomRouter(t *testing.T) (*Hub, *InMemoryGroupStore, *Router, *InMemoryUnreadTracker) {
	t.Helper()
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	gs := NewInMemoryGroupStore(sg)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	r.SetGroupStore(gs)
	ut := NewInMemoryUnreadTracker()
	r.SetUnreadTracker(ut)
	return h, gs, r, ut
}

func TestChatroomDeliversOnlineOnly(t *testing.T) {
	h, gs, r, ut := newChatroomRouter(t)
	ctx := context.Background()
	room, err := gs.CreateChatroom(ctx, "Room", "alice", []string{"bob", "carol"})
	if err != nil {
		t.Fatal(err)
	}
	carol := newTestClient(t, "carol", "Carol")
	h.Register(ctx, carol) // bob 离线

	sender := newTestClient(t, "alice", "Alice")
	r.Route(ctx, sender, &proto.Message{
		Cmd: proto.CmdChat, From: "alice", To: room.ID, ChatType: proto.ChatTypeGroup,
		MsgType: proto.MsgTypeText, Content: "hi room", NeedAck: true, Seq: 1,
	})

	if msg := readMessageFromChan(t, carol.send); msg.Content != "hi room" {
		t.Fatalf("carol got %q", msg.Content)
	}
	if ack := readMessageFromChan(t, sender.send); ack.Cmd != proto.CmdAck {
		t.Fatalf("expected ACK, got cmd=%d", ack.Cmd)
	}
	if n := len(h.DrainOffline(ctx, "bob")); n != 0 {
		t.Fatalf("chatroom message stored offline for bob: %d", n)
	}
	for _, uid := range []string{"bob", "carol"} {
		if n := ut.GetCount(ctx, uid, room.ID); n != 0 {
			t.Fatalf("unread for %s = %d, want 0", uid, n)
		}
	}

	// 普通群照旧：离线成员转存、计未读。
	g, _ := gs.Create(ctx, "Group", "alice", []string{"bob"})
	r.Route(ctx, sender, &proto.Message{
		Cmd: proto.CmdChat, From: "alice", To: g.ID, ChatType: proto.ChatTypeGroup,
		MsgType: proto.MsgTypeText, Content: "hi group", Seq: 2,
	})
	if n := len(h.DrainOffline(ctx, "bob")); n != 1 {
		t.Fatalf("group message offline for bob: %d, want 1", n)
	}
	if n := ut.GetCount(ctx, "bob", g.ID); n != 1 {
		t.Fatalf("group unread for bob = %d, want 1", n)
	}
}

func TestChatroomHiddenFromListsAndManagedByApp(t *testing.T) {
	h, gs, r, _ := newChatroomRouter(t)
	ctx := context.Background()
	room, _ := gs.CreateChatroom(ctx, "Room", "alice", nil)
	group, _ := gs.Create(ctx, "Group", "alice", nil)

	groups, _ := gs.GetUserGroups(ctx, "alice")
	if len(groups) != 1 || groups[0].ID != group.ID {
		t.Fatalf("GetUserGroups should list only the group, got %d", len(groups))
	}

	dave := newTestClient(t, "dave", "Dave")
	h.Register(ctx, dave)
	r.Route(ctx, dave, &proto.Message{Cmd: proto.CmdGroupJoin, From: "dave", To: room.ID})
	if resp := readMessageFromChan(t, dave.send); resp.Cmd != proto.CmdGroupJoin || gs.IsMember(ctx, room.ID, "dave") {
		t.Fatalf("join chatroom should be refused, cmd=%d member=%v", resp.Cmd, gs.IsMember(ctx, room.ID, "dave"))
	}

	alice := newTestClient(t, "alice", "Alice")
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdGroupInviteMember, From: "alice", To: room.ID, Content: `{"target_uid":"dave"}`})
	readMessageFromChan(t, alice.send)
	if gs.IsMember(ctx, room.ID, "dave") {
		t.Fatal("invite into chatroom should be refused")
	}
}

func TestForwardedChatroomMessageNotStoredOffline(t *testing.T) {
	h, gs, r, _ := newChatroomRouter(t)
	ctx := context.Background()
	room, _ := gs.CreateChatroom(ctx, "Room", "alice", []string{"bob"})
	group, _ := gs.Create(ctx, "Group", "alice", []string{"bob"})

	srv := NewGrpcGatewayServer(h, h, "n1")
	srv.SetOnlineOnly(r.OnlineOnly)
	for _, to := range []string{room.ID, group.ID} {
		_, err := srv.ForwardMessage(ctx, &proto.ForwardRequest{Uid: "bob", Message: &proto.Message{
			Cmd: proto.CmdChat, From: "alice", To: to, ChatType: proto.ChatTypeGroup, Content: to,
		}})
		if err != nil {
			t.Fatal(err)
		}
	}
	msgs := h.DrainOffline(ctx, "bob")
	if len(msgs) != 1 || msgs[0].Content != group.ID {
		t.Fatalf("only the group message should be stored offline, got %d", len(msgs))
	}
}
