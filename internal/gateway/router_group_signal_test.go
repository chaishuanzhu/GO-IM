package gateway

import (
	"context"
	"testing"

	"github.com/im/api/proto"
	"github.com/im/internal/pkg/snowflake"
)

func newGroupSignalFixture(t *testing.T) (*Hub, *Router, *Group) {
	t.Helper()
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	gs := NewInMemoryGroupStore(sg)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	r.SetGroupStore(gs)
	g, err := gs.Create(context.Background(), "room", "alice", []string{"bob", "carol"})
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	return h, r, g
}

func TestGroupSignalDeliveredToOnlineMembersOnly(t *testing.T) {
	h, r, g := newGroupSignalFixture(t)
	ctx := context.Background()

	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	h.Register(ctx, alice)
	h.Register(ctx, bob)

	r.Route(ctx, alice, &proto.Message{
		Cmd:     proto.CmdGroupSignal,
		From:    "mallory",
		To:      g.ID,
		Content: `{"action":"play","positionMs":1000}`,
		NeedAck: true,
	})

	got := readMessageFromChan(t, bob.send)
	if got.Cmd != proto.CmdGroupSignal {
		t.Fatalf("expected CmdGroupSignal, got cmd=%d", got.Cmd)
	}
	if got.From != "alice" {
		t.Errorf("expected server-stamped from=alice, got %q", got.From)
	}
	if got.To != g.ID || got.ChatType != proto.ChatTypeGroup {
		t.Errorf("unexpected target %q chatType=%d", got.To, got.ChatType)
	}
	if got.Content != `{"action":"play","positionMs":1000}` {
		t.Errorf("content mismatch: %s", got.Content)
	}
	if got.MsgId == 0 || got.Timestamp == 0 {
		t.Error("expected msg_id and timestamp to be assigned")
	}

	// 发送方不回显、不回 Ack。
	assertChanEmpty(t, alice.send)

	// 离线成员 carol 不应收到离线消息。
	if msgs := h.DrainOffline(ctx, "carol"); len(msgs) != 0 {
		t.Errorf("expected no offline messages for carol, got %d", len(msgs))
	}
}

func TestGroupSignalFromNonMemberDropped(t *testing.T) {
	h, r, g := newGroupSignalFixture(t)
	ctx := context.Background()

	bob := newTestClient(t, "bob", "Bob")
	dave := newTestClient(t, "dave", "Dave")
	h.Register(ctx, bob)
	h.Register(ctx, dave)

	r.Route(ctx, dave, &proto.Message{
		Cmd:     proto.CmdGroupSignal,
		To:      g.ID,
		Content: `{"action":"pause"}`,
	})

	assertChanEmpty(t, bob.send)
	assertChanEmpty(t, dave.send)
}

func TestGroupSignalValidation(t *testing.T) {
	if err := (&proto.Message{Cmd: proto.CmdGroupSignal}).Validate(); err != proto.ErrMissingTarget {
		t.Errorf("expected ErrMissingTarget, got %v", err)
	}
	if err := (&proto.Message{Cmd: proto.CmdGroupSignal, To: "g_1"}).Validate(); err != nil {
		t.Errorf("expected valid, got %v", err)
	}
	if err := (&proto.Message{Cmd: proto.CmdGroupSignal + 1, To: "g_1"}).Validate(); err == nil {
		t.Error("expected cmd beyond CmdGroupSignal to be invalid")
	}
	if !(&proto.Message{Cmd: proto.CmdGroupSignal}).IsTransient() {
		t.Error("CmdGroupSignal should be transient")
	}
	if (&proto.Message{Cmd: proto.CmdChat}).IsTransient() {
		t.Error("CmdChat should not be transient")
	}
}
