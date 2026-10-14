package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/pkg/jwt"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
)

// fakeDirect 用内存对象存储模拟直传：PUT 由测试直接写入 store。
type fakeDirect struct{ store *InMemoryObjectStore }

func (f *fakeDirect) PresignPut(_ context.Context, key string, size int64, ct string, _ time.Duration) (string, http.Header, error) {
	h := http.Header{}
	h.Set("Content-Type", ct)
	h.Set("Content-Length", strconv.FormatInt(size, 10))
	return "https://oss.test/put/" + key, h, nil
}

func (f *fakeDirect) PresignGet(_ context.Context, key, filename string, _ time.Duration) (string, error) {
	return "https://oss.test/get/" + key + "?fn=" + url.QueryEscape(filename), nil
}

func (f *fakeDirect) Stat(ctx context.Context, key string) (int64, string, error) {
	data, ct, err := f.store.Get(ctx, key)
	return int64(len(data)), ct, err
}

func (f *fakeDirect) ReadHead(ctx context.Context, key string, n int64) ([]byte, error) {
	data, _, err := f.store.Get(ctx, key)
	if int64(len(data)) > n {
		data = data[:n]
	}
	return data, err
}

type fileTestEnv struct {
	srv    *Server
	router *Router
	hub    *Hub
	jwt    *jwt.Manager
	objs   *InMemoryObjectStore
	files  *InMemoryFileStore
	groups *InMemoryGroupStore
}

func newFileTestEnv(t *testing.T, direct bool) *fileTestEnv {
	t.Helper()
	hub := NewHub(100)
	sg, _ := snowflake.New(1)
	router := NewRouter(hub, hub, sg, nil, DefaultRouterConfig())
	groups := NewInMemoryGroupStore(sg)
	router.SetGroupStore(groups)
	files := NewInMemoryFileStore()
	router.SetFileStore(files)
	objs := NewInMemoryObjectStore()
	mgr := jwt.New("test-secret", time.Hour)
	srv := NewServer(hub, router, mgr, nil, nil, groups, configs.AuthConfig{},
		time.Second, 3, configs.GatewayConnConfig{}, nil, sg, objs, 10<<20, nil)
	srv.SetFileStore(files)
	if direct {
		srv.SetDirectUpload(&fakeDirect{store: objs}, 100<<20, 15*time.Minute, time.Hour)
	}
	return &fileTestEnv{srv: srv, router: router, hub: hub, jwt: mgr, objs: objs, files: files, groups: groups}
}

