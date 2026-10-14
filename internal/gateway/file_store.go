package gateway

import (
	"context"
	"sort"
	"sync"

	"github.com/im/api/proto"
	"github.com/im/internal/repo"
)

var _ repo.FileStore = (*InMemoryFileStore)(nil)

// convKey 返回会话的文件引用 key：群聊为群 ID，单聊为两端 UID 排序后以 ":" 连接。
func convKey(chatType int32, from, to string) string {
	if chatType == proto.ChatTypeGroup {
		return to
	}
	if from > to {
		from, to = to, from
	}
	return from + ":" + to
}

// InMemoryFileStore 是无 MySQL 时使用的 repo.FileStore，重启后丢失。
type InMemoryFileStore struct {
	mu    sync.RWMutex
	files map[int64]*repo.File
	refs  map[int64]map[string]struct{}
}

// NewInMemoryFileStore 创建一个空的 InMemoryFileStore。
func NewInMemoryFileStore() *InMemoryFileStore {
	return &InMemoryFileStore{
		files: make(map[int64]*repo.File),
		refs:  make(map[int64]map[string]struct{}),
	}
}

func (s *InMemoryFileStore) CreateFile(_ context.Context, f *repo.File) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	cp := *f
	s.files[f.FileID] = &cp
	return nil
}

func (s *InMemoryFileStore) GetFile(_ context.Context, fileID int64) (*repo.File, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	f, ok := s.files[fileID]
	if !ok {
		return nil, nil
	}
	cp := *f
	return &cp, nil
}

func (s *InMemoryFileStore) MarkFileReady(_ context.Context, fileID int64, mime string, width, height int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if f, ok := s.files[fileID]; ok {
		f.Status = repo.FileStatusReady
		f.Mime, f.Width, f.Height = mime, width, height
	}
	return nil
}

func (s *InMemoryFileStore) SetFileThumb(_ context.Context, fileID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if f, ok := s.files[fileID]; ok {
		f.HasThumb = true
	}
	return nil
}

func (s *InMemoryFileStore) AddFileRef(_ context.Context, fileID int64, key string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.refs[fileID] == nil {
		s.refs[fileID] = make(map[string]struct{})
	}
	s.refs[fileID][key] = struct{}{}
	return nil
}

func (s *InMemoryFileStore) FileConvKeys(_ context.Context, fileID int64) ([]string, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	keys := make([]string, 0, len(s.refs[fileID]))
	for k := range s.refs[fileID] {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys, nil
}

func (s *InMemoryFileStore) StalePendingFiles(_ context.Context, createdBefore int64, limit int) ([]int64, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var ids []int64
	for id, f := range s.files {
		if f.Status == repo.FileStatusPending && f.CreatedAt < createdBefore {
			ids = append(ids, id)
			if len(ids) >= limit {
				break
			}
		}
	}
	return ids, nil
}

func (s *InMemoryFileStore) DeleteFile(_ context.Context, fileID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.files, fileID)
	delete(s.refs, fileID)
	return nil
}
