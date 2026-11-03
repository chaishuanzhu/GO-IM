package repo

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

var _ ExternalUserStore = (*MySQLStore)(nil)

// GetByExternalID 按接入方用户 ID 查询，未找到返回 (nil, nil)。
func (s *MySQLStore) GetByExternalID(ctx context.Context, externalID string) (*User, error) {
	u, err := scanUser(s.db.QueryRowContext(ctx, "SELECT "+userColumns+" FROM users WHERE external_id = ?", externalID))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get user by external id: %w", err)
	}
	return u, nil
}

// UpsertExternalUser 按 externalID 创建或更新用户。新用户分配自增 UID，没有密码（只能经服务端 API 签发令牌登录），
// 昵称为空时使用 externalID。并发导入同一 externalID 时以先插入者为准。
func (s *MySQLStore) UpsertExternalUser(ctx context.Context, externalID string, nickname *string, avatarFileID *int64) (*User, bool, error) {
	u, err := s.GetByExternalID(ctx, externalID)
	if err != nil {
		return nil, false, err
	}
	if u == nil {
		name := externalID
		if nickname != nil && *nickname != "" {
			name = *nickname
		}
		nu := &User{Nickname: name, ExternalID: externalID, CreatedAt: time.Now().UnixMilli()}
		err := s.CreateWithAutoUID(ctx, nu)
		if errors.Is(err, ErrExternalIDTaken) {
			if u, err = s.GetByExternalID(ctx, externalID); err != nil || u == nil {
				return nil, false, fmt.Errorf("upsert external user: %w", err)
			}
		} else if err != nil {
			return nil, false, err
		} else {
			if avatarFileID != nil {
				if err := s.UpdateProfile(ctx, nu.UID, nil, nil, avatarFileID); err != nil {
					return nil, false, err
				}
			}
			created, err := s.GetByUID(ctx, nu.UID)
			return created, true, err
		}
	}
	if nickname != nil && *nickname == "" {
		nickname = nil
	}
	if nickname == nil && avatarFileID == nil {
		return u, false, nil
	}
	if err := s.UpdateProfile(ctx, u.UID, nickname, nil, avatarFileID); err != nil {
		return nil, false, err
	}
	updated, err := s.GetByUID(ctx, u.UID)
	return updated, false, err
}

// BumpTokenVersion 递增令牌版本并返回新值。
func (s *MySQLStore) BumpTokenVersion(ctx context.Context, uid string) (int64, error) {
	res, err := s.db.ExecContext(ctx, "UPDATE users SET token_version = token_version + 1 WHERE uid = ?", uid)
	if err != nil {
		return 0, fmt.Errorf("bump token version: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, ErrUserNotFound
	}
	var v int64
	if err := s.db.QueryRowContext(ctx, "SELECT token_version FROM users WHERE uid = ?", uid).Scan(&v); err != nil {
		return 0, fmt.Errorf("bump token version: %w", err)
	}
	return v, nil
}
