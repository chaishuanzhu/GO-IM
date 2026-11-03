package watchroom

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"
)

// Groups 是房间依赖的 GO-IM 群操作（独立部署时走服务端 API，网关兼容模式下进程内调用）。
type Groups interface {
	Create(ctx context.Context, name, ownerUID string) (groupID string, err error)
	// Join 加入群；已是成员视为成功。群不存在返回 ErrEnded。
	Join(ctx context.Context, groupID, uid string) error
	// Leave 退群；不是成员或群不存在视为成功。
	Leave(ctx context.Context, groupID, uid string) error
	// Dismiss 解散群；群不存在视为成功。
	Dismiss(ctx context.Context, groupID string) error
}

// Notifier 在房间变化时通知群成员（瞬时群信令，不落库）。
type Notifier interface {
	RoomUpdated(ctx context.Context, actorUID string, room *Room)
}

// Service 实现房间状态机；所有授权检查都在这里。
type Service struct {
	store    Store
	groups   Groups
	notifier Notifier
	newID    func() string
	now      func() time.Time
}

// NewService 创建房间服务。notifier 可为 nil。
func NewService(store Store, groups Groups, notifier Notifier, newID func() string) *Service {
	return &Service{store: store, groups: groups, notifier: notifier, newID: newID, now: time.Now}
}

// Patch 是 /room/update 可修改的字段；nil 表示不修改。
type Patch struct {
	MovieID         *string
	HostUID         *string
	HostTransferSeq *int64
	LastAppliedSeq  *int64
	Ended           bool
	IfRevision      *int64
}

// Create 新建房间与对应的群，调用者成为房主。
func (s *Service) Create(ctx context.Context, uid, movieID string) (*Room, error) {
	movieID = strings.TrimSpace(movieID)
	if movieID == "" {
		return nil, errors.New("movie_id required")
	}
	id := s.newID()
	groupID, err := s.groups.Create(ctx, "paircast-"+id, uid)
	if err != nil {
		return nil, err
	}
	now := s.now().UnixMilli()
	room := &Room{
		ID:        id,
		GroupID:   groupID,
		MovieID:   movieID,
		HostUID:   uid,
		Members:   []string{uid},
		JoinOrder: []string{uid},
		Status:    StatusActive,
		Revision:  1,
		CreatedAt: now,
		UpdatedAt: now,
	}
	if err := s.store.Create(ctx, room); err != nil {
		_ = s.groups.Leave(ctx, groupID, uid)
		return nil, err
	}
	return room, nil
}

// Get 返回房间；只有成员可以读取。
func (s *Service) Get(ctx context.Context, uid, roomID string) (*Room, error) {
	room, err := s.store.Get(ctx, normalizeID(roomID))
	if err != nil {
		return nil, err
	}
	if !room.IsMember(uid) {
		return nil, ErrNotMember
	}
	return room, nil
}

