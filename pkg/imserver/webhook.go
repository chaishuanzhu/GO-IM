package imserver

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"time"
)

// webhook 事件类型。
const (
	EventMessageSent       = "message.sent"
	EventMessageRecalled   = "message.recalled"
	EventGroupMemberJoined = "group.member_joined"
	EventGroupMemberLeft   = "group.member_left"
	EventGroupDismissed    = "group.dismissed"
	EventUserOnline        = "user.online"
	EventUserOffline       = "user.offline"
	EventReportCreated     = "report.created"
	// EventBeforeSend 是同步发送前回调（before_send_url），请求体为 MessageEventData。
	EventBeforeSend = "message.before_send"
)

// webhook 请求头。
const (
	HeaderWebhookEvent     = "X-IM-Event"
	HeaderWebhookDelivery  = "X-IM-Delivery"
	HeaderWebhookTimestamp = "X-IM-Timestamp"
	HeaderWebhookSignature = "X-IM-Signature"
)

// WebhookEvent 是 webhook 请求体。同一事件重试时 ID 不变，接收方据此去重。
type WebhookEvent struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	AppID     string          `json:"app_id"`
	Timestamp int64           `json:"timestamp"`
	Data      json.RawMessage `json:"data"`
}

// MessageEventData 是 message.sent 与 message.before_send 的数据。
type MessageEventData struct {
	MsgID     int64  `json:"msg_id"`
	From      string `json:"from"`
	To        string `json:"to"`
	ChatType  int32  `json:"chat_type"`
	MsgType   int32  `json:"msg_type"`
	Content   string `json:"content"`
	Timestamp int64  `json:"timestamp"`
}

// ErrWebhookSignature 表示 webhook 签名或时间戳校验失败。
var ErrWebhookSignature = errors.New("imserver: bad webhook signature")

// webhookMaxSkew 是接受的回调时间戳偏差。
const webhookMaxSkew = 5 * time.Minute

// VerifyWebhook 校验回调签名（hex(HMAC-SHA256(secret, TIMESTAMP "\n" BODY))）与时间戳，返回原始请求体。
func VerifyWebhook(r *http.Request, secret string) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	ts := r.Header.Get(HeaderWebhookTimestamp)
	sec, err := strconv.ParseInt(ts, 10, 64)
	if err != nil {
		return nil, ErrWebhookSignature
	}
	if d := time.Since(time.Unix(sec, 0)); d > webhookMaxSkew || d < -webhookMaxSkew {
		return nil, ErrWebhookSignature
	}
	mac := hmac.New(sha256.New, []byte(secret))
	io.WriteString(mac, ts+"\n")
	mac.Write(body)
	want := hex.EncodeToString(mac.Sum(nil))
	if !hmac.Equal([]byte(want), []byte(r.Header.Get(HeaderWebhookSignature))) {
		return nil, ErrWebhookSignature
	}
	return body, nil
}

// ParseWebhook 校验并解析异步 webhook 事件。
func ParseWebhook(r *http.Request, secret string) (*WebhookEvent, error) {
	body, err := VerifyWebhook(r, secret)
	if err != nil {
		return nil, err
	}
	var ev WebhookEvent
	if err := json.Unmarshal(body, &ev); err != nil {
		return nil, err
	}
	return &ev, nil
}
