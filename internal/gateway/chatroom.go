package gateway

import (
	"context"
	"sync"

	"github.com/im/api/proto"
)

// groupKindCacheMax 是群类型缓存的上限，超过后整体清空（群类型不变，清空只多查几次库）。
const groupKindCacheMax = 100000

// groupKindCache 缓存群 ID → 是否聊天室。群类型创建后不再改变，群 ID 也不复用，所以不需要失效。
type groupKindCache struct {
	mu       sync.Mutex
	chatroom map[string]bool
}

func (c *groupKindCache) get(groupID string) (chatroom, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	chatroom, ok = c.chatroom[groupID]
	return chatroom, ok
}

func (c *groupKindCache) put(groupID string, chatroom bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.chatroom == nil || len(c.chatroom) >= groupKindCacheMax {
		c.chatroom = make(map[string]bool)
	}
	c.chatroom[groupID] = chatroom
}

// isChatroom 报告 groupID 是否为聊天室；群不存在或查询失败时按普通群处理。
func (r *Router) isChatroom(ctx context.Context, groupID string) bool {
	if r.groupStore == nil || groupID == "" {
		return false
	}
	if chatroom, ok := r.groupKinds.get(groupID); ok {
		return chatroom
	}
	g, err := r.groupStore.Get(ctx, groupID)
	if err != nil {
		return false
	}
	r.groupKinds.put(groupID, g.IsChatroom())
	return g.IsChatroom()
}

// OnlineOnly 报告消息是否只投递给在线连接：瞬时消息（信令、输入状态）与聊天室消息，
// 收不到就丢弃，不转存离线。对端网关处理转发时也用它判断。
func (r *Router) OnlineOnly(ctx context.Context, msg *proto.Message) bool {
	if msg.IsTransient() {
		return true
	}
	return msg.ChatType == proto.ChatTypeGroup && r.isChatroom(ctx, msg.To)
}

// deliverOnline 投递给 uid 在线的设备：本节点没有连接时转发给归属网关，收不到就丢弃。
func (r *Router) deliverOnline(ctx context.Context, uid string, msg *proto.Message) (found, failed bool) {
	found, failed = r.sendLocal(ctx, uid, msg, nil)
	if !found {
		r.forwardTransient(ctx, uid, msg)
	}
	return found, failed
}
