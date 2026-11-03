package gateway

import (
	"context"
	"slices"
	"sync"
	"time"
)

// transientMemberTTL 是瞬时消息（信令、输入状态）使用的群成员缓存有效期。
// 同步播放心跳每秒数条，不缓存时每条都要查一次 MySQL；成员变化最多晚这么久生效，对瞬时消息可以接受。
const transientMemberTTL = 3 * time.Second

type memberEntry struct {
	members []string
	expires time.Time
}

// memberCache 缓存群成员列表，只用于瞬时消息路径；持久消息的扇出仍直接查 GroupStore。
type memberCache struct {
	mu      sync.Mutex
	entries map[string]memberEntry
}

func (c *memberCache) get(ctx context.Context, store GroupStore, groupID string) ([]string, error) {
	now := time.Now()
	c.mu.Lock()
	if e, ok := c.entries[groupID]; ok && now.Before(e.expires) {
		c.mu.Unlock()
		return e.members, nil
	}
	c.mu.Unlock()

	members, err := store.GetMembers(ctx, groupID)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	if c.entries == nil {
		c.entries = make(map[string]memberEntry)
	}
	if len(c.entries) > 10000 {
		for k, e := range c.entries {
			if now.After(e.expires) {
				delete(c.entries, k)
			}
		}
	}
	c.entries[groupID] = memberEntry{members: members, expires: now.Add(transientMemberTTL)}
	c.mu.Unlock()
	return members, nil
}

func (c *memberCache) forget(groupID string) {
	c.mu.Lock()
	delete(c.entries, groupID)
	c.mu.Unlock()
}

// transientMembers 返回瞬时消息用的群成员（带短期缓存）。
func (r *Router) transientMembers(ctx context.Context, groupID string) ([]string, error) {
	return r.members.get(ctx, r.groupStore, groupID)
}

// transientIsMember 用缓存的成员列表判断 uid 是否在群里。
func (r *Router) transientIsMember(ctx context.Context, groupID, uid string) bool {
	members, err := r.transientMembers(ctx, groupID)
	return err == nil && slices.Contains(members, uid)
}

// ForgetGroupMembers 让本节点缓存的群成员失效，成员变更后调用。
func (r *Router) ForgetGroupMembers(groupID string) {
	r.members.forget(groupID)
}
