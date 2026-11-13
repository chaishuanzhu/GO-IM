package gateway

import (
	"bytes"
	"context"
	"fmt"
	"hash/fnv"
	"log"
	"mime"
	"net/http"
	"path"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/im/internal/repo"
)

// 对象存储按用途分前缀：聊天文件 files/{id}，头像 avatars/{id}，表情包 stickers/…；
// 缩略图与原文件同目录，键加 _thumb 后缀。
const (
	filesPrefix    = "files/"
	avatarsPrefix  = "avatars/"
	StickersPrefix = "stickers/"
	thumbSuffix    = "_thumb"
)

func fileKey(fileID int64) string   { return filesPrefix + strconv.FormatInt(fileID, 10) }
func avatarKey(fileID int64) string { return avatarsPrefix + strconv.FormatInt(fileID, 10) }
func thumbKey(key string) string    { return key + thumbSuffix }

// storedKey 返回文件当前所在的键：设为头像后对象已移到 avatars/。
func storedKey(f *repo.File) string {
	if f.IsAvatar {
		return avatarKey(f.FileID)
	}
	return fileKey(f.FileID)
}

// AvatarFiles 把文件标记为头像，并把对象（含缩略图）从 files/ 移到 avatars/。
// /user/profile 与服务端 API users/import 共用。
type AvatarFiles struct {
	Files   repo.FileStore
	Objects ObjectStore
}

// Avatars 返回网关的头像文件操作；未启用文件记录时返回 nil。
func (s *Server) Avatars() *AvatarFiles {
	if s.fileStore == nil || s.objectStore == nil {
		return nil
	}
	return &AvatarFiles{Files: s.fileStore, Objects: s.objectStore}
}

// GetFile 读取文件记录。
func (a *AvatarFiles) GetFile(ctx context.Context, fileID int64) (*repo.File, error) {
	return a.Files.GetFile(ctx, fileID)
}

// MarkAvatar 先复制对象到 avatars/ 再改记录，最后删除 files/ 下的原对象；已是头像时不做任何事。
func (a *AvatarFiles) MarkAvatar(ctx context.Context, fileID int64) error {
	f, err := a.Files.GetFile(ctx, fileID)
	if err != nil {
		return err
	}
	if f == nil {
		return fmt.Errorf("file %d not found", fileID)
	}
	if f.IsAvatar {
		return nil
	}
	src, dst := fileKey(fileID), avatarKey(fileID)
	if err := a.Objects.Copy(ctx, src, dst); err != nil {
		return err
	}
	if f.HasThumb {
		if err := a.Objects.Copy(ctx, thumbKey(src), thumbKey(dst)); err != nil {
			log.Printf("[server] avatar %d: copy thumbnail: %v", fileID, err)
		}
	}
	if err := a.Files.MarkAvatar(ctx, fileID); err != nil {
		return err
	}
	_ = a.Objects.Delete(ctx, src)
	_ = a.Objects.Delete(ctx, thumbKey(src))
	return nil
}

var stickerSegment = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)

// stickerPath 校验 /stickers/ 之后的路径：catalog.json 或 {pack}/{file}。
func stickerPath(p string) bool {
	parts := strings.Split(p, "/")
	switch len(parts) {
	case 1:
		return p == "catalog.json"
	case 2:
		for _, s := range parts {
			if !stickerSegment.MatchString(s) || strings.Contains(s, "..") {
				return false
			}
		}
		return true
	}
	return false
}

// HandleStickers 公开提供表情包，无需登录：
// GET /stickers/catalog.json（包列表）、/stickers/{pack_id}/pack.json（包清单）、/stickers/{pack_id}/{file}（图片 / 压缩包）。
// 内容在对象存储 stickers/ 下，由 scripts/upload_stickers.sh 上传。
func (s *Server) HandleStickers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := strings.TrimPrefix(r.URL.Path, "/stickers/")
	if s.objectStore == nil || !stickerPath(p) {
		http.NotFound(w, r)
		return
	}
	data, contentType, err := s.objectStore.Get(r.Context(), StickersPrefix+p)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if byExt := mime.TypeByExtension(path.Ext(p)); byExt != "" && (contentType == "" || contentType == "application/octet-stream") {
		contentType = byExt
	}
	if contentType == "" || strings.Contains(contentType, "html") || strings.Contains(contentType, "svg") ||
		strings.Contains(contentType, "javascript") {
		contentType = "application/octet-stream"
	}
	h := fnv.New64a()
	h.Write(data)
	etag := `"` + strconv.FormatUint(h.Sum64(), 36) + `"`
	if r.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	// 清单会随包更新变化；图片按包版本发布，文件名不复用。
	maxAge := 7 * 24 * time.Hour
	if strings.HasSuffix(p, ".json") {
		maxAge = 5 * time.Minute
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "public, max-age="+strconv.Itoa(int(maxAge.Seconds())))
	w.Header().Set("ETag", etag)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
}
