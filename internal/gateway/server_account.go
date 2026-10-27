package gateway

import (
	"bytes"
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"net/mail"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/im/internal/repo"
	"golang.org/x/crypto/bcrypt"
)

const (
	minPasswordLen  = 6
	maxPasswordLen  = 72 // bcrypt 只使用前 72 字节
	maxNicknameLen  = 32
	maxSearchLimit  = 50
	maxProfileBatch = 200
	mailSendTimeout = 15 * time.Second
)

// SetAccountServices 启用邮箱验证码相关接口（注册、找回密码、绑定邮箱）。
func (s *Server) SetAccountServices(mailer Mailer, codes CodeStore) {
	s.mailer = mailer
	s.codes = codes
}

// HandleEmailCode 发送邮箱验证码。
// POST /auth/email-code 表单字段：email、purpose（register / reset_password / bind_email）；
// bind_email 还需 uid、token。
func (s *Server) HandleEmailCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.mailer == nil || s.codes == nil || s.userStore == nil {
		http.Error(w, "email verification not available", http.StatusServiceUnavailable)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form data", http.StatusBadRequest)
		return
	}
	purpose := CodePurpose(r.FormValue("purpose"))
	if purpose == "" {
		purpose = PurposeRegister
	}
	if !purpose.valid() {
		http.Error(w, "invalid purpose", http.StatusBadRequest)
		return
	}
	if purpose == PurposeRegister && !s.registrationOpen() {
		http.Error(w, "registration closed", http.StatusForbidden)
		return
	}
	email, ok := normalizeEmail(r.FormValue("email"))
	if !ok {
		http.Error(w, "invalid email", http.StatusBadRequest)
		return
	}
	if purpose == PurposeBindEmail && s.authenticateRequest(w, r) == nil {
		return
	}

	ctx := r.Context()
	existing, err := s.userStore.GetByEmail(ctx, email)
	if err != nil {
		log.Printf("[server] email-code lookup: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	switch purpose {
	case PurposeRegister, PurposeBindEmail:
		if existing != nil {
			http.Error(w, "email already registered", http.StatusConflict)
			return
		}
	case PurposeResetPassword:
		if existing == nil {
			http.Error(w, "email not registered", http.StatusNotFound)
			return
		}
	}

	code := newVerificationCode()
	if err := s.codes.Issue(ctx, purpose, email, clientIP(r), code); err != nil {
		var cd *CodeCooldownError
		switch {
		case errors.As(err, &cd):
			w.Header().Set("Retry-After", strconv.Itoa(int(cd.RetryAfter.Seconds()+0.999)))
			http.Error(w, err.Error(), http.StatusTooManyRequests)
		case errors.Is(err, ErrCodeQuota):
			http.Error(w, err.Error(), http.StatusTooManyRequests)
		default:
			log.Printf("[server] email-code issue: %v", err)
			http.Error(w, "internal error", http.StatusInternalServerError)
		}
		return
	}

	subject, text, html := verificationMail(purpose, code)
	sendCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), mailSendTimeout)
	defer cancel()
	if err := s.mailer.Send(sendCtx, email, subject, text, html); err != nil {
		log.Printf("[server] email-code send to %s: %v", maskEmail(email), err)
		s.codes.Revoke(context.WithoutCancel(ctx), purpose, email)
		http.Error(w, "failed to send email", http.StatusBadGateway)
		return
	}
	log.Printf("[server] email-code sent: purpose=%s to=%s", purpose, maskEmail(email))
	writeJSON(w, map[string]interface{}{
		"status":      "ok",
		"expires_in":  int(codeTTL.Seconds()),
		"retry_after": int(codeCooldown.Seconds()),
	})
}

