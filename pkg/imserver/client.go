// Package imserver 是 GO-IM 服务端 API（/server/v1/*）的 Go 客户端，供接入方业务后端使用：
// 导入用户、签发 / 吊销 IM 令牌、管理群、发消息与瞬时信令、校验客户端令牌，以及校验 webhook 回调签名。
//
//	c := imserver.New("https://im.example.com", "myapp", os.Getenv("IM_APP_SECRET"))
//	u, _ := c.ImportUser(ctx, imserver.ImportUser{ExternalID: "42", Nickname: "Alice"})
//	tok, _ := c.IssueToken(ctx, imserver.External("42"), 24*time.Hour)
package imserver

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// 签名请求头，与服务端一致。
const (
	HeaderAppID     = "X-App-Id"
	HeaderTimestamp = "X-Timestamp"
	HeaderNonce     = "X-Nonce"
	HeaderSignature = "X-Signature"
)

// Sign 计算请求签名：hex(HMAC-SHA256(secret, METHOD "\n" PATH "\n" TIMESTAMP "\n" NONCE "\n" BODY))。
func Sign(secret, method, path, timestamp, nonce string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	io.WriteString(mac, method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n")
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// Client 调用服务端 API，可并发使用。
type Client struct {
	baseURL string
	appID   string
	secret  string
	// HTTP 默认超时 10 秒，可替换。
	HTTP *http.Client
	now  func() time.Time
}

// New 创建客户端，baseURL 为网关地址（如 https://im.example.com）。
func New(baseURL, appID, secret string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		appID:   appID,
		secret:  secret,
		HTTP:    &http.Client{Timeout: 10 * time.Second},
		now:     time.Now,
	}
}

// APIError 是服务端返回的非 2xx 响应。
type APIError struct {
	Status  int
	Message string
}

func (e *APIError) Error() string { return fmt.Sprintf("imserver: %d %s", e.Status, e.Message) }

// IsNotFound 报告错误是否为 404（用户或群不存在）。
func IsNotFound(err error) bool {
	e, ok := err.(*APIError)
	return ok && e.Status == http.StatusNotFound
}

func nonce() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func (c *Client) post(ctx context.Context, path string, in, out any) error {
	body, err := json.Marshal(in)
	if err != nil {
		return err
	}
	fullPath := "/server/v1/" + path
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+fullPath, bytes.NewReader(body))
	if err != nil {
		return err
	}
	ts := strconv.FormatInt(c.now().Unix(), 10)
	n := nonce()
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(HeaderAppID, c.appID)
	req.Header.Set(HeaderTimestamp, ts)
	req.Header.Set(HeaderNonce, n)
	req.Header.Set(HeaderSignature, Sign(c.secret, http.MethodPost, fullPath, ts, n, body))
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		var e struct {
			Error string `json:"error"`
		}
		if json.Unmarshal(data, &e) != nil || e.Error == "" {
			e.Error = strings.TrimSpace(string(data))
		}
		return &APIError{Status: resp.StatusCode, Message: e.Error}
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(data, out)
}

// UserRef 用 IM uid 或接入方 external_id 指定用户。
type UserRef struct {
	UID        string `json:"uid,omitempty"`
	ExternalID string `json:"external_id,omitempty"`
}

// UID 按 IM 用户 ID 引用用户。
func UID(uid string) UserRef { return UserRef{UID: uid} }

// External 按接入方用户 ID 引用用户。
func External(id string) UserRef { return UserRef{ExternalID: id} }

// User 是 IM 用户。
type User struct {
	UID          string `json:"uid"`
	ExternalID   string `json:"external_id,omitempty"`
	Nickname     string `json:"nickname"`
	AvatarFileID int64  `json:"avatar_file_id,omitempty"`
	Disabled     bool   `json:"disabled"`
	Created      bool   `json:"created,omitempty"`
}

// ImportUser 是导入参数；Nickname / AvatarFileID 为 nil 表示不修改。
type ImportUser struct {
	ExternalID   string  `json:"external_id"`
	Nickname     *string `json:"nickname,omitempty"`
	AvatarFileID *int64  `json:"avatar_file_id,omitempty"`
}

// String 返回字符串指针，便于填写可选字段。
func String(s string) *string { return &s }

