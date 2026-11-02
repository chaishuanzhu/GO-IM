package gateway

import (
	"net/http"
	"strconv"

	"github.com/im/internal/watchroom"
)

func (s *Server) roomAuth(w http.ResponseWriter, r *http.Request, method string) (uid string, ok bool) {
	if r.Method != method {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return "", false
	}
	if s.rooms == nil {
		http.Error(w, "rooms not available", http.StatusServiceUnavailable)
		return "", false
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return "", false
	}
	return claims.UID, true
}

func formInt64(r *http.Request, key string) *int64 {
	raw := r.FormValue(key)
	if raw == "" {
		return nil
	}
	v, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return nil
	}
	return &v
}

func formString(r *http.Request, key string) *string {
	if _, present := r.Form[key]; !present {
		return nil
	}
	v := r.FormValue(key)
	return &v
}

// HandleRoomCreate 新建观影房间（同时建群），调用者为房主。
// POST /room/create  (uid, token, movie_id)
func (s *Server) HandleRoomCreate(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.roomAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	movieID := r.FormValue("movie_id")
	if movieID == "" {
		http.Error(w, "movie_id required", http.StatusBadRequest)
		return
	}
	room, err := s.rooms.Create(r.Context(), uid, movieID)
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, room)
}

// HandleRoomJoin 加入房间（同时入群）。
// POST /room/join  (uid, token, room_id)
func (s *Server) HandleRoomJoin(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.roomAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	room, err := s.rooms.Join(r.Context(), uid, r.FormValue("room_id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, room)
}

// HandleRoomLeave 离开房间：房主离开按加入顺序转让，最后一人离开结束房间。
// POST /room/leave  (uid, token, room_id, transfer_seq?)
func (s *Server) HandleRoomLeave(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.roomAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	var seq int64
	if v := formInt64(r, "transfer_seq"); v != nil {
		seq = *v
	}
	room, err := s.rooms.Leave(r.Context(), uid, r.FormValue("room_id"), seq)
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, room)
}

// HandleRoomUpdate 修改房间（换片 / 转让房主 / 结束仅房主；序号只增不减）。
// POST /room/update  (uid, token, room_id, movie_id?, host_uid?, host_transfer_seq?, last_applied_seq?, status=ended?, if_revision?)
func (s *Server) HandleRoomUpdate(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.roomAuth(w, r, http.MethodPost)
	if !ok {
		return
	}
	room, err := s.rooms.Update(r.Context(), uid, r.FormValue("room_id"), watchroom.Patch{
		MovieID:         formString(r, "movie_id"),
		HostUID:         formString(r, "host_uid"),
		HostTransferSeq: formInt64(r, "host_transfer_seq"),
		LastAppliedSeq:  formInt64(r, "last_applied_seq"),
		Ended:           r.FormValue("status") == watchroom.StatusEnded,
		IfRevision:      formInt64(r, "if_revision"),
	})
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, room)
}

// HandleRoomGet 返回房间状态（仅成员）。
// GET /room?uid=&token=&room_id=
func (s *Server) HandleRoomGet(w http.ResponseWriter, r *http.Request) {
	uid, ok := s.roomAuth(w, r, http.MethodGet)
	if !ok {
		return
	}
	room, err := s.rooms.Get(r.Context(), uid, r.URL.Query().Get("room_id"))
	if err != nil {
		writeMediaError(w, err)
		return
	}
	writeJSON(w, room)
}
