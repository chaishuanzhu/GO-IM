package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"

	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/gateway"
	"github.com/im/internal/paircast"
	"github.com/im/internal/paircast/media"
	"github.com/im/internal/paircast/media/s3source"
	"github.com/im/internal/paircast/watchroom"
	"github.com/im/internal/pkg/snowflake"
)

// 网关内置的 Paircast 兼容模式：gateway.media.sources 非空时在进程内挂载 /media/* 与 /room/*，
// 与独立的 cmd/paircast 服务共用 internal/paircast 的接口实现。保留一个版本后移除。

// mediaSourceFactories 按 type 创建视频源。新增 openlist / emby / jellyfin 时在这里注册。
var mediaSourceFactories = map[string]media.SourceFactory{"s3": s3source.Factory}

// setupMedia 组装媒体库与观影房间；未配置视频源时两者均为 nil。
func setupMedia(cfg configs.MediaConfig, db *sql.DB, blobs gateway.ObjectStore, groups gateway.GroupStore,
	router *gateway.Router, snow *snowflake.Generator) (*media.Service, *watchroom.Service, error) {
	newBlobID := func() string { return fmt.Sprint(snow.Next()) }
	svc, err := media.NewService(cfg, mediaSourceFactories, db, blobs, newBlobID)
	if err != nil || svc == nil {
		if err == nil {
			log.Printf("[main] media library disabled (gateway.media.sources is empty)")
		}
		return nil, nil, err
	}
	log.Printf("[main] WARNING: gateway.media is deprecated — run cmd/paircast as a separate service; the in-gateway media library will be removed in the next release")
	if groups == nil {
		log.Printf("[main] WARNING: group store unavailable, watch rooms disabled")
		return svc, nil, nil
	}
	var store watchroom.Store = watchroom.NewMemoryStore()
	if db != nil {
		mysqlStore, err := watchroom.NewMySQLStore(db)
		if err != nil {
			return nil, nil, err
		}
		store = mysqlStore
	}
	rooms := watchroom.NewService(store, roomGroups{store: groups, router: router}, roomSignals{router: router}, watchroom.NewRandomID)
	ids := make([]string, 0, len(cfg.Sources))
	for _, s := range svc.Registry.Sources() {
		ids = append(ids, s.ID)
	}
	log.Printf("[main] media library enabled: sources=%v online_subtitles=%v stickers=%v",
		ids, svc.Subtitles.OnlineEnabled(), svc.Stickers != nil)
	return svc, rooms, nil
}

// registerMediaRoutes 挂载 /media/* 与 /room/*（未启用时返回 503）。
func registerMediaRoutes(mux *http.ServeMux, s *gateway.Server, svc *media.Service, rooms *watchroom.Service) {
	paircast.NewServer(svc, rooms, gatewayAuth{s}).Register(mux)
}

// gatewayAuth 用网关自身的令牌校验认证 Paircast 请求。
type gatewayAuth struct{ s *gateway.Server }

func (a gatewayAuth) Authenticate(w http.ResponseWriter, r *http.Request) (string, bool) {
	return a.s.AuthenticateHTTP(w, r)
}

// roomGroups 把 GroupStore 适配为 watchroom.Groups。
type roomGroups struct {
	store  gateway.GroupStore
	router *gateway.Router
}

func (g roomGroups) Create(ctx context.Context, name, ownerUID string) (string, error) {
	grp, err := g.store.Create(ctx, name, ownerUID, nil)
	if err != nil {
		return "", err
	}
	return grp.ID, nil
}

func (g roomGroups) Join(ctx context.Context, groupID, uid string) error {
	err := g.store.AddMember(ctx, groupID, uid)
	g.router.ForgetGroupMembers(groupID)
	switch {
	case err == nil, errors.Is(err, gateway.ErrAlreadyMember):
		return nil
	case errors.Is(err, gateway.ErrGroupNotFound):
		return watchroom.ErrEnded
	default:
		return err
	}
}

func (g roomGroups) Leave(ctx context.Context, groupID, uid string) error {
	err := g.store.RemoveMember(ctx, groupID, uid)
	g.router.ForgetGroupMembers(groupID)
	if errors.Is(err, gateway.ErrNotMember) || errors.Is(err, gateway.ErrGroupNotFound) {
		return nil
	}
	return err
}

// Dismiss 通知全体成员后逐个移出；最后一人移出时群被自动删除。
func (g roomGroups) Dismiss(ctx context.Context, groupID string) error {
	members, err := g.store.GetMembers(ctx, groupID)
	if errors.Is(err, gateway.ErrGroupNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	g.router.NotifyGroup(ctx, "", groupID, "group_dismissed", members)
	for _, uid := range members {
		if err := g.store.RemoveMember(ctx, groupID, uid); err != nil &&
			!errors.Is(err, gateway.ErrNotMember) && !errors.Is(err, gateway.ErrGroupNotFound) {
			g.router.ForgetGroupMembers(groupID)
			return err
		}
	}
	g.router.ForgetGroupMembers(groupID)
	return nil
}

// roomSignals 用群瞬时信令推送房间变化。
type roomSignals struct{ router *gateway.Router }

func (n roomSignals) RoomUpdated(ctx context.Context, actorUID string, room *watchroom.Room) {
	content, err := proto.EncodeSignal(paircast.RoomUpdatedSignal, map[string]any{"group_id": room.GroupID, "room": room})
	if err != nil {
		return
	}
	n.router.SendSignal(ctx, actorUID, proto.ChatTypeGroup, room.GroupID, 0, content)
}
