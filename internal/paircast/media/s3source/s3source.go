// Package s3source 实现 S3 兼容存储（阿里云 OSS、七牛、腾讯云 COS、MinIO）视频源。
package s3source

import (
	"context"
	"fmt"
	"io"
	"path"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/paircast/media"
)

// ObjectInfo 是列表中的一个对象。
type ObjectInfo struct {
	Key  string
	Size int64
}

// ObjectAPI 是 S3Source 需要的最小对象存储能力（minio 实现见 minio.go，测试用假实现）。
type ObjectAPI interface {
	List(ctx context.Context, prefix string) ([]ObjectInfo, error)
	Exists(ctx context.Context, key string) (bool, error)
	Get(ctx context.Context, key string) (io.ReadCloser, error)
	Presign(ctx context.Context, key string, ttl time.Duration) (string, error)
}

// 预签名上限（S3 SigV4 最长 7 天）。
const maxPresignTTL = 7 * 24 * time.Hour

// Options 配置 S3Source。
type Options struct {
	ID, Name     string
	Prefix       string
	PlayURLTTL   time.Duration
	ListCacheTTL time.Duration
	Now          func() time.Time
}

// Source 是 S3 兼容视频源。
type Source struct {
	api  ObjectAPI
	opts Options

	mu        sync.Mutex
	cached    []media.Item
	fetchedAt time.Time
}

var _ media.Source = (*Source)(nil)
var _ media.ArtworkSource = (*Source)(nil)
var _ media.FileSource = (*Source)(nil)

// New 用给定对象存储创建视频源。
func New(api ObjectAPI, opts Options) *Source {
	if opts.PlayURLTTL <= 0 {
		opts.PlayURLTTL = 6 * time.Hour
	}
	if opts.PlayURLTTL > maxPresignTTL {
		opts.PlayURLTTL = maxPresignTTL
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}
	opts.Prefix = strings.TrimLeft(opts.Prefix, "/")
	return &Source{api: api, opts: opts}
}

// FromConfig 用配置创建基于 minio-go 的视频源。
func FromConfig(sc configs.MediaSourceConfig, m configs.MediaConfig) (*Source, error) {
	api, err := NewMinioAPI(sc.S3)
	if err != nil {
		return nil, fmt.Errorf("media source %s: %w", sc.ID, err)
	}
	name := sc.Name
	if name == "" {
		name = sc.ID
	}
	return New(api, Options{
		ID:           sc.ID,
		Name:         name,
		Prefix:       sc.S3.Prefix,
		PlayURLTTL:   time.Duration(m.PlayURLTTL),
		ListCacheTTL: time.Duration(m.ListCacheTTL),
	}), nil
}

// Info 实现 media.Source。
func (s *Source) Info() media.SourceInfo {
	return media.SourceInfo{
		ID:           s.opts.ID,
		Name:         s.opts.Name,
		Type:         "s3",
		Capabilities: []string{"sidecar_subtitles", "artwork", "files"},
	}
}

// List 实现 media.Source：列出前缀下的 mp4 / m4v / mkv，结果缓存 ListCacheTTL。
func (s *Source) List(ctx context.Context, opt media.ListOptions) (media.Page, error) {
	items, err := s.all(ctx, opt.Refresh)
	if err != nil {
		return media.Page{}, err
	}
	if q := strings.ToLower(strings.TrimSpace(opt.Query)); q != "" {
		filtered := make([]media.Item, 0, len(items))
		for _, it := range items {
			if strings.Contains(strings.ToLower(it.Title), q) || strings.Contains(strings.ToLower(it.NativeID), q) {
				filtered = append(filtered, it)
			}
		}
		items = filtered
	}
	limit := opt.Limit
	if limit <= 0 || limit > 1000 {
		limit = 500
	}
	offset, _ := strconv.Atoi(opt.Cursor)
	if offset < 0 || offset > len(items) {
		offset = len(items)
	}
	end := min(offset+limit, len(items))
	page := media.Page{Items: append([]media.Item(nil), items[offset:end]...)}
	if end < len(items) {
		page.NextCursor = strconv.Itoa(end)
	}
	return page, nil
}

