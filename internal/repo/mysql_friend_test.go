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
