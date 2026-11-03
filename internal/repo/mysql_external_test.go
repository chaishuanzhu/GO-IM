package repo

import (
	"context"
	"testing"
)

func TestMySQLExternalUserUpsertAndRevoke(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	ext := "ext-test-user-1"
	if _, err := s.db.Exec("DELETE FROM users WHERE external_id = ?", ext); err != nil {
		t.Fatal(err)
	}

	u, created, err := s.UpsertExternalUser(ctx, ext, nil, nil)
	if err != nil || !created || u.ExternalID != ext || u.Nickname != ext {
		t.Fatalf("create: %+v created=%v err=%v", u, created, err)
	}
	defer s.DeleteUser(ctx, u.UID)

	name := "Alice"
	again, created, err := s.UpsertExternalUser(ctx, ext, &name, nil)
	if err != nil || created || again.UID != u.UID || again.Nickname != "Alice" {
		t.Fatalf("update: %+v created=%v err=%v", again, created, err)
	}

	v, err := s.BumpTokenVersion(ctx, u.UID)
	if err != nil || v != 1 {
		t.Fatalf("bump: %d %v", v, err)
	}
	if got, _ := s.GetByUID(ctx, u.UID); got.TokenVersion != 1 {
		t.Fatalf("token version = %d", got.TokenVersion)
	}
	if _, err := s.BumpTokenVersion(ctx, "no-such-user"); err != ErrUserNotFound {
		t.Fatalf("bump missing: %v", err)
	}
}

func TestMySQLDeleteUserCleansRelations(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	u := &User{Nickname: "gone"}
	if err := s.CreateWithAutoUID(ctx, u); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("INSERT INTO group_members (group_id, uid, joined_at) VALUES ('g_del_test', ?, 1)", u.UID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.db.Exec("INSERT INTO friends (uid, friend_uid, status, created_at, updated_at) VALUES ('peer', ?, 1, 1, 1)", u.UID); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteUser(ctx, u.UID); err != nil {
		t.Fatal(err)
	}
	var n int
	s.db.QueryRow("SELECT (SELECT COUNT(*) FROM group_members WHERE uid = ?) + (SELECT COUNT(*) FROM friends WHERE friend_uid = ?)", u.UID, u.UID).Scan(&n)
	if n != 0 {
		t.Fatalf("%d relation rows left", n)
	}
}
