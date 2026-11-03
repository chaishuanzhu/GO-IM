// Package serverapi 是供接入方业务后端调用的服务端 API（/server/v1/*）：导入用户、签发 / 吊销 IM 令牌、
// 管理群、以用户或系统身份发消息和瞬时信令、校验令牌。请求用 app_id + HMAC 签名鉴权（见 auth.go）。
package serverapi

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/im/api/proto"
	"github.com/im/internal/gateway"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/repo"
)

// Prefix 是服务端 API 的路由前缀。
const Prefix = "/server/v1/"

const (
	maxImportBatch  = 100
	maxGroupMembers = 500
	maxContentBytes = 64 << 10
)

// UserStore 是服务端 API 需要的用户存储能力（MySQLStore 实现）。
type UserStore interface {
	GetByUID(ctx context.Context, uid string) (*repo.User, error)
	repo.ExternalUserStore
}

// Accounts 是账号控制（gateway.Server 实现）。
type Accounts interface {
	IssueToken(u *repo.User, ttl time.Duration) (string, error)
	VerifyToken(ctx context.Context, token string) (*jwt.Claims, string, error)
	RevokeTokens(ctx context.Context, uid string) (int64, error)
	SetAccountDisabled(ctx context.Context, uid string, disabled bool) error
	DeleteAccount(ctx context.Context, uid string) error
}

// Messenger 是消息投递（gateway.Router 实现）。
type Messenger interface {
	SendMessage(ctx context.Context, from string, chatType int32, to string, msgType int32, content string) (*proto.Message, error)
	SendSignal(ctx context.Context, fromUID string, chatType int32, to string, msgType int32, content string)
	NotifyGroup(ctx context.Context, uid, groupID, notifType string, members []string)
	ForgetGroupMembers(groupID string)
}

// Deps 是 API 的依赖。
type Deps struct {
	Apps        *Registry
	Nonces      NonceStore
	Users       UserStore
	Groups      gateway.GroupStore
	Accounts    Accounts
	Messenger   Messenger
	MaxTokenTTL time.Duration
}

// API 处理 /server/v1/*。
type API struct {
	apps        *Registry
	nonces      NonceStore
	users       UserStore
	groups      gateway.GroupStore
	accounts    Accounts
	messenger   Messenger
	maxTokenTTL time.Duration
	now         func() time.Time
	routes      map[string]handlerFunc
}

type handlerFunc func(ctx context.Context, app *App, body []byte) (any, error)

// New 创建 API。
func New(d Deps) *API {
	a := &API{
		apps: d.Apps, nonces: d.Nonces, users: d.Users, groups: d.Groups,
		accounts: d.Accounts, messenger: d.Messenger,
		maxTokenTTL: d.MaxTokenTTL, now: time.Now,
	}
	if a.nonces == nil {
		a.nonces = &MemoryNonceStore{}
	}
	a.routes = map[string]handlerFunc{
		"users/import":          a.usersImport,
		"users/token":           a.usersToken,
		"users/revoke":          a.usersRevoke,
		"users/disable":         a.usersDisable,
		"users/delete":          a.usersDelete,
		"groups/create":         a.groupsCreate,
		"groups/members/add":    a.groupsMembersAdd,
		"groups/members/remove": a.groupsMembersRemove,
		"groups/dismiss":        a.groupsDismiss,
		"messages/send":         a.messagesSend,
		"signals/send":          a.signalsSend,
		"tokens/verify":         a.tokensVerify,
	}
	return a
}

// Routes 返回所有路由路径（含前缀），用于注册与文档核对。
func (a *API) Routes() []string {
	out := make([]string, 0, len(a.routes))
	for p := range a.routes {
		out = append(out, Prefix+p)
	}
	return out
}

// apiError 携带 HTTP 状态码。
type apiError struct {
	status int
	msg    string
}

func (e *apiError) Error() string { return e.msg }

func badRequest(msg string) error { return &apiError{http.StatusBadRequest, msg} }
func notFound(msg string) error   { return &apiError{http.StatusNotFound, msg} }

