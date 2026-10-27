package gateway

import (
	"context"
	"net/http"
	"strings"
	"time"
)

const maxPresenceBatch = 500

// SetPresence 启用跨网关的在线状态（Redis 位图）；未设置时只看本网关的连接。
func (s *Server) SetPresence(p Presence) {
	s.presence = p
}

// StartPresence 开始定时重建本网关的在线位图（未启用 Redis 位图时什么也不做）。
func (s *Server) StartPresence(ctx context.Context, interval time.Duration) {
	if rp, ok := s.presence.(*RedisPresence); ok {
		rp.Start(ctx, interval)
	}
}

func (s *Server) presenceOrLocal() Presence {
	if s.presence != nil {
		return s.presence
	}
	return localPresence{reg: s.clients}
}

// onlineUserCount 返回全集群在线用户数；无法统计时回退到本网关。
func (s *Server) onlineUserCount(ctx context.Context) int {
	if n, ok := s.presenceOrLocal().Count(ctx); ok {
		return int(n)
	}
	return s.clients.Count(ctx)
}

// HandlePresence 批量查询用户在线状态。
// GET /presence?uid=&token=&uids=100001,100002（最多 500 个）→ {"online":["100001"]}
func (s *Server) HandlePresence(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.authenticateRequest(w, r) == nil {
		return
	}
	var uids []string
	seen := make(map[string]bool)
	for _, uid := range strings.Split(r.URL.Query().Get("uids"), ",") {
		if uid = strings.TrimSpace(uid); uid != "" && !seen[uid] {
			seen[uid] = true
			uids = append(uids, uid)
		}
	}
	if len(uids) > maxPresenceBatch {
		http.Error(w, "too many uids", http.StatusBadRequest)
		return
	}
	online := s.presenceOrLocal().Online(r.Context(), uids)
	list := make([]string, 0, len(online))
	for _, uid := range uids {
		if online[uid] {
			list = append(list, uid)
		}
	}
	writeJSON(w, map[string]interface{}{"online": list})
}
