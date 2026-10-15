package gateway

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

// CodePurpose 区分验证码用途，不同用途的验证码互不通用。
type CodePurpose string

const (
	PurposeRegister      CodePurpose = "register"
	PurposeResetPassword CodePurpose = "reset_password"
	PurposeBindEmail     CodePurpose = "bind_email"
)

func (p CodePurpose) valid() bool {
	return p == PurposeRegister || p == PurposeResetPassword || p == PurposeBindEmail
}

const (
	codeTTL         = 10 * time.Minute
	codeCooldown    = 60 * time.Second
	codeMaxAttempts = 5
	codeDailyLimit  = 10 // 每个邮箱每天
	codeIPLimit     = 20 // 每个 IP 每小时
)

var (
	// ErrCodeInvalid 表示验证码错误、已过期或错误次数过多被作废。
	ErrCodeInvalid = errors.New("verification code invalid or expired")
	// ErrCodeQuota 表示该邮箱当日或该 IP 当前小时的发送次数已用完。
	ErrCodeQuota = errors.New("too many verification codes requested")
)

// CodeCooldownError 表示距离上次发送不足冷却时间。
type CodeCooldownError struct{ RetryAfter time.Duration }

func (e *CodeCooldownError) Error() string {
	return fmt.Sprintf("verification code requested too frequently, retry after %s", e.RetryAfter.Round(time.Second))
}

// CodeStore 保存邮箱验证码。只保存验证码的哈希；验证成功后作废。
type CodeStore interface {
	// Issue 检查冷却与配额后保存新验证码。
	Issue(ctx context.Context, purpose CodePurpose, email, ip, code string) error
	// Verify 校验并作废验证码；错误 codeMaxAttempts 次后验证码作废。
	Verify(ctx context.Context, purpose CodePurpose, email, code string) error
	// Revoke 撤销验证码与冷却（邮件发送失败时让用户可立即重试）。
	Revoke(ctx context.Context, purpose CodePurpose, email string)
}

// newVerificationCode 生成 6 位数字验证码。
func newVerificationCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("%06d", n.Int64())
}

func codeHash(purpose CodePurpose, email, code string) string {
	sum := sha256.Sum256([]byte(string(purpose) + "\x00" + email + "\x00" + code))
	return hex.EncodeToString(sum[:])
}

func hashEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// ---------- Redis ----------

// RedisCodeStore 在 Redis 中保存验证码，多网关共享。
type RedisCodeStore struct {
	rdb    *redis.Client
	prefix string
}

// NewRedisCodeStore 创建 RedisCodeStore，键前缀为 "im:vcode:"。
func NewRedisCodeStore(rdb *redis.Client) *RedisCodeStore {
	return &RedisCodeStore{rdb: rdb, prefix: "im:vcode:"}
}

func (s *RedisCodeStore) keys(purpose CodePurpose, email string) (code, cooldown string) {
	base := string(purpose) + ":" + email
	return s.prefix + "code:" + base, s.prefix + "cd:" + base
}

func (s *RedisCodeStore) Issue(ctx context.Context, purpose CodePurpose, email, ip, code string) error {
	codeKey, cdKey := s.keys(purpose, email)
	ok, err := s.rdb.SetNX(ctx, cdKey, 1, codeCooldown).Result()
	if err != nil {
		return err
	}
	if !ok {
		ttl, _ := s.rdb.TTL(ctx, cdKey).Result()
		if ttl <= 0 {
			ttl = time.Second
		}
		return &CodeCooldownError{RetryAfter: ttl}
	}
	now := time.Now()
	dayKey := s.prefix + "day:" + email + ":" + now.Format("20060102")
	ipKey := s.prefix + "ip:" + ip + ":" + now.Format("2006010215")
	if over, err := s.incrOver(ctx, dayKey, 24*time.Hour, codeDailyLimit); err != nil || over {
		return quotaOrErr(err)
	}
	if over, err := s.incrOver(ctx, ipKey, time.Hour, codeIPLimit); err != nil || over {
		return quotaOrErr(err)
	}
	pipe := s.rdb.TxPipeline()
	pipe.Del(ctx, codeKey)
	pipe.HSet(ctx, codeKey, "h", codeHash(purpose, email, code), "n", 0)
	pipe.Expire(ctx, codeKey, codeTTL)
	_, err = pipe.Exec(ctx)
	return err
}

func (s *RedisCodeStore) incrOver(ctx context.Context, key string, window time.Duration, limit int64) (bool, error) {
	n, err := s.rdb.Incr(ctx, key).Result()
	if err != nil {
		return false, err
	}
	if n == 1 {
		s.rdb.Expire(ctx, key, window)
	}
	return n > limit, nil
}

