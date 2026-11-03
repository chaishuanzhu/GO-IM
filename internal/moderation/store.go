package moderation

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"sync"
)

// MemoryStore 是进程内实现（MySQL 未启用或测试时）。
type MemoryStore struct {
	mu      sync.Mutex
	blocks  map[string]map[string]int64
	reports []Report
	nextID  int64
}

// NewMemoryStore 创建进程内存储。
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{blocks: map[string]map[string]int64{}}
}

// Block 实现 Store。
func (s *MemoryStore) Block(_ context.Context, owner, target string, at int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.blocks[owner] == nil {
		s.blocks[owner] = map[string]int64{}
	}
	if _, ok := s.blocks[owner][target]; !ok {
		s.blocks[owner][target] = at
	}
	return nil
}

// Unblock 实现 Store。
func (s *MemoryStore) Unblock(_ context.Context, owner, target string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.blocks[owner], target)
	return nil
}

// Blocked 实现 Store。
func (s *MemoryStore) Blocked(_ context.Context, owner string) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.blocks[owner]))
	for id := range s.blocks[owner] {
		out = append(out, id)
	}
	sort.Strings(out)
	return out, nil
}

// AddReport 实现 Store。
func (s *MemoryStore) AddReport(_ context.Context, r *Report) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if r.MessageID != "" {
		for _, e := range s.reports {
			if e.Status == StatusPending && e.ReporterUID == r.ReporterUID && e.TargetUID == r.TargetUID && e.MessageID == r.MessageID {
				return e.ID, nil
			}
		}
	}
	s.nextID++
	cp := *r
	cp.ID = s.nextID
	s.reports = append(s.reports, cp)
	return cp.ID, nil
}

func matches(filter, status string) bool {
	switch filter {
	case FilterPending:
		return status == StatusPending
	case FilterHandled:
		return status != StatusPending
	}
	return true
}

// Reports 实现 Store。
func (s *MemoryStore) Reports(_ context.Context, filter string, offset, limit int) ([]Report, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var all []Report
	for i := len(s.reports) - 1; i >= 0; i-- {
		if matches(filter, s.reports[i].Status) {
			all = append(all, s.reports[i])
		}
	}
	total := len(all)
	if offset >= total {
		return []Report{}, total, nil
	}
	end := min(offset+limit, total)
	return all[offset:end], total, nil
}

// CountPending 实现 Store。
func (s *MemoryStore) CountPending(_ context.Context) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for _, r := range s.reports {
		if r.Status == StatusPending {
			n++
		}
	}
	return n, nil
}

// GetReport 实现 Store。
func (s *MemoryStore) GetReport(_ context.Context, id int64) (*Report, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.reports {
		if r.ID == id {
			cp := r
			return &cp, nil
		}
	}
	return nil, ErrNotFound
}

// Resolve 实现 Store。
func (s *MemoryStore) Resolve(_ context.Context, id int64, status, by string, at int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i := range s.reports {
		if s.reports[i].ID == id {
			s.reports[i].Status, s.reports[i].HandledBy, s.reports[i].HandledAt = status, by, at
			return nil
		}
	}
	return ErrNotFound
}

// ResolveTarget 实现 Store。
func (s *MemoryStore) ResolveTarget(_ context.Context, target, status, by string, at int64) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	n := 0
	for i := range s.reports {
		if s.reports[i].TargetUID == target && s.reports[i].Status == StatusPending {
			s.reports[i].Status, s.reports[i].HandledBy, s.reports[i].HandledAt = status, by, at
			n++
		}
	}
	return n, nil
}

// MySQLStore 把屏蔽关系存进 user_blocks，举报存进 user_reports。
type MySQLStore struct {
	db *sql.DB
}

// NewMySQLStore 建表（若不存在）并返回存储。
func NewMySQLStore(db *sql.DB) (*MySQLStore, error) {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS user_blocks (
			owner_uid  VARCHAR(64) NOT NULL,
			target_uid VARCHAR(128) NOT NULL,
			created_at BIGINT      NOT NULL,
			PRIMARY KEY (owner_uid, target_uid)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
		`CREATE TABLE IF NOT EXISTS user_reports (
			id           BIGINT       NOT NULL AUTO_INCREMENT,
			reporter_uid VARCHAR(64)  NOT NULL,
			target_uid   VARCHAR(128) NOT NULL,
			message_id   VARCHAR(128) NOT NULL DEFAULT '',
			context      VARCHAR(128) NOT NULL DEFAULT '',
			content      TEXT         NOT NULL,
			status       VARCHAR(16)  NOT NULL,
			handled_by   VARCHAR(64)  NOT NULL DEFAULT '',
			created_at   BIGINT       NOT NULL,
			handled_at   BIGINT       NOT NULL DEFAULT 0,
			PRIMARY KEY (id),
			INDEX idx_status_created (status, created_at),
			INDEX idx_target (target_uid)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`,
	}
	for _, q := range stmts {
		if _, err := db.Exec(q); err != nil {
			return nil, fmt.Errorf("create moderation tables: %w", err)
		}
	}
	return &MySQLStore{db: db}, nil
}

