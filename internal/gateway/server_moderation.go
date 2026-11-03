package gateway

import (
	"context"
	"errors"
	"log"
	"net/http"
	"strconv"

	"github.com/im/internal/moderation"
	"github.com/im/internal/pkg/jwt"
)

// SetModeration 注入屏蔽 / 举报服务。
func (s *Server) SetModeration(svc *moderation.Service) {
	s.moderation = svc
}

func (s *Server) moderationAuth(w http.ResponseWriter, r *http.Request, method string, admin bool) *jwt.Claims {
	if r.Method != method {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return nil
	}
	if s.moderation == nil {
		http.Error(w, "moderation not available", http.StatusServiceUnavailable)
		return nil
	}
	if admin {
		return s.adminAuth(w, r)
	}
	return s.authenticateRequest(w, r)
}

func writeModerationError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, moderation.ErrInvalid):
		http.Error(w, "target_uid required", http.StatusBadRequest)
	case errors.Is(err, moderation.ErrSelf):
		http.Error(w, "cannot target yourself", http.StatusBadRequest)
	case errors.Is(err, moderation.ErrTooManyBlock):
		http.Error(w, "block list full", http.StatusConflict)
	case errors.Is(err, moderation.ErrNotFound):
		http.Error(w, "report not found", http.StatusNotFound)
	default:
		log.Printf("[server] moderation error: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
	}
}

// HandleUserBlock 屏蔽某个用户（幂等）。屏蔽只影响自己的客户端展示，对方无感知。
// POST /user/block  (uid, token, target_uid)
func (s *Server) HandleUserBlock(w http.ResponseWriter, r *http.Request) {
	claims := s.moderationAuth(w, r, http.MethodPost, false)
	if claims == nil {
		return
	}
	if err := s.moderation.Block(r.Context(), claims.UID, r.FormValue("target_uid")); err != nil {
		writeModerationError(w, err)
		return
	}
	writeJSON(w, map[string]string{"status": "ok"})
}

// HandleUserUnblock 取消屏蔽（幂等）。
// POST /user/unblock  (uid, token, target_uid)
func (s *Server) HandleUserUnblock(w http.ResponseWriter, r *http.Request) {
	claims := s.moderationAuth(w, r, http.MethodPost, false)
	if claims == nil {
		return
	}
	if err := s.moderation.Unblock(r.Context(), claims.UID, r.FormValue("target_uid")); err != nil {
		writeModerationError(w, err)
		return
	}
	writeJSON(w, map[string]string{"status": "ok"})
}

// HandleUserBlocks 返回自己屏蔽的 UID 列表。
// GET /user/blocks?uid&token
func (s *Server) HandleUserBlocks(w http.ResponseWriter, r *http.Request) {
	claims := s.moderationAuth(w, r, http.MethodGet, false)
	if claims == nil {
		return
	}
	ids, err := s.moderation.Blocked(r.Context(), claims.UID)
	if err != nil {
		writeModerationError(w, err)
		return
	}
	writeJSON(w, map[string]interface{}{"blocked": ids})
}

// HandleUserReport 举报某个用户（通常带上被举报的消息）。同一条消息重复举报返回同一个 id。
// POST /user/report  (uid, token, target_uid, content?, message_id?, context?)
func (s *Server) HandleUserReport(w http.ResponseWriter, r *http.Request) {
	claims := s.moderationAuth(w, r, http.MethodPost, false)
	if claims == nil {
		return
	}
	id, err := s.moderation.Report(r.Context(), claims.UID, r.FormValue("target_uid"),
		r.FormValue("message_id"), r.FormValue("context"), r.FormValue("content"))
	if err != nil {
		writeModerationError(w, err)
		return
	}
	writeJSON(w, map[string]interface{}{"status": "ok", "id": id})
}

type adminReportView struct {
	moderation.Report
	ReporterNickname string `json:"reporter_nickname"`
	TargetNickname   string `json:"target_nickname"`
	TargetDisabled   bool   `json:"target_disabled"`
}

