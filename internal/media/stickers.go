package media

import (
	"context"
	"encoding/json"
	"path"
	"strings"
	"sync"
	"time"
)

// Stickers 从数据源的贴纸目录读取 catalog.json / {pack}/pack.json，并为每张贴纸签发直链。
type Stickers struct {
	src    FileSource
	prefix string
	ttl    time.Duration
	now    func() time.Time

	mu    sync.Mutex
	packs map[string]cachedPack
}

type cachedPack struct {
	data json.RawMessage
	at   time.Time
}

// 贴纸直链有效 7 天，pack 响应缓存 1 小时。
const (
	stickerURLTTL   = 7 * 24 * time.Hour
	stickerCacheTTL = time.Hour
)

// NewStickers 创建贴纸服务；prefix 为空时用 "stickers/"。
func NewStickers(src FileSource, prefix string) *Stickers {
	prefix = strings.Trim(prefix, "/")
	if prefix == "" {
		prefix = "stickers"
	}
	return &Stickers{src: src, prefix: prefix + "/", ttl: stickerURLTTL, now: time.Now, packs: map[string]cachedPack{}}
}

// Catalog 原样返回 catalog.json（贴纸包列表）。
func (s *Stickers) Catalog(ctx context.Context) (json.RawMessage, error) {
	data, err := s.src.ReadFile(ctx, s.prefix+"catalog.json")
	if err != nil {
		return nil, err
	}
	if !json.Valid(data) {
		return nil, ErrNotFound
	}
	return data, nil
}

// Pack 返回 pack.json，并给每张贴纸补上 url，给封面补上 cover_url。
func (s *Stickers) Pack(ctx context.Context, packID string) (json.RawMessage, error) {
	packID = sanitizePackID(packID)
	s.mu.Lock()
	if c, ok := s.packs[packID]; ok && s.now().Sub(c.at) < stickerCacheTTL {
		s.mu.Unlock()
		return c.data, nil
	}
	s.mu.Unlock()

	raw, err := s.src.ReadFile(ctx, s.prefix+packID+"/pack.json")
	if err != nil {
		return nil, err
	}
	var pack map[string]any
	if err := json.Unmarshal(raw, &pack); err != nil {
		return nil, ErrNotFound
	}
	sign := func(file string) string {
		file = strings.ReplaceAll(strings.ReplaceAll(file, "/", "_"), "\\", "_")
		u, err := s.src.FileURL(ctx, path.Join(s.prefix, packID, file), s.ttl)
		if err != nil {
			return ""
		}
		return u
	}
	if cover, ok := pack["cover"].(string); ok && cover != "" {
		pack["cover_url"] = sign(cover)
	}
	if stickers, ok := pack["stickers"].([]any); ok {
		for _, st := range stickers {
			if m, ok := st.(map[string]any); ok {
				if file, ok := m["file"].(string); ok && file != "" {
					m["url"] = sign(file)
				}
			}
		}
	}
	out, err := json.Marshal(pack)
	if err != nil {
		return nil, err
	}
	s.mu.Lock()
	s.packs[packID] = cachedPack{data: out, at: s.now()}
	s.mu.Unlock()
	return out, nil
}

func sanitizePackID(v string) string {
	out := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' || r == '.' || r > 127 {
			return r
		}
		return '_'
	}, strings.TrimSpace(v))
	if out == "" || out == "." || out == ".." {
		return "pack"
	}
	return out
}
