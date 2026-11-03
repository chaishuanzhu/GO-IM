package watchroom

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/go-sql-driver/mysql"
)

// MemoryStore 是进程内实现（MySQL 未启用或测试时）。
type MemoryStore struct {
	mu    sync.Mutex
	rooms map[string]*Room
	now   func() time.Time
}

// NewMemoryStore 创建进程内存储。
func NewMemoryStore() *MemoryStore {
	return &MemoryStore{rooms: map[string]*Room{}, now: time.Now}
}

// Create 实现 Store。
func (s *MemoryStore) Create(_ context.Context, room *Room) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.rooms[room.ID]; ok {
		return fmt.Errorf("room %s exists", room.ID)
	}
	s.rooms[room.ID] = room.clone()
	return nil
}

// Get 实现 Store。
func (s *MemoryStore) Get(_ context.Context, id string) (*Room, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rooms[id]
	if !ok {
		return nil, ErrNotFound
	}
	return r.clone(), nil
}

// Update 实现 Store。
func (s *MemoryStore) Update(_ context.Context, id string, fn func(*Room) error) (*Room, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rooms[id]
	if !ok {
		return nil, ErrNotFound
	}
	next := r.clone()
	if err := fn(next); err != nil {
		return nil, err
	}
	next.Revision = r.Revision + 1
	next.UpdatedAt = s.now().UnixMilli()
	s.rooms[id] = next
	return next.clone(), nil
}

// GetByGroup 实现 Store。
func (s *MemoryStore) GetByGroup(_ context.Context, groupID string) (*Room, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.rooms {
		if r.GroupID == groupID {
			return r.clone(), nil
		}
	}
	return nil, ErrNotFound
}

// DeleteStale 实现 Store。
func (s *MemoryStore) DeleteStale(_ context.Context, before time.Time) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var groups []string
	for id, r := range s.rooms {
		if r.UpdatedAt < before.UnixMilli() {
			delete(s.rooms, id)
			groups = append(groups, r.GroupID)
		}
	}
	return groups, nil
}

// MySQLStore 把房间存进 watch_rooms 表（成员列表以 JSON 存储）。
type MySQLStore struct {
	db  *sql.DB
	now func() time.Time
}

