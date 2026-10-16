package snowflake

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func newTestRedis(t *testing.T) (*miniredis.Miniredis, *redis.Client) {
	t.Helper()
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	t.Cleanup(func() { rdb.Close() })
	return mr, rdb
}

func TestNewLeasedDistinctIDs(t *testing.T) {
	_, rdb := newTestRedis(t)
	ctx := context.Background()
	cfg := LeaseConfig{Min: 16, Max: 19, TTL: time.Minute}

	seen := map[int64]bool{}
	var leases []*Lease
	for i := 0; i < 4; i++ {
		gen, l, err := NewLeased(ctx, rdb, cfg)
		if err != nil {
			t.Fatalf("lease %d: %v", i, err)
		}
		leases = append(leases, l)
		id := gen.WorkerID()
		if id < cfg.Min || id > cfg.Max {
			t.Fatalf("worker ID %d out of range", id)
		}
		if seen[id] {
			t.Fatalf("worker ID %d leased twice", id)
		}
		seen[id] = true
	}
	if _, _, err := NewLeased(ctx, rdb, cfg); !errors.Is(err, ErrNoFreeWorkerID) {
		t.Fatalf("range exhausted: got %v, want ErrNoFreeWorkerID", err)
	}

	freed := leases[0].WorkerID()
	leases[0].Close()
	gen, l, err := NewLeased(ctx, rdb, cfg)
	if err != nil {
		t.Fatalf("lease after release: %v", err)
	}
	defer l.Close()
	if gen.WorkerID() != freed {
		t.Fatalf("got worker ID %d, want released %d", gen.WorkerID(), freed)
	}
	for _, l := range leases[1:] {
		l.Close()
	}
}

func TestLeaseCloseOnlyReleasesOwnKey(t *testing.T) {
	mr, rdb := newTestRedis(t)
	ctx := context.Background()
	_, l, err := NewLeased(ctx, rdb, LeaseConfig{Min: 5, Max: 5, TTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	key := DefaultLeasePrefix + "5"
	mr.Set(key, "someone-else")
	l.Close()
	if v, _ := mr.Get(key); v != "someone-else" {
		t.Fatalf("Close removed another owner's lease: %q", v)
	}
}

func TestLeaseRenewSwitchesWhenTaken(t *testing.T) {
	mr, rdb := newTestRedis(t)
	ctx := context.Background()
	gen, l, err := NewLeased(ctx, rdb, LeaseConfig{Min: 7, Max: 8, TTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	old := gen.WorkerID()
	mr.Set(l.key(old), "someone-else")

	l.renew()

	if gen.WorkerID() == old || l.WorkerID() != gen.WorkerID() {
		t.Fatalf("worker ID not switched: gen=%d lease=%d old=%d", gen.WorkerID(), l.WorkerID(), old)
	}
	if v, _ := mr.Get(l.key(gen.WorkerID())); v != l.owner {
		t.Fatalf("new lease not owned: %q", v)
	}
}

func TestLeaseRenewReacquiresExpired(t *testing.T) {
	mr, rdb := newTestRedis(t)
	ctx := context.Background()
	gen, l, err := NewLeased(ctx, rdb, LeaseConfig{Min: 3, Max: 4, TTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	id := gen.WorkerID()
	mr.Del(l.key(id))

	l.renew()

	if gen.WorkerID() != id {
		t.Fatalf("worker ID changed from %d to %d", id, gen.WorkerID())
	}
	if v, _ := mr.Get(l.key(id)); v != l.owner {
		t.Fatalf("lease not re-acquired: %q", v)
	}
}

func TestLeaseRenewExtendsTTL(t *testing.T) {
	mr, rdb := newTestRedis(t)
	gen, l, err := NewLeased(context.Background(), rdb, LeaseConfig{Min: 1, Max: 1, TTL: 30 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	key := l.key(gen.WorkerID())
	mr.FastForward(20 * time.Second)
	l.renew()
	if ttl := mr.TTL(key); ttl < 25*time.Second {
		t.Fatalf("TTL not extended: %v", ttl)
	}
}

func TestSetWorkerIDRange(t *testing.T) {
	g, _ := New(1)
	if err := g.SetWorkerID(workerMax + 1); !errors.Is(err, ErrWorkerIDInvalid) {
		t.Fatalf("got %v, want ErrWorkerIDInvalid", err)
	}
	if err := g.SetWorkerID(9); err != nil || g.WorkerID() != 9 {
		t.Fatalf("SetWorkerID(9): err=%v id=%d", err, g.WorkerID())
	}
}
