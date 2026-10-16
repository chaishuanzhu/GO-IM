package snowflake

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	mrand "math/rand/v2"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// DefaultLeasePrefix 是 worker ID 租约在 Redis 中的 key 前缀，后接 worker ID。
const DefaultLeasePrefix = "im:snowflake:worker:"

// ErrNoFreeWorkerID 表示范围内的 worker ID 都已被其他实例租用。
var ErrNoFreeWorkerID = errors.New("no free snowflake worker ID")

// renewScript 仅当租约仍属于自己时续期，返回 1；否则返回 0。
var renewScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
	return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0`)

// releaseScript 仅删除属于自己的租约。
var releaseScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
	return redis.call('DEL', KEYS[1])
end
return 0`)

// LeaseConfig 描述 worker ID 的租用范围。不同服务应使用互不重叠的范围，
// 且不包含各自配置文件里的固定 worker ID（Redis 不可用时的回退值）。
type LeaseConfig struct {
	Prefix string        // 空 = DefaultLeasePrefix
	Min    int64         // 含
	Max    int64         // 含
	TTL    time.Duration // 空 = 30s；每 TTL/3 续期一次
}

// Lease 持有一个 Redis 中的 worker ID 租约，后台续期；租约被他人占用时换一个新 ID 并更新 Generator。
// 多个实例同时运行（滚动更新的新旧 Pod 并存）时，由此保证各自的 worker ID 不同。
type Lease struct {
	rdb   redis.Cmdable
	gen   *Generator
	cfg   LeaseConfig
	owner string

	mu sync.Mutex
	id int64

	stop chan struct{}
	done chan struct{}
}

// NewLeased 从 Redis 租用一个 worker ID，返回使用该 ID 的 Generator 和租约。
// 调用方在退出时应调用 Lease.Close 释放租约。
func NewLeased(ctx context.Context, rdb redis.Cmdable, cfg LeaseConfig) (*Generator, *Lease, error) {
	if cfg.Prefix == "" {
		cfg.Prefix = DefaultLeasePrefix
	}
	if cfg.TTL <= 0 {
		cfg.TTL = 30 * time.Second
	}
	if cfg.Min < 0 || cfg.Max > workerMax || cfg.Min > cfg.Max {
		return nil, nil, fmt.Errorf("%w: lease range %d-%d", ErrWorkerIDInvalid, cfg.Min, cfg.Max)
	}
	l := &Lease{rdb: rdb, cfg: cfg, owner: leaseOwner(), stop: make(chan struct{}), done: make(chan struct{})}
	id, err := l.acquire(ctx)
	if err != nil {
		return nil, nil, err
	}
	gen, err := New(id)
	if err != nil {
		l.release(ctx, id)
		return nil, nil, err
	}
	l.gen, l.id = gen, id
	go l.renewLoop()
	return gen, l, nil
}

// WorkerID 返回当前租用的 worker ID。
func (l *Lease) WorkerID() int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.id
}

// Close 停止续期并释放租约。
func (l *Lease) Close() {
	select {
	case <-l.stop:
		return
	default:
	}
	close(l.stop)
	<-l.done
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	l.release(ctx, l.WorkerID())
}

func (l *Lease) key(id int64) string { return l.cfg.Prefix + strconv.FormatInt(id, 10) }

// acquire 从范围内随机起点开始依次尝试 SET NX，减少多个实例同时启动时的争抢。
func (l *Lease) acquire(ctx context.Context) (int64, error) {
	n := l.cfg.Max - l.cfg.Min + 1
	start := mrand.Int64N(n)
	for i := int64(0); i < n; i++ {
		id := l.cfg.Min + (start+i)%n
		ok, err := l.rdb.SetNX(ctx, l.key(id), l.owner, l.cfg.TTL).Result()
		if err != nil {
			return 0, fmt.Errorf("lease worker ID: %w", err)
		}
		if ok {
			return id, nil
		}
	}
	return 0, fmt.Errorf("%w in %d-%d", ErrNoFreeWorkerID, l.cfg.Min, l.cfg.Max)
}

func (l *Lease) release(ctx context.Context, id int64) {
	if err := releaseScript.Run(ctx, l.rdb, []string{l.key(id)}, l.owner).Err(); err != nil {
		log.Printf("[snowflake] release worker ID %d: %v", id, err)
	}
}

func (l *Lease) renewLoop() {
	defer close(l.done)
	t := time.NewTicker(l.cfg.TTL / 3)
	defer t.Stop()
	for {
		select {
		case <-l.stop:
			return
		case <-t.C:
			l.renew()
		}
	}
}

// renew 续期当前租约。Redis 暂时不可用时保持原 ID（续期失败超过 TTL 才可能被他人租走）；
// 租约已过期则重新占用，已被他人占用则换新 ID。
func (l *Lease) renew() {
	ctx, cancel := context.WithTimeout(context.Background(), l.cfg.TTL/3)
	defer cancel()
	id := l.WorkerID()
	ok, err := renewScript.Run(ctx, l.rdb, []string{l.key(id)}, l.owner, l.cfg.TTL.Milliseconds()).Int()
	if err != nil {
		log.Printf("[snowflake] renew worker ID %d: %v", id, err)
		return
	}
	if ok == 1 {
		return
	}
	if again, err := l.rdb.SetNX(ctx, l.key(id), l.owner, l.cfg.TTL).Result(); err == nil && again {
		log.Printf("[snowflake] worker ID %d lease expired, re-acquired", id)
		return
	}
	next, err := l.acquire(ctx)
	if err != nil {
		log.Printf("[snowflake] worker ID %d lease lost and no replacement: %v", id, err)
		return
	}
	if err := l.gen.SetWorkerID(next); err != nil {
		log.Printf("[snowflake] switch worker ID %d → %d: %v", id, next, err)
		l.release(ctx, next)
		return
	}
	l.mu.Lock()
	l.id = next
	l.mu.Unlock()
	log.Printf("[snowflake] worker ID %d lease taken by another instance, switched to %d", id, next)
}

func leaseOwner() string {
	host, _ := os.Hostname()
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%s:%d:%s", host, os.Getpid(), hex.EncodeToString(b))
}
