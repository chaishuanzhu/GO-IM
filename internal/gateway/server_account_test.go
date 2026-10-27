package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/png"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
)

// fakeUserStore 是内存 UserStore，自增 UID 从 100000 开始。
type fakeUserStore struct {
	mu    sync.Mutex
	users map[string]*repo.User
	next  int
}

func newFakeUserStore() *fakeUserStore {
	return &fakeUserStore{users: make(map[string]*repo.User), next: 100000}
}

func (f *fakeUserStore) emailTaken(email, except string) bool {
	for _, u := range f.users {
		if email != "" && u.Email == email && u.UID != except {
			return true
		}
	}
	return false
}

func (f *fakeUserStore) Create(_ context.Context, u *repo.User) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.emailTaken(u.Email, "") {
		return repo.ErrEmailTaken
	}
	cp := *u
	f.users[u.UID] = &cp
	return nil
}

func (f *fakeUserStore) CreateWithAutoUID(ctx context.Context, u *repo.User) error {
	f.mu.Lock()
	u.UID = strconv.Itoa(f.next)
	f.next++
	f.mu.Unlock()
	return f.Create(ctx, u)
}

func (f *fakeUserStore) GetByUID(_ context.Context, uid string) (*repo.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if u, ok := f.users[uid]; ok {
		cp := *u
		return &cp, nil
	}
	return nil, nil
}

func (f *fakeUserStore) GetByEmail(_ context.Context, email string) (*repo.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, u := range f.users {
		if u.Email == strings.ToLower(email) {
			cp := *u
			return &cp, nil
		}
	}
	return nil, nil
}

func (f *fakeUserStore) GetUsers(ctx context.Context, uids []string) ([]*repo.User, error) {
	var out []*repo.User
	for _, id := range uids {
		if u, _ := f.GetByUID(ctx, id); u != nil {
			out = append(out, u)
		}
	}
	return out, nil
}

func (f *fakeUserStore) UpdateProfile(_ context.Context, uid string, nickname, signature *string, avatar *int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	u, ok := f.users[uid]
	if !ok {
		return repo.ErrUserNotFound
	}
	if nickname != nil {
		u.Nickname = *nickname
	}
	if signature != nil {
		u.Signature = *signature
	}
	if avatar != nil {
		u.AvatarFileID = *avatar
	}
	return nil
}

func (f *fakeUserStore) SetEmail(_ context.Context, uid, email string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	u, ok := f.users[uid]
	if !ok {
		return repo.ErrUserNotFound
	}
	if f.emailTaken(email, uid) {
		return repo.ErrEmailTaken
	}
	u.Email = email
	return nil
}

func (f *fakeUserStore) SearchUsers(_ context.Context, q string, limit int) ([]*repo.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []*repo.User
	for _, u := range f.users {
		if u.UID == q || u.Email == strings.ToLower(q) || strings.Contains(u.Nickname, q) {
			cp := *u
			out = append(out, &cp)
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out, nil
}

func (f *fakeUserStore) UpdatePassword(_ context.Context, uid, hash string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	u, ok := f.users[uid]
	if !ok {
		return repo.ErrUserNotFound
	}
	u.PasswordHash = hash
	u.MustChangePassword = false
	return nil
}

func (f *fakeUserStore) ListUsers(context.Context, int, int) ([]*repo.User, int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := make([]*repo.User, 0, len(f.users))
	for _, u := range f.users {
		out = append(out, u)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].UID < out[j].UID })
	return out, len(out), nil
}
func (f *fakeUserStore) DeleteUser(context.Context, string) error { return nil }
func (f *fakeUserStore) UpdateUserRole(_ context.Context, uid, role string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if u, ok := f.users[uid]; ok {
		u.Role = role
	}
	return nil
}
func (f *fakeUserStore) UpdateUserDisabled(_ context.Context, uid string, disabled bool) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	u, ok := f.users[uid]
	if !ok {
		return fmt.Errorf("user %s not found", uid)
	}
	u.IsDisabled = disabled
	return nil
}
func (f *fakeUserStore) CountUsers(context.Context) (int, error) { return len(f.users), nil }

// captureMailer 记录最后一封邮件，可配置为发送失败。
type captureMailer struct {
	mu   sync.Mutex
	to   string
	text string
	fail bool
}

var sixDigits = regexp.MustCompile(`\b\d{6}\b`)

func (m *captureMailer) Send(_ context.Context, to, _, text, _ string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.fail {
		return errors.New("smtp down")
	}
	m.to, m.text = to, text
	return nil
}

