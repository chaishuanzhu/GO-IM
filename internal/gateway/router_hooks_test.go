package gateway

import (
	"context"
	"strings"
	"sync"
	"testing"

	"github.com/im/api/proto"
)

type denyHook struct{ word string }

func (h denyHook) BeforeSend(_ context.Context, m *proto.Message) (bool, string) {
	if strings.Contains(m.Content, h.word) {
		return false, "contains " + h.word
	}
	return true, ""
}

type recordingEvents struct {
	mu       sync.Mutex
	sent     []int64
	recalled []int64
}

func (e *recordingEvents) MessageSent(_ context.Context, m *proto.Message) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.sent = append(e.sent, m.MsgId)
}

func (e *recordingEvents) MessageRecalled(_ context.Context, id int64, _, _ string, _ int32) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.recalled = append(e.recalled, id)
}

func TestBeforeSendRejectsMessage(t *testing.T) {
	_, r, alice, bob := newBlockFixture(t)
	r.SetBlockChecker(nil)
	r.SetBeforeSend(denyHook{word: "spam"})
	ev := &recordingEvents{}
	r.SetMessageEvents(ev)
	ctx := context.Background()

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdChat, ChatType: proto.ChatTypeSingle, To: "bob", Content: "spam!", Seq: 1, NeedAck: true})
	ack := readMessageFromChan(t, alice.send)
	if ack.Cmd != proto.CmdAck || !strings.Contains(ack.Content, `"error":"rejected"`) || !strings.Contains(ack.Content, "contains spam") {
		t.Fatalf("ack = %+v", ack)
	}
	assertChanEmpty(t, bob.send)

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdChat, ChatType: proto.ChatTypeSingle, To: "bob", Content: "hello", Seq: 2})
	got := readMessageFromChan(t, bob.send)
	if got.Content != "hello" {
		t.Fatalf("allowed message: %+v", got)
	}
	ev.mu.Lock()
	defer ev.mu.Unlock()
	if len(ev.sent) != 1 || ev.sent[0] != got.MsgId {
		t.Fatalf("message.sent events = %v, want [%d]", ev.sent, got.MsgId)
	}
}

func TestSendMessageAsSystemAndUser(t *testing.T) {
	_, r, alice, bob := newBlockFixture(t)
	ctx := context.Background()

	if _, err := r.SendMessage(ctx, "alice", proto.ChatTypeSingle, "bob", 0, "x"); err != ErrBlocked {
		t.Fatalf("blocked user send: %v", err)
	}
	msg, err := r.SendMessage(ctx, "sys:app", proto.ChatTypeSingle, "bob", 0, "notice")
	if err != nil {
		t.Fatal(err)
	}
	if got := readMessageFromChan(t, bob.send); got.MsgId != msg.MsgId || got.From != "sys:app" || got.MsgType != proto.MsgTypeText {
		t.Fatalf("system message: %+v", got)
	}

	if _, err := r.SendMessage(ctx, "bob", proto.ChatTypeSingle, "alice", 0, "hey"); err != nil {
		t.Fatal(err)
	}
	if got := readMessageFromChan(t, alice.send); got.Content != "hey" {
		t.Fatalf("user message: %+v", got)
	}
	if got := readMessageFromChan(t, bob.send); got.Content != "hey" {
		t.Fatalf("sender's own device not synced: %+v", got)
	}
}
