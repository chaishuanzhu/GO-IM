package gateway

import (
	"context"
	"errors"
	"log"
	"sync"
	"time"

	"github.com/im/api/proto"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/repo"
)

// CmdKick 的内容：账号被管理员禁用 / 删除，或令牌被吊销。客户端据此提示原因并停止重连。
const (
	kickAccountDisabled = "account disabled"
	kickAccountDeleted  = "account deleted"
	kickTokenRevoked    = "token revoked"
)

// accountCacheTTL 是账号状态的本地缓存时长：每个 HTTP 请求都要校验，缓存避免逐请求查库。
// 本节点的禁用 / 删除 / 吊销立即生效（forgetAccount），其他网关最多滞后这么久。
const accountCacheTTL = 10 * time.Second

type accountEntry struct {
	user    *repo.User // nil = 用户不存在
	expires time.Time
}

type accountCache struct {
	mu      sync.Mutex
	entries map[string]accountEntry
}

// PeerBroadcaster 把控制消息发给所有对端网关（GrpcForwarder 实现）。
type PeerBroadcaster interface {
	Broadcast(ctx context.Context, uid string, msg *proto.Message)
}

// SetPeerBroadcaster 让踢下线同时作用于其他网关上的连接。
func (s *Server) SetPeerBroadcaster(b PeerBroadcaster) {
	s.peers = b
}

// cachedAccount 返回 uid 的用户记录（缓存 accountCacheTTL），ok=false 表示查询失败。
func (s *Server) cachedAccount(ctx context.Context, uid string) (u *repo.User, ok bool) {
	now := time.Now()
	s.accounts.mu.Lock()
	e, hit := s.accounts.entries[uid]
	s.accounts.mu.Unlock()
	if hit && now.Before(e.expires) {
		return e.user, true
	}
	u, err := s.userStore.GetByUID(ctx, uid)
	if err != nil {
		log.Printf("[server] account check %s: %v", uid, err)
		return nil, false
	}
	s.accounts.mu.Lock()
	if s.accounts.entries == nil {
		s.accounts.entries = map[string]accountEntry{}
	}
	s.accounts.entries[uid] = accountEntry{user: u, expires: now.Add(accountCacheTTL)}
	s.accounts.mu.Unlock()
	return u, true
}

// forgetAccount 丢弃 uid 的缓存，账号状态变更后调用。
func (s *Server) forgetAccount(uid string) {
	s.accounts.mu.Lock()
	delete(s.accounts.entries, uid)
	s.accounts.mu.Unlock()
}

// accountBlocked 返回 uid 不能使用的原因（CmdKick 内容），可以使用时返回空串。
// 已签发的 JWT 在过期前一直有效，所以连接和每个 HTTP 请求都要再查账号状态：被禁用的不行；
// 非 dev_mode 下已删除的也不行（dev_mode 登录不建用户记录）。
// 没有用户存储或查询失败时放行，避免数据库故障把所有人挡在外面。
func (s *Server) accountBlocked(ctx context.Context, uid string) string {
	if s.userStore == nil {
		return ""
	}
	u, ok := s.cachedAccount(ctx, uid)
	switch {
	case !ok:
		return ""
	case u == nil && !s.authCfg.DevMode:
		return kickAccountDeleted
	case u != nil && u.IsDisabled:
		return kickAccountDisabled
	}
	return ""
}

// claimsBlocked 在 accountBlocked 之上再检查令牌是否已被吊销（令牌版本低于用户当前版本）。
func (s *Server) claimsBlocked(ctx context.Context, claims *jwt.Claims) string {
	if reason := s.accountBlocked(ctx, claims.UID); reason != "" {
		return reason
	}
	if s.userStore == nil {
		return ""
	}
	if u, ok := s.cachedAccount(ctx, claims.UID); ok && u != nil && claims.Ver < u.TokenVersion {
		return kickTokenRevoked
	}
	return ""
}

