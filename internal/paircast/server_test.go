package paircast

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/im/configs"
	"github.com/im/internal/paircast/media"
	"github.com/im/internal/paircast/media/s3source"
	"github.com/im/internal/paircast/watchroom"
	"github.com/im/pkg/imserver"
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

type memBlobs struct {
	mu   sync.Mutex
	data map[string][]byte
}

func (b *memBlobs) Put(_ context.Context, key string, data []byte, _ string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.data[key] = data
	return nil
}

func (b *memBlobs) Get(_ context.Context, key string) ([]byte, string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	d, ok := b.data[key]
	if !ok {
		return nil, "", media.ErrNotFound
	}
	return d, "text/plain", nil
}

type fakeGroups struct {
	mu      sync.Mutex
	n       int
	members map[string]map[string]bool
}

func (g *fakeGroups) Create(_ context.Context, _, owner string) (string, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.n++
	id := fmt.Sprintf("g_%d", g.n)
	g.members[id] = map[string]bool{owner: true}
	return id, nil
}

func (g *fakeGroups) Join(_ context.Context, groupID, uid string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	m, ok := g.members[groupID]
	if !ok {
		return watchroom.ErrEnded
	}
	m[uid] = true
	return nil
}

func (g *fakeGroups) Leave(_ context.Context, groupID, uid string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.members[groupID], uid)
	return nil
}

func (g *fakeGroups) Dismiss(_ context.Context, groupID string) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.members, groupID)
	return nil
}

type nopNotifier struct{}

func (nopNotifier) RoomUpdated(context.Context, string, *watchroom.Room) {}

// tokenAuth 接受 token == "t-"+uid。
type tokenAuth struct{}

func (tokenAuth) Authenticate(w http.ResponseWriter, r *http.Request) (string, bool) {
	uid := r.FormValue("uid")
	if uid == "" || r.FormValue("token") != "t-"+uid {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return "", false
	}
	return uid, true
}

type mediaEnv struct {
	srv   *Server
	rooms *watchroom.Service
}

func newMediaEnv(t *testing.T) *mediaEnv {
	t.Helper()
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
	blobs := &memBlobs{data: map[string][]byte{}}
	svc, err := media.NewService(cfg, factories, nil, blobs, func() string { n++; return "blob" + strconv.Itoa(n) })
	if err != nil {
		t.Fatal(err)
	}
	groups := &fakeGroups{members: map[string]map[string]bool{}}
	rooms := watchroom.NewService(watchroom.NewMemoryStore(), groups, nopNotifier{}, watchroom.NewRandomID)
	return &mediaEnv{srv: NewServer(svc, rooms, tokenAuth{}), rooms: rooms}
}

func authValues(uid string, q url.Values) url.Values {
	q.Set("uid", uid)
	q.Set("token", "t-"+uid)
	return q
}

