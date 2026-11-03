// Package paircast 是 Paircast（一起看）业务服务的 HTTP 层：媒体库（/media/*）与观影房间（/room/*）。
// 它只通过 IM 的公开能力工作 —— 令牌校验、群、瞬时信令、webhook —— 不依赖网关内部实现。
// 独立部署时由 cmd/paircast 用服务端 API 适配（imadapter.go）；过渡期网关也可以进程内挂载同一套接口。
package paircast

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/im/internal/paircast/media"
	"github.com/im/internal/paircast/watchroom"
)

// RoomUpdatedSignal 是房间变化的信令类型，data 为 {"group_id":...,"room":{...}}。
const RoomUpdatedSignal = "paircast.room_updated"

// Authenticator 认证请求并返回 IM uid；失败时自己写响应并返回 ok=false。
type Authenticator interface {
	Authenticate(w http.ResponseWriter, r *http.Request) (uid string, ok bool)
}

// Server 处理 /media/* 与 /room/*。media 为 nil 时全部返回 503，rooms 为 nil 时房间接口返回 503。
type Server struct {
	media *media.Service
	rooms *watchroom.Service
	auth  Authenticator
	fetch *rateLimiter
}

// NewServer 创建 HTTP 层。
func NewServer(svc *media.Service, rooms *watchroom.Service, auth Authenticator) *Server {
	return &Server{media: svc, rooms: rooms, auth: auth, fetch: newRateLimiter(subtitleFetchPerMinute)}
}

// Register 挂载 /media/* 与 /room/*。
func (s *Server) Register(mux *http.ServeMux) {
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

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func (s *Server) mediaAuth(w http.ResponseWriter, r *http.Request, method string) (uid string, ok bool) {
	if r.Method != method {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return "", false
	}
	if s.media == nil {
		http.Error(w, "media library not configured", http.StatusServiceUnavailable)
		return "", false
	}
	return s.auth.Authenticate(w, r)
}

func (s *Server) roomAuth(w http.ResponseWriter, r *http.Request, method string) (uid string, ok bool) {
	if r.Method != method {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return "", false
	}
	if s.rooms == nil {
		http.Error(w, "rooms not available", http.StatusServiceUnavailable)
		return "", false
	}
	return s.auth.Authenticate(w, r)
}

func writeMediaError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, media.ErrNotFound), errors.Is(err, watchroom.ErrNotFound):
		http.Error(w, "not found", http.StatusNotFound)
	case errors.Is(err, media.ErrUnknownSource), errors.Is(err, media.ErrInvalidID), errors.Is(err, watchroom.ErrInvalidHost):
		http.Error(w, err.Error(), http.StatusBadRequest)
	case errors.Is(err, media.ErrUnsupported):
		http.Error(w, err.Error(), http.StatusNotImplemented)
	case errors.Is(err, watchroom.ErrEnded):
		http.Error(w, "room ended", http.StatusGone)
	case errors.Is(err, watchroom.ErrNotMember), errors.Is(err, watchroom.ErrNotHost):
		http.Error(w, err.Error(), http.StatusForbidden)
	case errors.Is(err, watchroom.ErrConflict):
		http.Error(w, err.Error(), http.StatusConflict)
	default:
		log.Printf("[paircast] error: %v", err)
		http.Error(w, "upstream error", http.StatusBadGateway)
	}
}

// HandleMediaSources 返回已配置的视频源与服务端能力。
// GET /media/sources?uid=&token=
func (s *Server) HandleMediaSources(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	writeJSON(w, map[string]any{
		"sources":          s.media.Registry.Sources(),
		"online_subtitles": s.media.Subtitles.OnlineEnabled(),
		"stickers":         s.media.Stickers != nil,
	})
}

// HandleMediaItems 列出影片（已合并缓存的元数据）。
// GET /media/items?uid=&token=&source=&q=&cursor=&limit=&refresh=1&enrich=1
func (s *Server) HandleMediaItems(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	page, err := s.media.List(r.Context(), q.Get("source"), media.ListOptions{
		Query:   q.Get("q"),
		Cursor:  q.Get("cursor"),
		Limit:   limit,
		Refresh: q.Get("refresh") == "1",
	}, q.Get("enrich") == "1")
	if err != nil {
		writeMediaError(w, err)
		return
	}
	if page.Items == nil {
		page.Items = []media.Item{}
	}
	writeJSON(w, page)
}

