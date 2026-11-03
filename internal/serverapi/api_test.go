package serverapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/gateway"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
	pb "google.golang.org/protobuf/proto"
)

const testSecret = "s3cret"

type fakeUsers struct {
	mu     sync.Mutex
	byUID  map[string]*repo.User
	nextID int
}

func (f *fakeUsers) GetByUID(_ context.Context, uid string) (*repo.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if u, ok := f.byUID[uid]; ok {
		cp := *u
		return &cp, nil
	}
	return nil, nil
}

func (f *fakeUsers) GetByExternalID(_ context.Context, ext string) (*repo.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, u := range f.byUID {
		if u.ExternalID == ext {
			cp := *u
			return &cp, nil
		}
	}
	return nil, nil
}

func (f *fakeUsers) UpsertExternalUser(ctx context.Context, ext string, nickname *string, avatar *int64) (*repo.User, bool, error) {
	u, _ := f.GetByExternalID(ctx, ext)
	f.mu.Lock()
	defer f.mu.Unlock()
	created := u == nil
	if created {
		f.nextID++
		u = &repo.User{UID: strconv.Itoa(100000 + f.nextID), ExternalID: ext, Nickname: ext}
	} else {
		u = f.byUID[u.UID]
	}
	if nickname != nil && *nickname != "" {
		u.Nickname = *nickname
	}
	if avatar != nil {
		u.AvatarFileID = *avatar
	}
	f.byUID[u.UID] = u
	cp := *u
	return &cp, created, nil
}

func (f *fakeUsers) BumpTokenVersion(_ context.Context, uid string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	u, ok := f.byUID[uid]
	if !ok {
		return 0, repo.ErrUserNotFound
	}
	u.TokenVersion++
	return u.TokenVersion, nil
}

// fakeAccounts 用真实 JWT 签发 / 校验，状态读 fakeUsers。
type fakeAccounts struct {
	users   *fakeUsers
	jwt     *jwt.Manager
	kicked  []string
	deleted []string
}

func (a *fakeAccounts) IssueToken(u *repo.User, ttl time.Duration) (string, error) {
	return a.jwt.GenerateVersioned(u.UID, u.Nickname, "user", u.TokenVersion, ttl)
}

func (a *fakeAccounts) VerifyToken(ctx context.Context, token string) (*jwt.Claims, string, error) {
	c, err := a.jwt.Validate(token)
	if err != nil {
		return nil, "", err
	}
	u, _ := a.users.GetByUID(ctx, c.UID)
	switch {
	case u == nil:
		return c, "account deleted", errors.New("unavailable")
	case u.IsDisabled:
		return c, "account disabled", errors.New("unavailable")
	case c.Ver < u.TokenVersion:
		return c, "token revoked", errors.New("unavailable")
	}
	return c, "", nil
}

func (a *fakeAccounts) RevokeTokens(ctx context.Context, uid string) (int64, error) {
	a.kicked = append(a.kicked, uid)
	return a.users.BumpTokenVersion(ctx, uid)
}

func (a *fakeAccounts) SetAccountDisabled(_ context.Context, uid string, disabled bool) error {
	a.users.mu.Lock()
	defer a.users.mu.Unlock()
	a.users.byUID[uid].IsDisabled = disabled
	return nil
}

func (a *fakeAccounts) DeleteAccount(_ context.Context, uid string) error {
	a.users.mu.Lock()
	defer a.users.mu.Unlock()
	delete(a.users.byUID, uid)
	a.deleted = append(a.deleted, uid)
	return nil
}

type chanTransport struct{ out chan []byte }

func (t *chanTransport) Close() error { return nil }
func (t *chanTransport) Ping() error  { return nil }
func (t *chanTransport) Write(b []byte) error {
	t.out <- b
	return nil
}

type env struct {
	api      *API
	hub      *gateway.Hub
	users    *fakeUsers
	accounts *fakeAccounts
	groups   gateway.GroupStore
	now      time.Time
	nonce    int
}

func newEnv(t *testing.T) *env {
	t.Helper()
	hub := gateway.NewHub(100)
	snow, _ := snowflake.New(1)
	router := gateway.NewRouter(hub, hub, snow, nil, gateway.DefaultRouterConfig())
	t.Cleanup(router.Stop)
	groups := gateway.NewInMemoryGroupStore(snow)
	router.SetGroupStore(groups)
	users := &fakeUsers{byUID: map[string]*repo.User{}}
	accounts := &fakeAccounts{users: users, jwt: jwt.New("k", time.Hour)}
	e := &env{hub: hub, users: users, accounts: accounts, groups: groups, now: time.Unix(1_800_000_000, 0)}
	e.api = New(Deps{
		Apps:        NewRegistry([]configs.ServerAppConfig{{AppID: "paircast", Secret: testSecret}}),
		Users:       users,
		Groups:      groups,
		Accounts:    accounts,
		Messenger:   router,
		MaxTokenTTL: time.Hour,
	})
	e.api.now = func() time.Time { return e.now }
	return e
}

