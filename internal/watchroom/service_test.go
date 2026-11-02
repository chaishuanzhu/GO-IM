package watchroom

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"testing"
)

type fakeGroups struct {
	members map[string][]string
	n       int
}

func (g *fakeGroups) Create(_ context.Context, _ string, owner string) (string, error) {
	g.n++
	id := fmt.Sprintf("g_%d", g.n)
	g.members[id] = []string{owner}
	return id, nil
}

func (g *fakeGroups) Join(_ context.Context, id, uid string) error {
	m, ok := g.members[id]
	if !ok {
		return ErrEnded
	}
	if !slices.Contains(m, uid) {
		g.members[id] = append(m, uid)
	}
	return nil
}

func (g *fakeGroups) Leave(_ context.Context, id, uid string) error {
	g.members[id] = slices.DeleteFunc(g.members[id], func(m string) bool { return m == uid })
	return nil
}

type recorder struct{ rooms []*Room }

func (r *recorder) RoomUpdated(_ context.Context, _ string, room *Room) {
	r.rooms = append(r.rooms, room)
}

func newService() (*Service, *fakeGroups, *recorder) {
	groups := &fakeGroups{members: map[string][]string{}}
	rec := &recorder{}
	n := 0
	return NewService(NewMemoryStore(), groups, rec, func() string { n++; return fmt.Sprintf("room-%d", n) }), groups, rec
}

func TestCreateJoinAndNotify(t *testing.T) {
	ctx := context.Background()
	svc, groups, rec := newService()
	room, err := svc.Create(ctx, "alice", "oss:films/a.mp4")
	if err != nil {
		t.Fatal(err)
	}
	if room.HostUID != "alice" || room.GroupID != "g_1" || room.Status != StatusActive {
		t.Fatalf("room = %+v", room)
	}
	joined, err := svc.Join(ctx, "bob", "ROOM-1")
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(joined.Members, []string{"alice", "bob"}) || joined.Revision != 2 {
		t.Fatalf("joined = %+v", joined)
	}
	if !slices.Contains(groups.members["g_1"], "bob") || len(rec.rooms) != 1 {
		t.Errorf("group/notify not updated: %v %d", groups.members, len(rec.rooms))
	}
	again, _ := svc.Join(ctx, "bob", "room-1")
	if len(again.Members) != 2 {
		t.Errorf("rejoin duplicated member: %v", again.Members)
	}
}

func TestHostLeaveTransfersByJoinOrderAndLastLeaveEnds(t *testing.T) {
	ctx := context.Background()
	svc, groups, _ := newService()
	svc.Create(ctx, "alice", "m")
	svc.Join(ctx, "bob", "room-1")
	svc.Join(ctx, "carol", "room-1")

	room, err := svc.Leave(ctx, "alice", "room-1", 500)
	if err != nil {
		t.Fatal(err)
	}
	if room.HostUID != "bob" || room.HostTransferSeq != 500 || room.Status != StatusActive {
		t.Fatalf("after host leave: %+v", room)
	}
	if slices.Contains(groups.members["g_1"], "alice") {
		t.Error("alice should have left the group")
	}
	svc.Leave(ctx, "carol", "room-1", 0)
	room, _ = svc.Leave(ctx, "bob", "room-1", 0)
	if room.Status != StatusEnded || len(room.Members) != 0 {
		t.Fatalf("last leave: %+v", room)
	}
	if _, err := svc.Join(ctx, "dave", "room-1"); !errors.Is(err, ErrEnded) {
		t.Errorf("join ended room: %v", err)
	}
}

func TestUpdateAuthorization(t *testing.T) {
	ctx := context.Background()
	svc, _, _ := newService()
	svc.Create(ctx, "alice", "m1")
	svc.Join(ctx, "bob", "room-1")
	m2 := "m2"
	if _, err := svc.Update(ctx, "bob", "room-1", Patch{MovieID: &m2}); !errors.Is(err, ErrNotHost) {
		t.Errorf("member movie change: %v", err)
	}
	if _, err := svc.Update(ctx, "eve", "room-1", Patch{}); !errors.Is(err, ErrNotMember) {
		t.Errorf("outsider update: %v", err)
	}
	svc.SetSubtitle(ctx, "alice", "room-1", "blob:x.srt", "ZH")
	room, err := svc.Update(ctx, "alice", "room-1", Patch{MovieID: &m2})
	if err != nil || room.MovieID != "m2" || room.SubtitleRef != "" {
		t.Fatalf("host movie change: %+v %v", room, err)
	}
	stranger := "eve"
	if _, err := svc.Update(ctx, "alice", "room-1", Patch{HostUID: &stranger}); !errors.Is(err, ErrInvalidHost) {
		t.Errorf("transfer to outsider: %v", err)
	}
	bob := "bob"
	seq := int64(42)
	room, _ = svc.Update(ctx, "alice", "room-1", Patch{HostUID: &bob, HostTransferSeq: &seq})
	if room.HostUID != "bob" || room.HostTransferSeq != 42 {
		t.Fatalf("transfer: %+v", room)
	}
	lower := int64(1)
	room, _ = svc.Update(ctx, "bob", "room-1", Patch{HostTransferSeq: &lower})
	if room.HostTransferSeq != 42 {
		t.Errorf("seq regressed: %d", room.HostTransferSeq)
	}
	stale := room.Revision - 1
	if _, err := svc.Update(ctx, "bob", "room-1", Patch{IfRevision: &stale}); !errors.Is(err, ErrConflict) {
		t.Errorf("stale revision: %v", err)
	}
	if _, err := svc.Get(ctx, "eve", "room-1"); !errors.Is(err, ErrNotMember) {
		t.Errorf("outsider get: %v", err)
	}
}

func TestRequireHost(t *testing.T) {
	ctx := context.Background()
	svc, _, _ := newService()
	svc.Create(ctx, "alice", "m")
	svc.Join(ctx, "bob", "room-1")
	if _, err := svc.RequireHost(ctx, "bob", "room-1"); !errors.Is(err, ErrNotHost) {
		t.Errorf("member as host: %v", err)
	}
	if _, err := svc.RequireHost(ctx, "alice", "room-1"); err != nil {
		t.Errorf("host: %v", err)
	}
}