func (m *captureMailer) code(t *testing.T) string {
	t.Helper()
	m.mu.Lock()
	defer m.mu.Unlock()
	c := sixDigits.FindString(m.text)
	if c == "" {
		t.Fatalf("no code in mail %q", m.text)
	}
	return c
}

type accountEnv struct {
	srv    *Server
	users  *fakeUserStore
	mail   *captureMailer
	codes  *MemoryCodeStore
	files  *InMemoryFileStore
	objs   *InMemoryObjectStore
	jwt    *jwt.Manager
	router *Router
}

func newAccountEnv(t *testing.T, devMode bool) *accountEnv {
	t.Helper()
	hub := NewHub(100)
	sg, _ := snowflake.New(1)
	router := NewRouter(hub, hub, sg, nil, DefaultRouterConfig())
	users := newFakeUserStore()
	router.SetUserStore(users)
	mgr := jwt.New("test-secret", time.Hour)
	objs := NewInMemoryObjectStore()
	srv := NewServer(hub, router, mgr, users, nil, nil, configs.AuthConfig{DevMode: devMode},
		time.Second, 3, configs.GatewayConnConfig{}, nil, sg, objs, 10<<20, nil)
	files := NewInMemoryFileStore()
	srv.SetFileStore(files)
	mail := &captureMailer{}
	codes := NewMemoryCodeStore()
	srv.SetAccountServices(mail, codes)
	return &accountEnv{srv: srv, users: users, mail: mail, codes: codes, files: files, objs: objs, jwt: mgr, router: router}
}

func (e *accountEnv) do(t *testing.T, h http.HandlerFunc, method string, form url.Values) *httptest.ResponseRecorder {
	t.Helper()
	var req *http.Request
	if method == http.MethodGet {
		req = httptest.NewRequest(method, "/?"+form.Encode(), nil)
	} else {
		req = httptest.NewRequest(method, "/", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	req.RemoteAddr = "192.0.2.1:5555"
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec
}

func (e *accountEnv) auth(t *testing.T, uid string, form url.Values) url.Values {
	t.Helper()
	if form == nil {
		form = url.Values{}
	}
	form.Set("uid", uid)
	form.Set("token", jwtToken(t, e.jwt, uid, uid))
	return form
}

func decode(t *testing.T, rec *httptest.ResponseRecorder) map[string]interface{} {
	t.Helper()
	var m map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return m
}

func (e *accountEnv) register(t *testing.T, email, password string) map[string]interface{} {
	t.Helper()
	rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {email}, "purpose": {"register"}})
	if rec.Code != http.StatusOK {
		t.Fatalf("email-code: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, e.srv.HandleRegister, http.MethodPost, url.Values{
		"email": {email}, "code": {e.mail.code(t)}, "password": {password},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("register: %d %s", rec.Code, rec.Body.String())
	}
	return decode(t, rec)
}

func tinyPNG(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 4, 4))); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestRegisterLoginWithEmailCode(t *testing.T) {
	e := newAccountEnv(t, false)
	resp := e.register(t, "Alice@Example.com", "secret1")
	if resp["uid"] != "100000" || resp["nickname"] != "alice" || resp["email"] != "alice@example.com" {
		t.Fatalf("register response = %v", resp)
	}
	if resp["token"] == "" {
		t.Fatal("missing token")
	}

	for _, account := range []string{"alice@example.com", "ALICE@example.com", "100000"} {
		rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {account}, "password": {"secret1"}})
		if rec.Code != http.StatusOK || decode(t, rec)["uid"] != "100000" {
			t.Fatalf("login %s: %d %s", account, rec.Code, rec.Body.String())
		}
	}
	rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {"100000"}, "password": {"wrong!!"}})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("wrong password: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {"100000"}})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing password outside dev mode: %d", rec.Code)
	}

	// 已注册邮箱不能再次申请注册验证码。
	rec = e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"alice@example.com"}, "purpose": {"register"}})
	if rec.Code != http.StatusConflict {
		t.Fatalf("duplicate email code: %d", rec.Code)
	}
	if second := e.register(t, "bob@example.com", "secret2"); second["uid"] != "100001" {
		t.Fatalf("second uid = %v", second["uid"])
	}
}

