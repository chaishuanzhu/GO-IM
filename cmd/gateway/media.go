package main

import (
	"database/sql"
	"fmt"
	"log"
	"net/http"

	"github.com/im/configs"
	"github.com/im/internal/gateway"
	"github.com/im/internal/media"
	"github.com/im/internal/media/s3source"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/watchroom"
)

// mediaSourceFactories 按 type 创建视频源。新增 openlist / emby / jellyfin 时在这里注册。
var mediaSourceFactories = map[string]media.SourceFactory{
	"s3": func(sc configs.MediaSourceConfig, cfg configs.MediaConfig) (media.Source, error) {
		return s3source.FromConfig(sc, cfg)
	},
}

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
	rooms := watchroom.NewService(store, gateway.RoomGroups{Store: groups}, gateway.RoomSignals{Router: router}, watchroom.NewRandomID)
	ids := make([]string, 0, len(cfg.Sources))
	for _, s := range svc.Registry.Sources() {
		ids = append(ids, s.ID)
	}
	log.Printf("[main] media library enabled: sources=%v online_subtitles=%v stickers=%v",
		ids, svc.Subtitles.OnlineEnabled(), svc.Stickers != nil)
	return svc, rooms, nil
}

// registerMediaRoutes 挂载 /media/* 与 /room/*（未启用时返回 503）。
func registerMediaRoutes(mux *http.ServeMux, s *gateway.Server) {
	mux.HandleFunc("/media/sources", s.HandleMediaSources)
	mux.HandleFunc("/media/items", s.HandleMediaItems)
	mux.HandleFunc("/media/item", s.HandleMediaItem)
	mux.HandleFunc("/media/play", s.HandleMediaPlay)
	mux.HandleFunc("/media/subtitles", s.HandleMediaSubtitles)
	mux.HandleFunc("/media/subtitle", s.HandleMediaSubtitle)
	mux.HandleFunc("/media/subtitles/search", s.HandleMediaSubtitleSearch)
	mux.HandleFunc("/media/subtitles/fetch", s.HandleMediaSubtitleFetch)
	mux.HandleFunc("/media/subtitles/share", s.HandleMediaSubtitleShare)
	mux.HandleFunc("/media/stickers", s.HandleMediaStickers)
	mux.HandleFunc("/media/stickers/pack", s.HandleMediaStickerPack)

	mux.HandleFunc("/room", s.HandleRoomGet)
	mux.HandleFunc("/room/create", s.HandleRoomCreate)
	mux.HandleFunc("/room/join", s.HandleRoomJoin)
	mux.HandleFunc("/room/leave", s.HandleRoomLeave)
	mux.HandleFunc("/room/update", s.HandleRoomUpdate)
}
