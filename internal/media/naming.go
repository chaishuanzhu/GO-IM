package media

import (
	"path"
	"regexp"
	"strings"
)

var (
	dottedTitle = regexp.MustCompile(`^(.+?)\.(\d{4})(?:\.|$)`)
	parenTitle  = regexp.MustCompile(`^(.+?)\s*[(（](\d{4})[)）]\s*$`)
)

// VideoFormat 按扩展名返回 mp4 / m4v / mkv；不是视频返回空串。
func VideoFormat(name string) string {
	switch strings.ToLower(strings.TrimPrefix(path.Ext(name), ".")) {
	case "mp4":
		return "mp4"
	case "m4v":
		return "m4v"
	case "mkv":
		return "mkv"
	}
	return ""
}

// ParseTitle 从文件名推断片名与年份（"Title.2010.1080p.mkv"、"片名 (2023).mp4"）。
func ParseTitle(key string) (title, year string) {
	base := strings.TrimSuffix(path.Base(key), path.Ext(key))
	if m := dottedTitle.FindStringSubmatch(base); m != nil {
		return strings.TrimSpace(strings.ReplaceAll(m[1], ".", " ")), m[2]
	}
	if m := parenTitle.FindStringSubmatch(base); m != nil {
		return strings.TrimSpace(m[1]), m[2]
	}
	return strings.TrimSpace(strings.ReplaceAll(base, ".", " ")), ""
}

var subtitleExts = map[string]bool{"srt": true, "vtt": true, "ass": true, "ssa": true}

// SubtitleExt 返回小写字幕扩展名；不是字幕返回空串。
func SubtitleExt(name string) string {
	ext := strings.ToLower(strings.TrimPrefix(path.Ext(name), "."))
	if subtitleExts[ext] {
		return ext
	}
	return ""
}

// MatchSidecars 从同目录对象中挑出与影片同名（或 "同名.zh"、"同名_en" 等）的字幕文件。
func MatchSidecars(keys []string, movieKey string) []string {
	movieLeaf := path.Base(movieKey)
	movieBase := strings.ToLower(strings.TrimSuffix(movieLeaf, path.Ext(movieLeaf)))
	if movieBase == "" {
		return nil
	}
	seen := map[string]bool{}
	var out []string
	for _, key := range keys {
		leaf := path.Base(key)
		if leaf == movieLeaf || SubtitleExt(leaf) == "" || seen[key] {
			continue
		}
		leafBase := strings.ToLower(strings.TrimSuffix(leaf, path.Ext(leaf)))
		if leafBase == movieBase || strings.HasPrefix(leafBase, movieBase+".") ||
			strings.HasPrefix(leafBase, movieBase+"_") || strings.HasPrefix(leafBase, movieBase+"-") {
			seen[key] = true
			out = append(out, key)
		}
	}
	return out
}

// GuessLanguage 由文件名推断语言代码与徽标。
func GuessLanguage(name string) (code, badge string) {
	lower := strings.ToLower(name)
	containsAny := func(subs ...string) bool {
		for _, s := range subs {
			if strings.Contains(lower, s) {
				return true
			}
		}
		return false
	}
	switch {
	case containsAny("zh-cn", "zh_cn", ".chs", "简体", "chi", ".zh.", "chinese") || strings.HasSuffix(lower, ".zh"):
		return "zh", "ZH"
	case containsAny("zh-tw", "zh_tw", ".cht", "繁体"):
		return "zh-tw", "ZH-TW"
	case containsAny(".en", "eng", "english"):
		return "en", "English"
	case containsAny("双语", "dual"):
		return "", "Bilingual"
	}
	return "", ""
}
