// Package mq 提供 Kafka 生产者和消费者组件，用于异步消息持久化。
// 生产者将消息发布到 Kafka（从调用方的角度看是即发即忘的），
// 消费者将消息批量写入 repo.MessageStore（通常是 MySQL），
// 并保证至少一次投递语义。
package mq

import (
	"context"
	"encoding/binary"
	"fmt"
	"log"
	"time"

	"github.com/im/api/proto"
	"github.com/segmentio/kafka-go"
	pb "google.golang.org/protobuf/proto"
)

// Producer 将消息发布到 Kafka 主题以进行异步持久化。
// 它故意设计为即发即忘：发布失败只会记录日志，绝不会
// 阻塞或使热路径的消息投递失败。
type Producer struct {
	writer *kafka.Writer
}

// ProducerConfig 保存创建 Producer 所需的设置。
type ProducerConfig struct {
	Brokers []string
	Topic   string
}

// NewProducer 创建一个 Kafka 生产者。使用惰性连接（segmentio/kafka-go），
// 因此即使 broker 不可达，此调用也总能成功。
// 错误会在后续的 Publish 调用中暴露。
func NewProducer(cfg ProducerConfig) (*Producer, error) {
	writer := &kafka.Writer{
		Addr:     kafka.TCP(cfg.Brokers...),
		Topic:    cfg.Topic,
		Balancer: &kafka.Hash{}, // 按键哈希以实现确定性分区
		// Router 按条数/时间聚合后一次提交；Writer 仍保留自身的小延迟聚合，
		// 兼容单条 Publish 调用。
		BatchSize:    200,
		BatchTimeout: 10 * time.Millisecond,
		// 尽力而为：不要无限期阻塞。
		WriteTimeout: 2 * time.Second,
		ReadTimeout:  2 * time.Second,
		RequiredAcks: kafka.RequireOne,
		// 同步写入：每次 WriteMessages 都会阻塞直到确认。
		// 调用方使用 goroutine，因此热路径永远不会被阻塞。
		Async: false,
		// 允许自动创建 topic：kafka-go 默认不允许，若生产 topic
		// im.message.persist 未被预创建，发布会静默失败导致消息无法持久化。
		// 开启后首次发布即自动建 topic，自愈部署遗漏。
		AllowAutoTopicCreation: true,
	}

	log.Printf("[mq] producer ready (brokers=%v topic=%s)", cfg.Brokers, cfg.Topic)
	return &Producer{writer: writer}, nil
}

// Publish 将消息发送到 Kafka。这在热路径上是即发即忘的：
// 调用方应在 goroutine 中调用。错误只记录日志而不返回
// （我们绝不想让 Kafka 故障破坏消息投递）。
func (p *Producer) Publish(ctx context.Context, msg *proto.Message) {
	if msg == nil {
		return
	}
	if err := p.PublishBatch(ctx, []*proto.Message{msg}); err != nil {
		log.Printf("[mq] publish error for msgId=%d: %v", msg.MsgId, err)
	}
}

// PublishBatch 将一批消息通过一次 WriteMessages 调用写入 Kafka。
func (p *Producer) PublishBatch(ctx context.Context, messages []*proto.Message) error {
	if len(messages) == 0 {
		return nil
	}
	kms := make([]kafka.Message, 0, len(messages))
	for _, msg := range messages {
		if msg == nil {
			continue
		}
		data, err := pb.Marshal(msg)
		if err != nil {
			return fmt.Errorf("marshal msgId=%d: %w", msg.MsgId, err)
		}
		key := make([]byte, 8)
		binary.BigEndian.PutUint64(key, uint64(msg.MsgId))
		kms = append(kms, kafka.Message{Key: key, Value: data})
	}
	if len(kms) == 0 {
		return nil
	}

	writeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(writeCtx, kms...); err != nil {
		return fmt.Errorf("write %d messages: %w", len(kms), err)
	}
	return nil
}

// Close 刷新并关闭底层的 Kafka writer。
func (p *Producer) Close() error {
	log.Printf("[mq] producer closing...")
	return p.writer.Close()
}