func TestRegisterRejectsBadInput(t *testing.T) {
	e := newAccountEnv(t, false)
	rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"not-an-email"}})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid email: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"a@b.co"}, "purpose": {"x"}})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid purpose: %d", rec.Code)
	}
	e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"a@b.co"}})
	code := e.mail.code(t)
	rec = e.do(t, e.srv.HandleRegister, http.MethodPost, url.Values{"email": {"a@b.co"}, "code": {code}, "password": {"123"}})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("short password: %d", rec.Code)
	}
	// 密码校验失败不应消耗验证码。
	rec = e.do(t, e.srv.HandleRegister, http.MethodPost, url.Values{"email": {"a@b.co"}, "code": {code}, "password": {"123456"}})
	if rec.Code != http.StatusOK {
		t.Fatalf("register after bad password: %d %s", rec.Code, rec.Body.String())
	}
	// 验证码只能用一次。
	rec = e.do(t, e.srv.HandleRegister, http.MethodPost, url.Values{"email": {"a@b.co"}, "code": {code}, "password": {"123456"}})
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("reused code: %d", rec.Code)
	}
}

func TestEmailCodeCooldownAndSendFailure(t *testing.T) {
	e := newAccountEnv(t, false)
	form := url.Values{"email": {"c@example.com"}}
	if rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, form); rec.Code != http.StatusOK {
		t.Fatalf("first: %d", rec.Code)
	}
	rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, form)
	if rec.Code != http.StatusTooManyRequests || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("cooldown: %d %v", rec.Code, rec.Header())
	}

	e.mail.fail = true
	form = url.Values{"email": {"d@example.com"}}
	if rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, form); rec.Code != http.StatusBadGateway {
		t.Fatalf("send failure: %d", rec.Code)
	}
	// 发送失败会撤销冷却，可立即重试。
	e.mail.fail = false
	if rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, form); rec.Code != http.StatusOK {
		t.Fatalf("retry after failure: %d %s", rec.Code, rec.Body.String())
	}
}

func TestPasswordReset(t *testing.T) {
	e := newAccountEnv(t, false)
	e.register(t, "r@example.com", "oldpass")
	e.codes.Revoke(context.Background(), PurposeRegister, "r@example.com")

	rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"nobody@example.com"}, "purpose": {"reset_password"}})
	if rec.Code != http.StatusNotFound {
		t.Fatalf("reset unknown email: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"r@example.com"}, "purpose": {"reset_password"}})
	if rec.Code != http.StatusOK {
		t.Fatalf("reset code: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, e.srv.HandlePasswordReset, http.MethodPost, url.Values{
		"email": {"r@example.com"}, "code": {e.mail.code(t)}, "new_password": {"newpass"},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("reset: %d %s", rec.Code, rec.Body.String())
	}
	if rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {"r@example.com"}, "password": {"newpass"}}); rec.Code != http.StatusOK {
		t.Fatalf("login with new password: %d", rec.Code)
	}
	if rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"account": {"r@example.com"}, "password": {"oldpass"}}); rec.Code != http.StatusUnauthorized {
		t.Fatalf("old password still works: %d", rec.Code)
	}
}

