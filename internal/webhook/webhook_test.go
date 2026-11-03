package webhook

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/chaishuanzhu/GO-IM/pkg/imserver"
	"github.com/im/api/proto"
	"github.com/im/internal/gateway"
	"github.com/im/internal/pkg/snowflake"
	"github.com/redis/go-redis/v9"
)

type receiver struct {
	mu     sync.Mutex
	events []*imserver.WebhookEvent
	fail   atomic.Int32 // 前 n 次返回 500
	srv    *httptest.Server
}

func newReceiver(t *testing.T, secret string) *receiver {
	r := &receiver{}
	r.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		ev, err := imserver.ParseWebhook(req, secret)
		if err != nil {
			http.Error(w, err.Error(), http.StatusUnauthorized)
			return
		}
		if r.fail.Add(-1) >= 0 {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		if req.Header.Get(HeaderDelivery) != ev.ID || req.Header.Get(HeaderEvent) != ev.Type {
			http.Error(w, "header mismatch", http.StatusBadRequest)
			return
		}
		r.mu.Lock()
		r.events = append(r.events, ev)
		r.mu.Unlock()
	}))
	t.Cleanup(r.srv.Close)
	return r
}

func (r *receiver) wait(t *testing.T, n int) []*imserver.WebhookEvent {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		r.mu.Lock()
		got := len(r.events)
		r.mu.Unlock()
		if got >= n {
			r.mu.Lock()
			defer r.mu.Unlock()
			return append([]*imserver.WebhookEvent(nil), r.events...)
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timeout waiting for %d events", n)
	return nil
}

func TestPublishAndDeliverSigned(t *testing.T) {
	rcv := newReceiver(t, "whsec")
	targets := []Target{
		{AppID: "a", URL: rcv.srv.URL, Secret: "whsec"},
		{AppID: "b", URL: rcv.srv.URL, Secret: "whsec", Events: []string{EventUserOnline}},
	}
	q := NewMemoryQueue()
	p := NewPublisher(targets, q)
	d := NewDispatcher(targets, q)
	d.poll = 20 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p.Start(ctx)
	d.Start(ctx)

	MessageEvents{P: p}.MessageSent(ctx, &proto.Message{MsgId: 7, From: "u1", To: "u2", ChatType: 1, Content: "hi"})
	evs := rcv.wait(t, 1)
	time.Sleep(50 * time.Millisecond)
	if len(rcv.wait(t, 1)) != 1 {
		t.Fatal("app b received an event it did not subscribe to")
	}
	var data MessageData
	json.Unmarshal(evs[0].Data, &data)
	if evs[0].Type != EventMessageSent || evs[0].AppID != "a" || data.MsgID != 7 || data.Content != "hi" {
		t.Fatalf("event = %+v data = %+v", evs[0], data)
	}
}

func TestDeliverRetriesThenDeadLetters(t *testing.T) {
	rcv := newReceiver(t, "s")
	target := Target{AppID: "a", URL: rcv.srv.URL, Secret: "s"}
	q := NewMemoryQueue()
	d := NewDispatcher([]Target{target}, q)
	now := time.Now()
	d.now = func() time.Time { return now }
	ctx := context.Background()

	rcv.fail.Store(1)
	it := delivery{ID: "e1", Type: EventUserOnline, Body: []byte(`{"id":"e1","type":"user.online"}`), First: now.UnixMilli()}
	d.deliver(ctx, target, it)
	if due, _ := q.Due(ctx, "a", now); len(due) != 0 {
		t.Fatal("retry due before backoff")
	}
	now = now.Add(backoff(1))
	due, _ := q.Due(ctx, "a", now)
	if len(due) != 1 || due[0].Attempt != 1 {
		t.Fatalf("due = %+v", due)
	}
	d.deliver(ctx, target, due[0])
	rcv.wait(t, 1)

	rcv.fail.Store(1000)
	old := delivery{ID: "e2", Type: EventUserOnline, Body: []byte(`{}`), First: now.Add(-MaxRetryAge).UnixMilli()}
	d.deliver(ctx, target, old)
	if len(q.dead["a"]) != 1 || q.dead["a"][0].ID != "e2" {
		t.Fatalf("dead = %+v", q.dead["a"])
	}
}

func TestBackoffCapped(t *testing.T) {
	if backoff(1) != 2*time.Second || backoff(20) != time.Hour || backoff(12) != time.Hour {
		t.Fatalf("backoff: %v %v %v", backoff(1), backoff(12), backoff(20))
	}
}

func TestRedisQueue(t *testing.T) {
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	q := NewRedisQueue(rdb, "node1")
	ctx := context.Background()
	now := time.Now()

	if got, err := q.Next(ctx, "a", 10*time.Millisecond); err != nil || len(got) != 0 {
		t.Fatalf("empty next: %v %v", got, err)
	}
	q.Add(ctx, "a", delivery{ID: "e1", Type: "t", Body: []byte(`{}`), First: now.UnixMilli()})
	q.Add(ctx, "a", delivery{ID: "e2", Type: "t", Body: []byte(`{}`), First: now.UnixMilli()})
	got, err := q.Next(ctx, "a", 10*time.Millisecond)
	if err != nil || len(got) != 2 || got[0].streamID == "" {
		t.Fatalf("next: %+v %v", got, err)
	}
	if err := q.Done(ctx, "a", got[0]); err != nil {
		t.Fatal(err)
	}
	got[1].Attempt = 1
	if err := q.Retry(ctx, "a", got[1], now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if n, _ := rdb.XLen(ctx, streamKey("a")).Result(); n != 0 {
		t.Fatalf("stream still has %d entries", n)
	}
	if due, _ := q.Due(ctx, "a", now); len(due) != 0 {
		t.Fatal("retry returned early")
	}
	due, err := q.Due(ctx, "a", now.Add(2*time.Second))
	if err != nil || len(due) != 1 || due[0].ID != "e2" || due[0].Attempt != 1 {
		t.Fatalf("due: %+v %v", due, err)
	}
	if again, _ := q.Due(ctx, "a", now.Add(3*time.Second)); len(again) != 0 {
		t.Fatal("claimed retry handed out twice within the lease")
	}
	if err := q.Dead(ctx, "a", due[0]); err != nil {
		t.Fatal(err)
	}
	if n, _ := rdb.LLen(ctx, deadKey("a")).Result(); n != 1 {
		t.Fatalf("dead len = %d", n)
	}
	if n, _ := rdb.ZCard(ctx, retryKey("a")).Result(); n != 0 {
		t.Fatalf("retry set still has %d", n)
	}
}

func TestGroupStoreEvents(t *testing.T) {
	snow, _ := snowflake.New(1)
	q := NewMemoryQueue()
	p := NewPublisher([]Target{{AppID: "a", URL: "x", Secret: "s"}}, q)
	gs := WrapGroupStore(gateway.NewInMemoryGroupStore(snow), p)
	ctx := context.Background()

	g, _ := gs.Create(ctx, "g", "alice", []string{"bob"})
	gs.AddMember(ctx, g.ID, "carol")
	gs.RemoveMember(ctx, g.ID, "carol")
	gs.RemoveMember(ctx, g.ID, "bob")
	gs.RemoveMember(ctx, g.ID, "alice")

	var types []string
	for len(p.ch) > 0 {
		types = append(types, (<-p.ch).d.Type)
	}
	joined, left, dismissed := 0, 0, 0
	for _, typ := range types {
		switch typ {
		case EventGroupMemberJoined:
			joined++
		case EventGroupMemberLeft:
			left++
		case EventGroupDismissed:
			dismissed++
		}
	}
	if joined != 3 || left != 3 || dismissed != 1 {
		t.Fatalf("events = %v", types)
	}
}

func TestBeforeSend(t *testing.T) {
	var mode atomic.Value
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := imserver.VerifyWebhook(r, "s")
		if err != nil {
			http.Error(w, "sig", http.StatusUnauthorized)
			return
		}
		var m imserver.MessageEventData
		json.Unmarshal(body, &m)
		switch mode.Load() {
		case "reject":
			w.Write([]byte(`{"allow":false,"reason":"spam"}`))
		case "slow":
			time.Sleep(200 * time.Millisecond)
			w.Write([]byte(`{"allow":false}`))
		default:
			w.Write([]byte(`{"allow":true}`))
		}
	}))
	defer srv.Close()
	b := NewBeforeSend(srv.URL, "s", 50*time.Millisecond)
	msg := &proto.Message{From: "u1", To: "u2", Content: "buy now"}
	ctx := context.Background()

	mode.Store("allow")
	if ok, _ := b.BeforeSend(ctx, msg); !ok {
		t.Error("allow rejected")
	}
	mode.Store("reject")
	if ok, reason := b.BeforeSend(ctx, msg); ok || reason != "spam" {
		t.Errorf("reject: %v %q", ok, reason)
	}
	mode.Store("slow")
	if ok, _ := b.BeforeSend(ctx, msg); !ok {
		t.Error("timeout should fail open")
	}
	if ok, _ := NewBeforeSend("http://127.0.0.1:1", "s", 50*time.Millisecond).BeforeSend(ctx, msg); !ok {
		t.Error("connection error should fail open")
	}
}
