package gateway

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/im/api/proto"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
)

type testFriendStore struct {
	pending map[[2]string]bool
}

func newTestFriendStore() *testFriendStore {
	return &testFriendStore{pending: map[[2]string]bool{}}
}

func (s *testFriendStore) SendRequest(_ context.Context, fromUID, toUID string) error {
	s.pending[[2]string{fromUID, toUID}] = true
	return nil
}

func (s *testFriendStore) AcceptRequest(_ context.Context, uid, fromUID string) error {
	delete(s.pending, [2]string{fromUID, uid})
	return nil
}

func (s *testFriendStore) RejectRequest(_ context.Context, uid, fromUID string) error {
	delete(s.pending, [2]string{fromUID, uid})
	return nil
}

func (s *testFriendStore) RemoveFriend(context.Context, string, string) error { return nil }

func (s *testFriendStore) GetFriends(context.Context, string) ([]*repo.Friend, error) {
	return nil, nil
}

func (s *testFriendStore) GetPendingRequests(context.Context, string) ([]*repo.FriendRequest, error) {
	return nil, nil
}

func (s *testFriendStore) SetRemark(context.Context, string, string, string) error { return nil }

func (s *testFriendStore) GetRequestHistory(context.Context, string, int) ([]*repo.FriendRequestRecord, error) {
	return nil, nil
}

func newFriendRouter(t *testing.T) (*Router, *Hub) {
	t.Helper()
	h := NewHub(100)
	sg, _ := snowflake.New(1)
	r := NewRouter(h, h, sg, nil, DefaultRouterConfig())
	r.SetFriendStore(newTestFriendStore())
	return r, h
}

// 在线的被请求方必须实时收到 CmdFriendRequest，而不是进离线队列。
func TestFriendRequestDeliveredToOnlineTarget(t *testing.T) {
	r, h := newFriendRouter(t)
	ctx := context.Background()
	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	h.Register(ctx, alice)
	h.Register(ctx, bob)

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdFriendRequest, Seq: 1, From: "alice", To: "bob"})

	got := readMessageFromChan(t, bob.send)
	if got.Cmd != proto.CmdFriendRequest || got.From != "alice" {
		t.Fatalf("bob got cmd=%d from=%q", got.Cmd, got.From)
	}
	var body map[string]string
	if err := json.Unmarshal([]byte(got.Content), &body); err != nil {
		t.Fatalf("content not JSON: %q", got.Content)
	}
	if body["from_uid"] != "alice" || body["username"] != "Alice" {
		t.Fatalf("unexpected content %v", body)
	}
	if n := len(h.DrainOffline(ctx, "bob")); n != 0 {
		t.Fatalf("expected no offline messages for bob, got %d", n)
	}

	ack := readMessageFromChan(t, alice.send)
	if ack.Cmd != proto.CmdFriendRequest || ack.Content != `{"status":"sent"}` {
		t.Fatalf("alice ack cmd=%d content=%q", ack.Cmd, ack.Content)
	}
}

func TestFriendResponseDeliveredToOnlineRequester(t *testing.T) {
	r, h := newFriendRouter(t)
	ctx := context.Background()
	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	h.Register(ctx, alice)
	h.Register(ctx, bob)

	r.Route(ctx, bob, &proto.Message{Cmd: proto.CmdFriendResponse, Seq: 1, From: "bob", To: "alice", Content: `{"action":"accept"}`})

	got := readMessageFromChan(t, alice.send)
	if got.Cmd != proto.CmdFriendResponse || got.From != "bob" {
		t.Fatalf("alice got cmd=%d from=%q", got.Cmd, got.From)
	}
	if got.Content != `{"action":"accept","from_uid":"bob"}` {
		t.Fatalf("unexpected content %q", got.Content)
	}
	if n := len(h.DrainOffline(ctx, "alice")); n != 0 {
		t.Fatalf("expected no offline messages for alice, got %d", n)
	}
}

func TestFriendRequestStoredOfflineWhenTargetOffline(t *testing.T) {
	r, h := newFriendRouter(t)
	ctx := context.Background()
	alice := newTestClient(t, "alice", "Alice")
	h.Register(ctx, alice)

	r.Route(ctx, alice, &proto.Message{Cmd: proto.CmdFriendRequest, Seq: 1, From: "alice", To: "bob"})

	offline := h.DrainOffline(ctx, "bob")
	if len(offline) != 1 || offline[0].Cmd != proto.CmdFriendRequest {
		t.Fatalf("expected 1 offline friend request for bob, got %v", offline)
	}
}

type remarkFriendStore struct {
	*testFriendStore
	remarks map[[2]string]string
}

func (s *remarkFriendStore) SetRemark(_ context.Context, uid, friendUID, remark string) error {
	if friendUID != "bob" {
		return repo.ErrNotFriends
	}
	s.remarks[[2]string{uid, friendUID}] = remark
	return nil
}
