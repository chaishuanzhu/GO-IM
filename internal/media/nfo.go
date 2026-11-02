package media

import (
	"html"
	"regexp"
	"strings"
)

// NFO 是 Kodi 风格影片 NFO 的子集。
type NFO struct {
	Title          string
	Year           string
	Plot           string
	PosterFileName string
	FanartFileName string
}

var (
	nfoFanartBlock = regexp.MustCompile(`(?is)<fanart\b[^>]*>(.*?)</fanart>`)
	nfoThumb       = regexp.MustCompile(`(?is)<thumb\b([^>]*)>(.*?)</thumb>`)
	nfoAspect      = regexp.MustCompile(`(?i)aspect\s*=\s*"([^"]*)"`)
)

// ParseNFO 解析 NFO；不是影片 NFO 或缺少标题时返回 false。
func ParseNFO(xml string) (NFO, bool) {
	if !strings.Contains(strings.ToLower(xml), "<movie") {
		return NFO{}, false
	}
	n := NFO{
		Title: nfoElement(xml, "title"),
		Year:  nfoElement(xml, "year"),
		Plot:  nfoElement(xml, "plot"),
	}
	if n.Title == "" {
		return NFO{}, false
	}
	if m := nfoFanartBlock.FindStringSubmatch(xml); m != nil {
		if t := nfoThumb.FindStringSubmatch(m[1]); t != nil {
			n.FanartFileName = strings.TrimSpace(html.UnescapeString(t[2]))
		}
	}
	outside := nfoFanartBlock.ReplaceAllString(xml, "")
	for _, t := range nfoThumb.FindAllStringSubmatch(outside, -1) {
		aspect := ""
		if a := nfoAspect.FindStringSubmatch(t[1]); a != nil {
			aspect = strings.ToLower(a[1])
		}
		text := strings.TrimSpace(html.UnescapeString(t[2]))
		if text != "" && (aspect == "" || aspect == "poster") {
			n.PosterFileName = text
			break
		}
	}
	return n, true
}

func nfoElement(xml, tag string) string {
	re := regexp.MustCompile(`(?is)<` + tag + `\b[^>]*>(.*?)</` + tag + `>`)
	m := re.FindStringSubmatch(xml)
	if m == nil {
		return ""
	}
	return strings.TrimSpace(html.UnescapeString(m[1]))
}