// ServeHTTP 校验签名后分发。所有接口都是 POST + JSON。
func (a *API) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h, ok := a.routes[strings.TrimPrefix(r.URL.Path, Prefix)]
	if !ok {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	app, body, status, err := a.authenticate(r)
	if err != nil {
		writeError(w, status, err.Error())
		return
	}
	resp, err := h(r.Context(), app, body)
	if err != nil {
		var ae *apiError
		if errors.As(err, &ae) {
			writeError(w, ae.status, ae.msg)
			return
		}
		log.Printf("[serverapi] %s %s: %v", app.ID, r.URL.Path, err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func decode(body []byte, v any) error {
	if len(body) == 0 {
		return badRequest("empty body")
	}
	if err := json.Unmarshal(body, v); err != nil {
		return badRequest("invalid JSON: " + err.Error())
	}
	return nil
}

// UserRef 用 IM uid 或接入方 external_id 指定用户，二选一。
type UserRef struct {
	UID        string `json:"uid,omitempty"`
	ExternalID string `json:"external_id,omitempty"`
}

// UserView 是返回给接入方的用户信息。
type UserView struct {
	UID          string `json:"uid"`
	ExternalID   string `json:"external_id,omitempty"`
	Nickname     string `json:"nickname"`
	AvatarFileID int64  `json:"avatar_file_id,omitempty"`
	Disabled     bool   `json:"disabled"`
	Created      bool   `json:"created,omitempty"`
}

func viewUser(u *repo.User) UserView {
	return UserView{UID: u.UID, ExternalID: u.ExternalID, Nickname: u.Nickname, AvatarFileID: u.AvatarFileID, Disabled: u.IsDisabled}
}

// resolve 返回引用的用户，不存在返回 404。
func (a *API) resolve(ctx context.Context, ref UserRef) (*repo.User, error) {
	var (
		u   *repo.User
		err error
	)
	switch {
	case ref.UID != "":
		u, err = a.users.GetByUID(ctx, ref.UID)
	case ref.ExternalID != "":
		u, err = a.users.GetByExternalID(ctx, ref.ExternalID)
	default:
		return nil, badRequest("uid or external_id required")
	}
	if errors.Is(err, repo.ErrUserNotFound) || (err == nil && u == nil) {
		return nil, notFound("user not found")
	}
	return u, err
}

func (a *API) resolveUIDs(ctx context.Context, refs []UserRef) ([]string, error) {
	if len(refs) > maxGroupMembers {
		return nil, badRequest("too many users")
	}
	out := make([]string, 0, len(refs))
	for _, ref := range refs {
		u, err := a.resolve(ctx, ref)
		if err != nil {
			return nil, err
		}
		out = append(out, u.UID)
	}
	return out, nil
}

// --- 用户 ---

type importUser struct {
	ExternalID   string  `json:"external_id"`
	Nickname     *string `json:"nickname,omitempty"`
	AvatarFileID *int64  `json:"avatar_file_id,omitempty"`
}

// usersImport 按 external_id 创建或更新用户。单个：{"external_id",...}；批量：{"users":[...]}（≤100）。
func (a *API) usersImport(ctx context.Context, _ *App, body []byte) (any, error) {
	var req struct {
		importUser
		Users []importUser `json:"users"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	batch := req.Users != nil
	items := req.Users
	if !batch {
		items = []importUser{req.importUser}
	}
	if len(items) == 0 || len(items) > maxImportBatch {
		return nil, badRequest("1-100 users per request")
	}
	out := make([]UserView, 0, len(items))
	for _, it := range items {
		if it.ExternalID == "" || len(it.ExternalID) > 128 {
			return nil, badRequest("external_id required (≤ 128 chars)")
		}
		if it.Nickname != nil && len([]rune(*it.Nickname)) > 32 {
			return nil, badRequest("nickname too long (≤ 32)")
		}
		u, created, err := a.users.UpsertExternalUser(ctx, it.ExternalID, it.Nickname, it.AvatarFileID)
		if err != nil {
			return nil, err
		}
		v := viewUser(u)
		v.Created = created
		out = append(out, v)
	}
	if !batch {
		return out[0], nil
	}
	return map[string]any{"users": out}, nil
}

// usersToken 为用户签发 IM 令牌：{"uid"|"external_id", "ttl_seconds"}。
func (a *API) usersToken(ctx context.Context, _ *App, body []byte) (any, error) {
	var req struct {
		UserRef
		TTLSeconds int64 `json:"ttl_seconds"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	u, err := a.resolve(ctx, req.UserRef)
	if err != nil {
		return nil, err
	}
	if u.IsDisabled {
		return nil, &apiError{http.StatusForbidden, "account disabled"}
	}
	ttl := time.Duration(req.TTLSeconds) * time.Second
	if ttl < 0 || (a.maxTokenTTL > 0 && ttl > a.maxTokenTTL) {
		return nil, badRequest("ttl_seconds out of range")
	}
	if ttl == 0 {
		ttl = a.maxTokenTTL
	}
	token, err := a.accounts.IssueToken(u, ttl)
	if err != nil {
		return nil, err
	}
	resp := map[string]any{"uid": u.UID, "token": token}
	if ttl > 0 {
		resp["expires_at"] = a.now().Add(ttl).Unix()
	}
	return resp, nil
}

func (a *API) usersRevoke(ctx context.Context, _ *App, body []byte) (any, error) {
	var req UserRef
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	u, err := a.resolve(ctx, req)
	if err != nil {
		return nil, err
	}
	v, err := a.accounts.RevokeTokens(ctx, u.UID)
	if err != nil {
		return nil, err
	}
	return map[string]any{"uid": u.UID, "token_version": v}, nil
}

func (a *API) usersDisable(ctx context.Context, _ *App, body []byte) (any, error) {
	var req struct {
		UserRef
		Disabled *bool `json:"disabled"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	u, err := a.resolve(ctx, req.UserRef)
	if err != nil {
		return nil, err
	}
	disabled := req.Disabled == nil || *req.Disabled
	if err := a.accounts.SetAccountDisabled(ctx, u.UID, disabled); err != nil {
		return nil, err
	}
	return map[string]any{"uid": u.UID, "disabled": disabled}, nil
}

func (a *API) usersDelete(ctx context.Context, _ *App, body []byte) (any, error) {
	var req UserRef
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	u, err := a.resolve(ctx, req)
	if err != nil {
		return nil, err
	}
	if err := a.accounts.DeleteAccount(ctx, u.UID); err != nil {
		return nil, err
	}
	return map[string]any{"uid": u.UID, "deleted": true}, nil
}

// --- 群 ---

type groupView struct {
	ID        string   `json:"group_id"`
	Name      string   `json:"name"`
	OwnerUID  string   `json:"owner_uid"`
	Members   []string `json:"members"`
	CreatedAt int64    `json:"created_at"`
}

func (a *API) needGroups() error {
	if a.groups == nil {
		return &apiError{http.StatusServiceUnavailable, "group chat not enabled"}
	}
	return nil
}

func groupError(err error) error {
	switch {
	case errors.Is(err, gateway.ErrGroupNotFound):
		return notFound("group not found")
	case errors.Is(err, gateway.ErrAlreadyMember), errors.Is(err, gateway.ErrNotMember):
		return &apiError{http.StatusConflict, err.Error()}
	}
	return err
}

// groupsCreate 建群：{"name","owner":{...},"members":[{...}]}，群主自动成为成员。
func (a *API) groupsCreate(ctx context.Context, _ *App, body []byte) (any, error) {
	if err := a.needGroups(); err != nil {
		return nil, err
	}
	var req struct {
		Name    string    `json:"name"`
		Owner   UserRef   `json:"owner"`
		Members []UserRef `json:"members"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || len([]rune(req.Name)) > 32 {
		return nil, badRequest("name required (≤ 32)")
	}
	owner, err := a.resolve(ctx, req.Owner)
	if err != nil {
		return nil, err
	}
	members, err := a.resolveUIDs(ctx, req.Members)
	if err != nil {
		return nil, err
	}
	g, err := a.groups.Create(ctx, req.Name, owner.UID, members)
	if err != nil {
		return nil, groupError(err)
	}
	view := groupView{ID: g.ID, Name: g.Name, OwnerUID: g.OwnerUID, CreatedAt: g.CreatedAt}
	view.Members, _ = a.groups.GetMembers(ctx, g.ID)
	a.messenger.NotifyGroup(ctx, owner.UID, g.ID, "member_joined", view.Members)
	return view, nil
}

type membersReq struct {
	GroupID string    `json:"group_id"`
	Users   []UserRef `json:"users"`
}

func (a *API) parseMembers(ctx context.Context, body []byte) (string, []string, error) {
	if err := a.needGroups(); err != nil {
		return "", nil, err
	}
	var req membersReq
	if err := decode(body, &req); err != nil {
		return "", nil, err
	}
	if req.GroupID == "" || len(req.Users) == 0 {
		return "", nil, badRequest("group_id and users required")
	}
	if _, err := a.groups.Get(ctx, req.GroupID); err != nil {
		return "", nil, groupError(err)
	}
	uids, err := a.resolveUIDs(ctx, req.Users)
	return req.GroupID, uids, err
}

// groupsMembersAdd 拉人进群，已在群里的跳过。
func (a *API) groupsMembersAdd(ctx context.Context, _ *App, body []byte) (any, error) {
	groupID, uids, err := a.parseMembers(ctx, body)
	if err != nil {
		return nil, err
	}
	added := []string{}
	for _, uid := range uids {
		err := a.groups.AddMember(ctx, groupID, uid)
		if errors.Is(err, gateway.ErrAlreadyMember) {
			continue
		}
		if err != nil {
			return nil, groupError(err)
		}
		added = append(added, uid)
	}
	a.messenger.ForgetGroupMembers(groupID)
	for _, uid := range added {
		a.messenger.NotifyGroup(ctx, uid, groupID, "member_joined", nil)
	}
	return map[string]any{"group_id": groupID, "added": added}, nil
}

// groupsMembersRemove 移出成员，不在群里的跳过；最后一人移出后群被删除。
func (a *API) groupsMembersRemove(ctx context.Context, _ *App, body []byte) (any, error) {
	groupID, uids, err := a.parseMembers(ctx, body)
	if err != nil {
		return nil, err
	}
	removed := []string{}
	for _, uid := range uids {
		if !a.groups.IsMember(ctx, groupID, uid) {
			continue
		}
		// 先通知再移除，被移出的人也能收到。
		a.messenger.NotifyGroup(ctx, uid, groupID, "member_left", nil)
		if err := a.groups.RemoveMember(ctx, groupID, uid); err != nil && !errors.Is(err, gateway.ErrNotMember) {
			return nil, groupError(err)
		}
		removed = append(removed, uid)
	}
	a.messenger.ForgetGroupMembers(groupID)
	return map[string]any{"group_id": groupID, "removed": removed}, nil
}

// groupsDismiss 解散群：通知全体成员后逐个移除（最后一人移除时群记录随之删除）。
func (a *API) groupsDismiss(ctx context.Context, app *App, body []byte) (any, error) {
	if err := a.needGroups(); err != nil {
		return nil, err
	}
	var req struct {
		GroupID string `json:"group_id"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	members, err := a.groups.GetMembers(ctx, req.GroupID)
	if err != nil {
		return nil, groupError(err)
	}
	if _, err := a.groups.Get(ctx, req.GroupID); err != nil {
		return nil, groupError(err)
	}
	a.messenger.NotifyGroup(ctx, app.SystemUID(), req.GroupID, "group_dismissed", members)
	for _, uid := range members {
		if err := a.groups.RemoveMember(ctx, req.GroupID, uid); err != nil && !errors.Is(err, gateway.ErrNotMember) && !errors.Is(err, gateway.ErrGroupNotFound) {
			return nil, groupError(err)
		}
	}
	a.messenger.ForgetGroupMembers(req.GroupID)
	return map[string]any{"group_id": req.GroupID, "dismissed": true}, nil
}

// --- 消息与信令 ---

type target struct {
	To      *UserRef `json:"to,omitempty"`
	GroupID string   `json:"group_id,omitempty"`
}

// resolveTarget 返回会话类型与目标 ID（用户 uid 或群 ID）。
func (a *API) resolveTarget(ctx context.Context, t target) (int32, string, error) {
	switch {
	case t.GroupID != "" && t.To != nil:
		return 0, "", badRequest("set either to or group_id")
	case t.GroupID != "":
		if err := a.needGroups(); err != nil {
			return 0, "", err
		}
		if _, err := a.groups.Get(ctx, t.GroupID); err != nil {
			return 0, "", groupError(err)
		}
		return proto.ChatTypeGroup, t.GroupID, nil
	case t.To != nil:
		u, err := a.resolve(ctx, *t.To)
		if err != nil {
			return 0, "", err
		}
		return proto.ChatTypeSingle, u.UID, nil
	}
	return 0, "", badRequest("to or group_id required")
}

// resolveSender 返回发送方：from 为空时用接入方的系统身份。
func (a *API) resolveSender(ctx context.Context, app *App, from *UserRef) (string, error) {
	if from == nil {
		return app.SystemUID(), nil
	}
	u, err := a.resolve(ctx, *from)
	if err != nil {
		return "", err
	}
	if u.IsDisabled {
		return "", &apiError{http.StatusForbidden, "sender disabled"}
	}
	return u.UID, nil
}

// messagesSend 发持久消息：{"from"?, "to"|"group_id", "msg_type"?, "content"}。
// msg_type 默认 1（文本）；自定义业务消息建议 msg_type=1 以外的约定值并在 content 里放 JSON。
func (a *API) messagesSend(ctx context.Context, app *App, body []byte) (any, error) {
	var req struct {
		target
		From    *UserRef `json:"from,omitempty"`
		MsgType int32    `json:"msg_type"`
		Content string   `json:"content"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	if req.Content == "" || len(req.Content) > maxContentBytes {
		return nil, badRequest("content required (≤ 64 KiB)")
	}
	chatType, to, err := a.resolveTarget(ctx, req.target)
	if err != nil {
		return nil, err
	}
	from, err := a.resolveSender(ctx, app, req.From)
	if err != nil {
		return nil, err
	}
	if from == to {
		return nil, badRequest("cannot send to yourself")
	}
	msg, err := a.messenger.SendMessage(ctx, from, chatType, to, req.MsgType, req.Content)
	switch {
	case errors.Is(err, gateway.ErrBlocked):
		return nil, &apiError{http.StatusForbidden, "blocked by recipient"}
	case errors.Is(err, gateway.ErrNotMember):
		return nil, &apiError{http.StatusForbidden, "sender is not a group member"}
	case err != nil:
		return nil, groupError(err)
	}
	return map[string]any{"msg_id": msg.MsgId, "from": msg.From, "to": msg.To, "chat_type": msg.ChatType, "timestamp": msg.Timestamp}, nil
}

// signalsSend 发瞬时信令：{"from"?, "to"|"group_id", "type":"<ns>.<name>", "data":{...}}，只投递给在线连接。
func (a *API) signalsSend(ctx context.Context, app *App, body []byte) (any, error) {
	var req struct {
		target
		From *UserRef        `json:"from,omitempty"`
		Type string          `json:"type"`
		Data json.RawMessage `json:"data,omitempty"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	if req.Type == "" || !strings.Contains(req.Type, ".") {
		return nil, badRequest(`type required, formatted "<ns>.<name>"`)
	}
	chatType, to, err := a.resolveTarget(ctx, req.target)
	if err != nil {
		return nil, err
	}
	from, err := a.resolveSender(ctx, app, req.From)
	if err != nil {
		return nil, err
	}
	content, err := json.Marshal(proto.SignalContent{Type: req.Type, Data: req.Data})
	if err != nil {
		return nil, badRequest(err.Error())
	}
	if len(content) > maxContentBytes {
		return nil, badRequest("signal too large (≤ 64 KiB)")
	}
	a.messenger.SendSignal(ctx, from, chatType, to, proto.MsgTypeText, string(content))
	return map[string]any{"sent": true}, nil
}

// tokensVerify 校验客户端提交的 IM 令牌（接入方后端用它认证自己的 HTTP 请求）。
func (a *API) tokensVerify(ctx context.Context, _ *App, body []byte) (any, error) {
	var req struct {
		Token string `json:"token"`
	}
	if err := decode(body, &req); err != nil {
		return nil, err
	}
	if req.Token == "" {
		return nil, badRequest("token required")
	}
	claims, reason, err := a.accounts.VerifyToken(ctx, req.Token)
	if err != nil {
		if reason == "" {
			reason = "invalid token"
		}
		return map[string]any{"valid": false, "reason": reason}, nil
	}
	resp := map[string]any{"valid": true, "uid": claims.UID, "nickname": claims.Nickname, "role": claims.Role}
	if claims.ExpiresAt != nil {
		resp["expires_at"] = claims.ExpiresAt.Unix()
	}
	if u, err := a.users.GetByUID(ctx, claims.UID); err == nil && u != nil && u.ExternalID != "" {
		resp["external_id"] = u.ExternalID
	}
	return resp, nil
}
