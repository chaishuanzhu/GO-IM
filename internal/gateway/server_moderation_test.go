package gateway

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"testing"

	"github.com/im/internal/moderation"
	"github.com/im/internal/repo"
)

func TestModerationFlow(t *testing.T) {
	srv, users, mgr := newAdminServer(t, nil)
	e := &accountEnv{jwt: mgr}
	ctx := context.Background()
	for _, u := range []*repo.User{
		{UID: "alice", Nickname: "Alice", Role: "user"},
		{UID: "bob", Nickname: "Bob", Role: "user"},
		{UID: "root", Nickname: "Root", Role: "admin"},
	} {
		_ = users.Create(ctx, u)
	}

	rec := e.do(t, srv.HandleUserBlock, http.MethodPost, e.auth(t, "alice", url.Values{"target_uid": {"bob"}}))
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("without service: %d", rec.Code)
	}
	srv.SetModeration(moderation.NewService(moderation.NewMemoryStore()))

	rec = e.do(t, srv.HandleUserBlock, http.MethodPost, e.auth(t, "alice", url.Values{"target_uid": {"bob"}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("block: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, srv.HandleUserBlock, http.MethodPost, e.auth(t, "alice", url.Values{"target_uid": {"alice"}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("self block: %d", rec.Code)
	}
	rec = e.do(t, srv.HandleUserBlocks, http.MethodGet, e.auth(t, "alice", nil))
	var blocks struct{ Blocked []string }
	_ = json.Unmarshal(rec.Body.Bytes(), &blocks)
	if len(blocks.Blocked) != 1 || blocks.Blocked[0] != "bob" {
		t.Fatalf("blocks: %s", rec.Body.String())
	}

	rec = e.do(t, srv.HandleUserReport, http.MethodPost, e.auth(t, "alice", url.Values{
		"target_uid": {"bob"}, "content": {"rude"}, "message_id": {"42"}, "context": {"room1"},
	}))
	if rec.Code != http.StatusOK {
		t.Fatalf("report: %d %s", rec.Code, rec.Body.String())
	}
	var created struct{ ID int64 }
	_ = json.Unmarshal(rec.Body.Bytes(), &created)

	rec = e.do(t, srv.HandleAdminReports, http.MethodGet, e.auth(t, "alice", nil))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("non-admin list: %d", rec.Code)
	}
	rec = e.do(t, srv.HandleAdminReports, http.MethodGet, adminQuery(t, mgr, "admin", url.Values{}))
	var list struct {
		Reports []struct {
			ID               int64
			Content          string
			ReporterNickname string `json:"reporter_nickname"`
			TargetNickname   string `json:"target_nickname"`
			TargetDisabled   bool   `json:"target_disabled"`
		}
		Pending int
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if rec.Code != http.StatusOK || list.Pending != 1 || len(list.Reports) != 1 ||
		list.Reports[0].ReporterNickname != "Alice" || list.Reports[0].TargetNickname != "Bob" || list.Reports[0].TargetDisabled {
		t.Fatalf("admin list: %d %s", rec.Code, rec.Body.String())
	}

	id := url.Values{"id": {"1"}}
	rec = e.do(t, srv.HandleAdminReportResolve, http.MethodPost, adminQuery(t, mgr, "admin", url.Values{"id": {"1"}, "action": {"nuke"}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad action: %d", rec.Code)
	}
	id.Set("action", "ban")
	rec = e.do(t, srv.HandleAdminReportResolve, http.MethodPost, adminQuery(t, mgr, "admin", id))
	if rec.Code != http.StatusOK {
		t.Fatalf("ban: %d %s", rec.Code, rec.Body.String())
	}
	if u, _ := users.GetByUID(ctx, "bob"); !u.IsDisabled {
		t.Fatal("bob should be disabled")
	}
	rec = e.do(t, srv.HandleAdminReports, http.MethodGet, adminQuery(t, mgr, "admin", url.Values{"status": {"handled"}}))
	_ = json.Unmarshal(rec.Body.Bytes(), &list)
	if list.Pending != 0 || len(list.Reports) != 1 || !list.Reports[0].TargetDisabled {
		t.Fatalf("handled list: %s", rec.Body.String())
	}

	// 举报管理员：只能忽略，不能封禁。
	rec = e.do(t, srv.HandleUserReport, http.MethodPost, e.auth(t, "alice", url.Values{"target_uid": {"root"}, "content": {"x"}}))
	_ = json.Unmarshal(rec.Body.Bytes(), &created)
	rec = e.do(t, srv.HandleAdminReportResolve, http.MethodPost, adminQuery(t, mgr, "admin", url.Values{
		"id": {jsonID(created.ID)}, "action": {"ban"},
	}))
	if rec.Code != http.StatusBadRequest && rec.Code != http.StatusForbidden {
		t.Fatalf("ban admin: %d", rec.Code)
	}
	rec = e.do(t, srv.HandleAdminReportResolve, http.MethodPost, adminQuery(t, mgr, "admin", url.Values{
		"id": {jsonID(created.ID)}, "action": {"ignore"},
	}))
	if rec.Code != http.StatusOK {
		t.Fatalf("ignore: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, srv.HandleAdminReportResolve, http.MethodPost, adminQuery(t, mgr, "admin", url.Values{"id": {"999"}, "action": {"ignore"}}))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("missing report: %d", rec.Code)
	}
}

func jsonID(id int64) string {
	b, _ := json.Marshal(id)
	return string(b)
}