// HandleAdminReports 分页列出举报（新的在前），附带双方昵称与被举报人是否已封禁。
// GET /admin/reports?uid&token&status=pending|handled|all&offset&limit
func (s *Server) HandleAdminReports(w http.ResponseWriter, r *http.Request) {
	if s.moderationAuth(w, r, http.MethodGet, true) == nil {
		return
	}
	q := r.URL.Query()
	offset, limit := 0, 50
	if v, err := strconv.Atoi(q.Get("offset")); err == nil && v >= 0 {
		offset = v
	}
	if v, err := strconv.Atoi(q.Get("limit")); err == nil && v > 0 && v <= 200 {
		limit = v
	}
	reports, total, pending, err := s.moderation.Reports(r.Context(), q.Get("status"), offset, limit)
	if err != nil {
		if errors.Is(err, moderation.ErrInvalid) {
			http.Error(w, "status must be pending, handled or all", http.StatusBadRequest)
			return
		}
		writeModerationError(w, err)
		return
	}

	type userInfo struct {
		nickname string
		disabled bool
	}
	users := map[string]userInfo{}
	lookup := func(uid string) userInfo {
		if u, ok := users[uid]; ok {
			return u
		}
		info := userInfo{}
		if s.userStore != nil {
			if u, err := s.userStore.GetByUID(r.Context(), uid); err == nil && u != nil {
				info = userInfo{nickname: u.Nickname, disabled: u.IsDisabled}
			}
		}
		users[uid] = info
		return info
	}
	views := make([]adminReportView, 0, len(reports))
	for _, rep := range reports {
		target := lookup(rep.TargetUID)
		views = append(views, adminReportView{
			Report:           rep,
			ReporterNickname: lookup(rep.ReporterUID).nickname,
			TargetNickname:   target.nickname,
			TargetDisabled:   target.disabled,
		})
	}
	writeJSON(w, map[string]interface{}{
		"reports": views,
		"total":   total,
		"pending": pending,
		"offset":  offset,
		"limit":   limit,
	})
}

// HandleAdminReportResolve 处理一条举报：ignore 只标记这条；ban 禁用被举报账号（立即下线、无法登录），
// 并把针对该用户的其他待处理举报一并标记为已封禁。解封走 /admin/users/disable。
// POST /admin/reports/resolve  (uid, token, id, action=ignore|ban)
func (s *Server) HandleAdminReportResolve(w http.ResponseWriter, r *http.Request) {
	claims := s.moderationAuth(w, r, http.MethodPost, true)
	if claims == nil {
		return
	}
	id, err := strconv.ParseInt(r.FormValue("id"), 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}
	switch r.FormValue("action") {
	case "ignore":
		if err := s.moderation.Ignore(r.Context(), id, claims.UID); err != nil {
			writeModerationError(w, err)
			return
		}
		writeJSON(w, map[string]string{"status": "ok", "report_status": moderation.StatusIgnored})
	case "ban":
		rep, err := s.moderation.Get(r.Context(), id)
		if err != nil {
			writeModerationError(w, err)
			return
		}
		if rep.TargetUID == claims.UID {
			http.Error(w, "cannot ban yourself", http.StatusBadRequest)
			return
		}
		if s.userStore == nil {
			http.Error(w, "user store not available", http.StatusServiceUnavailable)
			return
		}
		u, err := s.userStore.GetByUID(r.Context(), rep.TargetUID)
		if err != nil {
			log.Printf("[server] ban lookup %s: %v", rep.TargetUID, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if u == nil {
			http.Error(w, "user not found", http.StatusNotFound)
			return
		}
		if u.Role == "admin" || s.adminUIDs[u.UID] {
			http.Error(w, "cannot ban an admin", http.StatusForbidden)
			return
		}
		if err := s.userStore.UpdateUserDisabled(r.Context(), u.UID, true); err != nil {
			log.Printf("[server] ban %s: %v", u.UID, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		s.kickUser(context.WithoutCancel(r.Context()), u.UID, kickAccountDisabled)
		if err := s.moderation.MarkBanned(r.Context(), rep, claims.UID); err != nil {
			writeModerationError(w, err)
			return
		}
		writeJSON(w, map[string]string{"status": "ok", "report_status": moderation.StatusBanned})
	default:
		http.Error(w, "action must be ignore or ban", http.StatusBadRequest)
	}
}