// ImportUser 按 external_id 创建或更新用户。
func (c *Client) ImportUser(ctx context.Context, u ImportUser) (*User, error) {
	var out User
	if err := c.post(ctx, "users/import", u, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// ImportUsers 批量导入（每次 ≤ 100）。
func (c *Client) ImportUsers(ctx context.Context, users []ImportUser) ([]User, error) {
	var out struct {
		Users []User `json:"users"`
	}
	if err := c.post(ctx, "users/import", map[string]any{"users": users}, &out); err != nil {
		return nil, err
	}
	return out.Users, nil
}

// Token 是签发的 IM 令牌。
type Token struct {
	UID       string `json:"uid"`
	Token     string `json:"token"`
	ExpiresAt int64  `json:"expires_at,omitempty"`
}

// IssueToken 签发 IM 令牌，ttl 为 0 用服务端上限。
func (c *Client) IssueToken(ctx context.Context, user UserRef, ttl time.Duration) (*Token, error) {
	var out Token
	err := c.post(ctx, "users/token", struct {
		UserRef
		TTLSeconds int64 `json:"ttl_seconds,omitempty"`
	}{user, int64(ttl / time.Second)}, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// RevokeTokens 吊销用户的全部令牌并踢下线，返回新的令牌版本。
func (c *Client) RevokeTokens(ctx context.Context, user UserRef) (int64, error) {
	var out struct {
		TokenVersion int64 `json:"token_version"`
	}
	err := c.post(ctx, "users/revoke", user, &out)
	return out.TokenVersion, err
}

// SetUserDisabled 禁用或启用用户。
func (c *Client) SetUserDisabled(ctx context.Context, user UserRef, disabled bool) error {
	return c.post(ctx, "users/disable", struct {
		UserRef
		Disabled bool `json:"disabled"`
	}{user, disabled}, nil)
}

// DeleteUser 删除用户。
func (c *Client) DeleteUser(ctx context.Context, user UserRef) error {
	return c.post(ctx, "users/delete", user, nil)
}

// Group 是群。
type Group struct {
	ID        string   `json:"group_id"`
	Name      string   `json:"name"`
	OwnerUID  string   `json:"owner_uid"`
	Members   []string `json:"members"`
	CreatedAt int64    `json:"created_at"`
}

// CreateGroup 建群，owner 自动成为成员。
func (c *Client) CreateGroup(ctx context.Context, name string, owner UserRef, members []UserRef) (*Group, error) {
	var out Group
	err := c.post(ctx, "groups/create", map[string]any{"name": name, "owner": owner, "members": members}, &out)
	if err != nil {
		return nil, err
	}
	return &out, nil
}

// AddGroupMembers 拉人进群，返回实际加入的 UID。
func (c *Client) AddGroupMembers(ctx context.Context, groupID string, users []UserRef) ([]string, error) {
	var out struct {
		Added []string `json:"added"`
	}
	err := c.post(ctx, "groups/members/add", map[string]any{"group_id": groupID, "users": users}, &out)
	return out.Added, err
}

// RemoveGroupMembers 移出群成员，返回实际移出的 UID。
func (c *Client) RemoveGroupMembers(ctx context.Context, groupID string, users []UserRef) ([]string, error) {
	var out struct {
		Removed []string `json:"removed"`
	}
	err := c.post(ctx, "groups/members/remove", map[string]any{"group_id": groupID, "users": users}, &out)
	return out.Removed, err
}

// DismissGroup 解散群。
func (c *Client) DismissGroup(ctx context.Context, groupID string) error {
	return c.post(ctx, "groups/dismiss", map[string]string{"group_id": groupID}, nil)
}

// Message 是发消息参数：To 与 GroupID 二选一；From 为 nil 时以系统身份 sys:<app_id> 发送。
type Message struct {
	From    *UserRef `json:"from,omitempty"`
	To      *UserRef `json:"to,omitempty"`
	GroupID string   `json:"group_id,omitempty"`
	MsgType int32    `json:"msg_type,omitempty"`
	Content string   `json:"content"`
}

// SentMessage 是已发送的消息。
type SentMessage struct {
	MsgID     int64  `json:"msg_id"`
	From      string `json:"from"`
	To        string `json:"to"`
	ChatType  int32  `json:"chat_type"`
	Timestamp int64  `json:"timestamp"`
}

// SendMessage 发送持久消息。
func (c *Client) SendMessage(ctx context.Context, m Message) (*SentMessage, error) {
	var out SentMessage
	if err := c.post(ctx, "messages/send", m, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// Signal 是瞬时信令参数：Type 形如 "<ns>.<name>"，Data 会被编码为 JSON。
type Signal struct {
	From    *UserRef `json:"from,omitempty"`
	To      *UserRef `json:"to,omitempty"`
	GroupID string   `json:"group_id,omitempty"`
	Type    string   `json:"type"`
	Data    any      `json:"data,omitempty"`
}

// SendSignal 把信令投递给在线连接（不持久化）。
func (c *Client) SendSignal(ctx context.Context, s Signal) error {
	return c.post(ctx, "signals/send", s, nil)
}

// TokenInfo 是令牌校验结果。
type TokenInfo struct {
	Valid      bool   `json:"valid"`
	Reason     string `json:"reason,omitempty"`
	UID        string `json:"uid,omitempty"`
	ExternalID string `json:"external_id,omitempty"`
	Nickname   string `json:"nickname,omitempty"`
	Role       string `json:"role,omitempty"`
	ExpiresAt  int64  `json:"expires_at,omitempty"`
}

// VerifyToken 校验客户端提交的 IM 令牌；令牌无效时 err 为 nil、Valid 为 false。
func (c *Client) VerifyToken(ctx context.Context, token string) (*TokenInfo, error) {
	var out TokenInfo
	if err := c.post(ctx, "tokens/verify", map[string]string{"token": token}, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
