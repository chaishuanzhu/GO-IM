package media

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// MetadataCache 缓存刮削结果（键为全局影片 ID）。
type MetadataCache interface {
	Get(ctx context.Context, itemID string) (Artwork, time.Time, bool)
	Put(ctx context.Context, itemID string, art Artwork) error
}

// MemoryMetadataCache 是进程内缓存（MySQL 未启用时）。
type MemoryMetadataCache struct {
	mu   sync.RWMutex
	data map[string]cachedArtwork
	now  func() time.Time
}

type cachedArtwork struct {
	art Artwork
	at  time.Time
}

// NewMemoryMetadataCache 创建进程内缓存。
func NewMemoryMetadataCache() *MemoryMetadataCache {
	return &MemoryMetadataCache{data: map[string]cachedArtwork{}, now: time.Now}
}

// Get 实现 MetadataCache。
func (c *MemoryMetadataCache) Get(_ context.Context, id string) (Artwork, time.Time, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	v, ok := c.data[id]
	return v.art, v.at, ok
}

// Put 实现 MetadataCache。
func (c *MemoryMetadataCache) Put(_ context.Context, id string, art Artwork) error {
	c.mu.Lock()
	c.data[id] = cachedArtwork{art: art, at: c.now()}
	c.mu.Unlock()
	return nil
}

// MySQLMetadataCache 把刮削结果存进 media_metadata 表，重启与多网关共享。
type MySQLMetadataCache struct{ db *sql.DB }

