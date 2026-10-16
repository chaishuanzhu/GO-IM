// Logic 服务 —— 从 Kafka 消费消息并持久化到 MySQL，
// 同时为 Gateway 提供 gRPC 查询（历史记录、用户查找）。
//
// 用法：
//
//	go run ./cmd/logic/
//
// 需要 MySQL（docker-compose up -d），可选 Kafka。
package main

import (
	"context"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/im/api/proto"
	"github.com/im/configs"
	"github.com/im/internal/logic"
	"github.com/im/internal/mq"
	"github.com/im/internal/pkg/snowflake"
	"github.com/im/internal/repo"
	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc"
	"google.golang.org/grpc/reflection"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	log.SetOutput(os.Stdout)

	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "configs/config.json"
	}
	cfg, err := configs.Load(configPath)
	if err != nil {
		log.Fatalf("[logic] load config: %v", err)
	}
	log.Printf("[logic] configPath=%s brokers=%v", configPath, cfg.Logic.Kafka.Brokers)

	// --- MySQL（Logic 服务必需）---
	// 如果设置了 logic.mysql 配置则使用它，否则回退到 gateway.mysql。
	// 仅在 DSN 为空时回退，而不是因为 Enabled 为 false —
	// Enabled=true 但 DSN 为空的配置属于配置错误，而不是回退信号。
	mySQLCfg := cfg.Logic.MySQL
	if mySQLCfg.DSN == "" {
		mySQLCfg = cfg.Gateway.MySQL
	}
	if mySQLCfg.DSN == "" {
		log.Fatalf("[logic] MySQL must be enabled (set logic.mysql.dsn or gateway.mysql.dsn)")
	}

	mysqlStore, err := repo.NewMySQLStore(mySQLCfg.DSN)
	if err != nil {
		log.Fatalf("[logic] MySQL init: %v", err)
	}
	defer mysqlStore.Close()
	log.Printf("[logic] MySQL connected")

	// --- gRPC 服务器 ---
	grpcAddr := cfg.Logic.ListenAddr
	if grpcAddr == "" {
		grpcAddr = cfg.Gateway.LogicGateway.ListenAddr
	}
	if grpcAddr == "" {
		grpcAddr = ":50051" // 默认的 Logic gRPC 端口
	}
	lis, err := net.Listen("tcp", grpcAddr)
	if err != nil {
		log.Fatalf("[logic] gRPC listen %s: %v", grpcAddr, err)
	}

	// --- 群组 ID 的 snowflake 生成器 ---
	redisCfg := cfg.Logic.Redis
	if redisCfg.Addr == "" {
		redisCfg = cfg.Gateway.Redis
	}
	snow, releaseSnow := newSnowflake(redisCfg, cfg.Logic.WorkerID)
	defer releaseSnow()

	grpcServer := grpc.NewServer()
	logicSrv := logic.NewServer(mysqlStore, snow)
	proto.RegisterLogicServer(grpcServer, logicSrv)
	reflection.Register(grpcServer) // 启用 grpcurl 便于调试
	log.Printf("[logic] gRPC server listening on %s", grpcAddr)

	// 用于暴露 gRPC 致命错误而不绕过延迟清理的通道。
	gRPCDone := make(chan error, 1)
	go func() {
		if err := grpcServer.Serve(lis); err != nil {
			gRPCDone <- err
		}
	}()

	// --- Kafka 消费者（可选）---
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// 如果设置了 logic.kafka 配置则使用它，否则回退到 gateway.kafka。
	// 仅在 Brokers 为空时回退 —— Enabled=false 但 Brokers 有效时仍应工作。
	kafkaCfg := cfg.Logic.Kafka
	if len(kafkaCfg.Brokers) == 0 {
		kafkaCfg = cfg.Gateway.Kafka
	}

	var consumer *mq.Consumer
	if kafkaCfg.Enabled && len(kafkaCfg.Brokers) > 0 {
		topic := kafkaCfg.Topic
		if topic == "" {
			topic = "im.message.persist"
		}
		consumerCfg := mq.ConsumerConfig{
			Brokers: kafkaCfg.Brokers,
			Topic:   topic,
			GroupID: "im-logic",
		}
		consumer = mq.NewConsumer(consumerCfg, mysqlStore)

		go consumer.Run(ctx)
		log.Printf("[logic] Kafka consumer started (topic=%s group=%s)", topic, consumerCfg.GroupID)
	} else {
		log.Printf("[logic] Kafka disabled (kafka.enabled=false), consumer not started")
	}

	// --- 优雅关闭 ---
	// 阻塞等待信号或致命的 gRPC 错误，然后按顺序关闭：
	//   1. GracefulStop gRPC → 停止接受 RPC，排空进行中的请求
	//   2. 取消 context → 消费者 Run 循环退出（刷新缓冲区）
	//   3. 等待消费者 → 所有缓冲消息刷入 MySQL
	//   4. 关闭消费者 → 释放 Kafka 读取器
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case <-sigCh:
		log.Println("[logic] shutting down...")
	case err := <-gRPCDone:
		log.Printf("[logic] gRPC serve fatal error: %v — shutting down...", err)
	}
	// 注意：这里故意不使用 log.Fatalf，以便延迟清理
	// （mysqlStore.Close()）能通过 main 的延迟调用执行。

	// 1. 先停止 gRPC 服务器，确保消费者排空期间没有新的 RPC 到达。
	grpcServer.GracefulStop()

	// 2. 停止 Kafka 消费者循环（触发最终刷新）。
	cancel()

	// 3. 等待消费者完成刷新后关闭。
	if consumer != nil {
		consumer.Wait()
		consumer.Close()
	}

	log.Println("[logic] stopped")
}

// logicWorkerIDs 是 logic 从 Redis 租用 worker ID 的范围（网关用 16-511）。
var logicWorkerIDs = snowflake.LeaseConfig{Min: 512, Max: 1023}

// newSnowflake 在 Redis 可用时租用 worker ID，使滚动更新时并存的新旧实例 ID 不冲突；
// 否则使用配置的固定 worker ID（默认 2）。返回的 Generator 为 nil 表示 worker ID 无效；
// release 在退出时释放租约。
func newSnowflake(rc configs.RedisConfig, fixedID int64) (gen *snowflake.Generator, release func()) {
	release = func() {}
	if rc.Addr != "" {
		rdb := redis.NewClient(&redis.Options{Addr: rc.Addr, Password: rc.Password, DB: rc.DB})
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		leased, lease, err := snowflake.NewLeased(ctx, rdb, logicWorkerIDs)
		cancel()
		if err == nil {
			log.Printf("[logic] snowflake worker ID %d leased from Redis", leased.WorkerID())
			return leased, func() { lease.Close(); rdb.Close() }
		}
		rdb.Close()
		log.Printf("[logic] snowflake worker ID lease failed (%v), using configured worker ID %d", err, fixedID)
	}
	if fixedID <= 0 {
		fixedID = 2
	}
	gen, err := snowflake.New(fixedID)
	if err != nil {
		log.Printf("[logic] WARNING: snowflake init failed: %v", err)
		return nil, release
	}
	return gen, release
}
