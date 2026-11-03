package moderation

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func TestBlockIsIdempotentAndRejectsSelf(t *testing.T) {
	ctx := context.Background()
	svc := NewService(NewMemoryStore())
	if err := svc.Block(ctx, "a", "a"); !errors.Is(err, ErrSelf) {
		t.Fatalf("self block: %v", err)
	}
	if err := svc.Block(ctx, "a", " "); !errors.Is(err, ErrInvalid) {
		t.Fatalf("blank block: %v", err)
	}
	for range 2 {
		if err := svc.Block(ctx, "a", "b"); err != nil {
			t.Fatal(err)
		}
	}
	_ = svc.Block(ctx, "a", "c")
	got, _ := svc.Blocked(ctx, "a")
	if strings.Join(got, ",") != "b,c" {
		t.Fatalf("blocked = %v", got)
	}
	_ = svc.Unblock(ctx, "a", "b")
	got, _ = svc.Blocked(ctx, "a")
	if strings.Join(got, ",") != "c" {
		t.Fatalf("after unblock = %v", got)
	}
}

func TestReportDedupAndResolve(t *testing.T) {
	ctx := context.Background()
	svc := NewService(NewMemoryStore())
	if _, err := svc.Report(ctx, "a", "a", "", "", "x"); !errors.Is(err, ErrSelf) {
		t.Fatalf("self report: %v", err)
	}
	id1, _ := svc.Report(ctx, "a", "bad", "m1", "room1", strings.Repeat("长", 600))
	dup, _ := svc.Report(ctx, "a", "bad", "m1", "room1", "again")
	if dup != id1 {
		t.Fatalf("duplicate report got new id %d (want %d)", dup, id1)
	}
	id2, _ := svc.Report(ctx, "c", "bad", "m2", "", "spam")
	id3, _ := svc.Report(ctx, "c", "other", "m3", "", "meh")

	r, _ := svc.Get(ctx, id1)
	if n := len([]rune(r.Content)); n != maxContentRunes {
		t.Fatalf("content not truncated: %d runes", n)
	}

	list, total, pending, _ := svc.Reports(ctx, "", 0, 10)
	if total != 3 || pending != 3 || list[0].ID != id3 {
		t.Fatalf("pending list: total=%d pending=%d first=%d", total, pending, list[0].ID)
	}

	if err := svc.Ignore(ctx, id3, "root"); err != nil {
		t.Fatal(err)
	}
	if err := svc.MarkBanned(ctx, r, "root"); err != nil {
		t.Fatal(err)
	}
	r2, _ := svc.Get(ctx, id2)
	if r2.Status != StatusBanned || r2.HandledBy != "root" {
		t.Fatalf("other report against banned user: %+v", r2)
	}
	_, total, pending, _ = svc.Reports(ctx, FilterHandled, 0, 10)
	if total != 3 || pending != 0 {
		t.Fatalf("handled: total=%d pending=%d", total, pending)
	}
	if _, _, _, err := svc.Reports(ctx, "bogus", 0, 10); !errors.Is(err, ErrInvalid) {
		t.Fatalf("bad filter: %v", err)
	}
	if err := svc.Ignore(ctx, 999, "root"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing report: %v", err)
	}
}

func TestIsBlockedCachedAndInvalidated(t *testing.T) {
	ctx := context.Background()
	svc := NewService(NewMemoryStore())
	if svc.IsBlocked(ctx, "a", "b") {
		t.Fatal("unexpected block")
	}
	if err := svc.Block(ctx, "a", "b"); err != nil {
		t.Fatal(err)
	}
	if !svc.IsBlocked(ctx, "a", "b") || svc.IsBlocked(ctx, "b", "a") {
		t.Fatal("block not visible or not directional")
	}
	if err := svc.Unblock(ctx, "a", "b"); err != nil {
		t.Fatal(err)
	}
	if svc.IsBlocked(ctx, "a", "b") {
		t.Fatal("unblock not visible")
	}
}