func TestBindEmailForLegacyUser(t *testing.T) {
	e := newAccountEnv(t, false)
	e.users.Create(context.Background(), &repo.User{UID: "legacy", Nickname: "Old Timer"})
	e.users.Create(context.Background(), &repo.User{UID: "other", Nickname: "Other", Email: "taken@example.com"})

	rec := e.do(t, e.srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"l@example.com"}, "purpose": {"bind_email"}})
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("bind code without auth: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleEmailCode, http.MethodPost, e.auth(t, "legacy", url.Values{"email": {"taken@example.com"}, "purpose": {"bind_email"}}))
	if rec.Code != http.StatusConflict {
		t.Fatalf("bind taken email: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleEmailCode, http.MethodPost, e.auth(t, "legacy", url.Values{"email": {"l@example.com"}, "purpose": {"bind_email"}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("bind code: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.do(t, e.srv.HandleBindEmail, http.MethodPost, e.auth(t, "legacy", url.Values{"email": {"l@example.com"}, "code": {e.mail.code(t)}}))
	if rec.Code != http.StatusOK || decode(t, rec)["email"] != "l@example.com" {
		t.Fatalf("bind: %d %s", rec.Code, rec.Body.String())
	}
}

func TestProfileUpdateAndAvatar(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100000", Nickname: "a", Email: "a@example.com"})
	e.users.Create(ctx, &repo.User{UID: "100001", Nickname: "b"})

	png := tinyPNG(t)
	e.objs.Put(ctx, "501", png, "image/png")
	e.files.CreateFile(ctx, &repo.File{FileID: 501, OwnerUID: "100000", Mime: "image/png", Size: int64(len(png)), Status: repo.FileStatusReady})
	e.files.CreateFile(ctx, &repo.File{FileID: 502, OwnerUID: "100001", Mime: "image/png", Status: repo.FileStatusReady})
	e.files.CreateFile(ctx, &repo.File{FileID: 503, OwnerUID: "100000", Mime: "application/pdf", Status: repo.FileStatusReady})

	for id, want := range map[string]int{"502": http.StatusBadRequest, "503": http.StatusBadRequest, "x": http.StatusBadRequest} {
		rec := e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"avatar_file_id": {id}}))
		if rec.Code != want {
			t.Fatalf("avatar %s: %d %s", id, rec.Code, rec.Body.String())
		}
	}
	rec := e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"nickname": {strings.Repeat("长", 33)}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("long nickname: %d", rec.Code)
	}

	// 头像设置前不可公开访问。
	avatarRec := httptest.NewRecorder()
	e.srv.HandleAvatar(avatarRec, httptest.NewRequest(http.MethodGet, "/avatar/501", nil))
	if avatarRec.Code != http.StatusNotFound {
		t.Fatalf("avatar before claim: %d", avatarRec.Code)
	}

	rec = e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"nickname": {" 小明 "}, "avatar_file_id": {"501"}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("update: %d %s", rec.Code, rec.Body.String())
	}
	p := decode(t, rec)
	if p["nickname"] != "小明" || p["avatar_url"] != "/avatar/501" || p["email"] != "a@example.com" {
		t.Fatalf("profile = %v", p)
	}

	avatarRec = httptest.NewRecorder()
	e.srv.HandleAvatar(avatarRec, httptest.NewRequest(http.MethodGet, "/avatar/501", nil))
	if avatarRec.Code != http.StatusOK || avatarRec.Body.Len() != len(png) ||
		!strings.Contains(avatarRec.Header().Get("Cache-Control"), "immutable") {
		t.Fatalf("avatar: %d %v", avatarRec.Code, avatarRec.Header())
	}
	req := httptest.NewRequest(http.MethodGet, "/avatar/501", nil)
	req.Header.Set("If-None-Match", avatarRec.Header().Get("ETag"))
	avatarRec = httptest.NewRecorder()
	e.srv.HandleAvatar(avatarRec, req)
	if avatarRec.Code != http.StatusNotModified {
		t.Fatalf("avatar etag: %d", avatarRec.Code)
	}

	// 他人只能看到公开资料（无邮箱）。
	rec = e.do(t, e.srv.HandleProfile, http.MethodGet, e.auth(t, "100001", url.Values{"target": {"100000"}}))
	p = decode(t, rec)
	if p["nickname"] != "小明" || p["email"] != nil {
		t.Fatalf("public profile = %v", p)
	}
	rec = e.do(t, e.srv.HandleProfiles, http.MethodGet, e.auth(t, "100001", url.Values{"uids": {"100000,100001,missing"}}))
	var batch struct{ Users []map[string]interface{} }
	json.Unmarshal(rec.Body.Bytes(), &batch)
	if len(batch.Users) != 2 {
		t.Fatalf("batch profiles = %s", rec.Body.String())
	}
}

func TestUserSearch(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100000", Nickname: "张三丰", Email: "zs@example.com"})
	e.users.Create(ctx, &repo.User{UID: "100001", Nickname: "李四"})

	cases := map[string]string{"三丰": "100000", "ZS@example.com": "100000", "100001": "100001"}
	for q, want := range cases {
		rec := e.do(t, e.srv.HandleUserSearch, http.MethodGet, e.auth(t, "100001", url.Values{"q": {q}}))
		var resp struct{ Users []map[string]interface{} }
		json.Unmarshal(rec.Body.Bytes(), &resp)
		if len(resp.Users) != 1 || resp.Users[0]["uid"] != want || resp.Users[0]["email"] != nil {
			t.Fatalf("search %q = %s", q, rec.Body.String())
		}
	}
}

func TestDevLoginWithoutPassword(t *testing.T) {
	e := newAccountEnv(t, true)
	rec := e.do(t, e.srv.HandleLogin, http.MethodPost, url.Values{"uid": {"dev1"}, "username": {"Dev One"}})
	p := decode(t, rec)
	if rec.Code != http.StatusOK || p["uid"] != "dev1" || p["nickname"] != "Dev One" || p["username"] != "Dev One" {
		t.Fatalf("dev login: %d %v", rec.Code, p)
	}
	claims, err := e.jwt.Validate(p["token"].(string))
	if err != nil || claims.Nickname != "Dev One" {
		t.Fatalf("claims = %+v, %v", claims, err)
	}
}

