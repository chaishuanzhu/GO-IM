package gateway

import (
	"context"
	"log"
	"net/http"
	"strconv"
	"time"
)

// SetSettings 启用管理后台可改的系统设置；未设置时注册始终开放，多端策略取启动配置。
func (s *Server) SetSettings(m *SettingsManager) {
	s.settings = m
}

// StartSettingsRefresh 让其他网关实例在管理后台的修改后一个周期内生效。
func (s *Server) StartSettingsRefresh(ctx context.Context, interval time.Duration) {
	if s.settings != nil {
		s.settings.Start(ctx, interval)
	}
}

func (s *Server) registrationOpen() bool {
	return s.settings == nil || s.settings.Get().RegistrationOpen
}

// HandleAuthConfig 返回登录页需要的公开设置。
// GET /auth/config
func (s *Server) HandleAuthConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, map[string]interface{}{"registration_open": s.registrationOpen()})
}

// HandleAdminSettings 查看或修改系统设置。
// GET  /admin/settings
// POST /admin/settings 表单字段（均可选）:multi_device(single / per_platform / unlimited)、registration_open(true / false)
func (s *Server) HandleAdminSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	claims := s.adminAuth(w, r)
	if claims == nil {
		return
	}
	if s.settings == nil {
		http.Error(w, "settings not available", http.StatusServiceUnavailable)
		return
	}
	if r.Method == http.MethodGet {
		writeJSON(w, s.settings.Get())
		return
	}

	var policy *DevicePolicy
	if v := r.FormValue("multi_device"); v != "" {
		p := DevicePolicy(v)
		if p != DevicePolicySingle && p != DevicePolicyPerPlatform && p != DevicePolicyUnlimited {
			http.Error(w, "multi_device must be single, per_platform or unlimited", http.StatusBadRequest)
			return
		}
		policy = &p
	}
	var open *bool
	if v := r.FormValue("registration_open"); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			http.Error(w, "registration_open must be true or false", http.StatusBadRequest)
			return
		}
		open = &b
	}

	next, err := s.settings.Update(r.Context(), policy, open)
	if err != nil {
		log.Printf("[server] admin update settings: %v", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	log.Printf("[server] admin %s updated settings: multi_device=%s registration_open=%t", claims.UID, next.MultiDevice, next.RegistrationOpen)
	writeJSON(w, next)
}
