package repo

import (
	"context"
	"fmt"
	"time"
)

// GetSettings 读取全部系统设置（键 → 值）。
func (s *MySQLStore) GetSettings(ctx context.Context) (map[string]string, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT k, v FROM system_settings`)
	if err != nil {
		return nil, fmt.Errorf("query settings: %w", err)
	}
	defer rows.Close()
	out := make(map[string]string)
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, fmt.Errorf("scan setting: %w", err)
		}
		out[k] = v
	}
	return out, rows.Err()
}

// PutSettings 批量写入系统设置（存在则覆盖）。
func (s *MySQLStore) PutSettings(ctx context.Context, values map[string]string) error {
	now := time.Now().UnixMilli()
	for k, v := range values {
		if _, err := s.db.ExecContext(ctx,
			`INSERT INTO system_settings (k, v, updated_at) VALUES (?, ?, ?)
			 ON DUPLICATE KEY UPDATE v = VALUES(v), updated_at = VALUES(updated_at)`, k, v, now); err != nil {
			return fmt.Errorf("put setting %s: %w", k, err)
		}
	}
	return nil
}
