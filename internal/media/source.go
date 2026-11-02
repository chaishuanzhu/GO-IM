// Package media 实现服务端媒体库：多数据源（目前为 S3 兼容存储，后续 openlist / Emby / Jellyfin）、
// 影片列表、播放直链、刮削元数据、字幕与贴纸。访问密钥只存在服务端。
package media

import (
	"context"
	"errors"
	"io"
	"time"
)

// 哨兵错误。
var (
	ErrNotFound      = errors.New("media: not found")
	ErrUnknownSource = errors.New("media: unknown source")
	ErrInvalidID     = errors.New("media: invalid id")
	ErrUnsupported   = errors.New("media: not supported by source")
)

// SourceInfo 是返回给客户端的数据源描述。
type SourceInfo struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	Type         string   `json:"type"`
	Capabilities []string `json:"capabilities"` // 例如 "sidecar_subtitles"、"artwork"、"stickers"
}

// Item 是一部影片。ID 为全局唯一的不透明字符串（"<sourceId>:<nativeId>"）。
type Item struct {
	ID          string `json:"id"`
	SourceID    string `json:"source_id"`
	NativeID    string `json:"-"`
	Title       string `json:"title"`
	Year        string `json:"year,omitempty"`
	Overview    string `json:"overview,omitempty"`
	PosterURL   string `json:"poster_url,omitempty"`
	BackdropURL string `json:"backdrop_url,omitempty"`
	Format      string `json:"format"` // mp4 / m4v / mkv
	Size        int64  `json:"size,omitempty"`
}

// PlayInfo 是可直接交给播放器的地址。Headers 预留给需要请求头鉴权的源（Emby / Jellyfin）。
type PlayInfo struct {
	URL       string            `json:"url"`
	ExpiresAt int64             `json:"expires_at"` // unix 毫秒；0 = 不过期
	Format    string            `json:"format"`
	Headers   map[string]string `json:"headers,omitempty"`
}

// SubtitleRef 描述一条字幕。Ref 是不透明标识，用 GET /media/subtitle?ref= 取回内容。
type SubtitleRef struct {
	Ref      string `json:"ref"`
	Label    string `json:"label"`
	Language string `json:"language,omitempty"`
	Badge    string `json:"badge,omitempty"`
	Format   string `json:"format"`
	Origin   string `json:"origin"` // "sidecar" | "shared" | "online"
	Detail   string `json:"detail,omitempty"`
}

// Artwork 是数据源自带的元数据（S3 的 NFO / 海报 sidecar，Emby 的条目信息等）。
type Artwork struct {
	Title       string `json:"title,omitempty"`
	Year        string `json:"year,omitempty"`
	Overview    string `json:"overview,omitempty"`
	PosterURL   string `json:"poster_url,omitempty"`
	BackdropURL string `json:"backdrop_url,omitempty"`
}

// ListOptions 控制列表查询。
type ListOptions struct {
	Query   string
	Cursor  string
	Limit   int
	Refresh bool
}

// Page 是一页结果。NextCursor 为空表示没有更多。
type Page struct {
	Items      []Item `json:"items"`
	NextCursor string `json:"next_cursor,omitempty"`
}

// Source 是一个视频数据源。所有 nativeID 都是源内部标识（S3 为对象键）。
type Source interface {
	Info() SourceInfo
	List(ctx context.Context, opt ListOptions) (Page, error)
	Get(ctx context.Context, nativeID string) (Item, error)
	PlayURL(ctx context.Context, nativeID string) (PlayInfo, error)
	// Sidecars 返回影片旁的字幕；Ref 为源内标识（由 Registry 加上前缀）。
	Sidecars(ctx context.Context, nativeID string) ([]SubtitleRef, error)
	// OpenSidecar 读取 Sidecars 返回的字幕内容。
	OpenSidecar(ctx context.Context, nativeRef string) (io.ReadCloser, error)
}

// ArtworkSource 是可选能力：源自带元数据。
type ArtworkSource interface {
	Artwork(ctx context.Context, nativeID string) (Artwork, bool, error)
}

// FileSource 是可选能力：按相对路径读文件 / 签发直链（贴纸目录用）。
type FileSource interface {
	ReadFile(ctx context.Context, path string) ([]byte, error)
	FileURL(ctx context.Context, path string, ttl time.Duration) (string, error)
}
