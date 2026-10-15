package repo

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
)

var _ FileStore = (*MySQLStore)(nil)

// CreateFile 插入一条文件记录。
func (s *MySQLStore) CreateFile(ctx context.Context, f *File) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO files (file_id, owner_uid, name, size, mime, width, height, has_thumb, status, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		f.FileID, f.OwnerUID, f.Name, f.Size, f.Mime, f.Width, f.Height, f.HasThumb, f.Status, f.CreatedAt)
	if err != nil {
		return fmt.Errorf("insert file: %w", err)
	}
	return nil
}

// GetFile 查询文件记录，不存在时返回 (nil, nil)。
func (s *MySQLStore) GetFile(ctx context.Context, fileID int64) (*File, error) {
	f := &File{}
	err := s.db.QueryRowContext(ctx,
		`SELECT file_id, owner_uid, name, size, mime, width, height, has_thumb, status, is_avatar, created_at
		 FROM files WHERE file_id = ?`, fileID,
	).Scan(&f.FileID, &f.OwnerUID, &f.Name, &f.Size, &f.Mime, &f.Width, &f.Height, &f.HasThumb, &f.Status, &f.IsAvatar, &f.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get file: %w", err)
	}
	return f, nil
}

// MarkFileReady 把文件标记为已上传，并写入校验后的类型与尺寸。
func (s *MySQLStore) MarkFileReady(ctx context.Context, fileID int64, mime string, width, height int) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE files SET status = ?, mime = ?, width = ?, height = ? WHERE file_id = ?`,
		FileStatusReady, mime, width, height, fileID)
	if err != nil {
		return fmt.Errorf("mark file ready: %w", err)
	}
	return nil
}

// MarkAvatar 把文件标记为头像（公开可读）。
func (s *MySQLStore) MarkAvatar(ctx context.Context, fileID int64) error {
	if _, err := s.db.ExecContext(ctx, `UPDATE files SET is_avatar = 1 WHERE file_id = ?`, fileID); err != nil {
		return fmt.Errorf("mark avatar: %w", err)
	}
	return nil
}

// SetFileThumb 标记文件已有缩略图。
func (s *MySQLStore) SetFileThumb(ctx context.Context, fileID int64) error {
	_, err := s.db.ExecContext(ctx, `UPDATE files SET has_thumb = 1 WHERE file_id = ?`, fileID)
	if err != nil {
		return fmt.Errorf("set file thumb: %w", err)
	}
	return nil
}

// AddFileRef 记录文件被某个会话引用（重复插入忽略）。
func (s *MySQLStore) AddFileRef(ctx context.Context, fileID int64, convKey string) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT IGNORE INTO file_refs (file_id, conv_key) VALUES (?, ?)`, fileID, convKey)
	if err != nil {
		return fmt.Errorf("add file ref: %w", err)
	}
	return nil
}

// FileConvKeys 返回引用该文件的全部会话 key。
func (s *MySQLStore) FileConvKeys(ctx context.Context, fileID int64) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT conv_key FROM file_refs WHERE file_id = ?`, fileID)
	if err != nil {
		return nil, fmt.Errorf("file refs: %w", err)
	}
	defer rows.Close()
	var keys []string
	for rows.Next() {
		var k string
		if err := rows.Scan(&k); err != nil {
			return nil, fmt.Errorf("scan file ref: %w", err)
		}
		keys = append(keys, k)
	}
	return keys, rows.Err()
}

// StalePendingFiles 返回超时未确认的文件 ID。
func (s *MySQLStore) StalePendingFiles(ctx context.Context, createdBefore int64, limit int) ([]int64, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT file_id FROM files WHERE status = ? AND created_at < ? LIMIT ?`,
		FileStatusPending, createdBefore, limit)
	if err != nil {
		return nil, fmt.Errorf("stale files: %w", err)
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("scan stale file: %w", err)
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// DeleteFile 删除文件记录及其引用。
func (s *MySQLStore) DeleteFile(ctx context.Context, fileID int64) error {
	if _, err := s.db.ExecContext(ctx, `DELETE FROM file_refs WHERE file_id = ?`, fileID); err != nil {
		return fmt.Errorf("delete file refs: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM files WHERE file_id = ?`, fileID); err != nil {
		return fmt.Errorf("delete file: %w", err)
	}
	return nil
}
