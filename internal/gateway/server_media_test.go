package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/media"
	"github.com/im/internal/media/s3source"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/watchroom"
)

type mediaFakeAPI struct{ objects map[string]string }

func (f *mediaFakeAPI) List(_ context.Context, prefix string) ([]s3source.ObjectInfo, error) {
	var out []s3source.ObjectInfo
	for k, v := range f.objects {
		if strings.HasPrefix(k, prefix) {
			out = append(out, s3source.ObjectInfo{Key: k, Size: int64(len(v))})
		}
	}
	return out, nil
}

func (f *mediaFakeAPI) Exists(_ context.Context, key string) (bool, error) {
	_, ok := f.objects[key]
	return ok, nil
}

func (f *mediaFakeAPI) Get(_ context.Context, key string) (io.ReadCloser, error) {
	v, ok := f.objects[key]
	if !ok {
		return nil, media.ErrNotFound
	}
	return io.NopCloser(strings.NewReader(v)), nil
}

func (f *mediaFakeAPI) Presign(_ context.Context, key string, _ time.Duration) (string, error) {
	return "https://cdn.test/" + key + "?sig=1", nil
}

type mediaEnv struct {
	srv *Server
	jwt *jwt.Manager
}

func newMediaEnv(t *testing.T) *mediaEnv {
	t.Helper()
	acc := newAccountEnv(t, false)
	groups := newTestGroupStore(t)
	acc.router.SetGroupStore(groups)
	api := &mediaFakeAPI{objects: map[string]string{
		"films/Inception.2010.1080p.mkv":    "video",
		"films/Inception.2010.1080p.zh.srt": "1\n00:00:01,000 --> 00:00:02,000\n你好\n",
		"films/Interstellar (2014).mp4":     "video",
		"stickers/catalog.json":             `{"packs":[{"id":"cats"}]}`,
		"stickers/cats/pack.json":           `{"id":"cats","cover":"cover.png","stickers":[{"id":"a","file":"a.png"}]}`,
	}}
	cfg := configs.MediaConfig{
		Sources:        []configs.MediaSourceConfig{{ID: "oss", Name: "Library", Type: "fake"}},
		DefaultSource:  "oss",
		StickersSource: "oss",
	}
	factories := map[string]media.SourceFactory{
		"fake": func(sc configs.MediaSourceConfig, _ configs.MediaConfig) (media.Source, error) {
			return s3source.New(api, s3source.Options{ID: sc.ID, Name: sc.Name, Prefix: "films/"}), nil
		},
	}
	n := 0
	svc, err := media.NewService(cfg, factories, nil, acc.objs, func() string { n++; return "blob" + string(rune('0'+n)) })
	if err != nil {
		t.Fatal(err)
	}
	rooms := watchroom.NewService(watchroom.NewMemoryStore(), RoomGroups{Store: groups}, RoomSignals{Router: acc.router}, watchroom.NewRandomID)
	acc.srv.SetMedia(svc, rooms)
	return &mediaEnv{srv: acc.srv, jwt: acc.jwt}
}

func (e *mediaEnv) auth(t *testing.T, uid string, q url.Values) url.Values {
	t.Helper()
	token, err := e.jwt.Generate(uid, uid, "user")
	if err != nil {
		t.Fatal(err)
	}
	q.Set("uid", uid)
	q.Set("token", token)
	return q
}

