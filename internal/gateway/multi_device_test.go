package gateway

import (
	"context"
	"sync/atomic"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/im/api/proto"
	"github.com/im/internal/pkg/snowflake"
	"github.com/redis/go-redis/v9"
)

type fakeTransport struct{ closed atomic.Bool }

func (f *fakeTransport) Close() error       { f.closed.Store(true); return nil }
func (f *fakeTransport) Write([]byte) error { return nil }
func (f *fakeTransport) Ping() error        { return nil }

// newDeviceClient 创建带设备信息、可安全 Close 的测试连接。
func newDeviceClient(t *testing.T, uid, deviceID, platform string) (*Client, *fakeTransport) {
	t.Helper()
	tr := &fakeTransport{}
	c := NewClient(uid, uid, tr, nil, 64)
	c.SetDevice(deviceID, platform)
	return c, tr
}

func isClosed(c *Client) bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

func newMultiDeviceRouter(t *testing.T, policy DevicePolicy) (*Hub, *Router) {
	t.Helper()
	h := NewHub(100)
	h.SetDevicePolicy(policy)
	sg, _ := snowflake.New(1)
	return h, NewRouter(h, h, sg, nil, DefaultRouterConfig())
}

// ---------- 登录策略 ----------

func TestHubSinglePolicyKicksOtherDevice(t *testing.T) {
	h := NewHub(100)
	phone, _ := newDeviceClient(t, "alice", "p1", "ios")
	desktop, _ := newDeviceClient(t, "alice", "d1", "desktop")
	h.Register(context.Background(), phone)
	h.Register(context.Background(), desktop)

	if !isClosed(phone) {
		t.Fatal("single policy: phone should be kicked")
	}
	kick := readMessageFromChan(t, phone.send)
	if kick.Cmd != proto.CmdKick {
		t.Fatalf("expected CmdKick, got cmd=%d", kick.Cmd)
	}
	if got := h.GetAll(context.Background(), "alice"); len(got) != 1 || got[0] != desktop {
		t.Fatalf("expected only desktop online, got %d conns", len(got))
	}
}

func TestHubPerPlatformKeepsPhoneAndDesktop(t *testing.T) {
	h := NewHub(100)
	h.SetDevicePolicy(DevicePolicyPerPlatform)
	phone, _ := newDeviceClient(t, "alice", "p1", "ios")
	desktop, _ := newDeviceClient(t, "alice", "d1", "desktop")
	h.Register(context.Background(), phone)
	h.Register(context.Background(), desktop)

	if isClosed(phone) || isClosed(desktop) {
		t.Fatal("per_platform: phone and desktop should both stay online")
	}
	if n := len(h.GetAll(context.Background(), "alice")); n != 2 {
		t.Fatalf("expected 2 conns, got %d", n)
	}
	if h.Count(context.Background()) != 1 {
		t.Errorf("Count should count users, got %d", h.Count(context.Background()))
	}

	// 另一台手机(Android 与 iOS 同属手机类)挤掉旧手机,电脑不受影响。
	android, _ := newDeviceClient(t, "alice", "p2", "android")
	h.Register(context.Background(), android)
	if !isClosed(phone) {
		t.Fatal("new phone should kick the old phone")
	}
	if kick := readMessageFromChan(t, phone.send); kick.Cmd != proto.CmdKick {
		t.Fatalf("expected CmdKick, got cmd=%d", kick.Cmd)
	}
	if isClosed(desktop) {
		t.Fatal("desktop should stay online")
	}
}

func TestHubSameDeviceReconnectReplacesSilently(t *testing.T) {
	h := NewHub(100)
	h.SetDevicePolicy(DevicePolicyUnlimited)
	old, _ := newDeviceClient(t, "alice", "d1", "desktop")
	h.Register(context.Background(), old)
	reconnect, _ := newDeviceClient(t, "alice", "d1", "desktop")
	h.Register(context.Background(), reconnect)

	if !isClosed(old) {
		t.Fatal("stale connection of the same device should be closed")
	}
	assertChanEmpty(t, old.send) // 同设备重连不发踢下线通知
	if got := h.GetAll(context.Background(), "alice"); len(got) != 1 || got[0] != reconnect {
		t.Fatal("expected only the new connection")
	}
}

