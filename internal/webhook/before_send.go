package webhook

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/im/api/proto"
)

// BeforeSend 在用户消息投递前同步 POST 给接入方：请求体为 MessageData（msg_id 已分配），签名头与 webhook 相同
// （X-IM-Event: message.before_send）。响应 {"allow":false,"reason":"..."} 拒绝消息；
// 超时、网络错误、非 2xx 或无法解析的响应都放行（fail open），避免接入方故障拖垮聊天。
type BeforeSend struct {
	URL     string
	Secret  string
	Timeout time.Duration
	HTTP    *http.Client
}

// NewBeforeSend 创建回调，timeout<=0 用 500ms。
func NewBeforeSend(url, secret string, timeout time.Duration) *BeforeSend {
	if timeout <= 0 {
		timeout = 500 * time.Millisecond
	}
	return &BeforeSend{URL: url, Secret: secret, Timeout: timeout, HTTP: &http.Client{}}
}

// BeforeSend 实现 gateway.BeforeSendHook。
func (b *BeforeSend) BeforeSend(ctx context.Context, m *proto.Message) (bool, string) {
	body, _ := json.Marshal(MessageData{
		MsgID: m.MsgId, From: m.From, To: m.To, ChatType: m.ChatType,
		MsgType: m.MsgType, Content: m.Content, Timestamp: m.Timestamp,
	})
	ctx, cancel := context.WithTimeout(ctx, b.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, b.URL, bytes.NewReader(body))
	if err != nil {
		return true, ""
	}
	ts := strconv.FormatInt(time.Now().Unix(), 10)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set(HeaderEvent, "message.before_send")
	req.Header.Set(HeaderTimestamp, ts)
	req.Header.Set(HeaderSignature, Sign(b.Secret, ts, body))
	resp, err := b.HTTP.Do(req)
	if err != nil {
		log.Printf("[webhook] before_send failed open: %v", err)
		return true, ""
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		log.Printf("[webhook] before_send failed open: status %d", resp.StatusCode)
		return true, ""
	}
	var out struct {
		Allow  *bool  `json:"allow"`
		Reason string `json:"reason"`
	}
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<10))
	if json.Unmarshal(data, &out) != nil || out.Allow == nil {
		return true, ""
	}
	return *out.Allow, out.Reason
}
