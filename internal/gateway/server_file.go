package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/im/internal/repo"
)

const (
	// 待上传记录保留时长，超时未 complete 的对象与记录由清理任务删除。
	pendingFileTTL = 24 * time.Hour
	// 直传图片超过该大小时不生成缩略图（需整张读入内存解码）。
	maxThumbSourceBytes = 20 * 1024 * 1024
	maxImageDim         = 4096
	headSniffBytes      = 64 * 1024
	maxFileNameRunes    = 200
)

// SetFileStore 启用文件元数据记录与按会话的下载鉴权。
func (s *Server) SetFileStore(fs repo.FileStore) {
	s.fileStore = fs
}

// SetDirectUpload 启用客户端直传对象存储（POST /file/presign + /file/complete）。
func (s *Server) SetDirectUpload(du DirectUploader, maxSize int64, uploadTTL, downloadTTL time.Duration) {
	s.direct = du
	s.directMax = maxSize
	s.uploadTTL = uploadTTL
	s.downloadTTL = downloadTTL
	s.thumbSem = make(chan struct{}, 2)
}

// HandlePresign 为客户端直传签发一次性上传 URL。
// POST /file/presign 表单字段：uid、token、name、size、mime
// 未启用直传时返回 501，客户端应回退到 POST /upload。
func (s *Server) HandlePresign(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.direct == nil || s.fileStore == nil {
		http.Error(w, "direct upload not enabled", http.StatusNotImplemented)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	if s.router.checkRateLimit(claims.UID) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}

	size, err := strconv.ParseInt(r.FormValue("size"), 10, 64)
	if err != nil || size <= 0 {
		http.Error(w, "size must be a positive integer", http.StatusBadRequest)
		return
	}
	if s.directMax > 0 && size > s.directMax {
		http.Error(w, "file too large", http.StatusRequestEntityTooLarge)
		return
	}
	name := sanitizeFileName(r.FormValue("name"))
	mime := safeUploadMIME(r.FormValue("mime"))

	fileID := s.snow.Next()
	key := strconv.FormatInt(fileID, 10)
	if err := s.fileStore.CreateFile(r.Context(), &repo.File{
		FileID:    fileID,
		OwnerUID:  claims.UID,
		Name:      name,
		Size:      size,
		Mime:      mime,
		Status:    repo.FileStatusPending,
		CreatedAt: time.Now().UnixMilli(),
	}); err != nil {
		log.Printf("[server] presign: create file %d: %v", fileID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	uploadURL, headers, err := s.direct.PresignPut(r.Context(), key, size, mime, s.uploadTTL)
	if err != nil {
		log.Printf("[server] presign %d: %v", fileID, err)
		_ = s.fileStore.DeleteFile(r.Context(), fileID)
		http.Error(w, "presign failed", http.StatusInternalServerError)
		return
	}

	// Content-Length 由 HTTP 客户端按实际 body 设置，只把 Content-Type 交给客户端。
	writeJSON(w, map[string]interface{}{
		"file_id":    key,
		"upload_url": uploadURL,
		"method":     http.MethodPut,
		"headers":    map[string]string{"Content-Type": headers.Get("Content-Type")},
		"expires_at": time.Now().Add(s.uploadTTL).UnixMilli(),
		"max_size":   s.directMax,
	})
}

// HandleComplete 确认直传完成：核对对象大小、嗅探真实类型、记录图片尺寸并异步生成缩略图。
// POST /file/complete 表单字段：uid、token、file_id，可选 width、height（客户端已知的图片尺寸）
// 返回与 POST /upload 相同结构的文件元数据。
func (s *Server) HandleComplete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if s.direct == nil || s.fileStore == nil {
		http.Error(w, "direct upload not enabled", http.StatusNotImplemented)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	fileID, err := strconv.ParseInt(r.FormValue("file_id"), 10, 64)
	if err != nil {
		http.Error(w, "invalid file_id", http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	f, err := s.fileStore.GetFile(ctx, fileID)
	if err != nil {
		log.Printf("[server] complete: get file %d: %v", fileID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	if f == nil || f.OwnerUID != claims.UID {
		http.Error(w, "file not found", http.StatusNotFound)
		return
	}
	if f.Status == repo.FileStatusReady {
		writeJSON(w, fileResponse(f))
		return
	}

	key := strconv.FormatInt(fileID, 10)
	size, _, err := s.direct.Stat(ctx, key)
	if err != nil {
		http.Error(w, "object not uploaded", http.StatusConflict)
		return
	}
	if size != f.Size {
		log.Printf("[server] complete %d: size mismatch (declared %d, stored %d)", fileID, f.Size, size)
		s.discardFile(ctx, fileID)
		http.Error(w, "uploaded size does not match declared size", http.StatusBadRequest)
		return
	}

	head, err := s.direct.ReadHead(ctx, key, headSniffBytes)
	if err != nil {
		log.Printf("[server] complete %d: read head: %v", fileID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	mime := f.Mime
	sniffed := http.DetectContentType(head)
	if strings.HasPrefix(mime, "image/") && !strings.HasPrefix(sniffed, "image/") {
		mime = "application/octet-stream"
	}
	width, height := 0, 0
	if IsImageMIME(mime) {
		width, height = ImageDimensions(head)
		if width == 0 || height == 0 {
			width, height = formDimension(r, "width"), formDimension(r, "height")
		}
	}
	if err := s.fileStore.MarkFileReady(ctx, fileID, mime, width, height); err != nil {
		log.Printf("[server] complete %d: mark ready: %v", fileID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	f.Status, f.Mime, f.Width, f.Height = repo.FileStatusReady, mime, width, height

	if IsImageMIME(mime) && size <= maxThumbSourceBytes &&
		width > 0 && width <= maxImageDim && height > 0 && height <= maxImageDim {
		go s.generateThumb(fileID)
	}
	log.Printf("[server] direct upload %d: name=%s size=%d mime=%s dims=%dx%d", fileID, f.Name, size, mime, width, height)
	writeJSON(w, fileResponse(f))
}

// generateThumb 读取已直传的原图生成缩略图；并发受 thumbSem 限制，失败只记录日志。
func (s *Server) generateThumb(fileID int64) {
	s.thumbSem <- struct{}{}
	defer func() { <-s.thumbSem }()

	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	key := strconv.FormatInt(fileID, 10)
	data, _, err := s.objectStore.Get(ctx, key)
	if err != nil {
		log.Printf("[server] thumbnail %d: read original: %v", fileID, err)
		return
	}
	thumb, err := Thumbnail(data)
	if err != nil {
		log.Printf("[server] thumbnail %d: %v", fileID, err)
		return
	}
	if err := s.objectStore.Put(ctx, key+"_thumb", thumb, "image/jpeg"); err != nil {
		log.Printf("[server] thumbnail %d: upload: %v", fileID, err)
		return
	}
	if err := s.fileStore.SetFileThumb(ctx, fileID); err != nil {
		log.Printf("[server] thumbnail %d: mark: %v", fileID, err)
	}
}

// HandleDownload 提供文件/图片数据。
// GET /file?id={file_id}[&thumb=1]&uid=uid&token=token
// 有记录的文件仅上传者与文件所在会话的成员可读；无记录的旧文件登录即可读。
// 启用直传时 302 到预签名下载地址，否则由网关支持 Range 地转发。
func (s *Server) HandleDownload(w http.ResponseWriter, r *http.Request) {
	if s.objectStore == nil {
		http.Error(w, "object store not available", http.StatusServiceUnavailable)
		return
	}
	id := r.URL.Query().Get("id")
	if id == "" {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}
	claims := s.authenticateRequest(w, r)
	if claims == nil {
		return
	}
	ctx := r.Context()
	wantThumb := r.URL.Query().Get("thumb") == "1"

	key := id
	if wantThumb {
		key = id + "_thumb"
	}
	var f *repo.File
	if fileID, err := strconv.ParseInt(id, 10, 64); err == nil && s.fileStore != nil {
		if f, err = s.fileStore.GetFile(ctx, fileID); err != nil {
			log.Printf("[server] download: get file %d: %v", fileID, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
	}
	if f != nil {
		if f.Status != repo.FileStatusReady || !fileAccessible(ctx, s.fileStore, s.groupStore, claims.UID, f) {
			http.Error(w, "file not found", http.StatusNotFound)
			return
		}
		if wantThumb && !f.HasThumb {
			key = id
		}
	}

	if s.direct != nil {
		filename := ""
		if f != nil && key == id && !isInlineMIME(f.Mime) {
			filename = f.Name
		}
		u, err := s.direct.PresignGet(ctx, key, filename, s.downloadTTL)
		if err != nil {
			log.Printf("[server] presign download %s: %v", key, err)
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Cache-Control", "private, max-age="+strconv.Itoa(int(s.downloadTTL.Seconds()/2)))
		http.Redirect(w, r, u, http.StatusFound)
		return
	}

	data, contentType, err := s.objectStore.Get(ctx, key)
	if err != nil {
		log.Printf("[server] download %s failed: %v", key, err)
		http.Error(w, "file not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", contentType)
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
}

// StartFileJanitor 定期删除超时未完成的直传对象与记录，ctx 取消后退出。
func (s *Server) StartFileJanitor(ctx context.Context, interval time.Duration) {
	if s.fileStore == nil || s.objectStore == nil {
		return
	}
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweepPendingFiles(ctx)
			}
		}
	}()
}

func (s *Server) sweepPendingFiles(ctx context.Context) {
	before := time.Now().Add(-pendingFileTTL).UnixMilli()
	for {
		ids, err := s.fileStore.StalePendingFiles(ctx, before, 100)
		if err != nil {
			log.Printf("[server] file janitor: %v", err)
			return
		}
		for _, id := range ids {
			s.discardFile(ctx, id)
		}
		if len(ids) < 100 || ctx.Err() != nil {
			return
		}
	}
}

// discardFile 删除对象（含缩略图）与文件记录，对象不存在不算错误。
func (s *Server) discardFile(ctx context.Context, fileID int64) {
	key := strconv.FormatInt(fileID, 10)
	_ = s.objectStore.Delete(ctx, key)
	_ = s.objectStore.Delete(ctx, key+"_thumb")
	if err := s.fileStore.DeleteFile(ctx, fileID); err != nil {
		log.Printf("[server] delete file %d: %v", fileID, err)
	}
}

// recordUploadedFile 为经网关中转上传的文件写入已就绪记录，使其同样按会话鉴权。
func (s *Server) recordUploadedFile(ctx context.Context, f *repo.File) {
	if s.fileStore == nil {
		return
	}
	f.Status = repo.FileStatusReady
	f.CreatedAt = time.Now().UnixMilli()
	if err := s.fileStore.CreateFile(ctx, f); err != nil {
		log.Printf("[server] record upload %d: %v", f.FileID, err)
	}
}

func fileResponse(f *repo.File) map[string]interface{} {
	return map[string]interface{}{
		"file_id":      strconv.FormatInt(f.FileID, 10),
		"name":         f.Name,
		"size":         f.Size,
		"mime":         f.Mime,
		"width":        f.Width,
		"height":       f.Height,
		"thumb_width":  0,
		"thumb_height": 0,
	}
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	data, _ := json.Marshal(v)
	w.Header().Set("Content-Type", "application/json")
	w.Write(data)
}

// sanitizeFileName 去掉路径与控制字符并截断，空名返回 "file"。
func sanitizeFileName(name string) string {
	name = path.Base(strings.ReplaceAll(name, "\\", "/"))
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if !utf8.ValidString(name) {
		name = strings.ToValidUTF8(name, "")
	}
	if utf8.RuneCountInString(name) > maxFileNameRunes {
		name = string([]rune(name)[:maxFileNameRunes])
	}
	if name == "" || name == "." || name == "/" {
		return "file"
	}
	return name
}

// safeUploadMIME 规范化客户端声明的类型；可在浏览器中执行脚本的类型一律降级为二进制流，
// 防止预签名下载地址被当作同源页面渲染。
func safeUploadMIME(mime string) string {
	mime = strings.ToLower(strings.TrimSpace(mime))
	if i := strings.IndexByte(mime, ';'); i >= 0 {
		mime = strings.TrimSpace(mime[:i])
	}
	if mime == "" || !strings.Contains(mime, "/") || strings.ContainsAny(mime, " \t\r\n") {
		return "application/octet-stream"
	}
	switch {
	case mime == "text/html", mime == "application/xhtml+xml", mime == "image/svg+xml",
		mime == "text/xml", mime == "application/xml",
		strings.Contains(mime, "javascript"), strings.Contains(mime, "ecmascript"):
		return "application/octet-stream"
	}
	return mime
}

func isInlineMIME(mime string) bool {
	return strings.HasPrefix(mime, "image/") || strings.HasPrefix(mime, "video/") || strings.HasPrefix(mime, "audio/")
}

func formDimension(r *http.Request, field string) int {
	v, err := strconv.Atoi(r.FormValue(field))
	if err != nil || v <= 0 || v > 100000 {
		return 0
	}
	return v
}
