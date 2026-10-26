package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
	"golang.org/x/crypto/bcrypt"
	pb "google.golang.org/protobuf/proto"
)

// adminConvStore 给 testMsgStore 加上 AdminConversationStore。
type adminConvStore struct {
	*testMsgStore
	convs     []*repo.ConversationSummary
	queriedBy string
}

func (s *adminConvStore) UserConversations(_ context.Context, uid string, _ int) ([]*repo.ConversationSummary, error) {
	s.queriedBy = uid
	return s.convs, nil
}

func newAdminServer(t *testing.T, msgs repo.MessageStore) (*Server, *fakeUserStore, *jwt.Manager) {
	t.Helper()
	hub := NewHub(100)
	sg, _ := snowflake.New(1)
	router := NewRouter(hub, hub, sg, nil, DefaultRouterConfig())
	users := newFakeUserStore()
	mgr := jwt.New("test-secret", time.Hour)
	srv := NewServer(hub, router, mgr, users, msgs, nil, configs.AuthConfig{},
		time.Second, 3, configs.GatewayConnConfig{}, nil, sg, NewInMemoryObjectStore(), 10<<20, nil)
	return srv, users, mgr
}

func adminQuery(t *testing.T, mgr *jwt.Manager, role string, q url.Values) url.Values {
	t.Helper()
	token, err := mgr.Generate("root", "root", role)
	if err != nil {
		t.Fatal(err)
	}
	q.Set("uid", "root")
	q.Set("token", token)
	return q
}

