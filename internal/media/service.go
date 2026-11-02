package media

import (
	"context"
	"database/sql"
	"fmt"
	"log"
	"time"

	"github.com/im/configs"
)

// Service 汇总媒体库各部分，供 HTTP 层使用。
type Service struct {
	Registry  *Registry
	Metadata  *Metadata
	Subtitles *Subtitles
	Stickers  *Stickers // 未配置贴纸源时为 nil
}

// SourceFactory 按配置创建数据源（由调用方注入，避免 media 依赖具体实现包）。
type SourceFactory func(sc configs.MediaSourceConfig, cfg configs.MediaConfig) (Source, error)

// NewService 按配置组装媒体库。cfg.Sources 为空时返回 nil, nil（不启用）。
// db 为 nil 时元数据缓存在内存；blobs 为 nil 时不支持字幕共享与在线下载。
func NewService(cfg configs.MediaConfig, factories map[string]SourceFactory, db *sql.DB, blobs BlobStore, newID func() string) (*Service, error) {
	if len(cfg.Sources) == 0 {
		return nil, nil
	}
	sources := make([]Source, 0, len(cfg.Sources))
	for _, sc := range cfg.Sources {
		factory, ok := factories[sc.Type]
		if !ok {
			return nil, fmt.Errorf("media source %s: unsupported type %q", sc.ID, sc.Type)
		}
		src, err := factory(sc, cfg)
		if err != nil {
			return nil, err
		}
		sources = append(sources, src)
	}
	reg, err := NewRegistry(cfg.DefaultSource, sources...)
	if err != nil {
		return nil, err
	}

	var cache MetadataCache = NewMemoryMetadataCache()
	if db != nil {
		if c, err := NewMySQLMetadataCache(db); err == nil {
			cache = c
		} else {
			log.Printf("[media] WARNING: metadata cache falls back to memory: %v", err)
		}
	}
	var tmdb *TMDB
	if cfg.TMDBAccessToken != "" {
		tmdb = &TMDB{Token: cfg.TMDBAccessToken, Language: cfg.TMDBLanguage}
	}
	var os *OpenSubtitles
	if cfg.OpenSubtitlesAPIKey != "" {
		os = &OpenSubtitles{APIKey: cfg.OpenSubtitlesAPIKey}
	}

	svc := &Service{
		Registry:  reg,
		Metadata:  NewMetadata(cache, time.Duration(cfg.MetadataTTL), tmdb),
		Subtitles: NewSubtitles(reg, blobs, os, newID),
	}
	if src, err := reg.Source(cfg.StickersSource); err == nil {
		if fs, ok := src.(FileSource); ok {
			svc.Stickers = NewStickers(fs, cfg.StickersPrefix)
		}
	}
	return svc, nil
}

// Item 解析 ID 并返回补全元数据的影片。
func (s *Service) Item(ctx context.Context, id string) (Item, error) {
	src, native, err := s.Registry.Resolve(id)
	if err != nil {
		return Item{}, err
	}
	item, err := src.Get(ctx, native)
	if err != nil {
		return Item{}, err
	}
	return s.Metadata.Enrich(ctx, src, item), nil
}

// PlayURL 解析 ID 并返回播放直链。
func (s *Service) PlayURL(ctx context.Context, id string) (PlayInfo, error) {
	src, native, err := s.Registry.Resolve(id)
	if err != nil {
		return PlayInfo{}, err
	}
	return src.PlayURL(ctx, native)
}

// List 返回一页影片；enrich 为 true 时对本页逐条补全元数据（首次可能较慢，结果会缓存）。
func (s *Service) List(ctx context.Context, sourceID string, opt ListOptions, enrich bool) (Page, error) {
	src, err := s.Registry.Source(sourceID)
	if err != nil {
		return Page{}, err
	}
	page, err := src.List(ctx, opt)
	if err != nil {
		return Page{}, err
	}
	if enrich {
		for i := range page.Items {
			page.Items[i] = s.Metadata.Enrich(ctx, src, page.Items[i])
		}
	} else {
		s.Metadata.Apply(ctx, page.Items)
	}
	return page, nil
}