func (e *env) signed(path string, body any) *http.Request {
	raw, _ := json.Marshal(body)
	e.nonce++
	ts := strconv.FormatInt(e.now.Unix(), 10)
	nonce := "n" + strconv.Itoa(e.nonce)
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(raw))
	req.Header.Set(HeaderAppID, "paircast")
	req.Header.Set(HeaderTimestamp, ts)
	req.Header.Set(HeaderNonce, nonce)
	req.Header.Set(HeaderSignature, Sign(testSecret, http.MethodPost, path, ts, nonce, raw))
	return req
}

func (e *env) do(t *testing.T, req *http.Request) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	e.api.ServeHTTP(rec, req)
	var out map[string]any
	json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func (e *env) call(t *testing.T, path string, body any) map[string]any {
	t.Helper()
	code, out := e.do(t, e.signed(Prefix+path, body))
	if code != http.StatusOK {
		t.Fatalf("%s: status %d %v", path, code, out)
	}
	return out
}

func (e *env) connect(t *testing.T, uid string) chan []byte {
	t.Helper()
	tr := &chanTransport{out: make(chan []byte, 16)}
	c := gateway.NewClient(uid, uid, tr, e.hub, 16)
	go c.WriteLoop()
	e.hub.Register(context.Background(), c)
	t.Cleanup(c.Close)
	return tr.out
}

func recv(t *testing.T, ch chan []byte) *proto.Message {
	t.Helper()
	select {
	case b := <-ch:
		m := &proto.Message{}
		if err := pb.Unmarshal(b, m); err != nil {
			t.Fatal(err)
		}
		return m
	case <-time.After(2 * time.Second):
		t.Fatal("timeout waiting for message")
		return nil
	}
}

func TestSignatureChecks(t *testing.T) {
	e := newEnv(t)
	body := map[string]any{"external_id": "a"}

	req := e.signed(Prefix+"users/import", body)
	req.Header.Del(HeaderSignature)
	if code, _ := e.do(t, req); code != http.StatusUnauthorized {
		t.Errorf("missing signature: %d", code)
	}

	req = e.signed(Prefix+"users/import", body)
	req.Header.Set(HeaderSignature, "00")
	if code, _ := e.do(t, req); code != http.StatusUnauthorized {
		t.Errorf("bad signature: %d", code)
	}

	req = e.signed(Prefix+"users/import", body)
	req.Header.Set(HeaderAppID, "other")
	if code, _ := e.do(t, req); code != http.StatusUnauthorized {
		t.Errorf("unknown app: %d", code)
	}

	req = e.signed(Prefix+"users/import", body)
	e.now = e.now.Add(SignatureWindow + time.Second)
	if code, _ := e.do(t, req); code != http.StatusUnauthorized {
		t.Errorf("stale timestamp: %d", code)
	}

	req = e.signed(Prefix+"users/import", body)
	replay := req.Clone(context.Background())
	replay.Body = http.NoBody
	if code, out := e.do(t, req); code != http.StatusOK {
		t.Fatalf("valid request: %d %v", code, out)
	}
	raw, _ := json.Marshal(body)
	replay.Body = httptestBody(raw)
	if code, _ := e.do(t, replay); code != http.StatusUnauthorized {
		t.Errorf("replayed nonce: %d", code)
	}

	get := e.signed(Prefix+"users/import", body)
	get.Method = http.MethodGet
	if code, _ := e.do(t, get); code != http.StatusMethodNotAllowed {
		t.Errorf("GET: %d", code)
	}
}

func httptestBody(b []byte) *readCloser { return &readCloser{bytes.NewReader(b)} }

type readCloser struct{ *bytes.Reader }

func (readCloser) Close() error { return nil }

