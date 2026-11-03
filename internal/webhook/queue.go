package webhook

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// delivery 是一次待投递的回调。
type delivery struct {
	ID      string          `json:"id"`
	Type    string          `json:"type"`
	Body    json.RawMessage `json:"body"`
	Attempt int             `json:"attempt"`
	First   int64           `json:"first"` // 首次入队时间，Unix 毫秒

	streamID string // Redis Stream 条目 ID，仅新投递有
}

// Queue 保存待投递与待重试的回调。多网关共用 Redis 时每条只会被一个网关投递。
type Queue interface {
	// Add 加入新回调。
	Add(ctx context.Context, app string, d delivery) error
	// Next 取新回调，最多等 block。
	Next(ctx context.Context, app string, block time.Duration) ([]delivery, error)
	// Due 认领到期的重试（认领后一段时间内其他网关不会再取到）。
	Due(ctx context.Context, app string, now time.Time) ([]delivery, error)
	// Done 标记投递成功。
	Done(ctx context.Context, app string, d delivery) error
	// Retry 安排在 at 重试（d.Attempt 已递增）。
	Retry(ctx context.Context, app string, d delivery, at time.Time) error
	// Dead 放弃并移入死信队列。
	Dead(ctx context.Context, app string, d delivery) error
}

const (
	streamMaxLen  = 100000
	deadMaxLen    = 10000
	claimLease    = 2 * time.Minute // 认领后多久没完成就允许别的网关接手
	batchSize     = 32
	consumerGroup = "dispatch"
)

// RedisQueue：新回调写 Stream im:webhook:{app}（消费组 dispatch），重试放 ZSET im:webhook:{app}:retry
// （成员为事件 ID，内容在 HASH im:webhook:{app}:payload），死信 LIST im:webhook:{app}:dead。
type RedisQueue struct {
	rdb      *redis.Client
	consumer string

	mu      sync.Mutex
	grouped map[string]bool
}

// NewRedisQueue 创建 Redis 队列，consumer 为本网关在消费组里的名字（如节点 ID）。
func NewRedisQueue(rdb *redis.Client, consumer string) *RedisQueue {
	return &RedisQueue{rdb: rdb, consumer: consumer, grouped: map[string]bool{}}
}

func streamKey(app string) string  { return "im:webhook:" + app }
func retryKey(app string) string   { return "im:webhook:" + app + ":retry" }
func payloadKey(app string) string { return "im:webhook:" + app + ":payload" }
func deadKey(app string) string    { return "im:webhook:" + app + ":dead" }

func (q *RedisQueue) ensureGroup(ctx context.Context, app string) error {
	q.mu.Lock()
	ok := q.grouped[app]
	q.mu.Unlock()
	if ok {
		return nil
	}
	err := q.rdb.XGroupCreateMkStream(ctx, streamKey(app), consumerGroup, "0").Err()
	if err != nil && !isBusyGroup(err) {
		return err
	}
	q.mu.Lock()
	q.grouped[app] = true
	q.mu.Unlock()
	return nil
}

func isBusyGroup(err error) bool {
	return err != nil && len(err.Error()) >= 9 && err.Error()[:9] == "BUSYGROUP"
}

// Add 实现 Queue。
func (q *RedisQueue) Add(ctx context.Context, app string, d delivery) error {
	raw, _ := json.Marshal(d)
	return q.rdb.XAdd(ctx, &redis.XAddArgs{
		Stream: streamKey(app),
		MaxLen: streamMaxLen,
		Approx: true,
		Values: map[string]any{"d": raw},
	}).Err()
}

func parseEntries(msgs []redis.XMessage) []delivery {
	out := make([]delivery, 0, len(msgs))
	for _, m := range msgs {
		s, _ := m.Values["d"].(string)
		var d delivery
		if json.Unmarshal([]byte(s), &d) != nil {
			continue
		}
		d.streamID = m.ID
		out = append(out, d)
	}
	return out
}

// Next 实现 Queue：先接手其他网关认领后超时未确认的条目，再读新条目。
func (q *RedisQueue) Next(ctx context.Context, app string, block time.Duration) ([]delivery, error) {
	if err := q.ensureGroup(ctx, app); err != nil {
		return nil, err
	}
	claimed, _, err := q.rdb.XAutoClaim(ctx, &redis.XAutoClaimArgs{
		Stream: streamKey(app), Group: consumerGroup, Consumer: q.consumer,
		MinIdle: claimLease, Start: "0", Count: batchSize,
	}).Result()
	if err == nil && len(claimed) > 0 {
		return parseEntries(claimed), nil
	}
	res, err := q.rdb.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group: consumerGroup, Consumer: q.consumer,
		Streams: []string{streamKey(app), ">"},
		Count:   batchSize, Block: block,
	}).Result()
	if errors.Is(err, redis.Nil) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []delivery
	for _, s := range res {
		out = append(out, parseEntries(s.Messages)...)
	}
	return out, nil
}

// dueScript 取出到期成员并把分数推后 lease 毫秒（认领），返回成员列表。
var dueScript = redis.NewScript(`
local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[3]))
for _, id in ipairs(ids) do
  redis.call('ZADD', KEYS[1], tonumber(ARGV[1]) + tonumber(ARGV[2]), id)
end
return ids
`)

