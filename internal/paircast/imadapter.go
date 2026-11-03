package paircast

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/im/internal/paircast/watchroom"
	"github.com/im/pkg/imserver"
)

// tokenCacheTTL 是 IM 令牌校验结果的缓存时长：吊销 / 禁用最多晚这么久在 Paircast 生效。
const tokenCacheTTL = 60 * time.Second

// TokenVerifier 校验 IM 令牌（imserver.Client 实现）。
type TokenVerifier interface {
	VerifyToken(ctx context.Context, token string) (*imserver.TokenInfo, error)
}

type tokenEntry struct {
	info    *imserver.TokenInfo
	expires time.Time
}

// IMAuth 用 IM 令牌认证请求：令牌取自 token 参数（查询或表单）或 Authorization: Bearer；
// 带 uid 参数时必须与令牌一致（与网关接口相同的约定）。
type IMAuth struct {
	verifier TokenVerifier
	now      func() time.Time

	mu    sync.Mutex
	cache map[string]tokenEntry
}

// NewIMAuth 创建认证器。
func NewIMAuth(v TokenVerifier) *IMAuth {
	return &IMAuth{verifier: v, now: time.Now, cache: map[string]tokenEntry{}}
}

func (a *IMAuth) verify(ctx context.Context, token string) (*imserver.TokenInfo, error) {
	now := a.now()
	a.mu.Lock()
	if e, ok := a.cache[token]; ok && now.Before(e.expires) {
		a.mu.Unlock()
		return e.info, nil
	}
	a.mu.Unlock()
	info, err := a.verifier.VerifyToken(ctx, token)
	if err != nil {
		return nil, err
	}
	a.mu.Lock()
	if len(a.cache) > 50000 {
		for k, e := range a.cache {
			if now.After(e.expires) {
				delete(a.cache, k)
			}
		}
	}
	a.cache[token] = tokenEntry{info: info, expires: now.Add(tokenCacheTTL)}
	a.mu.Unlock()
	return info, nil
}

// Authenticate 实现 Authenticator。
func (a *IMAuth) Authenticate(w http.ResponseWriter, r *http.Request) (string, bool) {
	token := r.FormValue("token")
	if token == "" {
		token = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	}
	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return "", false
	}
	info, err := a.verify(r.Context(), token)
	if err != nil {
		log.Printf("[paircast] verify token: %v", err)
		http.Error(w, "auth service unavailable", http.StatusServiceUnavailable)
		return "", false
	}
	if !info.Valid {
		reason := info.Reason
		if reason == "" {
			reason = "invalid token"
		}
		http.Error(w, reason, http.StatusUnauthorized)
		return "", false
	}
	if uid := r.FormValue("uid"); uid != "" && uid != info.UID {
		http.Error(w, "uid does not match token", http.StatusForbidden)
		return "", false
	}
	return info.UID, true
}

// IMGroups 用服务端 API 实现 watchroom.Groups。
type IMGroups struct{ C *imserver.Client }

// Create 实现 watchroom.Groups。
func (g IMGroups) Create(ctx context.Context, name, ownerUID string) (string, error) {
	grp, err := g.C.CreateGroup(ctx, name, imserver.UID(ownerUID), nil)
	if err != nil {
		return "", err
	}
	return grp.ID, nil
}

// Join 实现 watchroom.Groups。
func (g IMGroups) Join(ctx context.Context, groupID, uid string) error {
	_, err := g.C.AddGroupMembers(ctx, groupID, []imserver.UserRef{imserver.UID(uid)})
	if imserver.IsNotFound(err) {
		return watchroom.ErrEnded
	}
	return err
}

// Leave 实现 watchroom.Groups。
func (g IMGroups) Leave(ctx context.Context, groupID, uid string) error {
	_, err := g.C.RemoveGroupMembers(ctx, groupID, []imserver.UserRef{imserver.UID(uid)})
	if imserver.IsNotFound(err) {
		return nil
	}
	return err
}

// Dismiss 实现 watchroom.Groups。
func (g IMGroups) Dismiss(ctx context.Context, groupID string) error {
	err := g.C.DismissGroup(ctx, groupID)
	if imserver.IsNotFound(err) {
		return nil
	}
	return err
}

// IMNotifier 用服务端 API 的瞬时信令推送房间变化（类型 paircast.room_updated）。
type IMNotifier struct{ C *imserver.Client }

// RoomUpdated 实现 watchroom.Notifier。
func (n IMNotifier) RoomUpdated(ctx context.Context, actorUID string, room *watchroom.Room) {
	from := imserver.UID(actorUID)
	err := n.C.SendSignal(ctx, imserver.Signal{
		From:    &from,
		GroupID: room.GroupID,
		Type:    RoomUpdatedSignal,
		Data:    map[string]any{"group_id": room.GroupID, "room": room},
	})
	if err != nil {
		log.Printf("[paircast] room_updated signal for %s: %v", room.ID, err)
	}
}

// WebhookHandler 接收 IM webhook，把 IM 侧的退群 / 解散同步到房间。处理失败返回 500，IM 会重试。
func WebhookHandler(rooms *watchroom.Service, secret string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		ev, err := imserver.ParseWebhook(r, secret)
		if err != nil {
			http.Error(w, "bad signature", http.StatusUnauthorized)
			return
		}
		var data struct {
			GroupID string `json:"group_id"`
			UID     string `json:"uid"`
		}
		json.Unmarshal(ev.Data, &data)
		switch ev.Type {
		case imserver.EventGroupMemberLeft:
			err = rooms.GroupMemberLeft(r.Context(), data.GroupID, data.UID)
		case imserver.EventGroupDismissed:
			err = rooms.GroupDismissed(r.Context(), data.GroupID)
		}
		if err != nil {
			log.Printf("[paircast] webhook %s %s: %v", ev.Type, ev.ID, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
