// Package jwt 提供 JWT 令牌的创建与校验。
package jwt

import (
	"errors"
	"fmt"
	"time"

	jwtlib "github.com/golang-jwt/jwt/v5"
)

// 用于令牌校验的哨兵错误。
var (
	ErrTokenExpired   = errors.New("token expired")
	ErrTokenInvalid   = errors.New("token invalid")
	ErrTokenSignature = errors.New("token signature mismatch")
)

// Claims 携带已认证用户的身份信息。
type Claims struct {
	UID      string `json:"uid"`
	Nickname string `json:"nickname"`
	Role     string `json:"role"` // "user" 或 "admin"
	// Ver 是签发时用户的令牌版本；吊销会递增用户版本，低于当前版本的令牌失效。
	Ver int64 `json:"ver,omitempty"`
	jwtlib.RegisteredClaims
}

// Manager 负责 JWT 操作。
type Manager struct {
	secret     []byte
	expiration time.Duration
}

// New 创建一个 JWT Manager。
func New(secret string, expiration time.Duration) *Manager {
	return &Manager{
		secret:     []byte(secret),
		expiration: expiration,
	}
}

// Generate 为用户创建带签名的 JWT 令牌（令牌版本 0）。
func (m *Manager) Generate(uid, nickname, role string) (string, error) {
	return m.GenerateVersioned(uid, nickname, role, 0, 0)
}

// GenerateVersioned 创建带令牌版本的 JWT；ttl <= 0 时使用默认有效期。
func (m *Manager) GenerateVersioned(uid, nickname, role string, ver int64, ttl time.Duration) (string, error) {
	if ttl <= 0 {
		ttl = m.expiration
	}
	claims := Claims{
		UID:      uid,
		Nickname: nickname,
		Role:     role,
		Ver:      ver,
		RegisteredClaims: jwtlib.RegisteredClaims{
			ExpiresAt: jwtlib.NewNumericDate(time.Now().Add(ttl)),
			IssuedAt:  jwtlib.NewNumericDate(time.Now()),
			Issuer:    "im-server",
		},
	}
	token := jwtlib.NewWithClaims(jwtlib.SigningMethodHS256, claims)
	return token.SignedString(m.secret)
}

// Validate 解析并校验令牌字符串，返回其中的声明。
// 返回特定的哨兵错误，便于调用方区分：
//   - ErrTokenExpired 当令牌已过期时
//   - ErrTokenSignature 当签名方法或密钥错误时
//   - ErrTokenInvalid 其他所有校验失败时
func (m *Manager) Validate(tokenStr string) (*Claims, error) {
	token, err := jwtlib.ParseWithClaims(tokenStr, &Claims{},
		func(t *jwtlib.Token) (interface{}, error) {
			if _, ok := t.Method.(*jwtlib.SigningMethodHMAC); !ok {
				return nil, fmt.Errorf("%w: unexpected signing method: %v", ErrTokenSignature, t.Header["alg"])
			}
			return m.secret, nil
		})
	if err != nil {
		if errors.Is(err, jwtlib.ErrTokenExpired) {
			return nil, ErrTokenExpired
		}
		// 将签名错误与其他失败区分开来
		if errors.Is(err, jwtlib.ErrSignatureInvalid) {
			return nil, ErrTokenSignature
		}
		return nil, fmt.Errorf("%w: %w", ErrTokenInvalid, err)
	}
	claims, ok := token.Claims.(*Claims)
	if !ok || !token.Valid {
		return nil, ErrTokenInvalid
	}
	return claims, nil
}