func (s *Source) all(ctx context.Context, refresh bool) ([]media.Item, error) {
	s.mu.Lock()
	fresh := s.cached != nil && s.opts.Now().Sub(s.fetchedAt) < s.opts.ListCacheTTL
	if fresh && !refresh {
		items := s.cached
		s.mu.Unlock()
		return items, nil
	}
	s.mu.Unlock()

	objects, err := s.api.List(ctx, s.opts.Prefix)
	if err != nil {
		return nil, err
	}
	items := make([]media.Item, 0, len(objects))
	for _, o := range objects {
		if it, ok := s.itemFor(o.Key); ok {
			it.Size = o.Size
			items = append(items, it)
		}
	}
	sort.Slice(items, func(i, j int) bool { return items[i].NativeID < items[j].NativeID })

	s.mu.Lock()
	s.cached, s.fetchedAt = items, s.opts.Now()
	s.mu.Unlock()
	return items, nil
}

func (s *Source) itemFor(key string) (media.Item, bool) {
	format := media.VideoFormat(key)
	if format == "" || !s.inScope(key) {
		return media.Item{}, false
	}
	title, year := media.ParseTitle(key)
	return media.Item{
		ID:       media.ItemID(s.opts.ID, key),
		SourceID: s.opts.ID,
		NativeID: key,
		Title:    title,
		Year:     year,
		Format:   format,
	}, true
}

// Get 实现 media.Source。
func (s *Source) Get(ctx context.Context, key string) (media.Item, error) {
	it, ok := s.itemFor(key)
	if !ok {
		return media.Item{}, media.ErrNotFound
	}
	s.mu.Lock()
	for _, c := range s.cached {
		if c.NativeID == key {
			s.mu.Unlock()
			return c, nil
		}
	}
	s.mu.Unlock()
	exists, err := s.api.Exists(ctx, key)
	if err != nil {
		return media.Item{}, err
	}
	if !exists {
		return media.Item{}, media.ErrNotFound
	}
	return it, nil
}

// PlayURL 实现 media.Source：返回预签名 GET 直链，视频流不经过服务器。
func (s *Source) PlayURL(ctx context.Context, key string) (media.PlayInfo, error) {
	it, ok := s.itemFor(key)
	if !ok {
		return media.PlayInfo{}, media.ErrNotFound
	}
	url, err := s.api.Presign(ctx, key, s.opts.PlayURLTTL)
	if err != nil {
		return media.PlayInfo{}, err
	}
	return media.PlayInfo{
		URL:       url,
		ExpiresAt: s.opts.Now().Add(s.opts.PlayURLTTL).UnixMilli(),
		Format:    it.Format,
	}, nil
}

// 列不到同名字幕时（部分存储列表有延迟）再按常见命名探测。
var candidateSidecarSuffixes = []string{
	".srt", ".zh.srt", ".chi.srt", ".chs.srt", ".zh-cn.srt", ".zh_cn.srt",
	".en.srt", ".eng.srt",
	".vtt", ".zh.vtt", ".en.vtt",
	".ass", ".ssa", ".zh.ass",
}

// Sidecars 实现 media.Source：影片同目录、同名的 srt / vtt / ass / ssa。
func (s *Source) Sidecars(ctx context.Context, key string) ([]media.SubtitleRef, error) {
	if _, ok := s.itemFor(key); !ok {
		return nil, media.ErrNotFound
	}
	base := strings.TrimSuffix(key, path.Ext(key))
	objects, err := s.api.List(ctx, base)
	if err != nil {
		return nil, err
	}
	keys := make([]string, 0, len(objects))
	for _, o := range objects {
		keys = append(keys, o.Key)
	}
	matched := media.MatchSidecars(keys, key)
	if len(matched) == 0 {
		for _, suffix := range candidateSidecarSuffixes {
			if ok, err := s.api.Exists(ctx, base+suffix); err == nil && ok {
				matched = append(matched, base+suffix)
			}
		}
	}
	sort.Strings(matched)
	refs := make([]media.SubtitleRef, 0, len(matched))
	for _, k := range matched {
		leaf := path.Base(k)
		ext := media.SubtitleExt(leaf)
		code, badge := media.GuessLanguage(leaf)
		label := leaf
		if badge != "" {
			label = badge + " (sidecar)"
		}
		refs = append(refs, media.SubtitleRef{
			Ref:      k,
			Label:    label,
			Language: code,
			Badge:    badge,
			Format:   strings.ToUpper(ext),
			Origin:   "sidecar",
			Detail:   "Library sidecar · " + strings.ToUpper(ext),
		})
	}
	return refs, nil
}