func TestMemoryCodeStoreRules(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryCodeStore()
	now := time.Unix(1_700_000_000, 0)
	s.now = func() time.Time { return now }

	if err := s.Issue(ctx, PurposeRegister, "x@example.com", "ip", "111111"); err != nil {
		t.Fatal(err)
	}
	// 不同用途的验证码互不通用。
	if err := s.Verify(ctx, PurposeResetPassword, "x@example.com", "111111"); !errors.Is(err, ErrCodeInvalid) {
		t.Fatalf("cross purpose: %v", err)
	}
	for i := 0; i < codeMaxAttempts; i++ {
		s.Verify(ctx, PurposeRegister, "x@example.com", "000000")
	}
	if err := s.Verify(ctx, PurposeRegister, "x@example.com", "111111"); !errors.Is(err, ErrCodeInvalid) {
		t.Fatalf("code should be burned after %d failures: %v", codeMaxAttempts, err)
	}

	now = now.Add(codeCooldown)
	s.Issue(ctx, PurposeRegister, "x@example.com", "ip", "222222")
	now = now.Add(codeTTL)
	if err := s.Verify(ctx, PurposeRegister, "x@example.com", "222222"); !errors.Is(err, ErrCodeInvalid) {
		t.Fatalf("expired code accepted: %v", err)
	}

	// 每日配额：已发 2 次，再发 8 次达到上限，第 11 次被拒。
	for i := 0; i < codeDailyLimit-2; i++ {
		now = now.Add(codeCooldown)
		if err := s.Issue(ctx, PurposeRegister, "x@example.com", "ip"+strconv.Itoa(i), "333333"); err != nil {
			t.Fatalf("issue %d: %v", i, err)
		}
	}
	now = now.Add(codeCooldown)
	if err := s.Issue(ctx, PurposeRegister, "x@example.com", "ipz", "444444"); !errors.Is(err, ErrCodeQuota) {
		t.Fatalf("daily quota: %v", err)
	}
}

func TestProfileSignature(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100000", Nickname: "a"})

	rec := e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"signature": {strings.Repeat("签", 61)}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("long signature: %d", rec.Code)
	}
	rec = e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"signature": {"a\nb"}}))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("newline in signature: %d", rec.Code)
	}

	rec = e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"signature": {" 今天也要开心 "}}))
	if rec.Code != http.StatusOK {
		t.Fatalf("set signature: %d %s", rec.Code, rec.Body.String())
	}
	if p := decode(t, rec); p["signature"] != "今天也要开心" || p["nickname"] != "a" {
		t.Fatalf("profile = %v", p)
	}

	// 空串清除
	rec = e.do(t, e.srv.HandleProfile, http.MethodPost, e.auth(t, "100000", url.Values{"signature": {""}}))
	if p := decode(t, rec); rec.Code != http.StatusOK || p["signature"] != "" {
		t.Fatalf("clear signature: %d %v", rec.Code, p)
	}
}

func TestHandleFriendRemark(t *testing.T) {
	e := newAccountEnv(t, false)
	store := &remarkFriendStore{testFriendStore: newTestFriendStore(), remarks: map[[2]string]string{}}
	e.router.SetFriendStore(store)
	post := func(form url.Values) *httptest.ResponseRecorder {
		return e.do(t, e.srv.HandleFriendRemark, http.MethodPost, e.auth(t, "100000", form))
	}

	if rec := post(url.Values{"friend_uid": {"bob"}, "remark": {" 老同学 "}}); rec.Code != http.StatusOK {
		t.Fatalf("set remark: %d %s", rec.Code, rec.Body.String())
	}
	if got := store.remarks[[2]string{"100000", "bob"}]; got != "老同学" {
		t.Fatalf("stored remark = %q", got)
	}
	if rec := post(url.Values{"friend_uid": {"carol"}, "remark": {"x"}}); rec.Code != http.StatusNotFound {
		t.Fatalf("non-friend: %d", rec.Code)
	}
	if rec := post(url.Values{"friend_uid": {"bob"}, "remark": {strings.Repeat("备", 33)}}); rec.Code != http.StatusBadRequest {
		t.Fatalf("long remark: %d", rec.Code)
	}
	if rec := post(url.Values{"remark": {"x"}}); rec.Code != http.StatusBadRequest {
		t.Fatalf("missing friend_uid: %d", rec.Code)
	}
}

