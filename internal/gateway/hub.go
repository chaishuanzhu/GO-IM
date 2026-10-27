package gateway

import (
	"context"
	"log"
	"sync"

	"github.com/im/api/proto"
)

// DevicePolicy 决定同一用户的新连接会踢掉哪些已有连接。
type DevicePolicy string

const (
	// DevicePolicySingle 每个用户只保留一个连接(默认,与多端改造前一致)。
	DevicePolicySingle DevicePolicy = "single"
	// DevicePolicyPerPlatform 每类平台(手机 / 电脑 / 网页)各保留一个连接。
	DevicePolicyPerPlatform DevicePolicy = "per_platform"
	// DevicePolicyUnlimited 不限设备数,只替换同一设备的旧连接。
	DevicePolicyUnlimited DevicePolicy = "unlimited"
)

// ParseDevicePolicy 解析配置值;空值或未知值按 single 处理。
func ParseDevicePolicy(s string) DevicePolicy {
	switch DevicePolicy(s) {
	case DevicePolicyPerPlatform, DevicePolicyUnlimited:
		return DevicePolicy(s)
	default:
		return DevicePolicySingle
	}
}

// platformClass 把平台归到互斥的类别:同类的新设备登录会踢掉旧设备。
// 未上报平台的老客户端单独成一类,彼此互踢(保持旧行为)。
func platformClass(platform string) string {
	switch platform {
	case "ios", "android", "ipados", "harmony":
		return "mobile"
	case "desktop", "macos", "windows", "linux":
		return "desktop"
	case "web":
		return "web"
	case "":
		return "legacy"
	default:
		return platform
	}
}

// Hub 管理所有已连接客户端并在它们之间路由消息。
type Hub struct {
	mu      sync.RWMutex
	clients map[string][]*Client // UID -> 该用户的所有连接(按注册先后)

	// offline 为离线用户存储消息:UID -> 消息队列
	offline        map[string][]*proto.Message
	offlineMu      sync.Mutex
	offlineMaxSize int // 每个用户的最大排队消息数

	maxConnections int // 0 = 无限制(按在线用户数计)
	policy         DevicePolicy

	// presenceHook 在用户于本节点上线(首个连接)或下线(最后一个连接断开)后异步调用。
	presenceHook func(uid string)
}

// NewHub 创建一个新的 Hub。
func NewHub(offlineMaxSize int) *Hub {
	return &Hub{
		clients:        make(map[string][]*Client),
		offline:        make(map[string][]*proto.Message),
		offlineMaxSize: offlineMaxSize,
		policy:         DevicePolicySingle,
	}
}

// SetPresenceHook 设置上下线回调(在锁外的新 goroutine 中调用,回调应自行读取当前状态)。
func (h *Hub) SetPresenceHook(fn func(uid string)) {
	h.mu.Lock()
	h.presenceHook = fn
	h.mu.Unlock()
}

// SetMaxConnections 设置全局连接数上限。0 表示无限制。
func (h *Hub) SetMaxConnections(max int) {
	h.mu.Lock()
	h.maxConnections = max
	h.mu.Unlock()
}

// SetDevicePolicy 设置多端登录策略。
func (h *Hub) SetDevicePolicy(p DevicePolicy) {
	h.mu.Lock()
	h.policy = p
	h.mu.Unlock()
}

// 服务器容量已满时,ErrServerFull 会以踢出消息的形式被返回。
const errServerFull = "server at capacity, please try later"

// kickedByOtherDevice 是被同类设备挤下线时 CmdKick 的内容。
const kickedByOtherDevice = "logged in from another device"

// conflicts 判断新连接 c 是否要踢掉同用户的已有连接 old。
func (h *Hub) conflicts(old, c *Client) bool {
	switch h.policy {
	case DevicePolicyUnlimited:
		return false
	case DevicePolicyPerPlatform:
		return platformClass(old.Platform) == platformClass(c.Platform)
	default:
		return true
	}
}

