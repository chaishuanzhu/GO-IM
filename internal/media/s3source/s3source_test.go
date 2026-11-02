package s3source

import (
	"context"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/im/internal/media"
)

type fakeAPI struct {
	objects map[string]string
	lists   int
}

func (f *fakeAPI) List(_ context.Context, prefix string) ([]ObjectInfo, error) {
	f.lists++
	var out []ObjectInfo
	for k, v := range f.objects {
		if strings.HasPrefix(k, prefix) {
			out = append(out, ObjectInfo{Key: k, Size: int64(len(v))})
		}
	}
	return out, nil
}

func (f *fakeAPI) Exists(_ context.Context, key string) (bool, error) {
	_, ok := f.objects[key]
	return ok, nil
}

func (f *fakeAPI) Get(_ context.Context, key string) (io.ReadCloser, error) {
	v, ok := f.objects[key]
	if !ok {
		return nil, media.ErrNotFound
	}
	return io.NopCloser(strings.NewReader(v)), nil
}

func (f *fakeAPI) Presign(_ context.Context, key string, ttl time.Duration) (string, error) {
	return "https://cdn.test/" + key + "?ttl=" + ttl.String(), nil
}

func newSource(objects map[string]string) (*Source, *fakeAPI) {
	api := &fakeAPI{objects: objects}
	now := time.UnixMilli(1_700_000_000_000)
	return New(api, Options{ID: "oss", Name: "Library", Prefix: "films/", ListCacheTTL: time.Minute, Now: func() time.Time { return now }}), api
}

func TestListFiltersVideosInPrefixAndCaches(t *testing.T) {
	src, api := newSource(map[string]string{
		"films/Inception.2010.1080p.mkv": "x",
		"films/星际穿越 (2014).mp4":          "x",
		"films/Inception.2010.1080p.srt": "s",
		"other/Outside.2001.mp4":         "x",
	})
	page, err := src.List(context.Background(), media.ListOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 2 {
		t.Fatalf("items = %+v", page.Items)
	}
	first := page.Items[0]
	if first.ID != "oss:films/Inception.2010.1080p.mkv" || first.Title != "Inception" || first.Year != "2010" || first.Format != "mkv" {
		t.Errorf("first = %+v", first)
	}
	if page.Items[1].Title != "星际穿越" || page.Items[1].Year != "2014" {
		t.Errorf("second = %+v", page.Items[1])
	}
	src.List(context.Background(), media.ListOptions{Query: "inception"})
	if api.lists != 1 {
		t.Errorf("expected cached listing, got %d list calls", api.lists)
	}
	src.List(context.Background(), media.ListOptions{Refresh: true})
	if api.lists != 2 {
		t.Errorf("refresh should relist, got %d", api.lists)
	}
}

func TestListPaginates(t *testing.T) {
	src, _ := newSource(map[string]string{"films/a.mp4": "", "films/b.mp4": "", "films/c.mp4": ""})
	page, _ := src.List(context.Background(), media.ListOptions{Limit: 2})
	if len(page.Items) != 2 || page.NextCursor != "2" {
		t.Fatalf("page1 = %+v", page)
	}
	page, _ = src.List(context.Background(), media.ListOptions{Limit: 2, Cursor: page.NextCursor})
	if len(page.Items) != 1 || page.NextCursor != "" {
		t.Fatalf("page2 = %+v", page)
	}
}

func TestPlayURLRejectsOutOfScopeKeys(t *testing.T) {
	src, _ := newSource(map[string]string{"films/a.mp4": ""})
	info, err := src.PlayURL(context.Background(), "films/a.mp4")
	if err != nil || !strings.HasPrefix(info.URL, "https://cdn.test/films/a.mp4") || info.Format != "mp4" || info.ExpiresAt == 0 {
		t.Fatalf("info = %+v err = %v", info, err)
	}
	for _, bad := range []string{"other/a.mp4", "films/../secret.mp4", "films/a.srt", "/films/a.mp4"} {
		if _, err := src.PlayURL(context.Background(), bad); !errors.Is(err, media.ErrNotFound) {
			t.Errorf("%s: err = %v", bad, err)
		}
	}
}

func TestSidecarsMatchByBasename(t *testing.T) {
	src, _ := newSource(map[string]string{
		"films/Movie.2010.mkv":        "",
		"films/Movie.2010.zh.srt":     "1",
		"films/Movie.2010.en.ass":     "2",
		"films/Movie.2010.Extras.mkv": "",
		"films/Movie.20101.srt":       "nope",
		"films/Movie.2010-poster.jpg": "",
	})
	refs, err := src.Sidecars(context.Background(), "films/Movie.2010.mkv")
	if err != nil {
		t.Fatal(err)
	}
	if len(refs) != 2 || refs[0].Ref != "films/Movie.2010.en.ass" || refs[1].Badge != "ZH" {
		t.Fatalf("refs = %+v", refs)
	}
	rc, err := src.OpenSidecar(context.Background(), refs[1].Ref)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(rc)
	if string(data) != "1" {
		t.Errorf("content = %q", data)
	}
	if _, err := src.OpenSidecar(context.Background(), "films/Movie.2010.mkv"); err == nil {
		t.Error("video must not be readable as a subtitle")
	}
}

func TestArtworkFromNFOAndPoster(t *testing.T) {
	src, _ := newSource(map[string]string{
		"films/Movie.2010.mkv":        "",
		"films/Movie.2010.nfo":        `<movie><title>盗梦空间</title><year>2010</year><plot>Dreams &amp; more</plot><thumb aspect="poster">cover.jpg</thumb></movie>`,
		"films/cover.jpg":             "",
		"films/Movie.2010-fanart.jpg": "",
	})
	art, ok, err := src.Artwork(context.Background(), "films/Movie.2010.mkv")
	if err != nil || !ok {
		t.Fatalf("ok=%v err=%v", ok, err)
	}
	if art.Title != "盗梦空间" || art.Year != "2010" || art.Overview != "Dreams & more" {
		t.Errorf("art = %+v", art)
	}
	if !strings.Contains(art.PosterURL, "films/cover.jpg") || !strings.Contains(art.BackdropURL, "Movie.2010-fanart.jpg") {
		t.Errorf("urls = %q %q", art.PosterURL, art.BackdropURL)
	}
}

func TestRegistryResolvesPrefixedAndLegacyIDs(t *testing.T) {
	src, _ := newSource(nil)
	reg, err := media.NewRegistry("", src)
	if err != nil {
		t.Fatal(err)
	}
	s, native, err := reg.Resolve("oss:films/a.mp4")
	if err != nil || s != src || native != "films/a.mp4" {
		t.Fatalf("prefixed: %v %q %v", s, native, err)
	}
	_, native, err = reg.Resolve("films/a.mp4")
	if err != nil || native != "films/a.mp4" {
		t.Fatalf("legacy: %q %v", native, err)
	}
	if _, _, err := reg.Resolve(""); err == nil {
		t.Error("empty id must fail")
	}
}