func (e *mediaEnv) call(t *testing.T, h http.HandlerFunc, method, uid string, q url.Values) *httptest.ResponseRecorder {
	t.Helper()
	q = e.auth(t, uid, q)
	var req *http.Request
	if method == http.MethodGet {
		req = httptest.NewRequest(method, "/?"+q.Encode(), nil)
	} else {
		req = httptest.NewRequest(method, "/", strings.NewReader(q.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec
}

func decodeJSON[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return v
}

func TestMediaDisabledReturns503(t *testing.T) {
	acc := newAccountEnv(t, false)
	e := &mediaEnv{srv: acc.srv, jwt: acc.jwt}
	if rec := e.call(t, e.srv.HandleMediaItems, http.MethodGet, "100001", url.Values{}); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("items: %d", rec.Code)
	}
	if rec := e.call(t, e.srv.HandleRoomCreate, http.MethodPost, "100001", url.Values{"movie_id": {"x"}}); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("room: %d", rec.Code)
	}
}

func TestMediaLibraryEndpoints(t *testing.T) {
	e := newMediaEnv(t)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()
	e.srv.HandleMediaItems(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated: %d", rec.Code)
	}

	rec = e.call(t, e.srv.HandleMediaSources, http.MethodGet, "100001", url.Values{})
	sources := decodeJSON[struct {
		Sources  []media.SourceInfo `json:"sources"`
		Stickers bool               `json:"stickers"`
	}](t, rec)
	if len(sources.Sources) != 1 || sources.Sources[0].ID != "oss" || !sources.Stickers {
		t.Fatalf("sources = %s", rec.Body.String())
	}

	rec = e.call(t, e.srv.HandleMediaItems, http.MethodGet, "100001", url.Values{})
	page := decodeJSON[media.Page](t, rec)
	if len(page.Items) != 2 || page.Items[0].ID != "oss:films/Inception.2010.1080p.mkv" || page.Items[0].Title != "Inception" {
		t.Fatalf("items = %s", rec.Body.String())
	}

	rec = e.call(t, e.srv.HandleMediaPlay, http.MethodGet, "100001", url.Values{"id": {"oss:films/Inception.2010.1080p.mkv"}})
	play := decodeJSON[media.PlayInfo](t, rec)
	if !strings.HasPrefix(play.URL, "https://cdn.test/films/Inception") || play.Format != "mkv" {
		t.Fatalf("play = %s", rec.Body.String())
	}
	// 不带数据源前缀的旧 ID 解析到默认数据源。
	if rec := e.call(t, e.srv.HandleMediaPlay, http.MethodGet, "100001", url.Values{"id": {"films/Inception.2010.1080p.mkv"}}); rec.Code != http.StatusOK {
		t.Fatalf("legacy id: %d", rec.Code)
	}
	if rec := e.call(t, e.srv.HandleMediaPlay, http.MethodGet, "100001", url.Values{"id": {"oss:other/secret.mkv"}}); rec.Code != http.StatusNotFound {
		t.Fatalf("out of scope: %d", rec.Code)
	}
	if rec := e.call(t, e.srv.HandleMediaPlay, http.MethodGet, "100001", url.Values{"id": {"oss:films/Inception.2010.1080p.mkv"}}); rec.Code != http.StatusOK {
		t.Fatalf("play again: %d", rec.Code)
	}

	rec = e.call(t, e.srv.HandleMediaSubtitles, http.MethodGet, "100001", url.Values{"id": {"oss:films/Inception.2010.1080p.mkv"}})
	subs := decodeJSON[struct {
		Subtitles []media.SubtitleRef `json:"subtitles"`
	}](t, rec)
	if len(subs.Subtitles) != 1 {
		t.Fatalf("subtitles = %s", rec.Body.String())
	}
	rec = e.call(t, e.srv.HandleMediaSubtitle, http.MethodGet, "100001", url.Values{"ref": {subs.Subtitles[0].Ref}})
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "你好") || rec.Header().Get("X-Subtitle-Format") != "srt" {
		t.Fatalf("subtitle: %d %q %v", rec.Code, rec.Body.String(), rec.Header())
	}

	rec = e.call(t, e.srv.HandleMediaStickers, http.MethodGet, "100001", url.Values{})
	if rec.Code != http.StatusOK || !strings.Contains(rec.Body.String(), "cats") {
		t.Fatalf("stickers: %d %s", rec.Code, rec.Body.String())
	}
	rec = e.call(t, e.srv.HandleMediaStickerPack, http.MethodGet, "100001", url.Values{"id": {"cats"}})
	if !strings.Contains(rec.Body.String(), `"url":"https://cdn.test/stickers/cats/a.png?sig=1"`) || !strings.Contains(rec.Body.String(), "cover_url") {
		t.Fatalf("pack: %s", rec.Body.String())
	}
}

