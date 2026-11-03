package serverapi

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/im/configs"
)

// appStatusRefresh 是从 apps 表重新读取启用状态的间隔。
const appStatusRefresh = 30 * time.Second

// Registry 保存接入方凭证。密钥只来自配置 / 环境变量，从不落库；apps 表只存元数据、
// 密钥摘要（核对多实例配置一致）和启用状态（status=0 即停用，无需重启）。
type Registry struct {
	mu    sync.RWMutex
	apps  map[string]*App
	store *AppStore
}

// NewRegistry 从配置构建接入方表，跳过缺少 app_id 或密钥的条目。
func NewRegistry(cfg []configs.ServerAppConfig) *Registry {
	r := &Registry{apps: make(map[string]*App)}
	for _, c := range cfg {
		if c.AppID == "" || c.Secret == "" {
			log.Printf("[serverapi] app %q skipped: app_id and secret are required", c.AppID)
			continue
		}
		ttl := time.Duration(c.BeforeSendTimeout)
		if ttl <= 0 {
			ttl = 500 * time.Millisecond
		}
		r.apps[c.AppID] = &App{
			ID:            c.AppID,
			Secret:        c.Secret,
			WebhookURL:    c.WebhookURL,
			WebhookSecret: c.WebhookSecret,
			WebhookEvents: c.WebhookEvents,
			BeforeSendURL: c.BeforeSendURL,
			BeforeSendTTL: ttl,
		}
	}
	return r
}

// Get 返回接入方，不存在返回 nil。返回值只读。
func (r *Registry) Get(id string) *App {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.apps[id]
}

// All 返回全部接入方（含已停用的）。
func (r *Registry) All() []*App {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*App, 0, len(r.apps))
	for _, a := range r.apps {
		out = append(out, a)
	}
	return out
}

// Len 返回接入方数量。
func (r *Registry) Len() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.apps)
}

// AttachStore 把配置同步进 apps 表并读取启用状态。
func (r *Registry) AttachStore(ctx context.Context, store *AppStore) error {
	if err := store.Sync(ctx, r.All()); err != nil {
		return err
	}
	r.store = store
	r.refreshStatus(ctx)
	return nil
}

// StartRefresh 在后台定期重读 apps 表的启用状态，直到 ctx 结束；未接 apps 表时什么也不做。
func (r *Registry) StartRefresh(ctx context.Context) {
	if r.store == nil {
		return
	}
	go func() {
		t := time.NewTicker(appStatusRefresh)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				r.refreshStatus(ctx)
			}
		}
	}()
}

func (r *Registry) refreshStatus(ctx context.Context) {
	disabled, err := r.store.Disabled(ctx)
	if err != nil {
		log.Printf("[serverapi] refresh app status: %v", err)
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	for id, a := range r.apps {
		if a.Disabled != disabled[id] {
			cp := *a
			cp.Disabled = disabled[id]
			r.apps[id] = &cp
		}
	}
}

// AppStore 是 apps 表。
type AppStore struct{ db *sql.DB }

// NewAppStore 建表（若不存在）。
func NewAppStore(db *sql.DB) (*AppStore, error) {
	_, err := db.Exec(`CREATE TABLE IF NOT EXISTS apps (
		app_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
		secret_hash    CHAR(64)     NOT NULL,
		webhook_url    VARCHAR(512) NOT NULL DEFAULT '',
		webhook_secret_hash CHAR(64) NOT NULL DEFAULT '',
		status         TINYINT      NOT NULL DEFAULT 1,
		created_at     BIGINT       NOT NULL,
		updated_at     BIGINT       NOT NULL
	) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	if err != nil {
		return nil, fmt.Errorf("create apps table: %w", err)
	}
	return &AppStore{db: db}, nil
}

func secretHash(s string) string {
	if s == "" {
		return ""
	}
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

// Sync 写入或更新配置里的接入方；已有行保留 status，密钥摘要变化时记日志（多实例配置不一致或刚轮换）。
func (s *AppStore) Sync(ctx context.Context, apps []*App) error {
	now := time.Now().UnixMilli()
	for _, a := range apps {
		hash := secretHash(a.Secret)
		var old string
		err := s.db.QueryRowContext(ctx, "SELECT secret_hash FROM apps WHERE app_id = ?", a.ID).Scan(&old)
		if err == nil && old != hash {
			log.Printf("[serverapi] app %s: secret differs from the stored hash (rotated, or instances disagree)", a.ID)
		}
		_, err = s.db.ExecContext(ctx, `INSERT INTO apps (app_id, secret_hash, webhook_url, webhook_secret_hash, status, created_at, updated_at)
			VALUES (?, ?, ?, ?, 1, ?, ?)
			ON DUPLICATE KEY UPDATE secret_hash = VALUES(secret_hash), webhook_url = VALUES(webhook_url),
				webhook_secret_hash = VALUES(webhook_secret_hash), updated_at = VALUES(updated_at)`,
			a.ID, hash, a.WebhookURL, secretHash(a.WebhookSecret), now, now)
		if err != nil {
			return fmt.Errorf("sync app %s: %w", a.ID, err)
		}
	}
	return nil
}

// Disabled 返回 status=0 的接入方。
func (s *AppStore) Disabled(ctx context.Context) (map[string]bool, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT app_id FROM apps WHERE status = 0")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}