// NewMySQLMetadataCache 建表（若不存在）并返回缓存。
func NewMySQLMetadataCache(db *sql.DB) (*MySQLMetadataCache, error) {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS media_metadata (
			item_id    VARCHAR(512) NOT NULL,
			data       TEXT         NOT NULL,
			updated_at BIGINT       NOT NULL,
			PRIMARY KEY (item_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	if err != nil {
		return nil, fmt.Errorf("create media_metadata: %w", err)
	}
	return &MySQLMetadataCache{db: db}, nil
}

// Get 实现 MetadataCache。
func (c *MySQLMetadataCache) Get(ctx context.Context, id string) (Artwork, time.Time, bool) {
	var data string
	var updated int64
	err := c.db.QueryRowContext(ctx, `SELECT data, updated_at FROM media_metadata WHERE item_id = ?`, id).Scan(&data, &updated)
	if err != nil {
		return Artwork{}, time.Time{}, false
	}
	var art Artwork
	if json.Unmarshal([]byte(data), &art) != nil {
		return Artwork{}, time.Time{}, false
	}
	return art, time.UnixMilli(updated), true
}

// Put 实现 MetadataCache。
func (c *MySQLMetadataCache) Put(ctx context.Context, id string, art Artwork) error {
	data, _ := json.Marshal(art)
	_, err := c.db.ExecContext(ctx, `
		INSERT INTO media_metadata (item_id, data, updated_at) VALUES (?, ?, ?)
		ON DUPLICATE KEY UPDATE data = VALUES(data), updated_at = VALUES(updated_at)`,
		id, string(data), time.Now().UnixMilli())
	return err
}

// Metadata 合并源自带元数据（NFO / 海报）与 TMDB 刮削结果，并缓存。
type Metadata struct {
	cache    MetadataCache
	ttl      time.Duration
	tmdb     *TMDB
	now      func() time.Time
	inflight sync.Map // itemID → struct{}，同一影片只刮削一次
}

// NewMetadata 创建元数据服务；tmdb 为 nil 时只用源自带元数据。
func NewMetadata(cache MetadataCache, ttl time.Duration, tmdb *TMDB) *Metadata {
	if cache == nil {
		cache = NewMemoryMetadataCache()
	}
	if ttl <= 0 {
		ttl = 7 * 24 * time.Hour
	}
	return &Metadata{cache: cache, ttl: ttl, tmdb: tmdb, now: time.Now}
}

// Apply 用已缓存的元数据填充列表条目（不发起网络请求）。
func (m *Metadata) Apply(ctx context.Context, items []Item) {
	for i := range items {
		if art, at, ok := m.cache.Get(ctx, items[i].ID); ok && m.fresh(at, art) {
			merge(&items[i], art)
		}
	}
}

// Enrich 返回补全元数据的条目：缓存 → 源自带 → TMDB。
func (m *Metadata) Enrich(ctx context.Context, src Source, item Item) Item {
	if art, at, ok := m.cache.Get(ctx, item.ID); ok && m.fresh(at, art) {
		merge(&item, art)
		return item
	}
	if _, busy := m.inflight.LoadOrStore(item.ID, struct{}{}); busy {
		return item
	}
	defer m.inflight.Delete(item.ID)

	var art Artwork
	if as, ok := src.(ArtworkSource); ok {
		if a, found, err := as.Artwork(ctx, item.NativeID); err == nil && found {
			art = a
		}
	}
	if m.tmdb != nil && (art.PosterURL == "" || art.Overview == "") {
		title, year := item.Title, item.Year
		if art.Title != "" {
			title = art.Title
		}
		if art.Year != "" {
			year = art.Year
		}
		if t, ok := m.tmdb.Search(ctx, title, year); ok {
			art = fillMissing(art, t)
		}
	}
	_ = m.cache.Put(ctx, item.ID, art)
	merge(&item, art)
	return item
}

// 源自带的海报是预签名地址（最长 7 天），缓存超过 6 天就重新取，避免返回过期链接。
// 没刮到任何内容的空结果只缓存 1 小时（可能是 TMDB 暂时不可用）。
func (m *Metadata) fresh(at time.Time, art Artwork) bool {
	age := m.now().Sub(at)
	if age >= m.ttl {
		return false
	}
	if art == (Artwork{}) {
		return age < time.Hour
	}
	signed := strings.Contains(art.PosterURL, "X-Amz-Signature") || strings.Contains(art.BackdropURL, "X-Amz-Signature")
	return !signed || age < 6*24*time.Hour
}

func merge(item *Item, art Artwork) {
	if art.Title != "" {
		item.Title = art.Title
	}
	if art.Year != "" {
		item.Year = art.Year
	}
	if art.Overview != "" {
		item.Overview = art.Overview
	}
	if art.PosterURL != "" {
		item.PosterURL = art.PosterURL
	}
	if art.BackdropURL != "" {
		item.BackdropURL = art.BackdropURL
	}
}

func fillMissing(base, extra Artwork) Artwork {
	if base.Title == "" {
		base.Title = extra.Title
	}
	if base.Year == "" {
		base.Year = extra.Year
	}
	if base.Overview == "" {
		base.Overview = extra.Overview
	}
	if base.PosterURL == "" {
		base.PosterURL = extra.PosterURL
	}
	if base.BackdropURL == "" {
		base.BackdropURL = extra.BackdropURL
	}
	return base
}

// TMDB 是最小的 TMDB 搜索客户端。
type TMDB struct {
	Token    string
	Language string
	BaseURL  string // 默认 https://api.themoviedb.org/3（测试可替换）
	Client   *http.Client
}

// Search 按片名（与年份）搜索，返回第一条匹配结果。
func (t *TMDB) Search(ctx context.Context, title, year string) (Artwork, bool) {
	title = strings.TrimSpace(title)
	if t == nil || t.Token == "" || title == "" {
		return Artwork{}, false
	}
	base := t.BaseURL
	if base == "" {
		base = "https://api.themoviedb.org/3"
	}
	q := url.Values{"query": {title}, "include_adult": {"false"}, "page": {"1"}}
	if t.Language != "" {
		q.Set("language", t.Language)
	}
	if year != "" {
		q.Set("primary_release_year", year)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/search/movie?"+q.Encode(), nil)
	if err != nil {
		return Artwork{}, false
	}
	req.Header.Set("Authorization", "Bearer "+t.Token)
	req.Header.Set("Accept", "application/json")
	client := t.Client
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	resp, err := client.Do(req)
	if err != nil {
		slog.Warn("tmdb search failed", "error", err)
		return Artwork{}, false
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		slog.Warn("tmdb search http error", "status", resp.StatusCode)
		return Artwork{}, false
	}
	var payload struct {
		Results []struct {
			Title         string `json:"title"`
			OriginalTitle string `json:"original_title"`
			Overview      string `json:"overview"`
			ReleaseDate   string `json:"release_date"`
			PosterPath    string `json:"poster_path"`
			BackdropPath  string `json:"backdrop_path"`
		} `json:"results"`
	}
	if json.NewDecoder(resp.Body).Decode(&payload) != nil {
		return Artwork{}, false
	}
	for _, r := range payload.Results {
		ry := ""
		if len(r.ReleaseDate) >= 4 {
			ry = r.ReleaseDate[:4]
		}
		if year != "" && ry != year {
			continue
		}
		name := r.Title
		if name == "" {
			name = r.OriginalTitle
		}
		return Artwork{
			Title:       name,
			Year:        ry,
			Overview:    strings.TrimSpace(r.Overview),
			PosterURL:   tmdbImage(r.PosterPath, "w500"),
			BackdropURL: tmdbImage(r.BackdropPath, "w1280"),
		}, true
	}
	return Artwork{}, false
}

func tmdbImage(p, size string) string {
	if !strings.HasPrefix(p, "/") {
		return ""
	}
	return "https://image.tmdb.org/t/p/" + size + p
}
