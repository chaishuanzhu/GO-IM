package media

import (
	"fmt"
	"strings"
)

// Registry 持有已配置的数据源，并在全局 ID（"<sourceId>:<nativeId>"）与源内标识之间转换。
type Registry struct {
	sources   map[string]Source
	order     []string
	defaultID string
}

// NewRegistry 按给定顺序注册数据源；defaultID 为空时取第一个。
func NewRegistry(defaultID string, sources ...Source) (*Registry, error) {
	r := &Registry{sources: make(map[string]Source, len(sources))}
	for _, s := range sources {
		id := s.Info().ID
		if !validSourceID(id) {
			return nil, fmt.Errorf("media: invalid source id %q", id)
		}
		if _, dup := r.sources[id]; dup {
			return nil, fmt.Errorf("media: duplicate source id %q", id)
		}
		r.sources[id] = s
		r.order = append(r.order, id)
	}
	if defaultID == "" && len(r.order) > 0 {
		defaultID = r.order[0]
	}
	if defaultID != "" {
		if _, ok := r.sources[defaultID]; !ok {
			return nil, fmt.Errorf("media: default source %q not configured", defaultID)
		}
	}
	r.defaultID = defaultID
	return r, nil
}

// Empty 表示没有配置任何源。
func (r *Registry) Empty() bool { return r == nil || len(r.order) == 0 }

// Sources 按配置顺序返回源描述。
func (r *Registry) Sources() []SourceInfo {
	out := make([]SourceInfo, 0, len(r.order))
	for _, id := range r.order {
		out = append(out, r.sources[id].Info())
	}
	return out
}

// Source 按 ID 取源；id 为空时返回默认源。
func (r *Registry) Source(id string) (Source, error) {
	if id == "" {
		id = r.defaultID
	}
	s, ok := r.sources[id]
	if !ok {
		return nil, ErrUnknownSource
	}
	return s, nil
}

// Resolve 解析全局影片 ID。没有已知源前缀的 ID（v2.0 客户端的裸对象键）按默认源解析。
func (r *Registry) Resolve(id string) (Source, string, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, "", ErrInvalidID
	}
	if sourceID, native, ok := strings.Cut(id, ":"); ok {
		if s, known := r.sources[sourceID]; known {
			if native == "" {
				return nil, "", ErrInvalidID
			}
			return s, native, nil
		}
	}
	s, err := r.Source("")
	if err != nil {
		return nil, "", err
	}
	return s, id, nil
}

// ItemID 组合全局 ID。
func ItemID(sourceID, nativeID string) string { return sourceID + ":" + nativeID }

func validSourceID(id string) bool {
	if id == "" || len(id) > 32 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}