func TestHubLegacyClientsStillKickEachOther(t *testing.T) {
	h := NewHub(100)
	h.SetDevicePolicy(DevicePolicyPerPlatform)
	a, _ := newDeviceClient(t, "alice", "", "")
	b, _ := newDeviceClient(t, "alice", "", "")
	h.Register(context.Background(), a)
	h.Register(context.Background(), b)
	if !isClosed(a) {
		t.Fatal("legacy clients without device info should keep the single-login behaviour")
	}
}

func TestHubUnregisterRemovesOnlyThatDevice(t *testing.T) {
	h := NewHub(100)
	h.SetDevicePolicy(DevicePolicyPerPlatform)
	phone, _ := newDeviceClient(t, "alice", "p1", "ios")
	desktop, _ := newDeviceClient(t, "alice", "d1", "desktop")
	h.Register(context.Background(), phone)
	h.Register(context.Background(), desktop)

	h.Unregister(context.Background(), phone)
	if got := h.GetAll(context.Background(), "alice"); len(got) != 1 || got[0] != desktop {
		t.Fatal("expected desktop to remain")
	}
	h.Unregister(context.Background(), desktop)
	if h.IsOnline(context.Background(), "alice") {
		t.Fatal("alice should be offline")
	}
}

func TestParseDevicePolicy(t *testing.T) {
	cases := map[string]DevicePolicy{
		"":             DevicePolicySingle,
		"bogus":        DevicePolicySingle,
		"per_platform": DevicePolicyPerPlatform,
		"unlimited":    DevicePolicyUnlimited,
	}
	for in, want := range cases {
		if got := ParseDevicePolicy(in); got != want {
			t.Errorf("ParseDevicePolicy(%q)=%s, want %s", in, got, want)
		}
	}
}

func TestParseLoginContent(t *testing.T) {
	lc := parseLoginContent(`{"token":"jwt","device_id":"abc","platform":"ios"}`)
	if lc.Token != "jwt" || lc.DeviceID != "abc" || lc.Platform != "ios" {
		t.Fatalf("unexpected %+v", lc)
	}
	if lc := parseLoginContent("eyJhbGciOi.plain.jwt"); lc.Token != "eyJhbGciOi.plain.jwt" || lc.DeviceID != "" {
		t.Fatalf("plain JWT should pass through, got %+v", lc)
	}
}

func TestSetDeviceSanitizes(t *testing.T) {
	c := &Client{}
	c.SetDevice("ab c\n<script>-1", "iOS")
	if c.DeviceID != "abcscript-1" || c.Platform != "ios" {
		t.Fatalf("got device=%q platform=%q", c.DeviceID, c.Platform)
	}
}

// ---------- 投递 ----------