// Due 实现 Queue。
func (q *RedisQueue) Due(ctx context.Context, app string, now time.Time) ([]delivery, error) {
	ids, err := dueScript.Run(ctx, q.rdb, []string{retryKey(app)}, now.UnixMilli(), claimLease.Milliseconds(), batchSize).StringSlice()
	if err != nil || len(ids) == 0 {
		return nil, err
	}
	vals, err := q.rdb.HMGet(ctx, payloadKey(app), ids...).Result()
	if err != nil {
		return nil, err
	}
	out := make([]delivery, 0, len(ids))
	for i, v := range vals {
		s, ok := v.(string)
		var d delivery
		if !ok || json.Unmarshal([]byte(s), &d) != nil {
			q.rdb.ZRem(ctx, retryKey(app), ids[i])
			continue
		}
		out = append(out, d)
	}
	return out, nil
}

// finish 清掉 d 在 Stream 或重试集合里的记录。
func (q *RedisQueue) finish(ctx context.Context, pipe redis.Pipeliner, app string, d delivery) {
	if d.streamID != "" {
		pipe.XAck(ctx, streamKey(app), consumerGroup, d.streamID)
		pipe.XDel(ctx, streamKey(app), d.streamID)
	} else {
		pipe.ZRem(ctx, retryKey(app), d.ID)
		pipe.HDel(ctx, payloadKey(app), d.ID)
	}
}

// Done 实现 Queue。
func (q *RedisQueue) Done(ctx context.Context, app string, d delivery) error {
	_, err := q.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		q.finish(ctx, p, app, d)
		return nil
	})
	return err
}

// Retry 实现 Queue。
func (q *RedisQueue) Retry(ctx context.Context, app string, d delivery, at time.Time) error {
	raw, _ := json.Marshal(d)
	_, err := q.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		if d.streamID != "" {
			p.XAck(ctx, streamKey(app), consumerGroup, d.streamID)
			p.XDel(ctx, streamKey(app), d.streamID)
		}
		p.HSet(ctx, payloadKey(app), d.ID, raw)
		p.ZAdd(ctx, retryKey(app), redis.Z{Score: float64(at.UnixMilli()), Member: d.ID})
		return nil
	})
	return err
}

// Dead 实现 Queue。
func (q *RedisQueue) Dead(ctx context.Context, app string, d delivery) error {
	raw, _ := json.Marshal(d)
	_, err := q.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		q.finish(ctx, p, app, d)
		p.LPush(ctx, deadKey(app), raw)
		p.LTrim(ctx, deadKey(app), 0, deadMaxLen-1)
		return nil
	})
	return err
}

// MemoryQueue 是单实例、无 Redis 时的实现，重启即丢失。
type MemoryQueue struct {
	mu      sync.Mutex
	fresh   map[string]chan delivery
	retries map[string]map[string]retryEntry
	dead    map[string][]delivery
}

type retryEntry struct {
	d  delivery
	at time.Time
}

// NewMemoryQueue 创建内存队列。
func NewMemoryQueue() *MemoryQueue {
	return &MemoryQueue{fresh: map[string]chan delivery{}, retries: map[string]map[string]retryEntry{}, dead: map[string][]delivery{}}
}

func (q *MemoryQueue) ch(app string) chan delivery {
	q.mu.Lock()
	defer q.mu.Unlock()
	c, ok := q.fresh[app]
	if !ok {
		c = make(chan delivery, publishBuffer)
		q.fresh[app] = c
	}
	return c
}

// Add 实现 Queue。
func (q *MemoryQueue) Add(_ context.Context, app string, d delivery) error {
	select {
	case q.ch(app) <- d:
		return nil
	default:
		return errors.New("memory queue full")
	}
}

// Next 实现 Queue。
func (q *MemoryQueue) Next(ctx context.Context, app string, block time.Duration) ([]delivery, error) {
	c := q.ch(app)
	t := time.NewTimer(block)
	defer t.Stop()
	select {
	case d := <-c:
		out := []delivery{d}
		for len(out) < batchSize {
			select {
			case d := <-c:
				out = append(out, d)
			default:
				return out, nil
			}
		}
		return out, nil
	case <-t.C:
		return nil, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// Due 实现 Queue。
func (q *MemoryQueue) Due(_ context.Context, app string, now time.Time) ([]delivery, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	var out []delivery
	for id, e := range q.retries[app] {
		if !now.Before(e.at) {
			out = append(out, e.d)
			delete(q.retries[app], id)
		}
	}
	return out, nil
}

// Done 实现 Queue。
func (q *MemoryQueue) Done(context.Context, string, delivery) error { return nil }

// Retry 实现 Queue。
func (q *MemoryQueue) Retry(_ context.Context, app string, d delivery, at time.Time) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.retries[app] == nil {
		q.retries[app] = map[string]retryEntry{}
	}
	q.retries[app][d.ID] = retryEntry{d: d, at: at}
	return nil
}

// Dead 实现 Queue。
func (q *MemoryQueue) Dead(_ context.Context, app string, d delivery) error {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.dead[app] = append(q.dead[app], d)
	if len(q.dead[app]) > deadMaxLen {
		q.dead[app] = q.dead[app][1:]
	}
	return nil
}
