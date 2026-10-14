package gateway

import (
	"context"
	"encoding/json"
	"log"
	"strconv"
	"strings"

	"github.com/im/api/proto"
	"github.com/im/internal/repo"
)

// SetFileStore 启用文件消息校验：消息只能引用发送者有权访问的文件。
func (r *Router) SetFileStore(fs repo.FileStore) {
	r.fileStore = fs
}

// fileAccessible 判断 uid 能否读取文件：上传者本人，或文件曾被发送到 uid 所在的会话。
func fileAccessible(ctx context.Context, fs repo.FileStore, gs GroupStore, uid string, f *repo.File) bool {
	if f.OwnerUID == uid {
		return true
	}
	keys, err := fs.FileConvKeys(ctx, f.FileID)
	if err != nil {
		log.Printf("[file] refs of %d: %v", f.FileID, err)
		return false
	}
	for _, k := range keys {
		if a, b, ok := strings.Cut(k, ":"); ok {
			if a == uid || b == uid {
				return true
			}
		} else if gs != nil && gs.IsMember(ctx, k, uid) {
			return true
		}
	}
	return false
}

// authorizeFileMessage 校验消息引用的文件：须已上传完成且发送者有权访问，
// 通过后记录文件被目标会话引用。没有记录的文件来自直传上线之前，按旧规则放行。
func (r *Router) authorizeFileMessage(ctx context.Context, sender *Client, msg *proto.Message) bool {
	if r.fileStore == nil {
		return true
	}
	ids := referencedFileIDs(msg)
	if len(ids) == 0 {
		return true
	}
	if msg.ChatType == proto.ChatTypeGroup && r.groupStore != nil && !r.groupStore.IsMember(ctx, msg.To, sender.UID) {
		return false
	}
	key := convKey(msg.ChatType, sender.UID, msg.To)
	for _, id := range ids {
		f, err := r.fileStore.GetFile(ctx, id)
		if err != nil {
			log.Printf("[router] file %d lookup failed for %s: %v", id, sender.UID, err)
			return false
		}
		if f == nil {
			continue
		}
		if f.Status != repo.FileStatusReady || !fileAccessible(ctx, r.fileStore, r.groupStore, sender.UID, f) {
			log.Printf("[router] file message from %s rejected: file %d not accessible (status=%d)", sender.UID, id, f.Status)
			return false
		}
		if err := r.fileStore.AddFileRef(ctx, id, key); err != nil {
			log.Printf("[router] add file ref %d → %s failed: %v", id, key, err)
			return false
		}
	}
	return true
}

// referencedFileIDs 从文件消息或转发消息的 content 中取出 file_id。
// content 形如 {"file_id":"123",...}；Web 端转发为 {"forwarded":true,"original_content":"<原 content>"}。
func referencedFileIDs(msg *proto.Message) []int64 {
	if msg.Cmd != proto.CmdFile && msg.Cmd != proto.CmdForward {
		return nil
	}
	var ids []int64
	content := msg.Content
	for depth := 0; depth < 2 && strings.HasPrefix(strings.TrimSpace(content), "{"); depth++ {
		var body struct {
			FileID          json.RawMessage `json:"file_id"`
			OriginalContent string          `json:"original_content"`
		}
		if json.Unmarshal([]byte(content), &body) != nil {
			break
		}
		if id, ok := parseFileID(body.FileID); ok {
			ids = append(ids, id)
		}
		content = body.OriginalContent
	}
	return ids
}

// parseFileID 兼容字符串与数字两种 file_id 写法。
func parseFileID(raw json.RawMessage) (int64, bool) {
	s := strings.Trim(string(raw), `"`)
	if s == "" {
		return 0, false
	}
	id, err := strconv.ParseInt(s, 10, 64)
	return id, err == nil && id > 0
}
