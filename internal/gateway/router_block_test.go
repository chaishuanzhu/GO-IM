package gateway

import (
	"context"
	"testing"

	"github.com/im/api/proto"
	"github.com/im/internal/pkg/snowflake"
)

type fakeBlocks map[string]bool // "owner>target"

func (f fakeBlocks) IsBlocked(_ context.Context, owner, target string) bool {
	return f[owner+">"+target]
}

func newBlockFixture(t *testing.T) (*Hub, *Router, *Client, *Client) {
	t.Helper()
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	r.SetBlockChecker(fakeBlocks{"bob>alice": true})
	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	h.Register(context.Background(), alice)
	h.Register(context.Background(), bob)
	return h, r, alice, bob
}

func TestBlockedChatNotDelivered(t *testing.T) {
	h, r, alice, bob := newBlockFixture(t)
	ctx := context.Background()

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdChat, ChatType: proto.ChatTypeSingle, To: "bob", Content: "hi", Seq: 1, NeedAck: true})

	ack := readMessageFromChan(t, alice.send)
	if ack.Cmd != proto.CmdAck || ack.Content != `{"error":"blocked"}` {
		t.Fatalf("expected blocked ACK, got %+v", ack)
	}
	assertChanEmpty(t, bob.send)
	if msgs := h.DrainOffline(ctx, "bob"); len(msgs) != 0 {
		t.Errorf("blocked message stored offline: %d", len(msgs))
	}

	// 反方向不受影响。
	r.Route(ctx, bob, &proto.Message{Cmd: proto.CmdChat, ChatType: proto.ChatTypeSingle, To: "alice", Content: "yo"})
	if got := readMessageFromChan(t, alice.send); got.Content != "yo" {
		t.Errorf("expected delivery from bob, got %+v", got)
	}
}

func TestBlockedSignalAndTypingDropped(t *testing.T) {
	_, r, alice, bob := newBlockFixture(t)
	ctx := context.Background()

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdSignal, ChatType: proto.ChatTypeSingle, To: "bob", Content: `{"type":"t.x"}`})
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdTyping, ChatType: proto.ChatTypeSingle, To: "bob"})
	assertChanEmpty(t, bob.send)
}
