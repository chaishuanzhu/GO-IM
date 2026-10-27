package repo

import (
	"context"
	"errors"
	"testing"
)

func TestMySQLGroupMemberAlias(t *testing.T) {
	s := newTestMySQLStore(t)
	defer s.Close()
	ctx := context.Background()
	var next int64 = 990000000
	groups := NewMySQLGroupStore(s.db, func() int64 { next++; return next })

	g, err := groups.CreateGroup(ctx, "alias test", "gal_test_a", []string{"gal_test_b"})
	if err != nil {
		t.Fatalf("create group: %v", err)
	}
	defer func() {
		s.db.Exec("DELETE FROM group_members WHERE group_id = ?", g.ID)
		s.db.Exec("DELETE FROM `groups` WHERE id = ?", g.ID)
	}()

	if err := groups.SetAlias(ctx, g.ID, "gal_test_c", "x"); !errors.Is(err, ErrNotMember) {
		t.Fatalf("alias for non-member: %v", err)
	}
	if err := groups.SetAlias(ctx, g.ID, "gal_test_b", "小王"); err != nil {
		t.Fatalf("set alias: %v", err)
	}
	aliases, err := groups.MemberAliases(ctx, g.ID)
	if err != nil || len(aliases) != 1 || aliases["gal_test_b"] != "小王" {
		t.Fatalf("aliases = %v, %v", aliases, err)
	}
	if err := groups.SetAlias(ctx, g.ID, "gal_test_b", ""); err != nil {
		t.Fatalf("clear alias: %v", err)
	}
	if aliases, _ := groups.MemberAliases(ctx, g.ID); len(aliases) != 0 {
		t.Fatalf("aliases after clear = %v", aliases)
	}
}
