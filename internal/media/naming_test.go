package media

import (
	"reflect"
	"testing"
)

func TestParseTitle(t *testing.T) {
	cases := []struct{ key, title, year string }{
		{"films/Inception.2010.1080p.BluRay.mkv", "Inception", "2010"},
		{"films/The.Dark.Knight.2008.mp4", "The Dark Knight", "2008"},
		{"films/星际穿越 (2014).mp4", "星际穿越", "2014"},
		{"films/流浪地球（2019）.mkv", "流浪地球", "2019"},
		{"films/Some.Movie.mkv", "Some Movie", ""},
	}
	for _, c := range cases {
		title, year := ParseTitle(c.key)
		if title != c.title || year != c.year {
			t.Errorf("ParseTitle(%q) = %q, %q", c.key, title, year)
		}
	}
}

func TestMatchSidecarsAndLanguage(t *testing.T) {
	keys := []string{
		"films/Inception.2010.mkv",
		"films/Inception.2010.srt",
		"films/Inception.2010.zh.ass",
		"films/Inception.2010_en.vtt",
		"films/Inception.2010.nfo",
		"films/Other.srt",
	}
	got := MatchSidecars(keys, "films/Inception.2010.mkv")
	want := []string{"films/Inception.2010.srt", "films/Inception.2010.zh.ass", "films/Inception.2010_en.vtt"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("MatchSidecars = %v", got)
	}
	if code, _ := GuessLanguage("Inception.2010.zh.ass"); code != "zh" {
		t.Errorf("zh: %q", code)
	}
	if code, _ := GuessLanguage("Inception_eng.srt"); code != "en" {
		t.Errorf("en: %q", code)
	}
}

func TestParseNFO(t *testing.T) {
	nfo, ok := ParseNFO(`<?xml version="1.0"?><movie><title>盗梦空间</title><year>2010</year><plot>Dreams &amp; more</plot>
<thumb aspect="poster">poster.jpg</thumb><fanart><thumb>fanart.jpg</thumb></fanart></movie>`)
	if !ok || nfo.Title != "盗梦空间" || nfo.Year != "2010" || nfo.Plot != "Dreams & more" {
		t.Fatalf("nfo = %+v", nfo)
	}
	if nfo.PosterFileName != "poster.jpg" || nfo.FanartFileName != "fanart.jpg" {
		t.Errorf("artwork = %+v", nfo)
	}
	if _, ok := ParseNFO("<tvshow><title>x</title></tvshow>"); ok {
		t.Error("tvshow should be rejected")
	}
}

func TestRegistryResolve(t *testing.T) {
	reg, err := NewRegistry("", fakeSource{id: "oss"}, fakeSource{id: "nas"})
	if err != nil {
		t.Fatal(err)
	}
	if src, native, err := reg.Resolve("nas:a/b.mkv"); err != nil || src.Info().ID != "nas" || native != "a/b.mkv" {
		t.Errorf("prefixed: %v %q %v", src, native, err)
	}
	if src, native, err := reg.Resolve("films/c:d.mkv"); err != nil || src.Info().ID != "oss" || native != "films/c:d.mkv" {
		t.Errorf("legacy: %v %q %v", src, native, err)
	}
	if _, err := NewRegistry("", fakeSource{id: "Bad ID"}); err == nil {
		t.Error("invalid source id accepted")
	}
}

type fakeSource struct {
	Source
	id string
}

func (f fakeSource) Info() SourceInfo { return SourceInfo{ID: f.id, Type: "fake"} }
