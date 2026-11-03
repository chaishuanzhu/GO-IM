package media

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"path"
	"strconv"
	"strings"
	"time"
)

// BlobStore 是服务器自有的对象存储（GO-IM 的 RustFS / 内存实现），存放共享与在线下载的字幕。
type BlobStore interface {
	Put(ctx context.Context, key string, data []byte, contentType string) error
	Get(ctx context.Context, key string) ([]byte, string, error)
}

// 字幕 ref 前缀：src:<sourceId>:<nativeRef> 为数据源 sidecar；blob:<key> 为服务器存储。
const (
	refSourcePrefix = "src:"
	refBlobPrefix   = "blob:"
	blobRoot        = "paircast/subtitles/"

	// MaxSubtitleBytes 是字幕文件上限。
	MaxSubtitleBytes = 2 << 20
)

// Subtitles 负责字幕列表、读取、共享与在线搜索。
type Subtitles struct {
	registry *Registry
	blobs    BlobStore
	os       *OpenSubtitles
	newID    func() string
}

// NewSubtitles 创建字幕服务；os 为 nil 时不提供在线搜索。
func NewSubtitles(registry *Registry, blobs BlobStore, os *OpenSubtitles, newID func() string) *Subtitles {
	return &Subtitles{registry: registry, blobs: blobs, os: os, newID: newID}
}

// OnlineEnabled 表示服务端配置了 OpenSubtitles。
func (s *Subtitles) OnlineEnabled() bool { return s.os != nil && s.os.APIKey != "" }

// Sidecars 列出影片旁的字幕，ref 已加上全局前缀。
func (s *Subtitles) Sidecars(ctx context.Context, itemID string) ([]SubtitleRef, error) {
	src, native, err := s.registry.Resolve(itemID)
	if err != nil {
		return nil, err
	}
	refs, err := src.Sidecars(ctx, native)
	if err != nil {
		return nil, err
	}
	for i := range refs {
		refs[i].Ref = refSourcePrefix + src.Info().ID + ":" + refs[i].Ref
	}
	return refs, nil
}

// Open 读取字幕内容（不超过 MaxSubtitleBytes）。
func (s *Subtitles) Open(ctx context.Context, ref string) ([]byte, string, error) {
	switch {
	case strings.HasPrefix(ref, refSourcePrefix):
		sourceID, native, ok := strings.Cut(strings.TrimPrefix(ref, refSourcePrefix), ":")
		if !ok {
			return nil, "", ErrInvalidID
		}
		src, err := s.registry.Source(sourceID)
		if err != nil || sourceID == "" {
			return nil, "", ErrUnknownSource
		}
		rc, err := src.OpenSidecar(ctx, native)
		if err != nil {
			return nil, "", err
		}
		defer rc.Close()
		data, err := io.ReadAll(io.LimitReader(rc, MaxSubtitleBytes+1))
		if err != nil {
			return nil, "", err
		}
		if len(data) > MaxSubtitleBytes {
			return nil, "", errors.New("subtitle too large")
		}
		return data, SubtitleExt(native), nil
	case strings.HasPrefix(ref, refBlobPrefix):
		key := strings.TrimPrefix(ref, refBlobPrefix)
		if !strings.HasPrefix(key, blobRoot) || strings.Contains(key, "..") || SubtitleExt(key) == "" {
			return nil, "", ErrInvalidID
		}
		if s.blobs == nil {
			return nil, "", ErrNotFound
		}
		data, _, err := s.blobs.Get(ctx, key)
		if err != nil {
			return nil, "", ErrNotFound
		}
		return data, SubtitleExt(key), nil
	}
	return nil, "", ErrInvalidID
}

// Share 保存房主共享的字幕，返回 blob ref。
func (s *Subtitles) Share(ctx context.Context, roomID, fileName string, data []byte) (string, error) {
	if s.blobs == nil {
		return "", ErrUnsupported
	}
	if len(data) == 0 || len(data) > MaxSubtitleBytes {
		return "", errors.New("invalid subtitle size")
	}
	ext := SubtitleExt(fileName)
	if ext == "" {
		ext = "srt"
	}
	key := fmt.Sprintf("%sshared/%s/%s.%s", blobRoot, safeSegment(roomID), s.newID(), ext)
	if err := s.blobs.Put(ctx, key, data, subtitleContentType(ext)); err != nil {
		return "", err
	}
	return refBlobPrefix + key, nil
}

// Search 在线搜索字幕。
func (s *Subtitles) Search(ctx context.Context, query, year string) ([]SubtitleRef, error) {
	if !s.OnlineEnabled() {
		return nil, ErrUnsupported
	}
	return s.os.Search(ctx, query, year)
}

// Fetch 下载在线字幕到服务器存储（已下载过则复用），返回 blob ref。
func (s *Subtitles) Fetch(ctx context.Context, onlineRef string) (string, error) {
	if !s.OnlineEnabled() || s.blobs == nil {
		return "", ErrUnsupported
	}
	fileID, err := strconv.ParseInt(strings.TrimPrefix(onlineRef, "os:"), 10, 64)
	if err != nil || !strings.HasPrefix(onlineRef, "os:") {
		return "", ErrInvalidID
	}
	prefix := fmt.Sprintf("%sonline/%d.", blobRoot, fileID)
	for _, ext := range []string{"srt", "ass", "vtt", "ssa"} {
		if _, _, err := s.blobs.Get(ctx, prefix+ext); err == nil {
			return refBlobPrefix + prefix + ext, nil
		}
	}
	data, name, err := s.os.Download(ctx, fileID)
	if err != nil {
		return "", err
	}
	ext := SubtitleExt(name)
	if ext == "" {
		ext = "srt"
	}
	if err := s.blobs.Put(ctx, prefix+ext, data, subtitleContentType(ext)); err != nil {
		return "", err
	}
	return refBlobPrefix + prefix + ext, nil
}

