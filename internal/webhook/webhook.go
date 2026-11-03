// Package webhook 把 IM 事件推送给接入方业务后端：网关发布事件到队列（Redis Stream，无 Redis 时用内存），
// 分发协程带 HMAC 签名 POST 到各接入方的 webhook_url，失败按指数退避重试约 24 小时，仍失败进入死信队列。
// 另提供可选的同步 before_send 回调，供接入方做内容审核或拒绝消息。
package webhook

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"time"
)

// 事件类型。
const (
	EventMessageSent       = "message.sent"
	EventMessageRecalled   = "message.recalled"
	EventGroupMemberJoined = "group.member_joined"
	EventGroupMemberLeft   = "group.member_left"
	EventGroupDismissed    = "group.dismissed"
	EventUserOnline        = "user.online"
	EventUserOffline       = "user.offline"
	EventReportCreated     = "report.created"
)

// 回调请求头。签名 = hex(HMAC-SHA256(webhook_secret, TIMESTAMP "\n" BODY))。
const (
	HeaderEvent     = "X-IM-Event"
	HeaderDelivery  = "X-IM-Delivery" // 事件 ID，重试时不变，接收方据此去重
	HeaderTimestamp = "X-IM-Timestamp"
	HeaderSignature = "X-IM-Signature"
)

// Event 是回调请求体。
type Event struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	AppID     string          `json:"app_id"`
	Timestamp int64           `json:"timestamp"` // Unix 毫秒
	Data      json.RawMessage `json:"data"`
}

// Sign 计算回调签名。
func Sign(secret, timestamp string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	io.WriteString(mac, timestamp+"\n")
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// Target 是一个接收回调的接入方。
type Target struct {
	AppID  string
	URL    string
	Secret string
	// Events 为空 = 订阅全部。
	Events []string
}

func (t Target) wants(typ string) bool {
	if len(t.Events) == 0 {
		return true
	}
	for _, e := range t.Events {
		if e == typ {
			return true
		}
	}
	return false
}

// publishBuffer 是发布缓冲：热路径只做一次非阻塞入队，由后台协程写入队列。
const publishBuffer = 10000

type pending struct {
	app string
	d   delivery
}

// Publisher 发布事件。Publish 从不阻塞，缓冲满时丢弃并记日志。
type Publisher struct {
	targets []Target
	queue   Queue
	ch      chan pending
	now     func() time.Time
}

// NewPublisher 创建发布者，调用 Start 后才真正写入队列。
func NewPublisher(targets []Target, queue Queue) *Publisher {
	return &Publisher{targets: targets, queue: queue, ch: make(chan pending, publishBuffer), now: time.Now}
}

// Start 启动写队列协程，直到 ctx 结束。
func (p *Publisher) Start(ctx context.Context) {
	go func() {
		for {
			select {
			case <-ctx.Done():
				return
			case it := <-p.ch:
				if err := p.queue.Add(ctx, it.app, it.d); err != nil {
					log.Printf("[webhook] enqueue %s for %s: %v", it.d.Type, it.app, err)
				}
			}
		}
	}()
}

func newEventID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// Publish 向订阅了该类型的接入方发布事件。
func (p *Publisher) Publish(typ string, data any) {
	if p == nil {
		return
	}
	raw, err := json.Marshal(data)
	if err != nil {
		log.Printf("[webhook] marshal %s: %v", typ, err)
		return
	}
	now := p.now()
	id := newEventID()
	for _, t := range p.targets {
		if !t.wants(typ) {
			continue
		}
		body, _ := json.Marshal(Event{ID: id, Type: typ, AppID: t.AppID, Timestamp: now.UnixMilli(), Data: raw})
		select {
		case p.ch <- pending{app: t.AppID, d: delivery{ID: id, Type: typ, Body: body, First: now.UnixMilli()}}:
		default:
			log.Printf("[webhook] publish buffer full, dropped %s for %s", typ, t.AppID)
		}
	}
}