func quotaOrErr(err error) error {
	if err != nil {
		return err
	}
	return ErrCodeQuota
}

func (s *RedisCodeStore) Verify(ctx context.Context, purpose CodePurpose, email, code string) error {
	codeKey, _ := s.keys(purpose, email)
	stored, err := s.rdb.HGet(ctx, codeKey, "h").Result()
	if errors.Is(err, redis.Nil) {
		return ErrCodeInvalid
	}
	if err != nil {
		return err
	}
	if !hashEqual(stored, codeHash(purpose, email, code)) {
		if n, err := s.rdb.HIncrBy(ctx, codeKey, "n", 1).Result(); err == nil && n >= codeMaxAttempts {
			s.rdb.Del(ctx, codeKey)
		}
		return ErrCodeInvalid
	}
	if deleted, err := s.rdb.Del(ctx, codeKey).Result(); err != nil || deleted == 0 {
		// 并发验证时只有删除成功的一方算通过。
		return ErrCodeInvalid
	}
	return nil
}

func (s *RedisCodeStore) Revoke(ctx context.Context, purpose CodePurpose, email string) {
	codeKey, cdKey := s.keys(purpose, email)
	s.rdb.Del(ctx, codeKey, cdKey)
}

// ---------- 内存 ----------

// MemoryCodeStore 是无 Redis 时使用的单进程 CodeStore。
type MemoryCodeStore struct {
	mu        sync.Mutex
	codes     map[string]*memCode
	cooldowns map[string]time.Time
	counters  map[string]*memCounter
	now       func() time.Time
}

type memCode struct {
	hash     string
	attempts int
	expires  time.Time
}

type memCounter struct {
	n       int64
	expires time.Time
}

// NewMemoryCodeStore 创建空的 MemoryCodeStore。
func NewMemoryCodeStore() *MemoryCodeStore {
	return &MemoryCodeStore{
		codes:     make(map[string]*memCode),
		cooldowns: make(map[string]time.Time),
		counters:  make(map[string]*memCounter),
		now:       time.Now,
	}
}

func (s *MemoryCodeStore) Issue(_ context.Context, purpose CodePurpose, email, ip, code string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	s.gc(now)
	key := string(purpose) + ":" + email
	if until, ok := s.cooldowns[key]; ok && now.Before(until) {
		return &CodeCooldownError{RetryAfter: until.Sub(now)}
	}
	s.cooldowns[key] = now.Add(codeCooldown)
	if s.incrOver("day:"+email+":"+now.Format("20060102"), now, 24*time.Hour, codeDailyLimit) ||
		s.incrOver("ip:"+ip+":"+now.Format("2006010215"), now, time.Hour, codeIPLimit) {
		return ErrCodeQuota
	}
	s.codes[key] = &memCode{hash: codeHash(purpose, email, code), expires: now.Add(codeTTL)}
	return nil
}

func (s *MemoryCodeStore) incrOver(key string, now time.Time, window time.Duration, limit int64) bool {
	c, ok := s.counters[key]
	if !ok || !now.Before(c.expires) {
		c = &memCounter{expires: now.Add(window)}
		s.counters[key] = c
	}
	c.n++
	return c.n > limit
}

func (s *MemoryCodeStore) Verify(_ context.Context, purpose CodePurpose, email, code string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := string(purpose) + ":" + email
	c, ok := s.codes[key]
	if !ok || !s.now().Before(c.expires) {
		delete(s.codes, key)
		return ErrCodeInvalid
	}
	if !hashEqual(c.hash, codeHash(purpose, email, code)) {
		c.attempts++
		if c.attempts >= codeMaxAttempts {
			delete(s.codes, key)
		}
		return ErrCodeInvalid
	}
	delete(s.codes, key)
	return nil
}

func (s *MemoryCodeStore) Revoke(_ context.Context, purpose CodePurpose, email string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := string(purpose) + ":" + email
	delete(s.codes, key)
	delete(s.cooldowns, key)
}

func (s *MemoryCodeStore) gc(now time.Time) {
	for k, c := range s.codes {
		if !now.Before(c.expires) {
			delete(s.codes, k)
		}
	}
	for k, until := range s.cooldowns {
		if !now.Before(until) {
			delete(s.cooldowns, k)
		}
	}
	for k, c := range s.counters {
		if !now.Before(c.expires) {
			delete(s.counters, k)
		}
	}
}
