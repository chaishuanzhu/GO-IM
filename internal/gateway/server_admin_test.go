package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"testing"
	"time"

	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
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

	decodeBody := func(q url.Values) (msgs []*proto.Message, profiles map[string]map[string]string) {
		t.Helper()
		rec := e.do(t, srv.HandleAdminConversationMessages, http.MethodGet, adminQuery(t, mgr, "admin", q))
		if rec.Code != http.StatusOK {
			t.Fatalf("messages: %d %s", rec.Code, rec.Body.String())
		}
		var body struct {
			Messages []*proto.Message            `json:"messages"`
			Users    map[string]map[string]string `json:"users"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatal(err)
		}
		return body.Messages, body.Users
	}

	msgs, profiles := decodeBody(url.Values{"target_uid": {"100001"}, "peer": {"100002"}})
	if len(msgs) != 2 || msgs[0].MsgId != 2 || msgs[1].MsgId != 1 {
		t.Fatalf("single chat = %+v", msgs)
	}
	if profiles["100002"]["nickname"] != "Bob" || profiles["100002"]["avatar_url"] != "/avatar/7" {
		t.Fatalf("profiles = %v", profiles)
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

func itoa64(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