func TestImportTokenVerifyRevoke(t *testing.T) {
	e := newEnv(t)
	u := e.call(t, "users/import", map[string]any{"external_id": "ext-1", "nickname": "Alice"})
	if u["created"] != true || u["nickname"] != "Alice" {
		t.Fatalf("import: %v", u)
	}
	again := e.call(t, "users/import", map[string]any{"users": []map[string]any{{"external_id": "ext-1"}, {"external_id": "ext-2"}}})
	list := again["users"].([]any)
	if len(list) != 2 || list[0].(map[string]any)["uid"] != u["uid"] || list[0].(map[string]any)["created"] == true {
		t.Fatalf("batch import: %v", again)
	}

	tok := e.call(t, "users/token", map[string]any{"external_id": "ext-1", "ttl_seconds": 600})
	token := tok["token"].(string)
	v := e.call(t, "tokens/verify", map[string]any{"token": token})
	if v["valid"] != true || v["uid"] != u["uid"] || v["external_id"] != "ext-1" {
		t.Fatalf("verify: %v", v)
	}

	if code, _ := e.do(t, e.signed(Prefix+"users/token", map[string]any{"external_id": "ext-1", "ttl_seconds": 7200})); code != http.StatusBadRequest {
		t.Errorf("ttl above max: %d", code)
	}

	e.call(t, "users/revoke", map[string]any{"uid": u["uid"]})
	if v := e.call(t, "tokens/verify", map[string]any{"token": token}); v["valid"] != false || v["reason"] != "token revoked" {
		t.Fatalf("verify after revoke: %v", v)
	}

	e.call(t, "users/disable", map[string]any{"external_id": "ext-1"})
	if code, _ := e.do(t, e.signed(Prefix+"users/token", map[string]any{"external_id": "ext-1"})); code != http.StatusForbidden {
		t.Errorf("token for disabled user: %d", code)
	}

	e.call(t, "users/delete", map[string]any{"external_id": "ext-2"})
	if code, _ := e.do(t, e.signed(Prefix+"users/token", map[string]any{"external_id": "ext-2"})); code != http.StatusNotFound {
		t.Errorf("token for deleted user: %d", code)
	}
	if code, _ := e.do(t, e.signed(Prefix+"tokens/verify", map[string]any{"token": "garbage"})); code != http.StatusOK {
		t.Errorf("verify garbage: %d", code)
	}
}

func TestGroupsMessagesAndSignals(t *testing.T) {
	e := newEnv(t)
	alice := e.call(t, "users/import", map[string]any{"external_id": "alice"})["uid"].(string)
	bob := e.call(t, "users/import", map[string]any{"external_id": "bob"})["uid"].(string)
	carol := e.call(t, "users/import", map[string]any{"external_id": "carol"})["uid"].(string)
	bobConn := e.connect(t, bob)

	g := e.call(t, "groups/create", map[string]any{"name": "room", "owner": map[string]string{"external_id": "alice"}, "members": []map[string]string{{"external_id": "bob"}}})
	groupID := g["group_id"].(string)
	if n := recv(t, bobConn); n.Cmd != proto.CmdChat || n.To != groupID {
		t.Fatalf("bob expected member_joined notification, got %+v", n)
	}

	add := e.call(t, "groups/members/add", map[string]any{"group_id": groupID, "users": []map[string]string{{"uid": carol}, {"uid": bob}}})
	if added := add["added"].([]any); len(added) != 1 || added[0] != carol {
		t.Fatalf("add: %v", add)
	}
	recv(t, bobConn) // carol joined

	sent := e.call(t, "messages/send", map[string]any{"from": map[string]string{"uid": alice}, "group_id": groupID, "content": "hello"})
	if m := recv(t, bobConn); m.Cmd != proto.CmdChat || m.From != alice || m.Content != "hello" || float64(m.MsgId) != sent["msg_id"].(float64) {
		t.Fatalf("group message: %+v", m)
	}

	e.call(t, "messages/send", map[string]any{"to": map[string]string{"external_id": "bob"}, "content": "system notice"})
	if m := recv(t, bobConn); m.From != "sys:paircast" || m.ChatType != proto.ChatTypeSingle {
		t.Fatalf("system message: %+v", m)
	}

	e.call(t, "signals/send", map[string]any{"group_id": groupID, "type": "paircast.room_updated", "data": map[string]any{"state": "playing"}})
	m := recv(t, bobConn)
	sig, err := proto.ParseSignal(m.Content)
	if m.Cmd != proto.CmdSignal || err != nil || sig.Type != "paircast.room_updated" {
		t.Fatalf("signal: %+v", m)
	}
	if code, _ := e.do(t, e.signed(Prefix+"signals/send", map[string]any{"group_id": groupID, "type": "bad"})); code != http.StatusBadRequest {
		t.Errorf("untyped signal: %d", code)
	}

	outsider := e.call(t, "users/import", map[string]any{"external_id": "dave"})["uid"].(string)
	if code, _ := e.do(t, e.signed(Prefix+"messages/send", map[string]any{"from": map[string]string{"uid": outsider}, "group_id": groupID, "content": "x"})); code != http.StatusForbidden {
		t.Errorf("non-member send: %d", code)
	}

	e.call(t, "groups/members/remove", map[string]any{"group_id": groupID, "users": []map[string]string{{"uid": carol}}})
	recv(t, bobConn) // carol left
	if e.groups.IsMember(context.Background(), groupID, carol) {
		t.Error("carol still a member")
	}

	e.call(t, "groups/dismiss", map[string]any{"group_id": groupID})
	if m := recv(t, bobConn); m.From != "sys:paircast" {
		t.Fatalf("dismiss notification: %+v", m)
	}
	if _, err := e.groups.Get(context.Background(), groupID); err == nil {
		t.Error("group still exists after dismiss")
	}
	if code, _ := e.do(t, e.signed(Prefix+"groups/dismiss", map[string]any{"group_id": groupID})); code != http.StatusNotFound {
		t.Errorf("dismiss missing group: %d", code)
	}
}