func TestChatDeliveredToAllRecipientDevicesAndSyncedToSender(t *testing.T) {
	h, r := newMultiDeviceRouter(t, DevicePolicyPerPlatform)
	ctx := context.Background()
	bobPhone, _ := newDeviceClient(t, "bob", "bp", "android")
	bobDesktop, _ := newDeviceClient(t, "bob", "bd", "desktop")
	alicePhone, _ := newDeviceClient(t, "alice", "ap", "ios")
	aliceDesktop, _ := newDeviceClient(t, "alice", "ad", "desktop")
	for _, c := range []*Client{bobPhone, bobDesktop, alicePhone, aliceDesktop} {
		h.Register(ctx, c)
	}

	r.Route(ctx, alicePhone, &proto.Message{
		Cmd: proto.CmdChat, From: "alice", To: "bob", ChatType: proto.ChatTypeSingle,
		MsgType: proto.MsgTypeText, Content: "hi", NeedAck: true, Seq: 7,
	})

	m1 := readMessageFromChan(t, bobPhone.send)
	m2 := readMessageFromChan(t, bobDesktop.send)
	synced := readMessageFromChan(t, aliceDesktop.send)
	ack := readMessageFromChan(t, alicePhone.send)
	if m1.MsgId == 0 || m1.MsgId != m2.MsgId || synced.MsgId != m1.MsgId {
		t.Fatalf("all copies should share the msg id: %d %d %d", m1.MsgId, m2.MsgId, synced.MsgId)
	}
	if synced.From != "alice" || synced.To != "bob" || synced.Seq != 7 {
		t.Fatalf("sync copy should keep from/to/seq, got from=%s to=%s seq=%d", synced.From, synced.To, synced.Seq)
	}
	if ack.Cmd != proto.CmdAck || ack.MsgId != m1.MsgId {
		t.Fatalf("sending device should get only the ACK, got cmd=%d", ack.Cmd)
	}
	assertChanEmpty(t, alicePhone.send)
}

func TestDedupIsPerDevice(t *testing.T) {
	h, r := newMultiDeviceRouter(t, DevicePolicyPerPlatform)
	ctx := context.Background()
	bob, _ := newDeviceClient(t, "bob", "b", "ios")
	alicePhone, _ := newDeviceClient(t, "alice", "ap", "ios")
	aliceDesktop, _ := newDeviceClient(t, "alice", "ad", "desktop")
	for _, c := range []*Client{bob, alicePhone, aliceDesktop} {
		h.Register(ctx, c)
	}

	send := func(from *Client, content string) {
		r.Route(ctx, from, &proto.Message{
			Cmd: proto.CmdChat, From: "alice", To: "bob", ChatType: proto.ChatTypeSingle,
			MsgType: proto.MsgTypeText, Content: content, NeedAck: true, Seq: 42,
		})
	}
	send(alicePhone, "from phone")
	send(aliceDesktop, "from desktop")

	first := readMessageFromChan(t, bob.send)
	second := readMessageFromChan(t, bob.send)
	if first.Content != "from phone" || second.Content != "from desktop" {
		t.Fatalf("same seq from two devices must not be deduplicated: %q, %q", first.Content, second.Content)
	}
}

func TestGroupFanoutReachesAllDevicesAndSyncsSender(t *testing.T) {
	h, r := newMultiDeviceRouter(t, DevicePolicyPerPlatform)
	ctx := context.Background()
	gsg, _ := snowflake.New(2)
	gs := NewInMemoryGroupStore(gsg)
	r.SetGroupStore(gs)
	g, err := gs.Create(ctx, "team", "alice", []string{"bob"})
	if err != nil {
		t.Fatal(err)
	}
	alicePhone, _ := newDeviceClient(t, "alice", "ap", "ios")
	aliceDesktop, _ := newDeviceClient(t, "alice", "ad", "desktop")
	bobPhone, _ := newDeviceClient(t, "bob", "bp", "ios")
	bobDesktop, _ := newDeviceClient(t, "bob", "bd", "desktop")
	for _, c := range []*Client{alicePhone, aliceDesktop, bobPhone, bobDesktop} {
		h.Register(ctx, c)
	}

	r.Route(ctx, aliceDesktop, &proto.Message{
		Cmd: proto.CmdChat, From: "alice", To: g.ID, ChatType: proto.ChatTypeGroup,
		MsgType: proto.MsgTypeText, Content: "hello team", Seq: 1,
	})

	for _, c := range []*Client{bobPhone, bobDesktop, alicePhone} {
		if m := readMessageFromChan(t, c.send); m.Content != "hello team" {
			t.Fatalf("device %s/%s got %q", c.UID, c.DeviceID, m.Content)
		}
	}
	assertChanEmpty(t, aliceDesktop.send)
}

