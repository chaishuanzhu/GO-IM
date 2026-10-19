package mq

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/im/api/proto"
	"github.com/im/internal/repo"
	"github.com/segmentio/kafka-go"
	pb "google.golang.org/protobuf/proto"
)

// bufferedMsg 将反序列化后的 proto.Message 与其 Kafka 消息配对，
// 这样我们可以在存储写入成功后才提交 offset。
type bufferedMsg struct {
	msg *proto.Message
	km  kafka.Message
}

// Consumer 从 Kafka 主题读取消息并批量写入 repo.MessageStore。
// 只有写入成功后才会提交 offset —— 这保证了至少一次投递语义：
// 写入与提交之间崩溃会导致重新投递，而 INSERT IGNORE 会处理重复。
type Consumer struct {
	reader *kafka.Reader
	store  repo.MessageStore // 接口 —— 便于在测试中模拟

	// 批量缓冲写入以减少往返次数。
	mu      sync.Mutex
	flushMu sync.Mutex // 串行化写库与 offset 提交，禁止后续批次越过失败批次
	buffer  []bufferedMsg
	flushCh chan struct{}
	done    chan struct{}
	wg      sync.WaitGroup // 跟踪 flushLoop goroutine
}

// ConsumerConfig 保存 Kafka 消费者的设置。
type ConsumerConfig struct {
	Brokers []string
	Topic   string
	GroupID string // 消费组，用于协同消费
}

// 批量常量。
const (
	defaultBatchSize    = 100
	defaultFlushTimeout = 1 * time.Second
	maxBufferSize       = 10000 // 安全上限，防止存储故障时内存无限增长
)

// NewConsumer 创建一个 Kafka 消费者，通过提供的 MessageStore
// （通常是 *repo.MySQLStore）批量写入消息。store 仅在测试时可为 nil ——
// 为 nil 时 flushAll 会跳过写入。
func NewConsumer(cfg ConsumerConfig, store repo.MessageStore) *Consumer {
	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:     cfg.Brokers,
		Topic:       cfg.Topic,
		GroupID:     cfg.GroupID,
		MinBytes:    1,    // 不等待 Kafka 积攒完整一批
		MaxBytes:    10e6, // 10 MB
		StartOffset: kafka.LastOffset,
	})

	return &Consumer{
		reader:  reader,
		store:   store,
		buffer:  make([]bufferedMsg, 0, defaultBatchSize),
		flushCh: make(chan struct{}, 1),
		done:    make(chan struct{}),
	}
}

// Run 启动消费循环。阻塞直到 ctx 被取消。
// 请在 goroutine 中调用。
func (c *Consumer) Run(ctx context.Context) {
	log.Printf("[mq] consumer starting (topic=%s group=%s)", c.reader.Config().Topic, c.reader.Config().GroupID)

	// 后台刷新定时器。
	c.wg.Add(1)
	go func() {
		defer c.wg.Done()
		c.flushLoop(ctx)
	}()

	for {
		select {
		case <-ctx.Done():
			log.Printf("[mq] consumer shutting down...")
			c.flushAll()
			close(c.done)
			return
		default:
		}

		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				continue // ctx 已取消，循环将在下一次 select 时退出
			}
			log.Printf("[mq] fetch error: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}

		// 反序列化 protobuf。
		var pm proto.Message
		if err := pb.Unmarshal(msg.Value, &pm); err != nil {
			log.Printf("[mq] unmarshal error: %v (offset=%d)", err, msg.Offset)
			// 提交并跳过格式错误的消息，避免卡住。
			// 使用独立的 context，确保关闭不会阻止提交
			// 在取消前刚到达的格式错误消息。
			commitCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			if err := c.reader.CommitMessages(commitCtx, msg); err != nil {
				log.Printf("[mq] commit error for malformed msg: %v", err)
			}
			cancel()
			continue
		}

		// 加入批处理 —— 跟踪 Kafka 消息，以便在存储刷新后提交 offset。
		// 缓冲达到安全上限时同步 flush，对 Kafka 读取施加背压；不得提交并
		// 丢弃最旧消息，否则数据库恢复后也无法重放。
		c.mu.Lock()
		if len(c.buffer) >= maxBufferSize {
			c.mu.Unlock()
			log.Printf("[mq] buffer at capacity (%d), applying backpressure", maxBufferSize)
			c.flushAll()
			c.mu.Lock()
		}
		c.buffer = append(c.buffer, bufferedMsg{msg: &pm, km: msg})
		shouldFlush := len(c.buffer) >= defaultBatchSize
		c.mu.Unlock()

		if shouldFlush {
			select {
			case c.flushCh <- struct{}{}:
			default:
				// 刷新已在等待中；不要阻塞。
			}
		}
		// 注意：offset 在 flushAll 中提交，而不是在这里。
		// 这可以防止进程在缓冲与存储写入之间崩溃时丢失数据。
	}
}