// Join 加入房间（已在房间内视为成功）。
func (s *Service) Join(ctx context.Context, uid, roomID string) (*Room, error) {
	id := normalizeID(roomID)
	room, err := s.store.Get(ctx, id)
	if err != nil {
		return nil, err
	}
	if room.Status != StatusActive {
		return nil, ErrEnded
	}
	if err := s.groups.Join(ctx, room.GroupID, uid); err != nil {
		return nil, err
	}
	updated, err := s.store.Update(ctx, id, func(r *Room) error {
		if r.Status != StatusActive {
			return ErrEnded
		}
		if !r.IsMember(uid) {
			r.Members = append(r.Members, uid)
			r.JoinOrder = append(r.JoinOrder, uid)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.notify(ctx, uid, updated)
	return updated, nil
}

// Leave 离开房间：房主离开按加入顺序转让，最后一人离开结束房间。transferSeq 为客户端的播控序号。
func (s *Service) Leave(ctx context.Context, uid, roomID string, transferSeq int64) (*Room, error) {
	id := normalizeID(roomID)
	updated, err := s.store.Update(ctx, id, func(r *Room) error {
		if !r.IsMember(uid) {
			return ErrNotMember
		}
		r.removeMember(uid, transferSeq)
		return nil
	})
	if err != nil {
		return nil, err
	}
	// 先通知（离开者仍在群内），再退群。
	s.notify(ctx, uid, updated)
	if err := s.groups.Leave(ctx, updated.GroupID, uid); err != nil {
		log.Printf("[watchroom] leave group %s for %s: %v", updated.GroupID, uid, err)
	}
	return updated, nil
}

// Update 修改房间：换片、转让房主、结束房间仅房主可做；序号只增不减。
func (s *Service) Update(ctx context.Context, uid, roomID string, p Patch) (*Room, error) {
	updated, err := s.store.Update(ctx, normalizeID(roomID), func(r *Room) error {
		if !r.IsMember(uid) {
			return ErrNotMember
		}
		if r.Status != StatusActive {
			return ErrEnded
		}
		if p.IfRevision != nil && *p.IfRevision != r.Revision {
			return ErrConflict
		}
		hostOnly := p.Ended || (p.MovieID != nil && *p.MovieID != r.MovieID) || (p.HostUID != nil && *p.HostUID != r.HostUID)
		if hostOnly && uid != r.HostUID {
			return ErrNotHost
		}
		if p.MovieID != nil && strings.TrimSpace(*p.MovieID) != "" && *p.MovieID != r.MovieID {
			r.MovieID = strings.TrimSpace(*p.MovieID)
			r.SubtitleRef, r.SubtitleLabel = "", ""
		}
		if p.HostUID != nil && *p.HostUID != r.HostUID {
			if !r.IsMember(*p.HostUID) {
				return ErrInvalidHost
			}
			r.HostUID = *p.HostUID
		}
		if p.HostTransferSeq != nil {
			r.HostTransferSeq = max(r.HostTransferSeq, *p.HostTransferSeq)
		}
		if p.LastAppliedSeq != nil {
			r.LastAppliedSeq = max(r.LastAppliedSeq, *p.LastAppliedSeq)
		}
		if p.Ended {
			r.Status = StatusEnded
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.notify(ctx, uid, updated)
	return updated, nil
}

// RequireHost 检查 uid 是否为活跃房间的房主（字幕共享前调用）。
func (s *Service) RequireHost(ctx context.Context, uid, roomID string) (*Room, error) {
	room, err := s.store.Get(ctx, normalizeID(roomID))
	if err != nil {
		return nil, err
	}
	if room.Status != StatusActive {
		return nil, ErrEnded
	}
	if room.HostUID != uid {
		return nil, ErrNotHost
	}
	return room, nil
}

// SetSubtitle 记录房主共享的字幕，让后加入的成员也能看到。
func (s *Service) SetSubtitle(ctx context.Context, uid, roomID, ref, label string) (*Room, error) {
	updated, err := s.store.Update(ctx, normalizeID(roomID), func(r *Room) error {
		if r.HostUID != uid {
			return ErrNotHost
		}
		r.SubtitleRef, r.SubtitleLabel = ref, label
		return nil
	})
	if err != nil {
		return nil, err
	}
	s.notify(ctx, uid, updated)
	return updated, nil
}

// GroupMemberLeft 同步 IM 侧的退群（group.member_left webhook）：成员不在房间里或房间已结束时什么也不做。
func (s *Service) GroupMemberLeft(ctx context.Context, groupID, uid string) error {
	room, err := s.store.GetByGroup(ctx, groupID)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if room.Status != StatusActive || !room.IsMember(uid) {
		return nil
	}
	updated, err := s.store.Update(ctx, room.ID, func(r *Room) error {
		if r.IsMember(uid) {
			r.removeMember(uid, 0)
		}
		return nil
	})
	if err != nil {
		return err
	}
	s.notify(ctx, uid, updated)
	return nil
}

// GroupDismissed 同步 IM 侧的群解散（group.dismissed webhook）：结束对应房间。
func (s *Service) GroupDismissed(ctx context.Context, groupID string) error {
	room, err := s.store.GetByGroup(ctx, groupID)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	_, err = s.store.Update(ctx, room.ID, func(r *Room) error {
		r.Status = StatusEnded
		return nil
	})
	return err
}

// StartJanitor 定期删除长时间未更新的房间（含已结束的），并解散它们绑定的群。
func (s *Service) StartJanitor(ctx context.Context, retention, interval time.Duration) {
	if retention <= 0 {
		return
	}
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.cleanup(ctx, retention)
			}
		}
	}()
}

func (s *Service) cleanup(ctx context.Context, retention time.Duration) {
	groups, err := s.store.DeleteStale(ctx, s.now().Add(-retention))
	if err != nil {
		log.Printf("[watchroom] janitor: %v", err)
		return
	}
	for _, g := range groups {
		if err := s.groups.Dismiss(ctx, g); err != nil {
			log.Printf("[watchroom] janitor: dismiss group %s: %v", g, err)
		}
	}
	if len(groups) > 0 {
		log.Printf("[watchroom] janitor removed %d stale rooms", len(groups))
	}
}

func (s *Service) notify(ctx context.Context, actorUID string, room *Room) {
	if s.notifier != nil {
		s.notifier.RoomUpdated(ctx, actorUID, room)
	}
}

func normalizeID(id string) string { return strings.ToLower(strings.TrimSpace(id)) }

// NewRandomID 生成 UUID v4 形式的房间 ID（出现在邀请链接里，不可猜测）。
func NewRandomID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