func subtitleContentType(ext string) string {
	switch ext {
	case "vtt":
		return "text/vtt"
	case "ass", "ssa":
		return "text/x-ssa"
	}
	return "application/x-subrip"
}

func safeSegment(s string) string {
	out := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '-' || r == '_' {
			return r
		}
		return '_'
	}, s)
	if out == "" {
		return "room"
	}
	return out
}

// OpenSubtitles 是 OpenSubtitles REST v1 的最小客户端（API Key 只在服务端）。
type OpenSubtitles struct {
	APIKey    string
	UserAgent string
	BaseURL   string // 默认 https://api.opensubtitles.com/api/v1
	Client    *http.Client
}

func (o *OpenSubtitles) base() string {
	if o.BaseURL != "" {
		return o.BaseURL
	}
	return "https://api.opensubtitles.com/api/v1"
}

func (o *OpenSubtitles) client() *http.Client {
	if o.Client != nil {
		return o.Client
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func (o *OpenSubtitles) do(req *http.Request) (*http.Response, error) {
	req.Header.Set("Api-Key", o.APIKey)
	ua := o.UserAgent
	if ua == "" {
		ua = "Paircast v2.1"
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "application/json")
	return o.client().Do(req)
}

// Search 搜索中英文字幕，按下载量排序。
func (o *OpenSubtitles) Search(ctx context.Context, query, year string) ([]SubtitleRef, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []SubtitleRef{}, nil
	}
	q := url.Values{
		"query":           {query},
		"languages":       {"zh-cn,zh-tw,en"},
		"order_by":        {"download_count"},
		"order_direction": {"desc"},
	}
	if year != "" {
		q.Set("year", year)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, o.base()+"/subtitles?"+q.Encode(), nil)
	if err != nil {
		return nil, err
	}
	resp, err := o.do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, fmt.Errorf("opensubtitles search: HTTP %d", resp.StatusCode)
	}
	var payload struct {
		Data []struct {
			Attributes struct {
				Language      string `json:"language"`
				Release       string `json:"release"`
				DownloadCount int    `json:"download_count"`
				Files         []struct {
					FileID   int64  `json:"file_id"`
					FileName string `json:"file_name"`
				} `json:"files"`
			} `json:"attributes"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	out := make([]SubtitleRef, 0, len(payload.Data))
	for _, d := range payload.Data {
		if len(d.Attributes.Files) == 0 {
			continue
		}
		f := d.Attributes.Files[0]
		name := f.FileName
		if name == "" {
			name = d.Attributes.Release
		}
		if name == "" {
			name = fmt.Sprintf("Subtitle %d", f.FileID)
		}
		format := strings.ToUpper(strings.TrimPrefix(path.Ext(name), "."))
		if format == "" {
			format = "SRT"
		}
		_, badge := GuessLanguage(name + "." + d.Attributes.Language)
		if badge == "" {
			badge = strings.ToUpper(d.Attributes.Language)
		}
		downloads := strconv.Itoa(d.Attributes.DownloadCount)
		if d.Attributes.DownloadCount >= 1000 {
			downloads = strconv.FormatFloat(float64(d.Attributes.DownloadCount)/1000, 'f', 1, 64) + "k"
		}
		out = append(out, SubtitleRef{
			Ref:      fmt.Sprintf("os:%d", f.FileID),
			Label:    name,
			Language: d.Attributes.Language,
			Badge:    badge,
			Format:   format,
			Origin:   "online",
			Detail:   "OpenSubtitles · Downloads " + downloads + " · " + format,
		})
	}
	return out, nil
}

// Download 换取下载链接并取回字幕内容。
func (o *OpenSubtitles) Download(ctx context.Context, fileID int64) ([]byte, string, error) {
	body := bytes.NewBufferString(fmt.Sprintf(`{"file_id":%d}`, fileID))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, o.base()+"/download", body)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := o.do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return nil, "", fmt.Errorf("opensubtitles download: HTTP %d", resp.StatusCode)
	}
	var link struct {
		Link     string `json:"link"`
		FileName string `json:"file_name"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&link); err != nil || link.Link == "" {
		return nil, "", errors.New("opensubtitles download: no link")
	}
	fileReq, err := http.NewRequestWithContext(ctx, http.MethodGet, link.Link, nil)
	if err != nil {
		return nil, "", err
	}
	fileResp, err := o.client().Do(fileReq)
	if err != nil {
		return nil, "", err
	}
	defer fileResp.Body.Close()
	if fileResp.StatusCode/100 != 2 {
		return nil, "", fmt.Errorf("opensubtitles file: HTTP %d", fileResp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(fileResp.Body, MaxSubtitleBytes+1))
	if err != nil || len(data) > MaxSubtitleBytes {
		return nil, "", errors.New("opensubtitles file: invalid size")
	}
	name := link.FileName
	if name == "" {
		name = path.Base(strings.SplitN(link.Link, "?", 2)[0])
	}
	return data, name, nil
}