// HandleMediaItem 返回单部影片（必要时刮削元数据）。
// GET /media/item?uid=&token=&id=
func (s *Server) HandleMediaItem(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	item, err := s.media.Item(r.Context(), r.URL.Query().Get("id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, item)
}

// HandleMediaPlay 返回播放直链（S3 为预签名 URL，视频流不经过服务器）。
// GET /media/play?uid=&token=&id=
func (s *Server) HandleMediaPlay(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	info, err := s.media.PlayURL(r.Context(), r.URL.Query().Get("id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, info)
}

// HandleMediaSubtitles 列出影片旁字幕；带 room_id 时附上房主共享的字幕。
// GET /media/subtitles?uid=&token=&id=&room_id=
func (s *Server) HandleMediaSubtitles(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.mediaAuth(w, r, http.MethodGet)
	if !ok {
		return
	}
	q := r.URL.Query()
	refs, err := s.media.Subtitles.Sidecars(r.Context(), q.Get("id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	if roomID := q.Get("room_id"); roomID != "" && s.rooms != nil {
		if room, err := s.rooms.Get(r.Context(), uid, roomID); err == nil && room.SubtitleRef != "" {
			label := room.SubtitleLabel
			if label == "" {
				label = "Host shared"
			}
			refs = append([]media.SubtitleRef{{
				Ref:    room.SubtitleRef,
				Label:  label,
				Format: strings.ToUpper(media.SubtitleExt(room.SubtitleRef)),
				Origin: "shared",
				Detail: "Host shared",
			}}, refs...)
		}
	}
	writeJSON(w, map[string]any{"subtitles": refs})
}

// HandleMediaSubtitle 返回字幕文件内容。
// GET /media/subtitle?uid=&token=&ref=
func (s *Server) HandleMediaSubtitle(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	data, ext, err := s.media.Subtitles.Open(r.Context(), r.URL.Query().Get("ref"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("X-Subtitle-Format", ext)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Write(data)
}

// HandleMediaSubtitleSearch 在线搜索字幕（OpenSubtitles，API Key 在服务端）。
// GET /media/subtitles/search?uid=&token=&q=&year=
func (s *Server) HandleMediaSubtitleSearch(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	refs, err := s.media.Subtitles.Search(r.Context(), r.URL.Query().Get("q"), r.URL.Query().Get("year"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, map[string]any{"subtitles": refs})
}

// HandleMediaSubtitleFetch 把在线字幕下载到服务器存储，返回可读取的 ref。每个用户每分钟限 subtitleFetchPerMinute 次
// （OpenSubtitles 按下载次数计费）。
// POST /media/subtitles/fetch  (uid, token, ref=os:<file_id>)
func (s *Server) HandleMediaSubtitleFetch(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.mediaAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	if !s.fetch.allow(uid) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}
	ref, err := s.media.Subtitles.Fetch(r.Context(), r.FormValue("ref"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, map[string]string{"ref": ref})
}

// HandleMediaSubtitleShare 房主共享字幕：保存到服务器存储并记到房间上。
// POST /media/subtitles/share  multipart (uid, token, room_id, label, file)
func (s *Server) HandleMediaSubtitleShare(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		r.Body = http.MaxBytesReader(w, r.Body, media.MaxSubtitleBytes+64<<10)
		if err := r.ParseMultipartForm(media.MaxSubtitleBytes + 64<<10); err != nil {
			http.Error(w, "invalid multipart form", http.StatusBadRequest)
			return
		}
	}
	uid, ok := s.mediaAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	if s.rooms == nil {
		http.Error(w, "rooms not available", http.StatusServiceUnavailable)
		return
	}
	roomID := r.FormValue("room_id")
	if _, err := s.rooms.RequireHost(r.Context(), uid, roomID); err != nil {
		writeMediaError(w, err)
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		http.Error(w, "file required", http.StatusBadRequest)
		return
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, media.MaxSubtitleBytes+1))
	if err != nil {
		http.Error(w, "read failed", http.StatusBadRequest)
		return
	}
	ref, err := s.media.Subtitles.Share(r.Context(), roomID, header.Filename, data)
	if err != nil {
		if errors.Is(err, media.ErrUnsupported) {
			writeMediaError(w, err)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	label := strings.TrimSpace(r.FormValue("label"))
	if _, err := s.rooms.SetSubtitle(r.Context(), uid, roomID, ref, label); err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, map[string]string{"ref": ref})
}

// HandleMediaStickers 返回贴纸目录（catalog.json）。
// GET /media/stickers?uid=&token=
func (s *Server) HandleMediaStickers(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	if s.media.Stickers == nil {
		http.Error(w, "stickers not configured", http.StatusNotFound)
		return
	}
	data, err := s.media.Stickers.Catalog(r.Context())
	if err != nil {
		writeMediaError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
}

// HandleMediaStickerPack 返回 pack.json，每张贴纸附带签名直链 url。
// GET /media/stickers/pack?uid=&token=&id=
func (s *Server) HandleMediaStickerPack(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.mediaAuth(w, r, http.MethodGet); !ok {
		return
	}
	if s.media.Stickers == nil {
		http.Error(w, "stickers not configured", http.StatusNotFound)
		return
	}
	data, err := s.media.Stickers.Pack(r.Context(), r.URL.Query().Get("id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
}
