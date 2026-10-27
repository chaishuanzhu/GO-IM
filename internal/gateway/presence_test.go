package gateway

import (
	"context"
	"net/http"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func TestRedisPresenceAcrossGateways(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatal(err)
	}
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	defer rdb.Close()
	ctx := context.Background()

	hubA, hubB := NewHub(10), NewHub(10)
	a, b := NewRedisPresence(rdb, hubA, "a"), NewRedisPresence(rdb, hubB, "b")

	alice, _ := newDeviceClient(t, "100001", "d1", "ios")
	hubA.Register(ctx, alice)
	a.Changed(ctx, "100001")
	legacy, _ := newDeviceClient(t, "alice", "d1", "ios")
	hubA.Register(ctx, legacy)
	a.Changed(ctx, "alice")
	b.rebuild(ctx)

	// B 上没有 100001 的连接，靠 A 的位图知道它在线；非数字 UID 不进位图。
	got := b.Online(ctx, []string{"100001", "100002", "alice"})
	if !got["100001"] || got["100002"] || got["alice"] {
		t.Fatalf("online from b = %v", got)
	}
	if n, ok := b.Count(ctx); !ok || n != 1 {
		t.Fatalf("count = %d %v", n, ok)
	}

	// 第二台设备断开不影响在线；最后一个连接断开后清位。
	phone, _ := newDeviceClient(t, "100001", "d2", "android")
	hubA.SetDevicePolicy(DevicePolicyUnlimited)
	hubA.Register(ctx, phone)
	hubA.Unregister(ctx, phone)
	a.Changed(ctx, "100001")
	if !b.Online(ctx, []string{"100001"})["100001"] {
		t.Fatal("still online on another device")
	}
	hubA.Unregister(ctx, alice)
	a.Changed(ctx, "100001")
	if b.Online(ctx, []string{"100001"})["100001"] {
		t.Fatal("should be offline after last connection")
	}

	// 漏写的增量由重建纠正；重建带 TTL，网关崩溃后位图过期。
	bob, _ := newDeviceClient(t, "100002", "d1", "web")
	hubA.Register(ctx, bob)
	a.rebuild(ctx)
	if !b.Online(ctx, []string{"100002"})["100002"] {
		t.Fatal("rebuild should publish bob")
	}
	if ttl := mr.TTL(presenceKeyPrefix + "a"); ttl <= 0 || ttl > presenceTTL {
		t.Fatalf("ttl = %v", ttl)
	}
	mr.FastForward(presenceTTL + time.Second)
	b.nodesAt = time.Time{}
	if b.Online(ctx, []string{"100002"})["100002"] {
		t.Fatal("crashed gateway's bitmap should expire")
	}
}

func TestHubPresenceHook(t *testing.T) {
	ctx := context.Background()
	hub := NewHub(10)
	hub.SetDevicePolicy(DevicePolicyUnlimited)
	var mu sync.Mutex
	var calls []string
	done := make(chan struct{}, 10)
	hub.SetPresenceHook(func(uid string) {
		mu.Lock()
		calls = append(calls, uid)
		mu.Unlock()
		done <- struct{}{}
	})
	wait := func(n int) {
		t.Helper()
		for i := 0; i < n; i++ {
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatalf("hook not called (%d/%d)", i, n)
			}
		}
	}

	c1, _ := newDeviceClient(t, "100001", "d1", "ios")
	c2, _ := newDeviceClient(t, "100001", "d2", "desktop")
	hub.Register(ctx, c1)
	wait(1)
	hub.Register(ctx, c2)
	hub.Unregister(ctx, c1)
	hub.Unregister(ctx, c2)
	wait(1)
	hub.Unregister(ctx, c2) // 重复注销不再触发
	time.Sleep(20 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()
	if len(calls) != 2 {
		t.Fatalf("hook calls = %v, want online + offline only", calls)
	}
}

func TestHandlePresence(t *testing.T) {
	e := newAccountEnv(t, true)
	ctx := context.Background()
	c, _ := newDeviceClient(t, "100002", "d1", "ios")
	e.srv.clients.Register(ctx, c)

	rec := e.do(t, e.srv.HandlePresence, http.MethodGet, e.auth(t, "100001", url.Values{"uids": {"100002, 100003,100002"}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("presence: %d %s", rec.Code, rec.Body)
	}
	online, _ := decode(t, rec)["online"].([]interface{})
	if len(online) != 1 || online[0] != "100002" {
		t.Fatalf("online = %v", online)
	}
	if rec := e.do(t, e.srv.HandlePresence, http.MethodGet, url.Values{"uids": {"100002"}}); rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated: %d", rec.Code)
	}
}