// Register 将客户端添加到 hub。
// 同一设备的旧连接(断线重连)被静默替换;按策略冲突的其他设备会先收到踢出通知再关闭。
// 当服务器容量已满且这是新 UID 时,拒绝连接。
func (h *Hub) Register(ctx context.Context, c *Client) {
	h.mu.Lock()
	existing := h.clients[c.UID]

	// 强制连接数上限 —— 仅针对新 UID。
	if len(existing) == 0 && h.maxConnections > 0 && len(h.clients) >= h.maxConnections {
		h.mu.Unlock()
		// 拒绝:发送踢出消息并关闭连接(尽力而为)。
		c.Send(&proto.Message{
			Cmd:     proto.CmdKick,
			Content: errServerFull,
		})
		c.Close()
		log.Printf("[hub] connection rejected for %s: server at capacity (%d)", c.UID, h.maxConnections)
		return
	}

	var replaced, kicked []*Client
	kept := make([]*Client, 0, len(existing)+1)
	for _, old := range existing {
		switch {
		case c.DeviceID != "" && old.DeviceID == c.DeviceID:
			replaced = append(replaced, old)
		case h.conflicts(old, c):
			kicked = append(kicked, old)
		default:
			kept = append(kept, old)
		}
	}
	// 在释放锁之前写入映射,以防另一个 goroutine 在关闭旧连接与写入映射之间注册。
	h.clients[c.UID] = append(kept, c)
	hook := h.presenceHook
	h.mu.Unlock()
	if len(existing) == 0 && hook != nil {
		go hook(c.UID)
	}

	// 在锁外通知并关闭旧连接,避免网络 I/O 阻塞其他 Register/Unregister。
	for _, old := range replaced {
		old.Close()
	}
	for _, old := range kicked {
		old.Send(&proto.Message{
			Cmd:     proto.CmdKick,
			Content: kickedByOtherDevice,
		})
		old.Close()
	}
	if len(kept) > 0 {
		log.Printf("[hub] uid=%s device=%s platform=%s online on %d devices", c.UID, c.DeviceID, c.Platform, len(kept)+1)
	}
}

// Unregister 将该连接从 hub 中移除;只移除同一个 *Client,
// 防止过期连接的清理误删同一用户的新连接。
func (h *Hub) Unregister(ctx context.Context, c *Client) {
	h.mu.Lock()
	conns := h.clients[c.UID]
	for i, existing := range conns {
		if existing == c {
			conns = append(conns[:i:i], conns[i+1:]...)
			break
		}
	}
	_, wasOnline := h.clients[c.UID]
	if len(conns) == 0 {
		delete(h.clients, c.UID)
	} else {
		h.clients[c.UID] = conns
	}
	hook := h.presenceHook
	h.mu.Unlock()
	if wasOnline && len(conns) == 0 && hook != nil {
		go hook(c.UID)
	}
}

// Get 返回指定 UID 最近注册的连接,不存在时返回 nil。
// 需要投递到用户所有设备时用 GetAll。
func (h *Hub) Get(ctx context.Context, uid string) *Client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	conns := h.clients[uid]
	if len(conns) == 0 {
		return nil
	}
	return conns[len(conns)-1]
}

// GetAll 返回指定 UID 在本节点的所有连接(副本),不存在时返回 nil。
func (h *Hub) GetAll(ctx context.Context, uid string) []*Client {
	h.mu.RLock()
	defer h.mu.RUnlock()
	conns := h.clients[uid]
	if len(conns) == 0 {
		return nil
	}
	return append([]*Client(nil), conns...)
}

// IsOnline 如果用户至少有一个连接则返回 true。
func (h *Hub) IsOnline(ctx context.Context, uid string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[uid]) > 0
}

// StoreOffline 为离线用户排队存储一条消息。
// 当队列超过配置上限时,丢弃最旧的消息并记录日志。
func (h *Hub) StoreOffline(ctx context.Context, uid string, msg *proto.Message) {
	h.offlineMu.Lock()
	h.offline[uid] = append(h.offline[uid], msg)
	if len(h.offline[uid]) > h.offlineMaxSize {
		dropped := len(h.offline[uid]) - h.offlineMaxSize
		h.offline[uid] = h.offline[uid][dropped:]
		log.Printf("[hub] offline queue truncated for %s: dropped %d oldest messages (limit=%d)", uid, dropped, h.offlineMaxSize)
	}
	h.offlineMu.Unlock()
}

// DrainOffline 返回并清除用户的离线消息。
func (h *Hub) DrainOffline(ctx context.Context, uid string) []*proto.Message {
	h.offlineMu.Lock()
	msgs := h.offline[uid]
	delete(h.offline, uid)
	h.offlineMu.Unlock()
	return msgs
}

// OnlineUsers 返回当前在线 UID 的列表。
func (h *Hub) OnlineUsers(ctx context.Context) []string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	uids := make([]string, 0, len(h.clients))
	for uid := range h.clients {
		uids = append(uids, uid)
	}
	return uids
}

// Count 返回在线用户数(一个用户的多台设备只算一次)。
func (h *Hub) Count(ctx context.Context) int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}