// NewMySQLStore 建表（若不存在）并返回存储。
func NewMySQLStore(db *sql.DB) (*MySQLStore, error) {
	_, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS watch_rooms (
			id                VARCHAR(64)  NOT NULL,
			group_id          VARCHAR(64)  NOT NULL,
			movie_id          VARCHAR(512) NOT NULL,
			host_uid          VARCHAR(64)  NOT NULL,
			members           TEXT         NOT NULL,
			join_order        TEXT         NOT NULL,
			status            VARCHAR(16)  NOT NULL,
			last_applied_seq  BIGINT       NOT NULL DEFAULT 0,
			host_transfer_seq BIGINT       NOT NULL DEFAULT 0,
			subtitle_ref      VARCHAR(512) NOT NULL DEFAULT '',
			subtitle_label    VARCHAR(256) NOT NULL DEFAULT '',
			revision          BIGINT       NOT NULL DEFAULT 0,
			created_at        BIGINT       NOT NULL,
			updated_at        BIGINT       NOT NULL,
			PRIMARY KEY (id),
			INDEX idx_updated (updated_at),
			INDEX idx_group (group_id)
		) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`)
	if err != nil {
		return nil, fmt.Errorf("create watch_rooms: %w", err)
	}
	// 旧表没有 idx_group（1061 = 索引已存在）。
	if _, err := db.Exec(`ALTER TABLE watch_rooms ADD INDEX idx_group (group_id)`); err != nil && !isDupKeyName(err) {
		return nil, fmt.Errorf("add watch_rooms.idx_group: %w", err)
	}
	return &MySQLStore{db: db, now: time.Now}, nil
}

const roomColumns = `id, group_id, movie_id, host_uid, members, join_order, status,
	last_applied_seq, host_transfer_seq, subtitle_ref, subtitle_label, revision, created_at, updated_at`

type rowScanner interface{ Scan(dest ...any) error }

func scanRoom(row rowScanner) (*Room, error) {
	var r Room
	var members, joinOrder string
	err := row.Scan(&r.ID, &r.GroupID, &r.MovieID, &r.HostUID, &members, &joinOrder, &r.Status,
		&r.LastAppliedSeq, &r.HostTransferSeq, &r.SubtitleRef, &r.SubtitleLabel, &r.Revision, &r.CreatedAt, &r.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal([]byte(members), &r.Members); err != nil {
		return nil, err
	}
	if err := json.Unmarshal([]byte(joinOrder), &r.JoinOrder); err != nil {
		return nil, err
	}
	return &r, nil
}

func roomArgs(r *Room) []any {
	members, _ := json.Marshal(nonNil(r.Members))
	joinOrder, _ := json.Marshal(nonNil(r.JoinOrder))
	return []any{r.ID, r.GroupID, r.MovieID, r.HostUID, string(members), string(joinOrder), r.Status,
		r.LastAppliedSeq, r.HostTransferSeq, r.SubtitleRef, r.SubtitleLabel, r.Revision, r.CreatedAt, r.UpdatedAt}
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// Create 实现 Store。
func (s *MySQLStore) Create(ctx context.Context, room *Room) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO watch_rooms (`+roomColumns+`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, roomArgs(room)...)
	return err
}

// Get 实现 Store。
func (s *MySQLStore) Get(ctx context.Context, id string) (*Room, error) {
	return scanRoom(s.db.QueryRowContext(ctx, `SELECT `+roomColumns+` FROM watch_rooms WHERE id = ?`, id))
}

// Update 实现 Store（SELECT … FOR UPDATE 事务）。
func (s *MySQLStore) Update(ctx context.Context, id string, fn func(*Room) error) (*Room, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	r, err := scanRoom(tx.QueryRowContext(ctx, `SELECT `+roomColumns+` FROM watch_rooms WHERE id = ? FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	revision := r.Revision
	if err := fn(r); err != nil {
		return nil, err
	}
	r.Revision = revision + 1
	r.UpdatedAt = s.now().UnixMilli()
	args := roomArgs(r)
	_, err = tx.ExecContext(ctx, `UPDATE watch_rooms SET group_id = ?, movie_id = ?, host_uid = ?, members = ?, join_order = ?,
		status = ?, last_applied_seq = ?, host_transfer_seq = ?, subtitle_ref = ?, subtitle_label = ?, revision = ?,
		created_at = ?, updated_at = ? WHERE id = ?`, append(args[1:], r.ID)...)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return r, nil
}

// GetByGroup 实现 Store。
func (s *MySQLStore) GetByGroup(ctx context.Context, groupID string) (*Room, error) {
	return scanRoom(s.db.QueryRowContext(ctx, `SELECT `+roomColumns+` FROM watch_rooms WHERE group_id = ? LIMIT 1`, groupID))
}

// DeleteStale 实现 Store。
func (s *MySQLStore) DeleteStale(ctx context.Context, before time.Time) ([]string, error) {
	cutoff := before.UnixMilli()
	rows, err := s.db.QueryContext(ctx, `SELECT id, group_id FROM watch_rooms WHERE updated_at < ?`, cutoff)
	if err != nil {
		return nil, err
	}
	type stale struct{ id, group string }
	var found []stale
	for rows.Next() {
		var r stale
		if err := rows.Scan(&r.id, &r.group); err != nil {
			rows.Close()
			return nil, err
		}
		found = append(found, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	var groups []string
	for _, r := range found {
		// 删除时再比一次时间，跳过刚被更新的房间（它的群不能解散）。
		res, err := s.db.ExecContext(ctx, `DELETE FROM watch_rooms WHERE id = ? AND updated_at < ?`, r.id, cutoff)
		if err != nil {
			return nil, err
		}
		if n, _ := res.RowsAffected(); n == 1 {
			groups = append(groups, r.group)
		}
	}
	return groups, nil
}

func isDupKeyName(err error) bool {
	var me *mysql.MySQLError
	return errors.As(err, &me) && me.Number == 1061
}