func TestReadReceiptSyncedToReadersOtherDevices(t *testing.T) {
	h, r := newMultiDeviceRouter(t, DevicePolicyPerPlatform)
	ctx := context.Background()
	alicePhone, _ := newDeviceClient(t, "alice", "ap", "ios")
	aliceDesktop, _ := newDeviceClient(t, "alice", "ad", "desktop")
	bob, _ := newDeviceClient(t, "bob", "b", "ios")
	for _, c := range []*Client{alicePhone, aliceDesktop, bob} {
		h.Register(ctx, c)
	}

	r.Route(ctx, alicePhone, &proto.Message{Cmd: proto.CmdReadReceipt, From: "alice", To: "bob", MsgId: 99})

	toBob := readMessageFromChan(t, bob.send)
	sync := readMessageFromChan(t, aliceDesktop.send)
	if toBob.Cmd != proto.CmdReadReceipt || sync.Cmd != proto.CmdReadReceipt {
		t.Fatalf("expected read receipts, got %d / %d", toBob.Cmd, sync.Cmd)
	}
	if sync.From != "alice" || sync.To != "bob" {
		t.Fatalf("sync receipt should be from=alice to=bob, got from=%s to=%s", sync.From, sync.To)
	}
	assertChanEmpty(t, alicePhone.send)
}

func TestFriendNotificationReachesAllDevices(t *testing.T) {
	h, r := newMultiDeviceRouter(t, DevicePolicyPerPlatform)
	ctx := context.Background()
	phone, _ := newDeviceClient(t, "bob", "bp", "ios")
	desktop, _ := newDeviceClient(t, "bob", "bd", "desktop")
	h.Register(ctx, phone)
	h.Register(ctx, desktop)

	r.NotifyFriendRequest(ctx, "alice", "Alice", "bob")
	for _, c := range []*Client{phone, desktop} {
		if m := readMessageFromChan(t, c.send); m.Cmd != proto.CmdFriendRequest {
			t.Fatalf("expected friend request on %s, got cmd=%d", c.DeviceID, m.Cmd)
		}
	}
}

// ---------- Redis 未读计数 ----------

func TestRedisUnreadTracker(t *testing.T) {
	mr, err := miniredis.Run()
	if err != nil {
		t.Fatal(err)
	}
	defer mr.Close()
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	defer rdb.Close()
	ctx := context.Background()

	// 两个网关共享同一个 Redis:一个自增,另一个读取 / 清除。
	a, b := NewRedisUnreadTracker(rdb), NewRedisUnreadTracker(rdb)
	a.Increment(ctx, "bob", "alice")
	a.Increment(ctx, "bob", "alice")
	a.Increment(ctx, "bob", "g_1")
	a.Increment(ctx, "bob", "bob")

	if got := b.GetCount(ctx, "bob", "alice"); got != 2 {
		t.Fatalf("GetCount=%d, want 2", got)
	}
	counts := b.GetCounts(ctx, "bob")
	if len(counts) != 2 || counts["g_1"] != 1 {
		t.Fatalf("unexpected counts %v", counts)
	}
	if ttl := mr.TTL(unreadKeyPrefix + "bob"); ttl <= 0 || ttl > unreadTTL {
		t.Fatalf("unexpected TTL %v", ttl)
	}

	b.MarkRead(ctx, "bob", "alice")
	if got := a.GetCount(ctx, "bob", "alice"); got != 0 {
		t.Fatalf("after MarkRead GetCount=%d, want 0", got)
	}

	// Redis 不可用时回退内存,不丢计数。
	mr.Close()
	a.Increment(ctx, "carol", "alice")
	deadline := time.Now().Add(time.Second)
	for a.fallback.GetCount(ctx, "carol", "alice") != 1 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := a.fallback.GetCount(ctx, "carol", "alice"); got != 1 {
		t.Fatalf("fallback count=%d, want 1", got)
	}
}