// HandleRegister 用邮箱验证码注册，服务端自动分配数字 UID。
// POST /register 表单字段：email、code、password，可选 nickname（默认取邮箱 @ 前部分）。
func (s *Server) HandleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.userStore == nil || s.codes == nil {
		http.Error(w, "registration not available", http.StatusServiceUnavailable)
		return
	}
	if !s.registrationOpen() {
		http.Error(w, "registration closed", http.StatusForbidden)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form data", http.StatusBadRequest)
		return
	}
	email, ok := normalizeEmail(r.FormValue("email"))
	if !ok {
		http.Error(w, "invalid email", http.StatusBadRequest)
		return
	}
	password := r.FormValue("password")
	if msg := checkPassword(password); msg != "" {
		http.Error(w, msg, http.StatusBadRequest)
		return
	}
	nickname := strings.TrimSpace(r.FormValue("nickname"))
	if nickname == "" {
		nickname = defaultNickname(email)
	} else if msg := checkNickname(nickname); msg != "" {
		http.Error(w, msg, http.StatusBadRequest)
		return
	}

	ctx := r.Context()
	if err := s.codes.Verify(ctx, PurposeRegister, email, r.FormValue("code")); err != nil {
		writeCodeError(w, err)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		log.Printf("[server] bcrypt error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	now := time.Now().UnixMilli()
	u := &repo.User{
		Nickname:     nickname,
		Email:        email,
		PasswordHash: string(hash),
		Role:         "user",
		CreatedAt:    now,
		UpdatedAt:    now,
	}
	if err := s.userStore.CreateWithAutoUID(ctx, u); err != nil {
		if errors.Is(err, repo.ErrEmailTaken) {
			http.Error(w, "email already registered", http.StatusConflict)
			return
		}
		log.Printf("[server] register %s: %v", maskEmail(email), err)
		http.Error(w, "registration failed", http.StatusInternalServerError)
		return
	}
	log.Printf("[server] registered uid=%s email=%s", u.UID, maskEmail(email))
	s.writeLoginResponse(w, u)
}

// HandleLogin 登录并签发 JWT。
// POST /login 表单字段：account（邮箱或 UID，兼容旧字段 uid）、password。
// 开发模式下可不带密码，仅凭 uid 登录，nickname 可选。
func (s *Server) HandleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form data", http.StatusBadRequest)
		return
	}
	account := strings.TrimSpace(r.FormValue("account"))
	if account == "" {
		account = strings.TrimSpace(r.FormValue("uid"))
	}
	if account == "" {
		account = strings.TrimSpace(r.FormValue("username"))
	}
	if account == "" {
		http.Error(w, "account required", http.StatusBadRequest)
		return
	}
	password := r.FormValue("password")

	if password == "" {
		if !s.authCfg.DevMode {
			http.Error(w, "password required", http.StatusUnauthorized)
			return
		}
		s.devLogin(w, r, account)
		return
	}

	if s.userStore == nil {
		http.Error(w, "user store not available", http.StatusServiceUnavailable)
		return
	}
	u, err := s.lookupAccount(r.Context(), account)
	if err != nil {
		log.Printf("[server] login lookup %q: %v", account, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if u == nil || bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)) != nil {
		http.Error(w, "invalid account or password", http.StatusUnauthorized)
		return
	}
	if u.IsDisabled {
		http.Error(w, "account disabled", http.StatusForbidden)
		return
	}
	if s.authCfg.DevMode && r.FormValue("role") == "admin" {
		u.Role = "admin"
	}
	s.writeLoginResponse(w, u)
}

// devLogin 是开发模式的免密登录：已存在的用户沿用其资料，否则以 uid 作为昵称。
func (s *Server) devLogin(w http.ResponseWriter, r *http.Request, uid string) {
	u := &repo.User{UID: uid, Role: "user"}
	if s.userStore != nil {
		if found, err := s.lookupAccount(r.Context(), uid); err == nil && found != nil {
			u = found
		}
	}
	if u.Nickname == "" {
		u.Nickname = strings.TrimSpace(r.FormValue("nickname"))
	}
	if u.Nickname == "" {
		u.Nickname = strings.TrimSpace(r.FormValue("username"))
	}
	if u.Nickname == "" {
		u.Nickname = u.UID
	}
	if r.FormValue("role") == "admin" {
		u.Role = "admin"
	}
	s.writeLoginResponse(w, u)
}

func (s *Server) lookupAccount(ctx context.Context, account string) (*repo.User, error) {
	if strings.Contains(account, "@") {
		email, ok := normalizeEmail(account)
		if !ok {
			return nil, nil
		}
		return s.userStore.GetByEmail(ctx, email)
	}
	return s.userStore.GetByUID(ctx, account)
}

// writeLoginResponse 签发 JWT 并返回登录结果；username 字段为旧客户端保留。
func (s *Server) writeLoginResponse(w http.ResponseWriter, u *repo.User) {
	role := u.Role
	if role == "" {
		role = "user"
	}
	if s.adminUIDs[u.UID] {
		role = "admin"
	}
	token, err := s.jwtMgr.Generate(u.UID, u.Nickname, role)
	if err != nil {
		http.Error(w, "generate token: "+err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]interface{}{
		"uid":        u.UID,
		"nickname":   u.Nickname,
		"username":   u.Nickname,
		"email":      u.Email,
		"avatar_url": avatarURL(u.AvatarFileID),
		"role":       role,
		"token":      token,
		// 为 true 时客户端须先调用 /change-password 再进入应用
		"must_change_password": u.MustChangePassword,
	})
}

