package gateway

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/im/internal/repo"
)

type fakeSettingsStore struct {
	mu     sync.Mutex
	values map[string]string
	fail   bool
}

func (f *fakeSettingsStore) GetSettings(context.Context) (map[string]string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail {
		return nil, errors.New("db down")
	}
	out := make(map[string]string, len(f.values))
	for k, v := range f.values {
		out[k] = v
	}
	return out, nil
}

func (f *fakeSettingsStore) PutSettings(_ context.Context, values map[string]string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail {
		return errors.New("db down")
	}
	if f.values == nil {
		f.values = map[string]string{}
	}
	for k, v := range values {
		f.values[k] = v
	}
	return nil
}

func TestSettingsManagerDefaultsRefreshUpdate(t *testing.T) {
	ctx := context.Background()
	store := &fakeSettingsStore{}
	var applied []DevicePolicy
	m := NewSettingsManager(SystemSettings{MultiDevice: DevicePolicySingle, RegistrationOpen: true}, store,
		func(s SystemSettings) { applied = append(applied, s.MultiDevice) })
	if len(applied) != 1 || applied[0] != DevicePolicySingle {
		t.Fatalf("defaults not applied: %v", applied)
	}

	// 其他实例写入的设置在 Refresh 后生效；无法解析的值回落到默认。
	store.values = map[string]string{settingMultiDevice: "unlimited", settingRegistrationOpen: "false"}
	if err := m.Refresh(ctx); err != nil {
		t.Fatal(err)
	}
	if got := m.Get(); got.MultiDevice != DevicePolicyUnlimited || got.RegistrationOpen {
		t.Fatalf("after refresh: %+v", got)
	}
	m.Refresh(ctx)
	if len(applied) != 2 {
		t.Fatalf("unchanged refresh must not re-apply: %v", applied)
	}

	p := DevicePolicyPerPlatform
	open := true
	got, err := m.Update(ctx, &p, &open)
	if err != nil || got.MultiDevice != DevicePolicyPerPlatform || !got.RegistrationOpen {
		t.Fatalf("update: %+v %v", got, err)
	}
	if store.values[settingMultiDevice] != "per_platform" || store.values[settingRegistrationOpen] != "true" {
		t.Fatalf("stored = %v", store.values)
	}
	if applied[len(applied)-1] != DevicePolicyPerPlatform {
		t.Fatalf("update not applied: %v", applied)
	}

	// 只改一项不影响另一项；写库失败时保持原值。
	closed := false
	if got, _ := m.Update(ctx, nil, &closed); got.MultiDevice != DevicePolicyPerPlatform || got.RegistrationOpen {
		t.Fatalf("partial update: %+v", got)
	}
	store.fail = true
	if _, err := m.Update(ctx, &p, &open); err == nil {
		t.Fatal("expected store error")
	}
	if m.Refresh(ctx) == nil || m.Get().RegistrationOpen {
		t.Fatalf("failed refresh must keep current: %+v", m.Get())
	}
}

func TestSettingsManagerWithoutStore(t *testing.T) {
	m := NewSettingsManager(SystemSettings{MultiDevice: DevicePolicySingle, RegistrationOpen: true}, nil, nil)
	m.Start(context.Background(), time.Millisecond)
	closed := false
	if got, err := m.Update(context.Background(), nil, &closed); err != nil || got.RegistrationOpen {
		t.Fatalf("in-memory update: %+v %v", got, err)
	}
}

func TestAdminSettingsAndRegistrationGate(t *testing.T) {
	e := newAccountEnv(t, false)
	srv, mgr := e.srv, e.jwt
	settings := func(role string, form url.Values) (int, map[string]interface{}) {
		method := http.MethodGet
		if form != nil {
			method = http.MethodPost
		} else {
			form = url.Values{}
		}
		rec := e.do(t, srv.HandleAdminSettings, method, adminQuery(t, mgr, role, form))
		if rec.Code != http.StatusOK {
			return rec.Code, nil
		}
		return rec.Code, decode(t, rec)
	}

	if code, _ := settings("admin", nil); code != http.StatusServiceUnavailable {
		t.Fatalf("without manager: %d", code)
	}
	if got := decode(t, e.do(t, srv.HandleAuthConfig, http.MethodGet, url.Values{})); got["registration_open"] != true {
		t.Fatalf("auth config without manager = %v", got)
	}

	hub := srv.clients.(*Hub)
	srv.SetSettings(NewSettingsManager(SystemSettings{MultiDevice: DevicePolicySingle, RegistrationOpen: true}, &fakeSettingsStore{},
		func(s SystemSettings) { hub.SetDevicePolicy(s.MultiDevice) }))

	for _, c := range []struct {
		role string
		form url.Values
		want int
	}{
		{"user", nil, http.StatusForbidden},
		{"admin", url.Values{"multi_device": {"two"}}, http.StatusBadRequest},
		{"admin", url.Values{"registration_open": {"maybe"}}, http.StatusBadRequest},
	} {
		if code, _ := settings(c.role, c.form); code != c.want {
			t.Fatalf("%+v: got %d", c, code)
		}
	}
	if code, got := settings("admin", nil); code != http.StatusOK || got["multi_device"] != "single" || got["registration_open"] != true {
		t.Fatalf("get: %d %v", code, got)
	}

	code, got := settings("admin", url.Values{"multi_device": {"unlimited"}, "registration_open": {"false"}})
	if code != http.StatusOK || got["multi_device"] != "unlimited" || got["registration_open"] != false {
		t.Fatalf("post: %d %v", code, got)
	}
	hub.mu.RLock()
	policy := hub.policy
	hub.mu.RUnlock()
	if policy != DevicePolicyUnlimited {
		t.Fatalf("hub policy = %s", policy)
	}

	if got := decode(t, e.do(t, srv.HandleAuthConfig, http.MethodGet, url.Values{})); got["registration_open"] != false {
		t.Fatalf("auth config = %v", got)
	}
	if rec := e.do(t, srv.HandleEmailCode, http.MethodPost, url.Values{"email": {"new@example.com"}}); rec.Code != http.StatusForbidden {
		t.Fatalf("register code while closed: %d", rec.Code)
	}
	if rec := e.do(t, srv.HandleRegister, http.MethodPost,
		url.Values{"email": {"new@example.com"}, "password": {"secret1"}, "code": {"123456"}}); rec.Code != http.StatusForbidden {
		t.Fatalf("register while closed: %d", rec.Code)
	}

	// 关闭注册不影响找回密码。
	e.users.Create(context.Background(), &repo.User{UID: "100001", Email: "old@example.com"})
	if rec := e.do(t, srv.HandleEmailCode, http.MethodPost,
		url.Values{"email": {"old@example.com"}, "purpose": {string(PurposeResetPassword)}}); rec.Code != http.StatusOK {
		t.Fatalf("reset code while closed: %d %s", rec.Code, rec.Body)
	}
}