func (e *fileTestEnv) post(t *testing.T, h http.HandlerFunc, uid string, form url.Values) *httptest.ResponseRecorder {
	t.Helper()
	form.Set("uid", uid)
	form.Set("token", jwtToken(t, e.jwt, uid, uid))
	req := httptest.NewRequest(http.MethodPost, "/", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec
}

func (e *fileTestEnv) download(t *testing.T, uid, id string, extra string) *httptest.ResponseRecorder {
	t.Helper()
	q := fmt.Sprintf("/file?id=%s&uid=%s&token=%s%s", id, uid, jwtToken(t, e.jwt, uid, uid), extra)
	rec := httptest.NewRecorder()
	e.srv.HandleDownload(rec, httptest.NewRequest(http.MethodGet, q, nil))
	return rec
}

// presign 签发上传并返回 file_id。
func (e *fileTestEnv) presign(t *testing.T, uid, name string, size int, mime string) string {
	t.Helper()
	rec := e.post(t, e.srv.HandlePresign, uid, url.Values{
		"name": {name}, "size": {strconv.Itoa(size)}, "mime": {mime},
	})
	if rec.Code != http.StatusOK {
		t.Fatalf("presign: %d %s", rec.Code, rec.Body.String())
	}
	var resp struct {
		FileID    string            `json:"file_id"`
		UploadURL string            `json:"upload_url"`
		Method    string            `json:"method"`
		Headers   map[string]string `json:"headers"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode presign: %v", err)
	}
	if resp.Method != "PUT" || resp.UploadURL == "" || resp.Headers["Content-Type"] != mime {
		t.Fatalf("unexpected presign response: %+v", resp)
	}
	return resp.FileID
}

func decodeFileResp(t *testing.T, rec *httptest.ResponseRecorder) map[string]interface{} {
	t.Helper()
	var m map[string]interface{}
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("decode: %v (%s)", err, rec.Body.String())
	}
	return m
}

// uploadReady 走完 presign → PUT → complete，返回 file_id。
func (e *fileTestEnv) uploadReady(t *testing.T, uid string, data []byte, mime string) string {
	t.Helper()
	id := e.presign(t, uid, "f.bin", len(data), mime)
	_ = e.objs.Put(context.Background(), id, data, mime)
	if rec := e.post(t, e.srv.HandleComplete, uid, url.Values{"file_id": {id}}); rec.Code != http.StatusOK {
		t.Fatalf("complete: %d %s", rec.Code, rec.Body.String())
	}
	return id
}

func fileMsg(to, id string) *proto.Message {
	return &proto.Message{Cmd: proto.CmdFile, To: to, MsgType: 5, Content: `{"file_id":"` + id + `","name":"f.bin"}`}
}

func TestPresignNotEnabled(t *testing.T) {
	e := newFileTestEnv(t, false)
	rec := e.post(t, e.srv.HandlePresign, "alice", url.Values{"name": {"a"}, "size": {"1"}})
	if rec.Code != http.StatusNotImplemented {
		t.Fatalf("expected 501, got %d", rec.Code)
	}
}

func TestPresignValidation(t *testing.T) {
	e := newFileTestEnv(t, true)
	for _, size := range []string{"0", "-1", "abc"} {
		if rec := e.post(t, e.srv.HandlePresign, "alice", url.Values{"size": {size}}); rec.Code != http.StatusBadRequest {
			t.Errorf("size=%s: expected 400, got %d", size, rec.Code)
		}
	}
	rec := e.post(t, e.srv.HandlePresign, "alice", url.Values{"size": {strconv.Itoa(100<<20 + 1)}})
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("expected 413, got %d", rec.Code)
	}

	id := e.presign(t, "alice", "../../etc/x.html", 10, "application/octet-stream")
	fid, _ := strconv.ParseInt(id, 10, 64)
	f, _ := e.files.GetFile(context.Background(), fid)
	if f == nil || f.Name != "x.html" || f.Status != repo.FileStatusPending || f.OwnerUID != "alice" {
		t.Fatalf("unexpected pending row: %+v", f)
	}
}

func TestDirectUploadImageFlow(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	png := newTestPNG(64, 32)
	id := e.presign(t, "alice", "pic.png", len(png), "image/png")

	if rec := e.post(t, e.srv.HandleComplete, "alice", url.Values{"file_id": {id}}); rec.Code != http.StatusConflict {
		t.Fatalf("complete before upload: expected 409, got %d", rec.Code)
	}
	_ = e.objs.Put(ctx, id, png, "image/png")
	if rec := e.post(t, e.srv.HandleComplete, "bob", url.Values{"file_id": {id}}); rec.Code != http.StatusNotFound {
		t.Fatalf("complete by non-owner: expected 404, got %d", rec.Code)
	}
	rec := e.post(t, e.srv.HandleComplete, "alice", url.Values{"file_id": {id}})
	if rec.Code != http.StatusOK {
		t.Fatalf("complete: %d %s", rec.Code, rec.Body.String())
	}
	m := decodeFileResp(t, rec)
	if m["file_id"] != id || m["mime"] != "image/png" || m["width"] != 64.0 || m["height"] != 32.0 || m["size"] != float64(len(png)) {
		t.Fatalf("unexpected complete response: %v", m)
	}
	// 重复 complete 幂等。
	if rec := e.post(t, e.srv.HandleComplete, "alice", url.Values{"file_id": {id}}); rec.Code != http.StatusOK {
		t.Fatalf("second complete: %d", rec.Code)
	}

	fid, _ := strconv.ParseInt(id, 10, 64)
	deadline := time.Now().Add(2 * time.Second)
	for {
		f, _ := e.files.GetFile(ctx, fid)
		if f.HasThumb {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("thumbnail not generated")
		}
		time.Sleep(10 * time.Millisecond)
	}

	rec = e.download(t, "alice", id, "&thumb=1")
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != "https://oss.test/get/"+id+"_thumb?fn=" {
		t.Fatalf("thumb download: %d %s", rec.Code, rec.Header().Get("Location"))
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "private, max-age=1800" {
		t.Errorf("unexpected Cache-Control %q", cc)
	}
}

func TestCompleteSizeMismatchDiscards(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	id := e.presign(t, "alice", "a.bin", 10, "application/octet-stream")
	_ = e.objs.Put(ctx, id, []byte("short"), "application/octet-stream")
	if rec := e.post(t, e.srv.HandleComplete, "alice", url.Values{"file_id": {id}}); rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400, got %d", rec.Code)
	}
	fid, _ := strconv.ParseInt(id, 10, 64)
	if f, _ := e.files.GetFile(ctx, fid); f != nil {
		t.Error("file row should be deleted")
	}
	if _, _, err := e.objs.Get(ctx, id); err == nil {
		t.Error("object should be deleted")
	}
}

func TestCompleteDowngradesFakeImage(t *testing.T) {
	e := newFileTestEnv(t, true)
	data := []byte("<html><script>alert(1)</script></html>")
	id := e.presign(t, "alice", "x.png", len(data), "image/png")
	_ = e.objs.Put(context.Background(), id, data, "image/png")
	rec := e.post(t, e.srv.HandleComplete, "alice", url.Values{"file_id": {id}, "width": {"10"}, "height": {"10"}})
	m := decodeFileResp(t, rec)
	if m["mime"] != "application/octet-stream" || m["width"] != 0.0 {
		t.Fatalf("expected downgrade to octet-stream without dims, got %v", m)
	}
}

func TestDownloadACLFollowsConversation(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	id := e.uploadReady(t, "alice", []byte("hello world"), "application/pdf")

	if rec := e.download(t, "bob", id, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("bob before message: expected 404, got %d", rec.Code)
	}
	rec := e.download(t, "alice", id, "")
	if rec.Code != http.StatusFound || !strings.HasSuffix(rec.Header().Get("Location"), "fn=f.bin") {
		t.Fatalf("owner download: %d %s", rec.Code, rec.Header().Get("Location"))
	}

	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	e.hub.Register(ctx, bob)
	e.router.Route(ctx, alice, fileMsg("bob", id))
	if got := readMessageFromChan(t, bob.send); got.Cmd != proto.CmdFile {
		t.Fatalf("bob expected file message, got cmd=%d", got.Cmd)
	}

	if rec := e.download(t, "bob", id, ""); rec.Code != http.StatusFound {
		t.Fatalf("bob after message: expected 302, got %d", rec.Code)
	}
	if rec := e.download(t, "carol", id, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("carol: expected 404, got %d", rec.Code)
	}
}

func TestDownloadPendingIsHidden(t *testing.T) {
	e := newFileTestEnv(t, true)
	id := e.presign(t, "alice", "a.bin", 3, "application/octet-stream")
	_ = e.objs.Put(context.Background(), id, []byte("abc"), "application/octet-stream")
	if rec := e.download(t, "alice", id, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("pending file: expected 404, got %d", rec.Code)
	}
}

func TestDownloadLegacyStreamsWithRange(t *testing.T) {
	e := newFileTestEnv(t, false)
	_ = e.objs.Put(context.Background(), "12345", []byte("0123456789"), "application/octet-stream")

	q := "/file?id=12345&uid=carol&token=" + jwtToken(t, e.jwt, "carol", "carol")
	req := httptest.NewRequest(http.MethodGet, q, nil)
	req.Header.Set("Range", "bytes=2-4")
	rec := httptest.NewRecorder()
	e.srv.HandleDownload(rec, req)
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "234" {
		t.Fatalf("expected 206 '234', got %d %q", rec.Code, rec.Body.String())
	}
}

func TestRouterRejectsInaccessibleFile(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	id := e.uploadReady(t, "alice", []byte("secret"), "application/pdf")

	carol := newTestClient(t, "carol", "Carol")
	bob := newTestClient(t, "bob", "Bob")
	e.hub.Register(ctx, bob)
	e.router.Route(ctx, carol, fileMsg("bob", id))
	assertChanEmpty(t, bob.send)
	if rec := e.download(t, "carol", id, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("carol must not gain access, got %d", rec.Code)
	}

	pending := e.presign(t, "carol", "p.bin", 1, "application/octet-stream")
	e.router.Route(ctx, carol, fileMsg("bob", pending))
	assertChanEmpty(t, bob.send)

	// 无记录的旧文件按旧规则放行。
	e.router.Route(ctx, carol, fileMsg("bob", "999"))
	if got := readMessageFromChan(t, bob.send); got.Cmd != proto.CmdFile {
		t.Fatalf("legacy file message should be delivered, got cmd=%d", got.Cmd)
	}
}

func TestRouterReshareAndForward(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	id := e.uploadReady(t, "alice", []byte("doc"), "application/pdf")

	alice := newTestClient(t, "alice", "Alice")
	bob := newTestClient(t, "bob", "Bob")
	carol := newTestClient(t, "carol", "Carol")
	e.hub.Register(ctx, bob)
	e.hub.Register(ctx, carol)

	e.router.Route(ctx, alice, fileMsg("bob", id))
	readMessageFromChan(t, bob.send)

	// bob 收到后可再发给 carol（KMP 转发即重发 CmdFile）。
	e.router.Route(ctx, bob, fileMsg("carol", id))
	readMessageFromChan(t, carol.send)

	// Web 转发把原 content 包在 original_content 中。
	inner, _ := json.Marshal(map[string]string{"file_id": id})
	fwd, _ := json.Marshal(map[string]interface{}{"forwarded": true, "original_from": "alice", "original_content": string(inner)})
	e.router.Route(ctx, carol, &proto.Message{Cmd: proto.CmdForward, To: "bob", MsgType: 5, Content: string(fwd)})
	if got := readMessageFromChan(t, bob.send); got.Cmd != proto.CmdForward {
		t.Fatalf("expected forward, got cmd=%d", got.Cmd)
	}

	dave := newTestClient(t, "dave", "Dave")
	e.router.Route(ctx, dave, &proto.Message{Cmd: proto.CmdForward, To: "bob", MsgType: 5, Content: string(fwd)})
	assertChanEmpty(t, bob.send)
}

func TestRouterGroupFileRef(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	g, err := e.groups.Create(ctx, "team", "alice", []string{"bob"})
	if err != nil {
		t.Fatal(err)
	}
	id := e.uploadReady(t, "alice", []byte("doc"), "application/pdf")

	alice := newTestClient(t, "alice", "Alice")
	msg := fileMsg(g.ID, id)
	msg.ChatType = proto.ChatTypeGroup
	e.router.Route(ctx, alice, msg)

	if rec := e.download(t, "bob", id, ""); rec.Code != http.StatusFound {
		t.Fatalf("group member: expected 302, got %d", rec.Code)
	}
	if rec := e.download(t, "carol", id, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("non-member: expected 404, got %d", rec.Code)
	}

	// 非成员不能把文件引用挂到群上。
	own := e.uploadReady(t, "carol", []byte("x"), "application/pdf")
	carol := newTestClient(t, "carol", "Carol")
	m2 := fileMsg(g.ID, own)
	m2.ChatType = proto.ChatTypeGroup
	e.router.Route(ctx, carol, m2)
	if rec := e.download(t, "bob", own, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("ref from non-member must not be recorded, got %d", rec.Code)
	}
}

func TestLegacyUploadRecordsFile(t *testing.T) {
	e := newFileTestEnv(t, false)
	var body strings.Builder
	body.WriteString("--X\r\nContent-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\nContent-Type: text/plain\r\n\r\nhello\r\n--X--\r\n")
	q := "/upload?uid=alice&token=" + jwtToken(t, e.jwt, "alice", "alice")
	req := httptest.NewRequest(http.MethodPost, q, strings.NewReader(body.String()))
	req.Header.Set("Content-Type", "multipart/form-data; boundary=X")
	rec := httptest.NewRecorder()
	e.srv.HandleUpload(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("upload: %d %s", rec.Code, rec.Body.String())
	}
	id := decodeFileResp(t, rec)["file_id"].(string)
	if r := e.download(t, "alice", id, ""); r.Code != http.StatusOK || r.Body.String() != "hello" {
		t.Fatalf("owner download: %d %q", r.Code, r.Body.String())
	}
	if r := e.download(t, "bob", id, ""); r.Code != http.StatusNotFound {
		t.Fatalf("stranger download: expected 404, got %d", r.Code)
	}
}

func TestSweepPendingFiles(t *testing.T) {
	e := newFileTestEnv(t, true)
	ctx := context.Background()
	old := &repo.File{FileID: 1, OwnerUID: "a", Status: repo.FileStatusPending, CreatedAt: time.Now().Add(-25 * time.Hour).UnixMilli()}
	fresh := &repo.File{FileID: 2, OwnerUID: "a", Status: repo.FileStatusPending, CreatedAt: time.Now().UnixMilli()}
	_ = e.files.CreateFile(ctx, old)
	_ = e.files.CreateFile(ctx, fresh)
	_ = e.objs.Put(ctx, "1", []byte("x"), "")

	e.srv.sweepPendingFiles(ctx)
	if f, _ := e.files.GetFile(ctx, 1); f != nil {
		t.Error("stale pending file should be removed")
	}
	if _, _, err := e.objs.Get(ctx, "1"); err == nil {
		t.Error("stale object should be removed")
	}
	if f, _ := e.files.GetFile(ctx, 2); f == nil {
		t.Error("fresh pending file should be kept")
	}
}

func TestSafeUploadMIME(t *testing.T) {
	cases := map[string]string{
		"":                         "application/octet-stream",
		"Image/PNG":                "image/png",
		"text/plain; charset=utf8": "text/plain",
		"text/html":                "application/octet-stream",
		"image/svg+xml":            "application/octet-stream",
		"application/javascript":   "application/octet-stream",
		"garbage":                  "application/octet-stream",
		"video/mp4":                "video/mp4",
	}
	for in, want := range cases {
		if got := safeUploadMIME(in); got != want {
			t.Errorf("safeUploadMIME(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSanitizeFileName(t *testing.T) {
	cases := map[string]string{
		"":                       "file",
		"a/b/c.txt":              "c.txt",
		`C:\Users\x\d.pdf`:       "d.pdf",
		"bad\x00\nname.txt":      "badname.txt",
		strings.Repeat("长", 300): strings.Repeat("长", maxFileNameRunes),
	}
	for in, want := range cases {
		if got := sanitizeFileName(in); got != want {
			t.Errorf("sanitizeFileName(%q) = %q, want %q", in, got, want)
		}
	}
}