// HandlePasswordReset 通过邮箱验证码重置密码。
// POST /password/reset 表单字段：email、code、new_password
func (s *Server) HandlePasswordReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.userStore == nil || s.codes == nil {
		http.Error(w, "password reset not available", http.StatusServiceUnavailable)
		return
	}
	if err := r.ParseForm(); err != nil {
		http.Error(w, "invalid form data", http.StatusBadRequest)
		return
	}
	email, ok := normalizeEmail(r.FormValue("email"))
	if !ok {
		http.Error(w, "invalid email", http.StatusBadRequest)
		return
	}
	password := r.FormValue("new_password")
	if msg := checkPassword(password); msg != "" {
		http.Error(w, msg, http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	if err := s.codes.Verify(ctx, PurposeResetPassword, email, r.FormValue("code")); err != nil {
		writeCodeError(w, err)
		return
	}
	u, err := s.userStore.GetByEmail(ctx, email)
	if err != nil || u == nil {
		if err != nil {
			log.Printf("[server] password reset lookup: %v", err)
		}
		http.Error(w, "email not registered", http.StatusNotFound)
		return
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if err := s.userStore.UpdatePassword(ctx, u.UID, string(hash)); err != nil {
		log.Printf("[server] password reset %s: %v", u.UID, err)
		http.Error(w, "failed to update password", http.StatusInternalServerError)
		return
	}
	log.Printf("[server] password reset uid=%s", u.UID)
	writeJSON(w, map[string]string{"status": "ok", "uid": u.UID})
}

// HandleBindEmail 为当前用户绑定或更换邮箱。
// POST /user/email 表单字段：uid、token、email、code
func (s *Server) HandleBindEmail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.userStore == nil || s.codes == nil {
		http.Error(w, "email binding not available", http.StatusServiceUnavailable)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	email, ok := normalizeEmail(r.FormValue("email"))
	if !ok {
		http.Error(w, "invalid email", http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	if err := s.codes.Verify(ctx, PurposeBindEmail, email, r.FormValue("code")); err != nil {
		writeCodeError(w, err)
		return
	}
	switch err := s.userStore.SetEmail(ctx, claims.UID, email); {
	case errors.Is(err, repo.ErrEmailTaken):
		http.Error(w, "email already registered", http.StatusConflict)
		return
	case errors.Is(err, repo.ErrUserNotFound):
		http.Error(w, "user not found", http.StatusNotFound)
		return
	case err != nil:
		log.Printf("[server] bind email %s: %v", claims.UID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	s.writeOwnProfile(w, r, claims.UID)
}

// HandleProfile 读取或修改用户资料。
// GET  /user/profile?uid&token[&target=UID]   不带 target 返回自己的完整资料（含邮箱）
// POST /user/profile 表单字段：uid、token，可选 nickname、avatar_file_id（"0" 清除头像）
func (s *Server) HandleProfile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.userStore == nil {
		http.Error(w, "user store not available", http.StatusServiceUnavailable)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	ctx := r.Context()

	if r.Method == http.MethodGet {
		target := r.URL.Query().Get("target")
		if target == "" || target == claims.UID {
			s.writeOwnProfile(w, r, claims.UID)
			return
		}
		u, err := s.userStore.GetByUID(ctx, target)
		if err != nil {
			log.Printf("[server] profile %s: %v", target, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if u == nil {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}
		writeJSON(w, publicProfile(u))
		return
	}

	var nickname *string
	if _, ok := r.PostForm["nickname"]; ok {
		n := strings.TrimSpace(r.PostForm.Get("nickname"))
		if msg := checkNickname(n); msg != "" {
			http.Error(w, msg, http.StatusBadRequest)
			return
		}
		nickname = &n
	}
	var avatar *int64
	if raw, ok := r.PostForm["avatar_file_id"]; ok && len(raw) > 0 {
		id, err := strconv.ParseInt(strings.TrimSpace(raw[0]), 10, 64)
		if err != nil || id < 0 {
			http.Error(w, "invalid avatar_file_id", http.StatusBadRequest)
			return
		}
		if id != 0 {
			if msg := s.claimAvatar(ctx, claims.UID, id); msg != "" {
				http.Error(w, msg, http.StatusBadRequest)
				return
			}
		}
		avatar = &id
	}
	if nickname == nil && avatar == nil {
		http.Error(w, "nothing to update", http.StatusBadRequest)
		return
	}
	if err := s.userStore.UpdateProfile(ctx, claims.UID, nickname, avatar); err != nil {
		if errors.Is(err, repo.ErrUserNotFound) {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}
		log.Printf("[server] update profile %s: %v", claims.UID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	s.writeOwnProfile(w, r, claims.UID)
}

// claimAvatar 校验文件属于该用户且是已就绪的图片，并标记为头像（之后可公开读取）。
func (s *Server) claimAvatar(ctx context.Context, uid string, fileID int64) string {
	if s.fileStore == nil {
		return "avatar upload not available"
	}
	f, err := s.fileStore.GetFile(ctx, fileID)
	if err != nil {
		log.Printf("[server] avatar file %d: %v", fileID, err)
		return "avatar file not found"
	}
	if f == nil || f.OwnerUID != uid || f.Status != repo.FileStatusReady {
		return "avatar file not found"
	}
	if !IsImageMIME(f.Mime) {
		return "avatar must be an image"
	}
	if !f.IsAvatar {
		if err := s.fileStore.MarkAvatar(ctx, fileID); err != nil {
			log.Printf("[server] mark avatar %d: %v", fileID, err)
			return "avatar file not found"
		}
	}
	return ""
}

func (s *Server) writeOwnProfile(w http.ResponseWriter, r *http.Request, uid string) {
	u, err := s.userStore.GetByUID(r.Context(), uid)
	if err != nil {
		log.Printf("[server] profile %s: %v", uid, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if u == nil {
		http.Error(w, "user not found", http.StatusNotFound)
		return
	}
	p := publicProfile(u)
	p["email"] = u.Email
	p["role"] = u.Role
	p["created_at"] = u.CreatedAt
	writeJSON(w, p)
}

// HandleProfiles 批量读取公开资料，供客户端缓存昵称与头像。
// GET /user/profiles?uid&token&uids=a,b,c
func (s *Server) HandleProfiles(w http.ResponseWriter, r *http.Request) {
	if s.userStore == nil {
		http.Error(w, "user store not available", http.StatusServiceUnavailable)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	var uids []string
	seen := make(map[string]bool)
	for _, id := range strings.Split(r.FormValue("uids"), ",") {
		if id = strings.TrimSpace(id); id != "" && !seen[id] {
			seen[id] = true
			uids = append(uids, id)
		}
	}
	if len(uids) > maxProfileBatch {
		http.Error(w, "too many uids", http.StatusBadRequest)
		return
	}
	users, err := s.userStore.GetUsers(r.Context(), uids)
	if err != nil {
		log.Printf("[server] profiles: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	out := make([]map[string]interface{}, 0, len(users))
	for _, u := range users {
		out = append(out, publicProfile(u))
	}
	writeJSON(w, map[string]interface{}{"users": out})
}

// HandleUserSearch 按 UID（精确）、邮箱（精确）或昵称（包含）搜索用户。
// GET /user/search?uid&token&q=xxx[&limit=20]
func (s *Server) HandleUserSearch(w http.ResponseWriter, r *http.Request) {
	if s.userStore == nil {
		http.Error(w, "user store not available", http.StatusServiceUnavailable)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	if s.router.checkRateLimit(claims.UID) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}
	q := strings.TrimSpace(r.FormValue("q"))
	if q == "" {
		writeJSON(w, map[string]interface{}{"users": []interface{}{}})
		return
	}
	if utf8.RuneCountInString(q) > 64 {
		http.Error(w, "query too long", http.StatusBadRequest)
		return
	}
	limit, _ := strconv.Atoi(r.FormValue("limit"))
	if limit <= 0 || limit > maxSearchLimit {
		limit = 20
	}
	users, err := s.userStore.SearchUsers(r.Context(), q, limit)
	if err != nil {
		log.Printf("[server] user search: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	out := make([]map[string]interface{}, 0, len(users))
	for _, u := range users {
		out = append(out, publicProfile(u))
	}
	writeJSON(w, map[string]interface{}{"users": out})
}

// HandleAvatar 公开提供头像图片，无需登录；文件 ID 不可变，可长期缓存。
// GET /avatar/{file_id}[?thumb=1]
func (s *Server) HandleAvatar(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.objectStore == nil || s.fileStore == nil {
		http.NotFound(w, r)
		return
	}
	id := strings.TrimPrefix(r.URL.Path, "/avatar/")
	fileID, err := strconv.ParseInt(id, 10, 64)
	if err != nil || fileID <= 0 {
		http.NotFound(w, r)
		return
	}
	ctx := r.Context()
	f, err := s.fileStore.GetFile(ctx, fileID)
	if err != nil {
		log.Printf("[server] avatar %d: %v", fileID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if f == nil || !f.IsAvatar || f.Status != repo.FileStatusReady {
		http.NotFound(w, r)
		return
	}
	key := id
	if r.URL.Query().Get("thumb") == "1" && f.HasThumb {
		key = id + "_thumb"
	}
	etag := `"` + key + `"`
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	data, contentType, err := s.objectStore.Get(ctx, key)
	if err != nil {
		log.Printf("[server] avatar %s read: %v", key, err)
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
	w.Header().Set("ETag", etag)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
}

// ---------- 辅助函数 ----------

func publicProfile(u *repo.User) map[string]interface{} {
	return map[string]interface{}{
		"uid":        u.UID,
		"nickname":   u.Nickname,
		"avatar_url": avatarURL(u.AvatarFileID),
	}
}

// avatarURL 返回相对于网关的头像路径，未设置头像时为空串。
func avatarURL(fileID int64) string {
	if fileID <= 0 {
		return ""
	}
	return "/avatar/" + strconv.FormatInt(fileID, 10)
}

func normalizeEmail(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 254 {
		return "", false
	}
	addr, err := mail.ParseAddress(raw)
	if err != nil || addr.Address != raw || addr.Name != "" {
		return "", false
	}
	at := strings.LastIndexByte(raw, '@')
	if at <= 0 || !strings.Contains(raw[at+1:], ".") {
		return "", false
	}
	return strings.ToLower(raw), true
}

func defaultNickname(email string) string {
	name := email
	if at := strings.IndexByte(email, '@'); at > 0 {
		name = email[:at]
	}
	if utf8.RuneCountInString(name) > maxNicknameLen {
		name = string([]rune(name)[:maxNicknameLen])
	}
	return name
}

func checkNickname(n string) string {
	if n == "" {
		return "nickname required"
	}
	if utf8.RuneCountInString(n) > maxNicknameLen {
		return "nickname too long"
	}
	for _, r := range n {
		if unicode.IsControl(r) {
			return "nickname contains invalid characters"
		}
	}
	return ""
}

func checkPassword(p string) string {
	if len(p) < minPasswordLen {
		return "password must be at least 6 characters"
	}
	if len(p) > maxPasswordLen {
		return "password too long"
	}
	return ""
}

func writeCodeError(w http.ResponseWriter, err error) {
	if errors.Is(err, ErrCodeInvalid) {
		http.Error(w, "invalid or expired verification code", http.StatusBadRequest)
		return
	}
	log.Printf("[server] verify code: %v", err)
	http.Error(w, "internal error", http.StatusInternalServerError)
}

// maskEmail 只保留邮箱首字符与域名，用于日志。
func maskEmail(email string) string {
	at := strings.IndexByte(email, '@')
	if at <= 0 {
		return "***"
	}
	return email[:1] + "***" + email[at:]
}

// clientIP 依次取 X-Real-IP、X-Forwarded-For 首项与 RemoteAddr，仅用于发送频率限制。
func clientIP(r *http.Request) string {
	if ip := strings.TrimSpace(r.Header.Get("X-Real-IP")); ip != "" {
		return ip
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i >= 0 {
			xff = xff[:i]
		}
		if ip := strings.TrimSpace(xff); ip != "" {
			return ip
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func verificationMail(purpose CodePurpose, code string) (subject, text, html string) {
	action := map[CodePurpose]string{
		PurposeRegister:      "注册 GO-IM 账号",
		PurposeResetPassword: "重置 GO-IM 密码",
		PurposeBindEmail:     "绑定 GO-IM 邮箱",
	}[purpose]
	minutes := strconv.Itoa(int(codeTTL.Minutes()))
	subject = "GO-IM 验证码：" + code
	text = "你正在" + action + "，验证码为：" + code + "\n\n验证码 " + minutes +
		" 分钟内有效，请勿告诉他人。如非本人操作，请忽略本邮件。\n"
	html = `<div style="font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;max-width:480px;margin:0 auto;padding:24px;color:#1f2329">` +
		`<h2 style="margin:0 0 16px;font-size:18px">GO-IM</h2>` +
		`<p style="margin:0 0 12px">你正在` + action + `，验证码为：</p>` +
		`<p style="margin:0 0 16px;font-size:32px;font-weight:600;letter-spacing:6px">` + code + `</p>` +
		`<p style="margin:0;color:#8f959e;font-size:13px">验证码 ` + minutes + ` 分钟内有效，请勿告诉他人。如非本人操作，请忽略本邮件。</p>` +
		`</div>`
	return
}
