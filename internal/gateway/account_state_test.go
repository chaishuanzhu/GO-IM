package gateway

import (
	"context"
	"net/http"
	"net/url"
	"sync"
	"testing"

	"github.com/im/api/proto"
	"github.com/im/internal/repo"
)

type recordingBroadcaster struct {
	mu   sync.Mutex
	msgs []*proto.Message
}

func (b *recordingBroadcaster) Broadcast(_ context.Context, uid string, msg *proto.Message) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.msgs = append(b.msgs, msg)
}

func TestHTTPRejectsDisabledAndRevoked(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100000", Nickname: "a"})

	get := func() int {
		return e.do(t, e.srv.HandleProfile, http.MethodGet, e.auth(t, "100000", url.Values{})).Code
	}
	if code := get(); code != http.StatusOK {
		t.Fatalf("active user: %d", code)
	}

	e.users.UpdateUserDisabled(ctx, "100000", true)
	e.srv.forgetAccount("100000")
	if code := get(); code != http.StatusUnauthorized {
		t.Fatalf("disabled user: %d", code)
	}

	e.users.UpdateUserDisabled(ctx, "100000", false)
	e.users.users["100000"].TokenVersion = 1
	e.srv.forgetAccount("100000")
	if code := get(); code != http.StatusUnauthorized {
		t.Fatalf("revoked token (ver 0 < 1): %d", code)
	}
}

func TestKickUserBroadcastsToPeers(t *testing.T) {
	e := newAccountEnv(t, false)
	b := &recordingBroadcaster{}
	e.srv.SetPeerBroadcaster(b)
	e.srv.kickUser(context.Background(), "100000", kickAccountDisabled)
	if len(b.msgs) != 1 || b.msgs[0].Cmd != proto.CmdKick || b.msgs[0].Content != kickAccountDisabled {
		t.Fatalf("broadcast = %+v", b.msgs)
	}
}

func TestGrpcServerKickClosesLocalConnections(t *testing.T) {
	h := NewHub(100)
	c, _ := newDeviceClient(t, "bob", "d1", "ios")
	h.Register(context.Background(), c)
	srv := NewGrpcGatewayServer(h, h, "gw-2")
	resp, _ := srv.ForwardMessage(context.Background(), &proto.ForwardRequest{Uid: "bob", Message: &proto.Message{Cmd: proto.CmdKick, Content: "account deleted"}})
	if !resp.Delivered {
		t.Fatal("expected kick delivered")
	}
	if got := readMessageFromChan(t, c.kick); got.Content != "account deleted" {
		t.Fatalf("kick content = %q", got.Content)
	}
}

func TestIssueAndVerifyToken(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100001", Nickname: "b"})
	u, _ := e.users.GetByUID(ctx, "100001")

	token, err := e.srv.IssueToken(u, 0)
	if err != nil {
		t.Fatal(err)
	}
	if c, reason, err := e.srv.VerifyToken(ctx, token); err != nil || reason != "" || c.UID != "100001" {
		t.Fatalf("verify active: %v %q %v", c, reason, err)
	}

	if err := e.srv.SetAccountDisabled(ctx, "100001", true); err != nil {
		t.Fatal(err)
	}
	if _, reason, err := e.srv.VerifyToken(ctx, token); err != ErrAccountUnavailable || reason != kickAccountDisabled {
		t.Fatalf("verify disabled: %q %v", reason, err)
	}
	if _, _, err := e.srv.VerifyToken(ctx, "garbage"); err == nil {
		t.Fatal("garbage token accepted")
	}
}