func TestAdminConversations(t *testing.T) {
	store := &adminConvStore{
		testMsgStore: &testMsgStore{},
		convs: []*repo.ConversationSummary{
			{Peer: "100002", ChatType: 1, Title: "Bob", MessageCount: 3, LastMessage: &proto.Message{MsgId: 9, From: "100002", To: "100001", Content: "hi"}},
		},
	}
	srv, _, mgr := newAdminServer(t, store)
	e := &accountEnv{}

	rec := e.do(t, srv.HandleAdminConversations, http.MethodGet, adminQuery(t, mgr, "user", url.Values{"target_uid": {"100001"}}))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("non-admin: %d", rec.Code)
	}
	rec = e.do(t, srv.HandleAdminConversations, http.MethodGet, adminQuery(t, mgr, "admin", url.Values{}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("missing target: %d", rec.Code)
	}
	rec = e.do(t, srv.HandleAdminConversations, http.MethodGet, adminQuery(t, mgr, "admin", url.Values{"target_uid": {"100001"}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("conversations: %d %s", rec.Code, rec.Body.String())
	}
	var body struct {
		Conversations []repo.ConversationSummary `json:"conversations"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if store.queriedBy != "100001" || len(body.Conversations) != 1 || body.Conversations[0].Title != "Bob" ||
		body.Conversations[0].LastMessage.Content != "hi" {
		t.Fatalf("body = %s", rec.Body.String())
	}
}

func TestAdminConversationsWithoutSupport(t *testing.T) {
	srv, _, mgr := newAdminServer(t, &testMsgStore{})
	rec := (&accountEnv{}).do(t, srv.HandleAdminConversations, http.MethodGet,
		adminQuery(t, mgr, "admin", url.Values{"target_uid": {"100001"}}))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", rec.Code)
	}
}

func TestAdminConversationMessages(t *testing.T) {
	now := time.Now().UnixMilli()
	store := &testMsgStore{msgs: []*proto.Message{
		{MsgId: 1, From: "100001", To: "100002", ChatType: 1, Content: "a", Timestamp: now - 3000},
		{MsgId: 2, From: "100002", To: "100001", ChatType: 1, Content: "b", Timestamp: now - 2000},
		{MsgId: 3, From: "100003", To: "100001", ChatType: 1, Content: "other", Timestamp: now - 1000},
		{MsgId: 4, From: "100003", To: "g_1", ChatType: 2, Content: "group", Timestamp: now - 500},
	}}
	srv, users, mgr := newAdminServer(t, store)
	_ = users.Create(context.Background(), &repo.User{UID: "100002", Nickname: "Bob", AvatarFileID: 7})
	e := &accountEnv{}

	var title string
	decodeBody := func(q url.Values) (msgs []*proto.Message, profiles map[string]map[string]string) {
		t.Helper()
		rec := e.do(t, srv.HandleAdminConversationMessages, http.MethodGet, adminQuery(t, mgr, "admin", q))
		if rec.Code != http.StatusOK {
			t.Fatalf("messages: %d %s", rec.Code, rec.Body.String())
		}
		var body struct {
			Messages []*proto.Message             `json:"messages"`
			Users    map[string]map[string]string `json:"users"`
			Title    string                       `json:"title"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		title = body.Title
		return body.Messages, body.Users
	}

	msgs, profiles := decodeBody(url.Values{"target_uid": {"100001"}, "peer": {"100002"}})
	if len(msgs) != 2 || msgs[0].MsgId != 2 || msgs[1].MsgId != 1 {
		t.Fatalf("single chat = %+v", msgs)
	}
	if profiles["100002"]["nickname"] != "Bob" || profiles["100002"]["avatar_url"] != "/avatar/7" {
		t.Fatalf("profiles = %v", profiles)
	}
	if title != "Bob" {
		t.Fatalf("title = %q", title)
	}

	msgs, _ = decodeBody(url.Values{"target_uid": {"100001"}, "peer": {"100002"}, "before": {itoa64(now - 2000)}})
	if len(msgs) != 1 || msgs[0].MsgId != 1 {
		t.Fatalf("paged = %+v", msgs)
	}

	msgs, _ = decodeBody(url.Values{"target_uid": {"100001"}, "peer": {"g_1"}, "chat_type": {"2"}})
	if len(msgs) != 1 || msgs[0].MsgId != 4 {
		t.Fatalf("group = %+v", msgs)
	}

	rec := e.do(t, srv.HandleAdminConversationMessages, http.MethodGet, adminQuery(t, mgr, "admin", url.Values{"target_uid": {"100001"}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("missing peer: %d", rec.Code)
	}
}

func TestAdminUserCreate(t *testing.T) {
	srv, users, mgr := newAdminServer(t, &testMsgStore{})
	ctx := context.Background()
	e := &accountEnv{}
	create := func(role, email string) *httptest.ResponseRecorder {
		return e.do(t, srv.HandleAdminUserCreate, http.MethodPost, adminQuery(t, mgr, role, url.Values{"email": {email}}))
	}

	if got := create("admin", "bob@example.com").Code; got != http.StatusServiceUnavailable {
		t.Fatalf("without mailer: %d", got)
	}
	mail := &captureMailer{}
	srv.SetAccountServices(mail, NewMemoryCodeStore())
	for _, c := range []struct {
		role, email string
		want        int
	}{
		{"user", "bob@example.com", http.StatusForbidden},
		{"admin", "not-an-email", http.StatusBadRequest},
	} {
		if got := create(c.role, c.email).Code; got != c.want {
			t.Fatalf("%+v: got %d", c, got)
		}
	}

	rec := create("admin", " Bob@Example.com ")
	if rec.Code != http.StatusOK {
		t.Fatalf("create: %d %s", rec.Code, rec.Body)
	}
	var resp struct {
		User            repo.User `json:"user"`
		Mailed          bool      `json:"mailed"`
		InitialPassword string    `json:"initial_password"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if !resp.Mailed || resp.InitialPassword != "" || resp.User.Email != "bob@example.com" || resp.User.Role != "user" {
		t.Fatalf("resp = %+v", resp)
	}
	u, _ := users.GetByUID(ctx, resp.User.UID)
	if u == nil || !u.MustChangePassword || u.Nickname != "bob" {
		t.Fatalf("stored user = %+v", u)
	}
	password := regexp.MustCompile(`初始密码：(\S+)`).FindStringSubmatch(mail.text)
	if mail.to != "bob@example.com" || password == nil {
		t.Fatalf("mail to %q: %q", mail.to, mail.text)
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password[1])) != nil {
		t.Fatal("mailed password does not match")
	}

	if got := create("admin", "bob@example.com").Code; got != http.StatusConflict {
		t.Fatalf("duplicate: %d", got)
	}

	mail.fail = true
	rec = create("admin", "carol@example.com")
	resp.InitialPassword = ""
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil || rec.Code != http.StatusOK {
		t.Fatalf("create with mail down: %d %v", rec.Code, err)
	}
	carol, _ := users.GetByUID(ctx, resp.User.UID)
	if resp.Mailed || carol == nil || bcrypt.CompareHashAndPassword([]byte(carol.PasswordHash), []byte(resp.InitialPassword)) != nil {
		t.Fatalf("mail down: resp = %+v", resp)
	}
}

func TestAdminUserDisable(t *testing.T) {
	srv, users, mgr := newAdminServer(t, &testMsgStore{})
	ctx := context.Background()
	users.Create(ctx, &repo.User{UID: "100001", Nickname: "alice"})
	alice, tr := newDeviceClient(t, "100001", "d1", "ios")
	srv.clients.Register(ctx, alice)
	e := &accountEnv{}
	disable := func(role, target, value string) int {
		return e.do(t, srv.HandleAdminUserDisable, http.MethodPost,
			adminQuery(t, mgr, role, url.Values{"target_uid": {target}, "disabled": {value}})).Code
	}

	for _, c := range []struct {
		role, target, value string
		want                int
	}{
		{"user", "100001", "true", http.StatusForbidden},
		{"admin", "", "true", http.StatusBadRequest},
		{"admin", "100001", "maybe", http.StatusBadRequest},
		{"admin", "root", "true", http.StatusBadRequest},
		{"admin", "999999", "true", http.StatusNotFound},
	} {
		if got := disable(c.role, c.target, c.value); got != c.want {
			t.Fatalf("%+v: got %d", c, got)
		}
	}
	if isClosed(alice) {
		t.Fatal("rejected requests must not kick")
	}

	if got := disable("admin", "100001", "true"); got != http.StatusOK {
		t.Fatalf("disable: %d", got)
	}
	if u, _ := users.GetByUID(ctx, "100001"); !u.IsDisabled {
		t.Fatal("user not disabled")
	}
	if !isClosed(alice) || !tr.closed.Load() {
		t.Fatal("disabled user's connection should be closed")
	}
	if kick := readMessageFromChan(t, alice.send); kick.Cmd != proto.CmdKick || kick.Content != kickAccountDisabled {
		t.Fatalf("kick = %+v", kick)
	}

	if got := disable("admin", "100001", "false"); got != http.StatusOK {
		t.Fatalf("enable: %d", got)
	}
	if u, _ := users.GetByUID(ctx, "100001"); u.IsDisabled {
		t.Fatal("user still disabled")
	}
}

func TestAccountBlocked(t *testing.T) {
	srv, users, _ := newAdminServer(t, &testMsgStore{})
	ctx := context.Background()
	users.Create(ctx, &repo.User{UID: "100001"})
	users.Create(ctx, &repo.User{UID: "100002", IsDisabled: true})

	for uid, want := range map[string]string{"100001": "", "100002": kickAccountDisabled, "100003": kickAccountDeleted} {
		if got := srv.accountBlocked(ctx, uid); got != want {
			t.Errorf("%s: got %q want %q", uid, got, want)
		}
	}
	srv.authCfg.DevMode = true
	if got := srv.accountBlocked(ctx, "100003"); got != "" {
		t.Errorf("dev_mode unknown user: got %q", got)
	}
	srv.userStore = nil
	if got := srv.accountBlocked(ctx, "100002"); got != "" {
		t.Errorf("no user store: got %q", got)
	}
}

func TestWSRejectsDisabledAccount(t *testing.T) {
	srv, users, mgr := newAdminServer(t, &testMsgStore{})
	users.Create(context.Background(), &repo.User{UID: "100002", IsDisabled: true})
	ts := httptest.NewServer(http.HandlerFunc(srv.HandleWS))
	defer ts.Close()

	token, err := mgr.Generate("100002", "bob", "user")
	if err != nil {
		t.Fatal(err)
	}
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(ts.URL, "http")+"/?token="+token, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, data, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	msg := &proto.Message{}
	if err := pb.Unmarshal(data, msg); err != nil {
		t.Fatal(err)
	}
	if msg.Cmd != proto.CmdKick || msg.Content != kickAccountDisabled {
		t.Fatalf("first message = %+v", msg)
	}
	if _, _, err := conn.ReadMessage(); err == nil {
		t.Fatal("connection should be closed after kick")
	}
	if srv.clients.IsOnline(context.Background(), "100002") {
		t.Fatal("disabled user must not be registered")
	}
}

func itoa64(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