// purgeUserState 清掉已删除用户在 Redis / 内存里的离线队列与未读计数(MySQL 部分由 DeleteUser 完成)。
func (s *Server) purgeUserState(ctx context.Context, uid string) {
	if s.router == nil {
		return
	}
	if s.router.offline != nil {
		s.router.offline.DrainOffline(ctx, uid)
	}
	if t := s.router.unreadTracker; t != nil {
		for peer := range t.GetCounts(ctx, uid) {
			t.MarkRead(ctx, uid, peer)
		}
	}
}

// kickUser 通知 uid 的所有连接下线并关闭：本节点直接踢，配置了多网关时再广播给对端。
// 返回本节点踢掉的连接数。
func (s *Server) kickUser(ctx context.Context, uid, reason string) int {
	s.forgetAccount(uid)
	conns := s.clients.GetAll(ctx, uid)
	for _, c := range conns {
		c.Kick(reason)
	}
	if len(conns) > 0 {
		log.Printf("[server] kicked %d connection(s) of %s: %s", len(conns), uid, reason)
	}
	if s.peers != nil {
		s.peers.Broadcast(ctx, uid, &proto.Message{Cmd: proto.CmdKick, To: uid, Content: reason, Timestamp: time.Now().UnixMilli()})
	}
	return len(conns)
}

// ErrAccountUnavailable 表示令牌合法但账号已禁用、删除或令牌已吊销。
var ErrAccountUnavailable = errors.New("account unavailable")

// IssueToken 为用户签发 IM 令牌（服务端 API 用，与 /login 签发的相同），ttl<=0 用默认有效期。
func (s *Server) IssueToken(u *repo.User, ttl time.Duration) (string, error) {
	role := u.Role
	if role == "" {
		role = "user"
	}
	if s.adminUIDs[u.UID] {
		role = "admin"
	}
	return s.jwtMgr.GenerateVersioned(u.UID, u.Nickname, role, u.TokenVersion, ttl)
}

// VerifyToken 校验令牌签名、有效期和账号状态。账号不可用时返回 ErrAccountUnavailable 和原因。
func (s *Server) VerifyToken(ctx context.Context, token string) (*jwt.Claims, string, error) {
	claims, err := s.jwtMgr.Validate(token)
	if err != nil {
		return nil, "", err
	}
	if reason := s.claimsBlocked(ctx, claims); reason != "" {
		return claims, reason, ErrAccountUnavailable
	}
	return claims, "", nil
}

// RevokeTokens 让 uid 已签发的令牌全部失效并踢掉其连接，返回新的令牌版本。
func (s *Server) RevokeTokens(ctx context.Context, uid string) (int64, error) {
	ext, ok := s.userStore.(repo.ExternalUserStore)
	if !ok {
		return 0, errors.New("token revocation needs MySQL")
	}
	v, err := ext.BumpTokenVersion(ctx, uid)
	if err != nil {
		return 0, err
	}
	s.kickUser(context.WithoutCancel(ctx), uid, kickTokenRevoked)
	return v, nil
}

// SetAccountDisabled 禁用或启用账号，禁用时踢掉所有连接。
func (s *Server) SetAccountDisabled(ctx context.Context, uid string, disabled bool) error {
	if err := s.userStore.UpdateUserDisabled(ctx, uid, disabled); err != nil {
		return err
	}
	s.forgetAccount(uid)
	if disabled {
		s.kickUser(context.WithoutCancel(ctx), uid, kickAccountDisabled)
	}
	return nil
}

// DeleteAccount 删除账号及其关系数据，踢掉连接并清理离线队列与未读。
func (s *Server) DeleteAccount(ctx context.Context, uid string) error {
	if err := s.userStore.DeleteUser(ctx, uid); err != nil {
		return err
	}
	ctx = context.WithoutCancel(ctx)
	s.kickUser(ctx, uid, kickAccountDeleted)
	s.purgeUserState(ctx, uid)
	return nil
}
