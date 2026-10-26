package gateway

import (
	"context"
	"log"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

const unreadKeyPrefix = "im:unread:"

// unreadTTL 是未读计数在无新消息时的保留时间;每次自增都会刷新。
const unreadTTL = 30 * 24 * time.Hour

// RedisUnreadTracker 用 Redis Hash(im:unread:{uid},field = peer)保存未读计数,
// 多个网关、同一用户的多台设备看到的是同一份计数。Redis 出错时回退到内存实现。
type RedisUnreadTracker struct {
	client   *redis.Client
	fallback UnreadTracker
}

// NewRedisUnreadTracker 创建基于 Redis 的未读计数跟踪器。
func NewRedisUnreadTracker(client *redis.Client) *RedisUnreadTracker {
	return &RedisUnreadTracker{client: client, fallback: NewInMemoryUnreadTracker()}
}

func (t *RedisUnreadTracker) Increment(ctx context.Context, toUID, fromUID string) {
	if toUID == fromUID {
		return
	}
	key := unreadKeyPrefix + toUID
	pipe := t.client.Pipeline()
	pipe.HIncrBy(ctx, key, fromUID, 1)
	pipe.Expire(ctx, key, unreadTTL)
	if _, err := pipe.Exec(ctx); err != nil {
		log.Printf("[unread] redis increment %s<-%s failed, using memory: %v", toUID, fromUID, err)
		t.fallback.Increment(ctx, toUID, fromUID)
	}
}

func (t *RedisUnreadTracker) MarkRead(ctx context.Context, readerUID, peerUID string) {
	t.fallback.MarkRead(ctx, readerUID, peerUID)
	if err := t.client.HDel(ctx, unreadKeyPrefix+readerUID, peerUID).Err(); err != nil {
		log.Printf("[unread] redis mark read %s/%s failed: %v", readerUID, peerUID, err)
	}
}

func (t *RedisUnreadTracker) GetCounts(ctx context.Context, uid string) map[string]int64 {
	raw, err := t.client.HGetAll(ctx, unreadKeyPrefix+uid).Result()
	if err != nil {
		log.Printf("[unread] redis get counts %s failed, using memory: %v", uid, err)
		return t.fallback.GetCounts(ctx, uid)
	}
	counts := t.fallback.GetCounts(ctx, uid)
	for peer, v := range raw {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > 0 {
			counts[peer] += n
		}
	}
	return counts
}

func (t *RedisUnreadTracker) GetCount(ctx context.Context, uid, peerUID string) int64 {
	n, err := t.client.HGet(ctx, unreadKeyPrefix+uid, peerUID).Int64()
	if err != nil && err != redis.Nil {
		log.Printf("[unread] redis get count %s/%s failed, using memory: %v", uid, peerUID, err)
	}
	return n + t.fallback.GetCount(ctx, uid, peerUID)
}
