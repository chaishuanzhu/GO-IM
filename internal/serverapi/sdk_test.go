package serverapi

import (
	"context"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/chaishuanzhu/GO-IM/pkg/imserver"
	"github.com/im/api/proto"
)

func TestSDKAgainstAPI(t *testing.T) {
	e := newEnv(t)
	e.api.now = time.Now
	srv := httptest.NewServer(e.api)
	defer srv.Close()
	ctx := context.Background()

	if imserver.Sign("k", "POST", "/p", "1", "n", []byte("b")) != Sign("k", "POST", "/p", "1", "n", []byte("b")) {
		t.Fatal("SDK and server signatures differ")
	}

	c := imserver.New(srv.URL, "paircast", testSecret)
	alice, err := c.ImportUser(ctx, imserver.ImportUser{ExternalID: "alice", Nickname: imserver.String("Alice")})
	if err != nil || !alice.Created || alice.Nickname != "Alice" {
		t.Fatalf("import: %+v %v", alice, err)
	}
	users, err := c.ImportUsers(ctx, []imserver.ImportUser{{ExternalID: "bob"}})
	if err != nil || len(users) != 1 {
		t.Fatalf("batch import: %v %v", users, err)
	}
	bobConn := e.connect(t, users[0].UID)

	tok, err := c.IssueToken(ctx, imserver.External("alice"), time.Minute)
	if err != nil || tok.UID != alice.UID {
		t.Fatalf("token: %+v %v", tok, err)
	}
	info, err := c.VerifyToken(ctx, tok.Token)
	if err != nil || !info.Valid || info.ExternalID != "alice" {
		t.Fatalf("verify: %+v %v", info, err)
	}

	g, err := c.CreateGroup(ctx, "room", imserver.External("alice"), []imserver.UserRef{imserver.External("bob")})
	if err != nil || len(g.Members) != 2 {
		t.Fatalf("group: %+v %v", g, err)
	}
	recv(t, bobConn)

	if err := c.SendSignal(ctx, imserver.Signal{GroupID: g.ID, Type: "paircast.room_updated", Data: map[string]int{"n": 1}}); err != nil {
		t.Fatal(err)
	}
	if m := recv(t, bobConn); m.Cmd != proto.CmdSignal {
		t.Fatalf("signal: %+v", m)
	}
	from := imserver.External("alice")
	if _, err := c.SendMessage(ctx, imserver.Message{From: &from, To: &imserver.UserRef{UID: users[0].UID}, Content: "hi"}); err != nil {
		t.Fatal(err)
	}
	if m := recv(t, bobConn); m.Content != "hi" {
		t.Fatalf("message: %+v", m)
	}

	_, err = c.IssueToken(ctx, imserver.External("nobody"), 0)
	if !imserver.IsNotFound(err) {
		t.Fatalf("missing user: %v", err)
	}
	if err := imserver.New(srv.URL, "paircast", "wrong").DismissGroup(ctx, g.ID); err == nil {
		t.Fatal("wrong secret accepted")
	}
	if err := c.DismissGroup(ctx, g.ID); err != nil {
		t.Fatal(err)
	}
}