func (e *mediaEnv) call(t *testing.T, h http.HandlerFunc, method, uid string, q url.Values) *httptest.ResponseRecorder {
	t.Helper()
	q = authValues(uid, q)
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
	e := &mediaEnv{srv: NewServer(nil, nil, tokenAuth{})}
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

func TestRoomRejectsUnknownMovie(t *testing.T) {
	e := newMediaEnv(t)
	if rec := e.call(t, e.srv.HandleRoomCreate, http.MethodPost, "100001", url.Values{"movie_id": {"oss:films/missing.mkv"}}); rec.Code != http.StatusNotFound {
		t.Fatalf("create with unknown movie: %d %s", rec.Code, rec.Body.String())
	}
	rec := e.call(t, e.srv.HandleRoomCreate, http.MethodPost, "100001", url.Values{"movie_id": {"oss:films/Inception.2010.1080p.mkv"}})
	room := decodeJSON[watchroom.Room](t, rec)
	if rec := e.call(t, e.srv.HandleRoomUpdate, http.MethodPost, "100001", url.Values{"room_id": {room.ID}, "movie_id": {"oss:films/missing.mkv"}}); rec.Code != http.StatusNotFound {
		t.Fatalf("update with unknown movie: %d %s", rec.Code, rec.Body.String())
	}
}

func TestSubtitleFetchRateLimited(t *testing.T) {
	e := newMediaEnv(t)
	now := time.Unix(1_700_000_000, 0)
	e.srv.fetch.now = func() time.Time { return now }
	for i := 0; i < subtitleFetchPerMinute; i++ {
		if rec := e.call(t, e.srv.HandleMediaSubtitleFetch, http.MethodPost, "100001", url.Values{"ref": {"os:1"}}); rec.Code == http.StatusTooManyRequests {
			t.Fatalf("call %d limited too early", i)
		}
	}
	if rec := e.call(t, e.srv.HandleMediaSubtitleFetch, http.MethodPost, "100001", url.Values{"ref": {"os:1"}}); rec.Code != http.StatusTooManyRequests {
		t.Fatalf("over limit: %d", rec.Code)
	}
	if rec := e.call(t, e.srv.HandleMediaSubtitleFetch, http.MethodPost, "100002", url.Values{"ref": {"os:1"}}); rec.Code == http.StatusTooManyRequests {
		t.Fatal("limit should be per user")
	}
	now = now.Add(time.Minute)
	if rec := e.call(t, e.srv.HandleMediaSubtitleFetch, http.MethodPost, "100001", url.Values{"ref": {"os:1"}}); rec.Code == http.StatusTooManyRequests {
		t.Fatal("bucket should refill after a minute")
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
	for k, v := range authValues(alice, url.Values{"room_id": {room.ID}, "label": {"中文"}}) {
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

type fakeVerifier struct {
	calls int
	infos map[string]*imserver.TokenInfo
	err   error
}

func (v *fakeVerifier) VerifyToken(_ context.Context, token string) (*imserver.TokenInfo, error) {
	v.calls++
	if v.err != nil {
		return nil, v.err
	}
	if info, ok := v.infos[token]; ok {
		return info, nil
	}
	return &imserver.TokenInfo{Valid: false, Reason: "token revoked"}, nil
}

func TestIMAuth(t *testing.T) {
	v := &fakeVerifier{infos: map[string]*imserver.TokenInfo{"good": {Valid: true, UID: "100001"}}}
	a := NewIMAuth(v)
	now := time.Unix(1_700_000_000, 0)
	a.now = func() time.Time { return now }

	run := func(target string, header string) (*httptest.ResponseRecorder, string) {
		req := httptest.NewRequest(http.MethodGet, target, nil)
		if header != "" {
			req.Header.Set("Authorization", "Bearer "+header)
		}
		rec := httptest.NewRecorder()
		uid, _ := a.Authenticate(rec, req)
		return rec, uid
	}

	if rec, _ := run("/", ""); rec.Code != http.StatusUnauthorized {
		t.Fatalf("missing token: %d", rec.Code)
	}
	if _, uid := run("/?token=good", ""); uid != "100001" {
		t.Fatalf("query token uid = %q", uid)
	}
	if _, uid := run("/", "good"); uid != "100001" || v.calls != 1 {
		t.Fatalf("bearer token uid = %q calls = %d (want cached)", uid, v.calls)
	}
	if rec, _ := run("/?token=good&uid=100002", ""); rec.Code != http.StatusForbidden {
		t.Fatalf("uid mismatch: %d", rec.Code)
	}
	if rec, _ := run("/?token=bad", ""); rec.Code != http.StatusUnauthorized || !strings.Contains(rec.Body.String(), "revoked") {
		t.Fatalf("invalid token: %d %s", rec.Code, rec.Body.String())
	}
	now = now.Add(tokenCacheTTL + time.Second)
	run("/?token=good", "")
	if v.calls != 3 {
		t.Fatalf("cache should expire, calls = %d", v.calls)
	}
	v.err = errors.New("down")
	if rec, _ := run("/?token=other", ""); rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("verifier down: %d", rec.Code)
	}
}

func signedWebhook(t *testing.T, secret, typ string, data any) *http.Request {
	t.Helper()
	raw, _ := json.Marshal(data)
	body, _ := json.Marshal(imserver.WebhookEvent{ID: "d1", Type: typ, AppID: "paircast", Timestamp: time.Now().Unix(), Data: raw})
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	mac := hmac.New(sha256.New, []byte(secret))
	io.WriteString(mac, ts+"\n")
	mac.Write(body)
	req := httptest.NewRequest(http.MethodPost, "/im/webhook", bytes.NewReader(body))
	req.Header.Set(imserver.HeaderWebhookEvent, typ)
	req.Header.Set(imserver.HeaderWebhookTimestamp, ts)
	req.Header.Set(imserver.HeaderWebhookSignature, hex.EncodeToString(mac.Sum(nil)))
	return req
}

func TestWebhookHandlerSyncsRooms(t *testing.T) {
	e := newMediaEnv(t)
	ctx := context.Background()
	room, err := e.rooms.Create(ctx, "100001", "oss:films/Inception.2010.1080p.mkv")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.rooms.Join(ctx, "100002", room.ID); err != nil {
		t.Fatal(err)
	}
	h := WebhookHandler(e.rooms, "whsec")

	rec := httptest.NewRecorder()
	h(rec, signedWebhook(t, "wrong", imserver.EventGroupMemberLeft, map[string]string{"group_id": room.GroupID, "uid": "100002"}))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("bad signature: %d", rec.Code)
	}

	rec = httptest.NewRecorder()
	h(rec, signedWebhook(t, "whsec", imserver.EventGroupMemberLeft, map[string]string{"group_id": room.GroupID, "uid": "100002"}))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("member_left: %d %s", rec.Code, rec.Body.String())
	}
	got, err := e.rooms.Get(ctx, "100001", room.ID)
	if err != nil || len(got.Members) != 1 {
		t.Fatalf("after member_left: %+v %v", got, err)
	}

	rec = httptest.NewRecorder()
	h(rec, signedWebhook(t, "whsec", imserver.EventGroupDismissed, map[string]string{"group_id": room.GroupID}))
	if rec.Code != http.StatusNoContent {
		t.Fatalf("dismissed: %d", rec.Code)
	}
	if _, err := e.rooms.Join(ctx, "100002", room.ID); !errors.Is(err, watchroom.ErrEnded) {
		t.Fatalf("join after dismiss: %v", err)
	}
}
