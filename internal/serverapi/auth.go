package serverapi

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// 签名请求头。签名 = hex(HMAC-SHA256(secret, METHOD "\n" PATH "\n" TIMESTAMP "\n" NONCE "\n" BODY))。
const (
	HeaderAppID     = "X-App-Id"
	HeaderTimestamp = "X-Timestamp" // Unix 秒
	HeaderNonce     = "X-Nonce"
	HeaderSignature = "X-Signature"
)

// SignatureWindow 是允许的时间戳偏差，同时也是 nonce 的去重时长。
const SignatureWindow = 5 * time.Minute

const maxBodyBytes = 1 << 20

// App 是一个接入方的运行时凭证与回调配置。
type App struct {
	ID            string
	Secret        string
	WebhookURL    string
	WebhookSecret string
	WebhookEvents []string
	BeforeSendURL string
	BeforeSendTTL time.Duration
	Disabled      bool
}

// SystemUID 返回该接入方的系统发送方 ID。
func (a *App) SystemUID() string { return "sys:" + a.ID }

// Sign 计算请求签名，客户端 SDK 与服务端共用。
func Sign(secret, method, path, timestamp, nonce string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	io.WriteString(mac, method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n")
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// NonceStore 记录见过的 nonce，Seen 首次返回 false。
type NonceStore interface {
	Seen(ctx context.Context, appID, nonce string) (bool, error)
}

// RedisNonceStore 用 SET NX EX 去重，多网关共享。
type RedisNonceStore struct{ RDB *redis.Client }

// Seen 实现 NonceStore。
func (s RedisNonceStore) Seen(ctx context.Context, appID, nonce string) (bool, error) {
	ok, err := s.RDB.SetNX(ctx, "im:serverapi:nonce:"+appID+":"+nonce, 1, 2*SignatureWindow).Result()
	if err != nil {
		return false, err
	}
	return !ok, nil
}

// MemoryNonceStore 是单实例用的内存实现。
type MemoryNonceStore struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

// Seen 实现 NonceStore。
func (s *MemoryNonceStore) Seen(_ context.Context, appID, nonce string) (bool, error) {
	now := time.Now()
	key := appID + ":" + nonce
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.seen == nil {
		s.seen = make(map[string]time.Time)
	}
	if len(s.seen) > 100000 {
		for k, exp := range s.seen {
			if now.After(exp) {
				delete(s.seen, k)
			}
		}
	}
	if exp, ok := s.seen[key]; ok && now.Before(exp) {
		return true, nil
	}
	s.seen[key] = now.Add(2 * SignatureWindow)
	return false, nil
}

var (
	errMissingAuth = errors.New("missing X-App-Id / X-Timestamp / X-Nonce / X-Signature")
	errUnknownApp  = errors.New("unknown or disabled app")
	errTimestamp   = errors.New("timestamp outside the allowed window")
	errSignature   = errors.New("bad signature")
	errReplay      = errors.New("nonce already used")
)

// authenticate 校验签名并返回接入方与请求体（已读出，后续从返回值解析）。
func (a *API) authenticate(r *http.Request) (*App, []byte, int, error) {
	appID := r.Header.Get(HeaderAppID)
	ts := r.Header.Get(HeaderTimestamp)
	nonce := r.Header.Get(HeaderNonce)
	sig := r.Header.Get(HeaderSignature)
	if appID == "" || ts == "" || nonce == "" || sig == "" || len(nonce) > 64 {
		return nil, nil, http.StatusUnauthorized, errMissingAuth
	}
	app := a.apps.Get(appID)
	if app == nil || app.Disabled {
		return nil, nil, http.StatusUnauthorized, errUnknownApp
	}
	sec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return nil, nil, http.StatusUnauthorized, errTimestamp
	}
	if d := a.now().Sub(time.Unix(sec, 0)); d > SignatureWindow || d < -SignatureWindow {
		return nil, nil, http.StatusUnauthorized, errTimestamp
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes+1))
	if err != nil {
		return nil, nil, http.StatusBadRequest, err
	}
	if len(body) > maxBodyBytes {
		return nil, nil, http.StatusRequestEntityTooLarge, errors.New("body too large")
	}
	want := Sign(app.Secret, r.Method, r.URL.Path, ts, nonce, body)
	if !hmac.Equal([]byte(want), []byte(sig)) {
		return nil, nil, http.StatusUnauthorized, errSignature
	}
	seen, err := a.nonces.Seen(r.Context(), appID, nonce)
	if err != nil {
		return nil, nil, http.StatusServiceUnavailable, err
	}
	if seen {
		return nil, nil, http.StatusUnauthorized, errReplay
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	return app, body, 0, nil
}