// Block 实现 Store。
func (s *MySQLStore) Block(ctx context.Context, owner, target string, at int64) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT IGNORE INTO user_blocks (owner_uid, target_uid, created_at) VALUES (?, ?, ?)`, owner, target, at)
	return err
}

// Unblock 实现 Store。
func (s *MySQLStore) Unblock(ctx context.Context, owner, target string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM user_blocks WHERE owner_uid = ? AND target_uid = ?`, owner, target)
	return err
}

// Blocked 实现 Store。
func (s *MySQLStore) Blocked(ctx context.Context, owner string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT target_uid FROM user_blocks WHERE owner_uid = ? ORDER BY target_uid`, owner)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

const reportColumns = `id, reporter_uid, target_uid, message_id, context, content, status, handled_by, created_at, handled_at`

type rowScanner interface{ Scan(dest ...any) error }

func scanReport(row rowScanner) (*Report, error) {
	var r Report
	err := row.Scan(&r.ID, &r.ReporterUID, &r.TargetUID, &r.MessageID, &r.Context, &r.Content,
		&r.Status, &r.HandledBy, &r.CreatedAt, &r.HandledAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return &r, err
}

// AddReport 实现 Store。
func (s *MySQLStore) AddReport(ctx context.Context, r *Report) (int64, error) {
	if r.MessageID != "" {
		var id int64
		err := s.db.QueryRowContext(ctx, `SELECT id FROM user_reports
			WHERE reporter_uid = ? AND target_uid = ? AND message_id = ? AND status = ? LIMIT 1`,
			r.ReporterUID, r.TargetUID, r.MessageID, StatusPending).Scan(&id)
		if err == nil {
			return id, nil
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return 0, err
		}
	}
	res, err := s.db.ExecContext(ctx, `INSERT INTO user_reports
		(reporter_uid, target_uid, message_id, context, content, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		r.ReporterUID, r.TargetUID, r.MessageID, r.Context, r.Content, r.Status, r.CreatedAt)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func filterClause(filter string) (string, []any) {
	switch filter {
	case FilterPending:
		return ` WHERE status = ?`, []any{StatusPending}
	case FilterHandled:
		return ` WHERE status <> ?`, []any{StatusPending}
	}
	return "", nil
}

// Reports 实现 Store。
func (s *MySQLStore) Reports(ctx context.Context, filter string, offset, limit int) ([]Report, int, error) {
	where, args := filterClause(filter)
	var total int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM user_reports`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT `+reportColumns+` FROM user_reports`+where+
		` ORDER BY id DESC LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []Report{}
	for rows.Next() {
		r, err := scanReport(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *r)
	}
	return out, total, rows.Err()
}

// CountPending 实现 Store。
func (s *MySQLStore) CountPending(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM user_reports WHERE status = ?`, StatusPending).Scan(&n)
	return n, err
}

// GetReport 实现 Store。
func (s *MySQLStore) GetReport(ctx context.Context, id int64) (*Report, error) {
	return scanReport(s.db.QueryRowContext(ctx, `SELECT `+reportColumns+` FROM user_reports WHERE id = ?`, id))
}

// Resolve 实现 Store。
func (s *MySQLStore) Resolve(ctx context.Context, id int64, status, by string, at int64) error {
	res, err := s.db.ExecContext(ctx, `UPDATE user_reports SET status = ?, handled_by = ?, handled_at = ? WHERE id = ?`,
		status, by, at, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		if _, err := s.GetReport(ctx, id); err != nil {
			return err
		}
	}
	return nil
}

// ResolveTarget 实现 Store。
func (s *MySQLStore) ResolveTarget(ctx context.Context, target, status, by string, at int64) (int, error) {
	res, err := s.db.ExecContext(ctx, `UPDATE user_reports SET status = ?, handled_by = ?, handled_at = ?
		WHERE target_uid = ? AND status = ?`, status, by, at, target, StatusPending)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}
