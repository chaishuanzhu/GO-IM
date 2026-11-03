// Package moderation 保存用户之间的屏蔽关系与举报，并提供管理员处理举报的流程（Paircast 聊天安全）。
package moderation

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

// 举报状态。
const (
	StatusPending = "pending"
	StatusIgnored = "ignored"
	StatusBanned  = "banned"
)

// 举报列表过滤：pending 只看待处理，handled 看已处理（ignored + banned），all 全部。
const (
	FilterPending = "pending"
	FilterHandled = "handled"
	FilterAll     = "all"
)

const (
	maxContentRunes = 500
	maxFieldLen     = 128
	maxBlocks       = 1000
)

var (
	ErrInvalid      = errors.New("invalid request")
	ErrSelf         = errors.New("cannot target yourself")
	ErrNotFound     = errors.New("report not found")
	ErrTooManyBlock = errors.New("block list full")
)

// Report 是一条举报。Content 是举报时客户端带上的消息摘要（截断到 500 字）。
type Report struct {
	ID          int64  `json:"id"`
	ReporterUID string `json:"reporter_uid"`
	TargetUID   string `json:"target_uid"`
	MessageID   string `json:"message_id,omitempty"`
	Context     string `json:"context,omitempty"`
	Content     string `json:"content"`
	Status      string `json:"status"`
	HandledBy   string `json:"handled_by,omitempty"`
	CreatedAt   int64  `json:"created_at"`
	HandledAt   int64  `json:"handled_at,omitempty"`
}

// Store 持久化屏蔽与举报。
type Store interface {
	Block(ctx context.Context, owner, target string, at int64) error
	Unblock(ctx context.Context, owner, target string) error
	Blocked(ctx context.Context, owner string) ([]string, error)
	// AddReport 写入举报；同一举报人对同一条消息的待处理举报只保留一条，返回已有的 ID。
	AddReport(ctx context.Context, r *Report) (int64, error)
	Reports(ctx context.Context, filter string, offset, limit int) ([]Report, int, error)
	CountPending(ctx context.Context) (int, error)
	GetReport(ctx context.Context, id int64) (*Report, error)
	// Resolve 把一条举报标记为 status（不论原状态）。
	Resolve(ctx context.Context, id int64, status, by string, at int64) error
	// ResolveTarget 把针对 target 的所有待处理举报标记为 status，返回条数。
	ResolveTarget(ctx context.Context, target, status, by string, at int64) (int, error)
}

// Service 校验输入后读写 Store。
type Service struct {
	store Store
	now   func() time.Time
}

// NewService 创建服务。
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now}
}

func cleanID(v string) (string, error) {
	v = strings.TrimSpace(v)
	if v == "" || len(v) > maxFieldLen {
		return "", ErrInvalid
	}
	return v, nil
}

func truncateRunes(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// Block 让 owner 屏蔽 target（幂等）。
func (s *Service) Block(ctx context.Context, owner, target string) error {
	target, err := cleanID(target)
	if err != nil {
		return err
	}
	if target == owner {
		return ErrSelf
	}
	list, err := s.store.Blocked(ctx, owner)
	if err != nil {
		return err
	}
	for _, id := range list {
		if id == target {
			return nil
		}
	}
	if len(list) >= maxBlocks {
		return ErrTooManyBlock
	}
	return s.store.Block(ctx, owner, target, s.now().UnixMilli())
}

// Unblock 取消屏蔽（幂等）。
func (s *Service) Unblock(ctx context.Context, owner, target string) error {
	target, err := cleanID(target)
	if err != nil {
		return err
	}
	return s.store.Unblock(ctx, owner, target)
}

// Blocked 返回 owner 屏蔽的 UID 列表。
func (s *Service) Blocked(ctx context.Context, owner string) ([]string, error) {
	return s.store.Blocked(ctx, owner)
}

// Report 记录 reporter 对 target 的举报。
func (s *Service) Report(ctx context.Context, reporter, target, messageID, roomContext, content string) (int64, error) {
	target, err := cleanID(target)
	if err != nil {
		return 0, err
	}
	if target == reporter {
		return 0, ErrSelf
	}
	if len(messageID) > maxFieldLen || len(roomContext) > maxFieldLen {
		return 0, ErrInvalid
	}
	return s.store.AddReport(ctx, &Report{
		ReporterUID: reporter,
		TargetUID:   target,
		MessageID:   strings.TrimSpace(messageID),
		Context:     strings.TrimSpace(roomContext),
		Content:     truncateRunes(content, maxContentRunes),
		Status:      StatusPending,
		CreatedAt:   s.now().UnixMilli(),
	})
}

// Reports 分页列出举报（新的在前）及待处理总数。
func (s *Service) Reports(ctx context.Context, filter string, offset, limit int) (reports []Report, total, pending int, err error) {
	switch filter {
	case FilterPending, FilterHandled, FilterAll:
	case "":
		filter = FilterPending
	default:
		return nil, 0, 0, ErrInvalid
	}
	reports, total, err = s.store.Reports(ctx, filter, offset, limit)
	if err != nil {
		return nil, 0, 0, err
	}
	pending, err = s.store.CountPending(ctx)
	return reports, total, pending, err
}

// Get 返回一条举报。
func (s *Service) Get(ctx context.Context, id int64) (*Report, error) {
	return s.store.GetReport(ctx, id)
}

// Ignore 忽略一条举报。
func (s *Service) Ignore(ctx context.Context, id int64, admin string) error {
	if _, err := s.store.GetReport(ctx, id); err != nil {
		return err
	}
	return s.store.Resolve(ctx, id, StatusIgnored, admin, s.now().UnixMilli())
}

// MarkBanned 把这条举报以及针对同一用户的其他待处理举报都标记为已封禁。
// 封禁账号本身由调用方完成（网关的用户存储）。
func (s *Service) MarkBanned(ctx context.Context, r *Report, admin string) error {
	at := s.now().UnixMilli()
	if err := s.store.Resolve(ctx, r.ID, StatusBanned, admin, at); err != nil {
		return err
	}
	_, err := s.store.ResolveTarget(ctx, r.TargetUID, StatusBanned, admin, at)
	return err
}
