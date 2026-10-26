package gateway

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/im/api/proto"
)

type batchTestStore struct {
	*testMsgStore
	mu         sync.Mutex
	batchSizes []int
	saved      []*proto.Message
	attempts   int
	failures   int
}

type blockingBatchTestStore struct {
	*testMsgStore
	started chan struct{}
	once    sync.Once
}

func (s *blockingBatchTestStore) SaveBatch(ctx context.Context, _ []*proto.Message) error {
	s.once.Do(func() { close(s.started) })
	<-ctx.Done()
	return ctx.Err()
}

func newBatchTestStore() *batchTestStore {
	return &batchTestStore{testMsgStore: &testMsgStore{}}
}

func (s *batchTestStore) SaveBatch(_ context.Context, messages []*proto.Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.attempts++
	if s.failures > 0 {
		s.failures--
		return errors.New("temporary batch failure")
	}
	s.batchSizes = append(s.batchSizes, len(messages))
	s.saved = append(s.saved, messages...)
	return nil
}

func (s *batchTestStore) snapshot() (sizes []int, saved, attempts int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]int(nil), s.batchSizes...), len(s.saved), s.attempts
}

func newPersistTestRouter(store *batchTestStore, mutate func(*RouterConfig)) *Router {
	cfg := DefaultRouterConfig()
	cfg.PersistQueueSize = 32
	cfg.PersistBatchSize = 3
	cfg.PersistFlushInterval = time.Hour
	cfg.PersistBatchWorkers = 1
	cfg.PersistEnqueueTimeout = time.Second
	cfg.PersistMaxRetries = 0
	if mutate != nil {
		mutate(&cfg)
	}
	return NewRouter(nil, nil, nil, store, cfg)
}

func enqueuePersistMessages(r *Router, n int) {
	for i := 0; i < n; i++ {
		r.persistAsync(context.Background(), &proto.Message{
			MsgId: int64(i + 1),
			Cmd:   proto.CmdChat,
			From:  "a",
			To:    "b",
		})
	}
}

func TestPersistBatcherFlushesBySizeAndOnStop(t *testing.T) {
	store := newBatchTestStore()
	r := newPersistTestRouter(store, nil)

	enqueuePersistMessages(r, 5)
	r.Stop() // 必须排空 3 条满批次和最后 2 条尾批次。

	sizes, saved, attempts := store.snapshot()
	if saved != 5 {
		t.Fatalf("saved=%d, want 5", saved)
	}
	if attempts != 2 {
		t.Fatalf("attempts=%d, want 2", attempts)
	}
	if len(sizes) != 2 || sizes[0] != 3 || sizes[1] != 2 {
		t.Fatalf("batch sizes=%v, want [3 2]", sizes)
	}
}

func TestPersistBatcherFlushesOnTimer(t *testing.T) {
	store := newBatchTestStore()
	r := newPersistTestRouter(store, func(cfg *RouterConfig) {
		cfg.PersistBatchSize = 100
		cfg.PersistFlushInterval = 10 * time.Millisecond
	})
	defer r.Stop()

	enqueuePersistMessages(r, 1)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		_, saved, _ := store.snapshot()
		if saved == 1 {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("timed flush did not persist message")
}

func TestPersistBatchRetriesTransientFailure(t *testing.T) {
	store := newBatchTestStore()
	store.failures = 2
	r := newPersistTestRouter(store, func(cfg *RouterConfig) {
		cfg.PersistMaxRetries = 3
	})

	enqueuePersistMessages(r, 3)
	r.Stop()

	_, saved, attempts := store.snapshot()
	if saved != 3 {
		t.Fatalf("saved=%d, want 3", saved)
	}
	if attempts != 3 {
		t.Fatalf("attempts=%d, want 3", attempts)
	}
}

type fakeBatchPublisher struct {
	mu        sync.Mutex
	published int
	failures  int
}

func (p *fakeBatchPublisher) PublishBatch(_ context.Context, messages []*proto.Message) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failures > 0 {
		p.failures--
		return errors.New("temporary kafka failure")
	}
	p.published += len(messages)
	return nil
}

func TestPersistWithKafkaSkipsDirectMySQL(t *testing.T) {
	store := newBatchTestStore()
	pub := &fakeBatchPublisher{failures: 1}
	r := newPersistTestRouter(store, func(cfg *RouterConfig) {
		cfg.PersistMaxRetries = 1
	})
	r.kafka = pub

	enqueuePersistMessages(r, 3)
	r.Stop()

	if pub.published != 3 {
		t.Fatalf("published=%d, want 3", pub.published)
	}
	if _, saved, attempts := store.snapshot(); saved != 0 || attempts != 0 {
		t.Fatalf("direct MySQL saved=%d attempts=%d, want 0 when Kafka is enabled", saved, attempts)
	}
}

func TestSetKafkaProducerNilDisablesKafka(t *testing.T) {
	r := newPersistTestRouter(newBatchTestStore(), nil)
	defer r.Stop()
	r.SetKafkaProducer(nil)
	if r.kafka != nil {
		t.Fatal("nil producer must leave kafka disabled")
	}
}

func TestPersistStopCancelsDrainAtShutdownTimeout(t *testing.T) {
	store := &blockingBatchTestStore{
		testMsgStore: &testMsgStore{},
		started:      make(chan struct{}),
	}
	cfg := DefaultRouterConfig()
	cfg.PersistBatchSize = 1
	cfg.PersistBatchWorkers = 1
	cfg.PersistShutdownWait = 50 * time.Millisecond
	r := NewRouter(nil, nil, nil, store, cfg)

	enqueuePersistMessages(r, 1)
	select {
	case <-store.started:
	case <-time.After(time.Second):
		t.Fatal("batch write did not start")
	}

	start := time.Now()
	r.Stop()
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Fatalf("Stop took %v, expected shutdown timeout cancellation", elapsed)
	}
}
