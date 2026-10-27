package repo

import (
	"context"
	"testing"
)

func TestMySQLFriendRequestAfterReject(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	a, b := "fr_test_a", "fr_test_b"
	clear := func() {
		s.db.Exec(`DELETE FROM friends WHERE uid IN (?, ?) OR friend_uid IN (?, ?)`, a, b, a, b)
	}
	clear()
	defer clear()

	if err := s.SendRequest(ctx, a, b); err != nil {
		t.Fatalf("first request: %v", err)
	}
	if err := s.SendRequest(ctx, a, b); err == nil {
		t.Fatal("duplicate pending request should fail")
	}
	if err := s.SendRequest(ctx, b, a); err == nil {
		t.Fatal("reverse request while one is pending should fail")
	}
	if err := s.RejectRequest(ctx, b, a); err != nil {
		t.Fatalf("reject: %v", err)
	}

	// 被拒后可以再次发起，任一方向均可。
	if err := s.SendRequest(ctx, a, b); err != nil {
		t.Fatalf("re-request after reject: %v", err)
	}
	if err := s.RejectRequest(ctx, b, a); err != nil {
		t.Fatalf("second reject: %v", err)
	}
	if err := s.SendRequest(ctx, b, a); err != nil {
		t.Fatalf("reverse request after reject: %v", err)
	}
	pending, err := s.GetPendingRequests(ctx, a)
	if err != nil || len(pending) != 1 || pending[0].FromUID != b {
		t.Fatalf("pending for a = %v, %v", pending, err)
	}

	if err := s.AcceptRequest(ctx, a, b); err != nil {
		t.Fatalf("accept: %v", err)
	}
	if err := s.SendRequest(ctx, a, b); err == nil {
		t.Fatal("request between friends should fail")
	}
	friends, err := s.GetFriends(ctx, a)
	if err != nil || len(friends) != 1 || friends[0].UID != b {
		t.Fatalf("friends of a = %v, %v", friends, err)
	}
}

func TestMySQLFriendRequestHistory(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	a, b, c := "frh_test_a", "frh_test_b", "frh_test_c"
	clear := func() {
		s.db.Exec(`DELETE FROM friends WHERE uid IN (?, ?, ?) OR friend_uid IN (?, ?, ?)`, a, b, c, a, b, c)
		s.db.Exec(`DELETE FROM friend_requests WHERE from_uid IN (?, ?, ?) OR to_uid IN (?, ?, ?)`, a, b, c, a, b, c)
	}
	clear()
	defer clear()

	// a → b 先被拒、再次发起后被接受，然后删除好友；c → a 待处理。
	for _, step := range []func() error{
		func() error { return s.SendRequest(ctx, a, b) },
		func() error { return s.RejectRequest(ctx, b, a) },
		func() error { return s.SendRequest(ctx, a, b) },
		func() error { return s.AcceptRequest(ctx, b, a) },
		func() error { return s.RemoveFriend(ctx, a, b) },
		func() error { return s.SendRequest(ctx, c, a) },
	} {
		if err := step(); err != nil {
			t.Fatal(err)
		}
	}

	history, err := s.GetRequestHistory(ctx, a, 100)
	if err != nil {
		t.Fatal(err)
	}
	if len(history) != 2 {
		t.Fatalf("history = %d records, want 2 (latest per direction)", len(history))
	}
	got := map[string]FriendStatus{}
	for _, rec := range history {
		got[rec.FromUID+">"+rec.ToUID] = rec.Status
	}
	if got[a+">"+b] != FriendStatusAccepted || got[c+">"+a] != FriendStatusPending {
		t.Fatalf("history statuses = %v", got)
	}
}
