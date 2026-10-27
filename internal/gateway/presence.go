package gateway

import (
	"context"
	"log"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// Presence 查询用户是否在线（任一网关上有连接）。
type Presence interface {
	// Online 返回 uids 中在线的那些。
	Online(ctx context.Context, uids []string) map[string]bool
	// Count 返回全集群在线用户数；ok=false 表示无法统计（调用方回退到本网关的数）。
	Count(ctx context.Context) (n int64, ok bool)
}

// localPresence 只看本网关的连接（未配置 Redis 的单机部署）。
type localPresence struct{ reg ClientRegistry }

func (p localPresence) Online(ctx context.Context, uids []string) map[string]bool {
	out := make(map[string]bool, len(uids))
	for _, uid := range uids {
		if p.reg.IsOnline(ctx, uid) {
			out[uid] = true
		}
	}
	return out
}

func (p localPresence) Count(context.Context) (int64, bool) { return 0, false }

const (
	presenceKeyPrefix = "im:online:"       // + 节点名：该网关的在线位图，位偏移 = 数字 UID
	presenceTmpPrefix = "im:online-tmp:"   // 重建时先写临时键再 RENAME，避免读到半成品
	presenceAggPrefix = "im:online-total:" // Count 时 BITOP OR 的结果
	presenceTTL       = 90 * time.Second
	presenceNodesTTL  = 10 * time.Second
	// presenceMaxUID 限制位图大小（2^27 位 = 16MB）；超出或非数字的 UID（早期自定义 ID）视为离线。
	presenceMaxUID = 1 << 27
)

// RedisPresence 用 Redis 位图记录在线状态：每个网关一张位图，第 UID 位为 1 表示该用户在这台网关上有连接。
// 用户上下线时增量 SETBIT，并每隔一段时间用本机连接表整张重建（同时续期），
// 所以网关崩溃后它的位图会在 presenceTTL 内过期，增量写入的乱序也会在下次重建时纠正。
type RedisPresence struct {
	rdb  *redis.Client
	reg  ClientRegistry
	node string

	mu      sync.Mutex
	nodes   []string
	nodesAt time.Time
}

// NewRedisPresence 创建位图在线状态；node 在集群内须唯一（多网关时用 node_id 或主机名）。
func NewRedisPresence(rdb *redis.Client, reg ClientRegistry, node string) *RedisPresence {
	return &RedisPresence{rdb: rdb, reg: reg, node: node}
}

func (p *RedisPresence) key() string { return presenceKeyPrefix + p.node }

func presenceOffset(uid string) (int64, bool) {
	n, err := strconv.ParseInt(uid, 10, 64)
	if err != nil || n < 0 || n >= presenceMaxUID {
		return 0, false
	}
	return n, true
}

// Changed 在用户于本网关首次上线或最后一个连接断开后调用，按当前连接表写入该位。
func (p *RedisPresence) Changed(ctx context.Context, uid string) {
	off, ok := presenceOffset(uid)
	if !ok {
		return
	}
	bit := 0
	if p.reg.IsOnline(ctx, uid) {
		bit = 1
	}
	if err := p.rdb.SetBit(ctx, p.key(), off, bit).Err(); err != nil {
		log.Printf("[presence] setbit %s=%d failed: %v", uid, bit, err)
	}
}

// Start 立即重建一次本网关的位图，之后每 interval 重建并续期，直到 ctx 结束。
func (p *RedisPresence) Start(ctx context.Context, interval time.Duration) {
	p.rebuild(ctx)
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				// 正常停机时清掉自己的位图，不必等过期。
				cleanup, cancel := context.WithTimeout(context.Background(), 2*time.Second)
				p.rdb.Del(cleanup, p.key())
				cancel()
				return
			case <-ticker.C:
				p.rebuild(ctx)
			}
		}
	}()
}

func (p *RedisPresence) rebuild(ctx context.Context) {
	var bitmap []byte
	for _, uid := range p.reg.OnlineUsers(ctx) {
		off, ok := presenceOffset(uid)
		if !ok {
			continue
		}
		idx := int(off / 8)
		if idx >= len(bitmap) {
			grown := make([]byte, idx+1)
			copy(grown, bitmap)
			bitmap = grown
		}
		bitmap[idx] |= 0x80 >> (off % 8) // Redis 位序：字节内高位在前
	}
	if len(bitmap) == 0 {
		// 没人在线时保留一个空字节，让其他网关仍能发现本节点（也便于续期）。
		bitmap = []byte{0}
	}
	tmp := presenceTmpPrefix + p.node
	pipe := p.rdb.TxPipeline()
	pipe.Set(ctx, tmp, bitmap, presenceTTL)
	pipe.Rename(ctx, tmp, p.key())
	if _, err := pipe.Exec(ctx); err != nil {
		log.Printf("[presence] rebuild failed: %v", err)
	}
}

// nodeKeys 返回当前存活网关的位图键（缓存 presenceNodesTTL）。
func (p *RedisPresence) nodeKeys(ctx context.Context) []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.nodes != nil && time.Since(p.nodesAt) < presenceNodesTTL {
		return p.nodes
	}
	var keys []string
	iter := p.rdb.Scan(ctx, 0, presenceKeyPrefix+"*", 100).Iterator()
	for iter.Next(ctx) {
		keys = append(keys, iter.Val())
	}
	if err := iter.Err(); err != nil {
		log.Printf("[presence] scan nodes failed: %v", err)
		if p.nodes != nil {
			return p.nodes
		}
		return []string{p.key()}
	}
	p.nodes, p.nodesAt = keys, time.Now()
	return keys
}

func (p *RedisPresence) Online(ctx context.Context, uids []string) map[string]bool {
	out := make(map[string]bool, len(uids))
	type probe struct {
		uid string
		cmd *redis.IntCmd
	}
	var probes []probe
	keys := p.nodeKeys(ctx)
	pipe := p.rdb.Pipeline()
	for _, uid := range uids {
		if p.reg.IsOnline(ctx, uid) {
			out[uid] = true
			continue
		}
		off, ok := presenceOffset(uid)
		if !ok {
			continue
		}
		for _, k := range keys {
			probes = append(probes, probe{uid, pipe.GetBit(ctx, k, off)})
		}
	}
	if len(probes) == 0 {
		return out
	}
	if _, err := pipe.Exec(ctx); err != nil && err != redis.Nil {
		log.Printf("[presence] getbit failed: %v", err)
	}
	for _, pr := range probes {
		if pr.cmd.Val() == 1 {
			out[pr.uid] = true
		}
	}
	return out
}

func (p *RedisPresence) Count(ctx context.Context) (int64, bool) {
	keys := p.nodeKeys(ctx)
	if len(keys) == 0 {
		return 0, true
	}
	agg := presenceAggPrefix + p.node
	pipe := p.rdb.TxPipeline()
	pipe.BitOpOr(ctx, agg, keys...)
	count := pipe.BitCount(ctx, agg, nil)
	pipe.Del(ctx, agg)
	if _, err := pipe.Exec(ctx); err != nil {
		log.Printf("[presence] count failed: %v", err)
		return 0, false
	}
	return count.Val(), true
}
