package gateway

import (
	"context"
	"net/http"
	"net/url"
	"regexp"
	"testing"
)

var initialPassword = regexp.MustCompile(`初始密码：(\S+)`)

func TestBootstrapAdminCreatesAccountAndForcesPasswordChange(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()

	if err := BootstrapAdmin(ctx, e.users, e.mail, " Admin@Example.com "); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}
	u, _ := e.users.GetByEmail(ctx, "admin@example.com")
	if u == nil || u.Role != "admin" || !u.MustChangePassword {
		t.Fatalf("admin account = %+v", u)
	}
	if e.mail.to != "admin@example.com" {
		t.Fatalf("mail sent to %q", e.mail.to)
	}
	m := initialPassword.FindStringSubmatch(e.mail.text)
	if m == nil || len(m[1]) != bootstrapPasswordLen {
		t.Fatalf("no password in mail %q", e.mail.text)
	}
	password := m[1]

	rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {"admin@example.com"}, "password": {password}})
	if rec.Code != http.StatusOK {
		t.Fatalf("login: %d %s", rec.Code, rec.Body.String())
	}
	body := decode(t, rec)
	if body["must_change_password"] != true || body["role"] != "admin" {
		t.Fatalf("login body = %v", body)
	}

	form := url.Values{"uid": {u.UID}, "token": {body["token"].(string)}, "old_password": {password}, "new_password": {"brand-new-pass"}}
	if rec := e.do(t, e.srv.HandleChangePassword, http.MethodPost, form); rec.Code != http.StatusOK {
		t.Fatalf("change-password: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {u.UID}, "password": {"brand-new-pass"}})
	if body := decode(t, rec); body["must_change_password"] != false {
		t.Fatalf("flag not cleared: %v", body)
	}

	// 再次启动：账号已存在，不重建、不重发
	e.mail.to, e.mail.text = "", ""
	if err := BootstrapAdmin(ctx, e.users, e.mail, "admin@example.com"); err != nil {
		t.Fatalf("second bootstrap: %v", err)
	}
	if e.mail.to != "" {
		t.Fatalf("second bootstrap sent mail")
	}
	if n, _ := e.users.CountUsers(ctx); n != 1 {
		t.Fatalf("users = %d, want 1", n)
	}
}

func TestBootstrapAdminPromotesExistingAccount(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	reg := e.register(t, "boss@example.com", "secret1")
	e.mail.to = ""

	if err := BootstrapAdmin(ctx, e.users, e.mail, "boss@example.com"); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}
	u, _ := e.users.GetByUID(ctx, reg["uid"].(string))
	if u.Role != "admin" || u.MustChangePassword {
		t.Fatalf("promoted account = %+v", u)
	}
	if e.mail.to != "" {
		t.Fatalf("existing account must not get a password mail")
	}
}

func TestBootstrapAdminStillCreatesWhenMailFails(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.mail.fail = true
	if err := BootstrapAdmin(ctx, e.users, e.mail, "admin@example.com"); err != nil {
		t.Fatalf("bootstrap: %v", err)
	}
	if err := BootstrapAdmin(ctx, e.users, nil, "other@example.com"); err != nil {
		t.Fatalf("bootstrap without mailer: %v", err)
	}
	for _, email := range []string{"admin@example.com", "other@example.com"} {
		if u, _ := e.users.GetByEmail(ctx, email); u == nil || u.Role != "admin" {
			t.Fatalf("%s not created: %+v", email, u)
		}
	}
}

func TestBootstrapAdminRejectsInvalidEmail(t *testing.T) {
	e := newAccountEnv(t, false)
	if err := BootstrapAdmin(context.Background(), e.users, e.mail, "not-an-email"); err == nil {
		t.Fatal("expected error for invalid email")
	}
}

func TestRandomPasswordAlphabet(t *testing.T) {
	p, err := randomPassword(64)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range p {
		if !regexp.MustCompile(`^[` + bootstrapPasswordAlphabet + `]$`).MatchString(string(c)) {
			t.Fatalf("unexpected char %q", c)
		}
	}
}