func TestHandleGroupAliasAndMembers(t *testing.T) {
	e := newAccountEnv(t, false)
	sg, _ := snowflake.New(2)
	gs := NewInMemoryGroupStore(sg)
	e.srv.groupStore = gs
	e.router.SetGroupStore(gs)
	g, err := gs.Create(context.Background(), "Dev", "100000", []string{"100001"})
	if err != nil {
		t.Fatal(err)
	}
	alias := func(uid, value string) *httptest.ResponseRecorder {
		form := url.Values{"group_id": {g.ID}, "alias": {value}}
		return e.do(t, e.srv.HandleGroupAlias, http.MethodPost, e.auth(t, uid, form))
	}

	if rec := alias("100001", " 小王 "); rec.Code != http.StatusOK {
		t.Fatalf("set alias: %d %s", rec.Code, rec.Body.String())
	}
	if rec := alias("100009", "x"); rec.Code != http.StatusForbidden {
		t.Fatalf("non-member: %d", rec.Code)
	}
	if rec := alias("100001", strings.Repeat("名", 33)); rec.Code != http.StatusBadRequest {
		t.Fatalf("long alias: %d", rec.Code)
	}

	rec := e.do(t, e.srv.HandleGroupMembers, http.MethodGet, e.auth(t, "100000", url.Values{"group_id": {g.ID}}))
	var body struct {
		Members []string          `json:"members"`
		Aliases map[string]string `json:"aliases"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("members body %q: %v", rec.Body.String(), err)
	}
	if len(body.Members) != 2 || body.Aliases["100001"] != "小王" || len(body.Aliases) != 1 {
		t.Fatalf("members = %+v", body)
	}

	if rec := alias("100001", ""); rec.Code != http.StatusOK {
		t.Fatalf("clear alias: %d", rec.Code)
	}
	if aliases, _ := gs.MemberAliases(context.Background(), g.ID); len(aliases) != 0 {
		t.Fatalf("aliases after clear = %v", aliases)
	}
}

func TestHandleGroupRenameValidation(t *testing.T) {
	e := newAccountEnv(t, false)
	sg, _ := snowflake.New(2)
	gs := NewInMemoryGroupStore(sg)
	e.srv.groupStore = gs
	e.router.SetGroupStore(gs)
	g, _ := gs.Create(context.Background(), "Dev", "100000", []string{"100001"})
	rename := func(uid, name string) *httptest.ResponseRecorder {
		form := url.Values{"group_id": {g.ID}, "name": {name}}
		return e.do(t, e.srv.HandleGroupRename, http.MethodPost, e.auth(t, uid, form))
	}

	if rec := rename("100001", "Ops"); rec.Code != http.StatusForbidden {
		t.Fatalf("member rename: %d", rec.Code)
	}
	if rec := rename("100000", "   "); rec.Code != http.StatusBadRequest {
		t.Fatalf("blank name: %d", rec.Code)
	}
	if rec := rename("100000", strings.Repeat("群", 33)); rec.Code != http.StatusBadRequest {
		t.Fatalf("long name: %d", rec.Code)
	}
	if rec := rename("100000", " Ops "); rec.Code != http.StatusOK {
		t.Fatalf("owner rename: %d %s", rec.Code, rec.Body.String())
	}
	if got, _ := gs.Get(context.Background(), g.ID); got.Name != "Ops" {
		t.Fatalf("name = %q", got.Name)
	}
}

func TestAdminUsersIncludeAvatarURL(t *testing.T) {
	e := newAccountEnv(t, false)
	ctx := context.Background()
	e.users.Create(ctx, &repo.User{UID: "100001", Nickname: "Bob", AvatarFileID: 7})
	e.users.Create(ctx, &repo.User{UID: "100002", Nickname: "Carol"})

	rec := e.do(t, e.srv.HandleAdminUsers, http.MethodGet, adminQuery(t, e.jwt, "admin", url.Values{}))
	var body struct {
		Users []map[string]interface{} `json:"users"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("body %q: %v", rec.Body.String(), err)
	}
	got := map[string]interface{}{}
	for _, u := range body.Users {
		got[u["uid"].(string)] = u["avatar_url"]
	}
	if got["100001"] != "/avatar/7" || got["100002"] != "" {
		t.Fatalf("avatar_url by uid = %v", got)
	}
}