// Wait 阻塞直到消费者完全关闭（运行循环已退出且刷新循环已完成）。
func (c *Consumer) Wait() {
	<-c.done
	c.wg.Wait() // 确保 flushLoop 已完全退出
}

// Close 关闭底层的 Kafka reader。
func (c *Consumer) Close() error {
	return c.reader.Close()
}

// flushLoop 周期性地将缓冲的消息刷新到存储。
func (c *Consumer) flushLoop(ctx context.Context) {
	ticker := time.NewTicker(defaultFlushTimeout)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.flushAll()
		case <-c.flushCh:
			c.flushAll()
		}
	}
}

// flushAll 将所有缓冲的消息写入存储，然后提交 Kafka offset。
// offset 在写入成功之后才提交，因此崩溃永远不会导致
// 数据丢失（至少一次语义；INSERT IGNORE 处理重放）。
func (c *Consumer) flushAll() {
	c.flushMu.Lock()
	defer c.flushMu.Unlock()

	c.mu.Lock()
	if len(c.buffer) == 0 {
		c.mu.Unlock()
		return
	}
	batch := c.buffer
	c.buffer = make([]bufferedMsg, 0, defaultBatchSize)
	c.mu.Unlock()

	// 使用带超时的 context，避免关闭无限期挂起。
	writeCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	writeOK := true
	if c.store == nil {
		// 未配置存储（测试模式）—— 直接提交，避免卡在相同消息上。
	} else if batchStore, ok := c.store.(repo.BatchMessageStore); ok {
		messages := make([]*proto.Message, 0, len(batch))
		for _, bm := range batch {
			messages = append(messages, bm.msg)
		}
		if err := batchStore.SaveBatch(writeCtx, messages); err != nil {
			log.Printf("[mq] store batch save error (size=%d): %v", len(messages), err)
			writeOK = false
		}
	} else {
		for _, bm := range batch {
			if err := c.store.Save(writeCtx, bm.msg); err != nil {
				log.Printf("[mq] store save error (msgId=%d): %v", bm.msg.MsgId, err)
				writeOK = false
				break
			}
		}
	}

	if !writeOK {
		// 整批放回队首持续重试。不能提交同一分区中更高的 offset，
		// 否则前面的失败消息会永久丢失。
		c.mu.Lock()
		c.buffer = append(batch, c.buffer...)
		c.mu.Unlock()
		return
	}

	succeeded := make([]kafka.Message, 0, len(batch))
	for _, bm := range batch {
		succeeded = append(succeeded, bm.km)
	}
	commitCtx, commitCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer commitCancel()
	if err := c.reader.CommitMessages(commitCtx, succeeded...); err != nil {
		log.Printf("[mq] commit error: %v", err)
		// 重启后消息将被重新投递，INSERT IGNORE 会跳过重复。
	}
	log.Printf("[mq] flushed %d messages to store", len(batch))
}
