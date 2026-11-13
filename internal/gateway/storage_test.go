package gateway

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/im/internal/repo"
)

func TestAvatarFilesMovesObjectAndThumb(t *testing.T) {
	ctx := context.Background()
	objs := NewInMemoryObjectStore()
	files := NewInMemoryFileStore()
	a := &AvatarFiles{Files: files, Objects: objs}
	_ = files.CreateFile(ctx, &repo.File{FileID: 7, OwnerUID: "u", Mime: "image/png", HasThumb: true, Status: repo.FileStatusReady})
	_ = objs.Put(ctx, "files/7", []byte("orig"), "image/png")
	_ = objs.Put(ctx, "files/7_thumb", []byte("thumb"), "image/jpeg")

	if err := a.MarkAvatar(ctx, 7); err != nil {
		t.Fatalf("mark avatar: %v", err)
	}
	for _, k := range []string{"files/7", "files/7_thumb"} {
		if _, _, err := objs.Get(ctx, k); err == nil {
			t.Errorf("%s should be removed", k)
		}
	}
	if data, ct, err := objs.Get(ctx, "avatars/7"); err != nil || string(data) != "orig" || ct != "image/png" {
		t.Errorf("avatars/7 = %q %q %v", data, ct, err)
	}
	if data, _, err := objs.Get(ctx, "avatars/7_thumb"); err != nil || string(data) != "thumb" {
		t.Errorf("avatars/7_thumb = %q %v", data, err)
	}
	if f, _ := files.GetFile(ctx, 7); f == nil || !f.IsAvatar || storedKey(f) != "avatars/7" {
		t.Fatalf("record = %+v", f)
	}
	// 重复设置幂等。
	if err := a.MarkAvatar(ctx, 7); err != nil {
		t.Fatalf("second mark: %v", err)
	}
	if err := a.MarkAvatar(ctx, 8); err == nil {
		t.Fatal("unknown file should fail")
	}
}

func TestAvatarFilesMissingObjectKeepsRecord(t *testing.T) {
	ctx := context.Background()
	files := NewInMemoryFileStore()
	a := &AvatarFiles{Files: files, Objects: NewInMemoryObjectStore()}
	_ = files.CreateFile(ctx, &repo.File{FileID: 9, OwnerUID: "u", Mime: "image/png", Status: repo.FileStatusReady})
	if err := a.MarkAvatar(ctx, 9); err == nil {
		t.Fatal("expected error when object is missing")
	}
	if f, _ := files.GetFile(ctx, 9); f.IsAvatar {
		t.Fatal("record must not be marked when copy fails")
	}
}

func TestStickerPath(t *testing.T) {
	ok := []string{"catalog.json", "capoo/pack.json", "capoo/001.webp", "capoo_sp/a-b.gif"}
	bad := []string{"", "x.json", "../catalog.json", "capoo/../x", "capoo/.hidden", "a/b/c", "capoo/", "/capoo/a.webp", "capoo/a..b"}
	for _, p := range ok {
		if !stickerPath(p) {
			t.Errorf("%q should be allowed", p)
		}
	}
	for _, p := range bad {
		if stickerPath(p) {
			t.Errorf("%q should be rejected", p)
		}
	}
}

func TestHandleStickers(t *testing.T) {
	ctx := context.Background()
	objs := NewInMemoryObjectStore()
	_ = objs.Put(ctx, "stickers/catalog.json", []byte(`{"packs":[]}`), "application/json")
	_ = objs.Put(ctx, "stickers/capoo/001.webp", []byte("RIFFxxxxWEBP"), "")
	_ = objs.Put(ctx, "stickers/capoo/evil.webp", []byte("<html>"), "text/html")
	_ = objs.Put(ctx, "files/1", []byte("secret"), "text/plain")
	s := &Server{objectStore: objs}

	get := func(path, inm string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		if inm != "" {
			req.Header.Set("If-None-Match", inm)
		}
		rec := httptest.NewRecorder()
		s.HandleStickers(rec, req)
		return rec
	}

	rec := get("/stickers/catalog.json", "")
	if rec.Code != http.StatusOK || rec.Body.String() != `{"packs":[]}` ||
		rec.Header().Get("Cache-Control") != "public, max-age=300" || rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("catalog: %d %v %q", rec.Code, rec.Header(), rec.Body.String())
	}
	if r := get("/stickers/catalog.json", rec.Header().Get("ETag")); r.Code != http.StatusNotModified {
		t.Fatalf("etag: %d", r.Code)
	}

	rec = get("/stickers/capoo/001.webp", "")
	if rec.Code != http.StatusOK || rec.Header().Get("Content-Type") != "image/webp" ||
		rec.Header().Get("Cache-Control") != "public, max-age=604800" {
		t.Fatalf("image: %d %v", rec.Code, rec.Header())
	}
	if ct := get("/stickers/capoo/evil.webp", "").Header().Get("Content-Type"); ct != "application/octet-stream" {
		t.Fatalf("html content type must be neutralised, got %q", ct)
	}

	for _, p := range []string{"/stickers/capoo/missing.webp", "/stickers/../files/1", "/stickers/files/1/x"} {
		if r := get(p, ""); r.Code != http.StatusNotFound {
			t.Errorf("%s: expected 404, got %d", p, r.Code)
		}
	}
	req := httptest.NewRequest(http.MethodPost, "/stickers/catalog.json", nil)
	rec = httptest.NewRecorder()
	s.HandleStickers(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("post: %d", rec.Code)
	}
}