// OpenSidecar 实现 media.Source。
func (s *Source) OpenSidecar(ctx context.Context, key string) (io.ReadCloser, error) {
	if media.SubtitleExt(key) == "" || !s.inScope(key) {
		return nil, media.ErrNotFound
	}
	return s.api.Get(ctx, key)
}

// Artwork 实现 media.ArtworkSource：读取 Kodi 风格 sidecar（{base}.nfo、{base}-poster.jpg、{base}-fanart.jpg）。
func (s *Source) Artwork(ctx context.Context, key string) (media.Artwork, bool, error) {
	if _, ok := s.itemFor(key); !ok {
		return media.Artwork{}, false, media.ErrNotFound
	}
	base := strings.TrimSuffix(key, path.Ext(key))
	dir := path.Dir(key)
	var art media.Artwork
	posterKey, fanartKey := base+"-poster.jpg", base+"-fanart.jpg"
	if rc, err := s.api.Get(ctx, base+".nfo"); err == nil {
		data, _ := io.ReadAll(io.LimitReader(rc, 256<<10))
		rc.Close()
		if n, ok := media.ParseNFO(string(data)); ok {
			art.Title, art.Year, art.Overview = n.Title, n.Year, n.Plot
			if n.PosterFileName != "" {
				posterKey = s.joinDir(dir, n.PosterFileName, posterKey)
			}
			if n.FanartFileName != "" {
				fanartKey = s.joinDir(dir, n.FanartFileName, fanartKey)
			}
		}
	}
	art.PosterURL = s.signedIfExists(ctx, posterKey, base+"-poster.jpg")
	art.BackdropURL = s.signedIfExists(ctx, fanartKey, base+"-fanart.jpg")
	found := art.Title != "" || art.PosterURL != "" || art.BackdropURL != ""
	return art, found, nil
}

// joinDir 解析 NFO 里的图片文件名；越界或非法时回退到约定命名。
func (s *Source) joinDir(dir, name, fallback string) string {
	if strings.Contains(name, "://") {
		return fallback
	}
	joined := name
	if !strings.Contains(name, "/") && dir != "." {
		joined = dir + "/" + name
	}
	if !s.inScope(joined) {
		return fallback
	}
	return joined
}

func (s *Source) signedIfExists(ctx context.Context, keys ...string) string {
	tried := map[string]bool{}
	for _, k := range keys {
		if tried[k] {
			continue
		}
		tried[k] = true
		if ok, err := s.api.Exists(ctx, k); err == nil && ok {
			if url, err := s.api.Presign(ctx, k, maxPresignTTL); err == nil {
				return url
			}
		}
	}
	return ""
}

// ReadFile 实现 media.FileSource（相对桶根目录）。
func (s *Source) ReadFile(ctx context.Context, p string) ([]byte, error) {
	if !safePath(p) {
		return nil, media.ErrNotFound
	}
	rc, err := s.api.Get(ctx, p)
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	return io.ReadAll(io.LimitReader(rc, 4<<20))
}

// FileURL 实现 media.FileSource。
func (s *Source) FileURL(ctx context.Context, p string, ttl time.Duration) (string, error) {
	if !safePath(p) {
		return "", media.ErrNotFound
	}
	if ttl <= 0 || ttl > maxPresignTTL {
		ttl = maxPresignTTL
	}
	return s.api.Presign(ctx, p, ttl)
}

func (s *Source) inScope(key string) bool {
	return safePath(key) && strings.HasPrefix(key, s.opts.Prefix)
}

func safePath(p string) bool {
	if p == "" || strings.HasPrefix(p, "/") {
		return false
	}
	for _, seg := range strings.Split(p, "/") {
		if seg == ".." {
			return false
		}
	}
	return true
}

// Factory 是 FromConfig 的 media.SourceFactory 形式，注册为 type "s3"。
func Factory(sc configs.MediaSourceConfig, m configs.MediaConfig) (media.Source, error) {
	return FromConfig(sc, m)
}
