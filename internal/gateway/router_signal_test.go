package gateway

import (
	"context"
	"fmt"
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
		Cmd:     proto.CmdSignal,
		From:    "mallory",
		To:      g.ID,
		Content: `{"type":"paircast.playback","data":{"action":"play","positionMs":1000}}`,
		NeedAck: true,
	})

	got := readMessageFromChan(t, bob.send)
	if got.Cmd != proto.CmdSignal {
		t.Fatalf("expected CmdSignal, got cmd=%d", got.Cmd)
	}
	if got.From != "alice" {
		t.Errorf("expected server-stamped from=alice, got %q", got.From)
	}
	if got.To != g.ID || got.ChatType != proto.ChatTypeGroup {
		t.Errorf("unexpected target %q chatType=%d", got.To, got.ChatType)
	}
	if got.Content != `{"type":"paircast.playback","data":{"action":"play","positionMs":1000}}` {
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
		Cmd:     proto.CmdSignal,
		To:      g.ID,
		Content: `{"type":"paircast.playback","data":{"action":"pause"}}`,
	})

	assertChanEmpty(t, bob.send)
	assertChanEmpty(t, dave.send)
}

func TestGroupSignalValidation(t *testing.T) {
	if err := (&proto.Message{Cmd: proto.CmdSignal}).Validate(); err != proto.ErrMissingTarget {
		t.Errorf("expected ErrMissingTarget, got %v", err)
	}
	if err := (&proto.Message{Cmd: proto.CmdSignal, To: "g_1"}).Validate(); err != nil {
		t.Errorf("expected valid, got %v", err)
	}
	if err := (&proto.Message{Cmd: proto.CmdSignal + 1, To: "g_1"}).Validate(); err == nil {
		t.Error("expected cmd beyond CmdSignal to be invalid")
	}
	if !(&proto.Message{Cmd: proto.CmdSignal}).IsTransient() {
		t.Error("CmdSignal should be transient")
	}
	if (&proto.Message{Cmd: proto.CmdChat}).IsTransient() {
		t.Error("CmdChat should not be transient")
	}
}

func TestDirectSignalDeliveredToAllTargetDevices(t *testing.T) {
	h := NewHub(100)
	h.SetDevicePolicy(DevicePolicyUnlimited)
	sg, _ := snowflake.New(1)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	ctx := context.Background()

	alicePhone, _ := newDeviceClient(t, "alice", "a-phone", "android")
	aliceDesk, _ := newDeviceClient(t, "alice", "a-desk", "desktop")
	bobPhone, _ := newDeviceClient(t, "bob", "b-phone", "ios")
	bobDesk, _ := newDeviceClient(t, "bob", "b-desk", "desktop")
	for _, c := range []*Client{alicePhone, aliceDesk, bobPhone, bobDesk} {
		h.Register(ctx, c)
	}

	r.Route(ctx, alicePhone, &proto.Message{
		Cmd:      proto.CmdSignal,
		ChatType: proto.ChatTypeSingle,
		To:       "bob",
		Content:  `{"type":"call.ring"}`,
		NeedAck:  true,
	})

	for _, c := range []*Client{bobPhone, bobDesk} {
		got := readMessageFromChan(t, c.send)
		if got.Cmd != proto.CmdSignal || got.ChatType != proto.ChatTypeSingle || got.To != "bob" || got.From != "alice" {
			t.Fatalf("unexpected signal on %s: %+v", c.DeviceID, got)
		}
	}
	assertChanEmpty(t, alicePhone.send)
	assertChanEmpty(t, aliceDesk.send)
}

func TestDirectSignalOfflineTargetDropped(t *testing.T) {
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	ctx := context.Background()

	alice := newTestClient(t, "alice", "Alice")
	h.Register(ctx, alice)
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdSignal, ChatType: proto.ChatTypeSingle, To: "bob", Content: `{"type":"t.x"}`})
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdSignal, ChatType: proto.ChatTypeSingle, To: "alice", Content: `{"type":"t.self"}`})

	if msgs := h.DrainOffline(ctx, "bob"); len(msgs) != 0 {
		t.Errorf("expected no offline messages for bob, got %d", len(msgs))
	}
	assertChanEmpty(t, alice.send)
}

func TestTransientForwardedToOwningGateway(t *testing.T) {
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	r.SetHashRing(makeHashRing("gw-1", "gw-2"))
	r.SetThisNodeID("gw-1")
	fw := &mockForwarder{}
	r.SetForwarder(fw)
	ctx := context.Background()

	// 找一个归属 gw-2 的用户。
	remote := ""
	for i := 0; i < 1000 && remote == ""; i++ {
		if uid := fmt.Sprintf("u%d", i); r.hashRing.Get(uid) == "gw-2" {
			remote = uid
		}
	}
	if remote == "" {
		t.Fatal("no uid owned by gw-2")
	}

	alice := newTestClient(t, "alice", "Alice")
	h.Register(ctx, alice)
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdSignal, ChatType: proto.ChatTypeSingle, To: remote, Content: `{"type":"t.x"}`})
	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdTyping, ChatType: proto.ChatTypeSingle, To: remote})

	fw.mu.Lock()
	defer fw.mu.Unlock()
	if len(fw.forwarded) != 2 {
		t.Fatalf("expected signal and typing forwarded, got %d", len(fw.forwarded))
	}
	if fw.forwarded[0].Msg.Cmd != proto.CmdSignal || fw.forwarded[1].Msg.Cmd != proto.CmdTyping {
		t.Errorf("unexpected forwarded cmds: %d, %d", fw.forwarded[0].Msg.Cmd, fw.forwarded[1].Msg.Cmd)
	}
	if msgs := h.DrainOffline(ctx, remote); len(msgs) != 0 {
		t.Error("transient messages must not be stored offline")
	}
}

func TestSignalWithoutTypeDropped(t *testing.T) {
	h, r, g := newGroupSignalFixture(t)
	ctx := context.Background()
	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	h.Register(ctx, alice)
	h.Register(ctx, bob)

	for _, content := range []string{"", "plain", `{"action":"play"}`, `{"type":""}`} {
		r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdSignal, To: g.ID, Content: content})
	}
	assertChanEmpty(t, bob.send)
}

type countingGroupStore struct {
	GroupStore
	getMembers int
}

func (s *countingGroupStore) GetMembers(ctx context.Context, groupID string) ([]string, error) {
	s.getMembers++
	return s.GroupStore.GetMembers(ctx, groupID)
}

func TestSignalMembersCachedUntilMembershipChanges(t *testing.T) {
	h, r, g := newGroupSignalFixture(t)
	ctx := context.Background()
	cs := &countingGroupStore{GroupStore: r.groupStore}
	r.SetGroupStore(cs)
	alice := newTestClient(t, "alice", "Alice")
	dave := newTestClient(t, "dave", "Dave")
	h.Register(ctx, alice)
	h.Register(ctx, dave)

	sig := &proto.Message{Cmd: proto.CmdSignal, To: g.ID, Content: `{"type":"t.x"}`}
	for range 5 {
		r.Route(ctx, alice, sig)
	}
	if cs.getMembers != 1 {
		t.Fatalf("GetMembers called %d times, want 1", cs.getMembers)
	}

	r.Route(ctx, dave, &proto.Message{Cmd: proto.CmdGroupJoin, To: g.ID})
	for len(dave.send) > 0 {
		<-dave.send
	}
	r.Route(ctx, alice, sig)
	if got := readMessageFromChan(t, dave.send); got.Cmd != proto.CmdSignal {
		t.Fatalf("new member got cmd %d, want signal", got.Cmd)
	}
}