func TestRoomLifecycleAndSharedSubtitle(t *testing.T) {
	e := newMediaEnv(t)
	const alice, bob = "100001", "100002"
	movie := "oss:films/Inception.2010.1080p.mkv"

	rec := e.call(t, e.srv.HandleRoomCreate, http.MethodPost, alice, url.Values{"movie_id": {movie}})
	room := decodeJSON[watchroom.Room](t, rec)
	if room.ID == "" || room.HostUID != alice || room.GroupID == "" {
		t.Fatalf("create = %s", rec.Body.String())
	}

	if rec := e.call(t, e.srv.HandleRoomGet, http.MethodGet, bob, url.Values{"room_id": {room.ID}}); rec.Code != http.StatusForbidden {
		t.Fatalf("non-member get: %d", rec.Code)
	}
	rec = e.call(t, e.srv.HandleRoomJoin, http.MethodPost, bob, url.Values{"room_id": {strings.ToUpper(room.ID)}})
	room = decodeJSON[watchroom.Room](t, rec)
	if len(room.Members) != 2 {
		t.Fatalf("join = %s", rec.Body.String())
	}

	if rec := e.call(t, e.srv.HandleRoomUpdate, http.MethodPost, bob, url.Values{"room_id": {room.ID}, "movie_id": {"oss:films/Interstellar (2014).mp4"}}); rec.Code != http.StatusForbidden {
		t.Fatalf("guest change movie: %d", rec.Code)
	}
	rec = e.call(t, e.srv.HandleRoomUpdate, http.MethodPost, alice, url.Values{"room_id": {room.ID}, "movie_id": {"oss:films/Interstellar (2014).mp4"}})
	updated := decodeJSON[watchroom.Room](t, rec)
	if updated.MovieID != "oss:films/Interstellar (2014).mp4" || updated.Revision <= room.Revision {
		t.Fatalf("update = %s", rec.Body.String())
	}
	if rec := e.call(t, e.srv.HandleRoomUpdate, http.MethodPost, alice, url.Values{"room_id": {room.ID}, "last_applied_seq": {"3"}, "if_revision": {"0"}}); rec.Code != http.StatusConflict {
		t.Fatalf("stale revision: %d", rec.Code)
	}

	// 房主共享字幕（multipart）。
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for k, v := range e.auth(t, alice, url.Values{"room_id": {room.ID}, "label": {"中文"}}) {
		_ = mw.WriteField(k, v[0])
	}
	fw, _ := mw.CreateFormFile("file", "movie.zh.srt")
	_, _ = fw.Write([]byte("1\n00:00:01,000 --> 00:00:02,000\nshared\n"))
	_ = mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	rec = httptest.NewRecorder()
	e.srv.HandleMediaSubtitleShare(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("share: %d %s", rec.Code, rec.Body.String())
	}

	rec = e.call(t, e.srv.HandleMediaSubtitles, http.MethodGet, bob, url.Values{"id": {"oss:films/Interstellar (2014).mp4"}, "room_id": {room.ID}})
	subs := decodeJSON[struct {
		Subtitles []media.SubtitleRef `json:"subtitles"`
	}](t, rec)
	if len(subs.Subtitles) != 1 || subs.Subtitles[0].Origin != "shared" || subs.Subtitles[0].Label != "中文" {
		t.Fatalf("room subtitles = %s", rec.Body.String())
	}
	rec = e.call(t, e.srv.HandleMediaSubtitle, http.MethodGet, bob, url.Values{"ref": {subs.Subtitles[0].Ref}})
	if !strings.Contains(rec.Body.String(), "shared") {
		t.Fatalf("shared content: %d %s", rec.Code, rec.Body.String())
	}

	rec = e.call(t, e.srv.HandleRoomLeave, http.MethodPost, alice, url.Values{"room_id": {room.ID}, "transfer_seq": {"7"}})
	left := decodeJSON[watchroom.Room](t, rec)
	if left.HostUID != bob || left.HostTransferSeq != 7 || left.Status != watchroom.StatusActive {
		t.Fatalf("host leave = %s", rec.Body.String())
	}
	rec = e.call(t, e.srv.HandleRoomLeave, http.MethodPost, bob, url.Values{"room_id": {room.ID}})
	if final := decodeJSON[watchroom.Room](t, rec); final.Status != watchroom.StatusEnded {
		t.Fatalf("last leave = %s", rec.Body.String())
	}
	if rec := e.call(t, e.srv.HandleRoomJoin, http.MethodPost, alice, url.Values{"room_id": {room.ID}}); rec.Code != http.StatusGone {
		t.Fatalf("join ended: %d", rec.Code)
	}
}
