package gateway

import (
	"context"
	"log"
	"strconv"
	"sync"
	"time"

	"github.com/im/internal/repo"
)

const (
	settingMultiDevice      = "multi_device"
	settingRegistrationOpen = "registration_open"
)

// SystemSettings 是管理后台可改的系统设置。
type SystemSettings struct {
	MultiDevice      DevicePolicy `json:"multi_device"`
	RegistrationOpen bool         `json:"registration_open"`
}

// SettingsManager 以配置文件的值为默认，叠加数据库中管理员改过的设置。
// 多网关部署时每个实例定时 Refresh，修改在一个刷新周期内生效；处理修改请求的实例立即生效。
type SettingsManager struct {
	store    repo.SettingsStore // nil = 只在本进程内存中生效
	defaults SystemSettings
	onChange func(SystemSettings)

	mu  sync.RWMutex
	cur SystemSettings
}

// NewSettingsManager 创建设置管理器并立即应用默认值（调用一次 onChange）。
func NewSettingsManager(defaults SystemSettings, store repo.SettingsStore, onChange func(SystemSettings)) *SettingsManager {
	m := &SettingsManager{store: store, defaults: defaults, onChange: onChange, cur: defaults}
	if onChange != nil {
		onChange(defaults)
	}
	return m
}

// Get 返回当前设置。
func (m *SettingsManager) Get() SystemSettings {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.cur
}

// Refresh 从数据库重新读取设置；读取失败时保持当前值。
func (m *SettingsManager) Refresh(ctx context.Context) error {
	if m.store == nil {
		return nil
	}
	values, err := m.store.GetSettings(ctx)
	if err != nil {
		return err
	}
	m.apply(m.merge(m.defaults, values))
	return nil
}

// Update 写入修改并立即在本实例生效；nil 字段表示不修改。
func (m *SettingsManager) Update(ctx context.Context, multiDevice *DevicePolicy, registrationOpen *bool) (SystemSettings, error) {
	values := make(map[string]string, 2)
	if multiDevice != nil {
		values[settingMultiDevice] = string(*multiDevice)
	}
	if registrationOpen != nil {
		values[settingRegistrationOpen] = strconv.FormatBool(*registrationOpen)
	}
	if m.store != nil && len(values) > 0 {
		if err := m.store.PutSettings(ctx, values); err != nil {
			return m.Get(), err
		}
	}
	next := m.merge(m.Get(), values)
	m.apply(next)
	return next, nil
}

// Start 每隔 interval 刷新一次，直到 ctx 结束。启动时先同步刷新一次。
func (m *SettingsManager) Start(ctx context.Context, interval time.Duration) {
	if m.store == nil {
		return
	}
	if err := m.Refresh(ctx); err != nil {
		log.Printf("[settings] initial load failed, using config defaults: %v", err)
	}
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := m.Refresh(ctx); err != nil {
					log.Printf("[settings] refresh failed: %v", err)
				}
			}
		}
	}()
}

func (m *SettingsManager) merge(base SystemSettings, values map[string]string) SystemSettings {
	if v, ok := values[settingMultiDevice]; ok {
		base.MultiDevice = ParseDevicePolicy(v)
	}
	if v, ok := values[settingRegistrationOpen]; ok {
		if b, err := strconv.ParseBool(v); err == nil {
			base.RegistrationOpen = b
		}
	}
	return base
}

func (m *SettingsManager) apply(next SystemSettings) {
	m.mu.Lock()
	changed := next != m.cur
	m.cur = next
	m.mu.Unlock()
	if !changed {
		return
	}
	log.Printf("[settings] multi_device=%s registration_open=%t", next.MultiDevice, next.RegistrationOpen)
	if m.onChange != nil {
		m.onChange(next)
	}
}
