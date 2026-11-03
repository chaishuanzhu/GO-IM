// Package watchroom 管理 Paircast 观影房间：成员、房主、当前影片与共享字幕。
// 每个房间绑定一个 GO-IM 群（聊天与播控信令走群），房间状态以 Paircast 服务端为准；
// 群在 IM 侧被改动时（成员退群、群解散）由 webhook 同步回来。
package watchroom

import (
	"context"
	"errors"
	"slices"
	"time"
)

// 房间状态。
const (
	StatusActive = "active"
	StatusEnded  = "ended"
)

// 哨兵错误。
var (
	ErrNotFound    = errors.New("watchroom: room not found")
	ErrEnded       = errors.New("watchroom: room ended")
	ErrNotMember   = errors.New("watchroom: not a member")
	ErrNotHost     = errors.New("watchroom: only the host can do this")
	ErrConflict    = errors.New("watchroom: revision conflict")
	ErrInvalidHost = errors.New("watchroom: new host is not a member")
)

// Room 是一个观影房间。JSON 字段即 /room/* 接口的响应格式。
type Room struct {
	ID              string   `json:"id"`
	GroupID         string   `json:"group_id"`
	MovieID         string   `json:"movie_id"`
	HostUID         string   `json:"host_uid"`
	Members         []string `json:"members"`
	JoinOrder       []string `json:"join_order"`
	Status          string   `json:"status"`
	LastAppliedSeq  int64    `json:"last_applied_seq"`
	HostTransferSeq int64    `json:"host_transfer_seq"`
	SubtitleRef     string   `json:"subtitle_ref,omitempty"`
	SubtitleLabel   string   `json:"subtitle_label,omitempty"`
	Revision        int64    `json:"revision"`
	CreatedAt       int64    `json:"created_at"`
	UpdatedAt       int64    `json:"updated_at"`
}

// IsMember 报告 uid 是否在房间里。
func (r *Room) IsMember(uid string) bool { return slices.Contains(r.Members, uid) }

func (r *Room) clone() *Room {
	c := *r
	c.Members = slices.Clone(r.Members)
	c.JoinOrder = slices.Clone(r.JoinOrder)
	return &c
}

// removeMember 移除成员；房主离开时按加入顺序转让，最后一人离开时结束房间。
// 返回是否发生了房主转让。
func (r *Room) removeMember(uid string, transferSeq int64) (transferred bool) {
	r.Members = slices.DeleteFunc(r.Members, func(m string) bool { return m == uid })
	r.JoinOrder = slices.DeleteFunc(r.JoinOrder, func(m string) bool { return m == uid })
	if len(r.Members) == 0 {
		r.Status = StatusEnded
		return false
	}
	if uid != r.HostUID {
		return false
	}
	next := r.Members[0]
	for _, m := range r.JoinOrder {
		if slices.Contains(r.Members, m) {
			next = m
			break
		}
	}
	r.HostUID = next
	r.HostTransferSeq = max(r.HostTransferSeq+1, transferSeq)
	return true
}

// Store 持久化房间。Update 必须原子地读-改-写，并递增 Revision、刷新 UpdatedAt。
type Store interface {
	Create(ctx context.Context, room *Room) error
	Get(ctx context.Context, id string) (*Room, error)
	Update(ctx context.Context, id string, fn func(*Room) error) (*Room, error)
	// GetByGroup 按绑定的群 ID 查房间，没有返回 ErrNotFound。
	GetByGroup(ctx context.Context, groupID string) (*Room, error)
	// DeleteStale 删除 UpdatedAt 早于 before 的房间，返回被删房间绑定的群 ID。
	DeleteStale(ctx context.Context, before time.Time) ([]string, error)
}
