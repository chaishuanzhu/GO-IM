# 11 — 单机持久化批量写入优化方案

> 状态：Phase A–C 已实现，待测试环境压测验证；WAL / DLQ 属于后续 Phase 2  
> 基线数据：2026-10-19 生产环境渐进压测（见 [09-load-test-report.md](09-load-test-report.md#11-生产环境渐进压测--2026-10-19)）  
> 适用范围：Kafka 关闭、Gateway 直接写 MySQL 的单机生产形态；批量存储接口也可供 Logic Kafka Consumer 复用

## 1. 目标

在不改变消息实时投递语义的前提下，提高单机 MySQL 持久化吞吐，并消除正常峰值下的静默丢历史问题。

第一阶段目标：

- 持续写入 **2,000 msg/s**，落库率 100%
- 30 秒峰值 **5,000 msg/s**，峰值结束后 30 秒内清空积压
- ACK P99 < 500ms
- 持久化队列正常运行时不丢弃
- 进程优雅关闭时排空已接收任务

第二阶段目标：

- 持续写入 **5,000 msg/s**，落库率 100%
- MySQL 故障或长时间过载时通过 WAL / Kafka 保证消息最终可恢复

## 2. 当前实现与问题

### 2.1 当前链路

```text
Router.persistAsync
  → Clone protobuf
  → persistTasks（固定容量 1,024）
  → 64 个 persistWorker
  → 每个 worker 调用 MessageStore.Save
  → 单条 INSERT IGNORE
  → 单条事务提交 / redo log flush
```

相关代码：

- `internal/gateway/router.go`
  - `persistQueueCap = 1024`
  - `persist_concurrency` 默认 64
  - 队列满后进入 `default` 分支，直接丢弃持久化任务
- `internal/repo/mysql.go`
  - `Save` 每次执行一条 `INSERT IGNORE`
  - MySQL 连接池 `MaxOpenConns=25`、`MaxIdleConns=5`
- `internal/mq/consumer.go`
  - 虽然缓存 100 条消息，但 flush 时仍逐条调用 `store.Save`

生产环境 Kafka 和 Logic 未启用，因此当前瓶颈是 Gateway → MySQL，而不是 Kafka Producer。

### 2.2 生产实测

| 实时发送速率 | 实时投递 | MySQL 落库 |
|-------------|---------|-------------|
| 95.6 msg/s | 100% | 100% |
| 283–470 msg/s | 100% | 合计约 98.4% |
| 946.7 msg/s | 100% | 29.7% |

累计发送 55,300 条，最终落库 34,969 条（63.2%）。等待 30 秒后数量不再增长，确认是任务在 Gateway 队列满时被丢弃，而不是数据库消费延迟。

### 2.3 根因

1. **单行 autocommit**：每条消息都产生一次数据库往返、事务提交和潜在 fsync。
2. **并发模型不匹配**：64 个 worker 竞争最多 25 条数据库连接，大量 goroutine 只是在等待连接。
3. **队列太短**：1,024 条只能吸收约 1 秒甚至更短的峰值。
4. **满队列静默丢弃**：不影响在线投递，但历史、搜索和离线恢复缺少消息。
5. **逐消息成功日志**：每条成功写入均记录日志，高吞吐时增加格式化、锁和磁盘 I/O。
6. **关闭时不排空**：`Router.Stop` 通知 worker 退出后，队列内尚未处理的任务可能丢失。

## 3. 方案概览

```text
消息实时投递
      │
      ▼
persistAsync
      │ Clone
      ▼
有界任务队列（建议 8,192）
      │
      ▼
Batcher
  ├─ 达到 200 条
  └─ 或等待 10ms
      │
      ▼
2–4 个 Batch Writer
      │
      ▼
MySQLStore.SaveBatch
      │
      ▼
单条多值 INSERT IGNORE + 单次事务提交
```

核心不是增加并发 worker，而是把多条消息合并为一次数据库操作。

## 4. 详细设计

### 4.1 批量存储接口

不直接修改 `MessageStore`，避免所有测试 mock 和其他实现被迫同时改造。增加可选接口：

```go
type BatchMessageStore interface {
    SaveBatch(ctx context.Context, messages []*proto.Message) error
}
```

Gateway 和 Logic Consumer 运行时检测：

```go
batchStore, ok := store.(repo.BatchMessageStore)
```

- 支持批量接口：调用 `SaveBatch`
- 不支持：退化为逐条 `Save`，保证兼容

### 4.2 MySQL `SaveBatch`

使用一条多值语句：

```sql
INSERT IGNORE INTO messages
  (msg_id, seq, cmd, from_uid, to_uid, chat_type, msg_type,
   content, timestamp, need_ack, recalled)
VALUES
  (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0),
  (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0),
  ...;
```

建议：

- 初始 `batch_size=200`
- 每批最多 500 条，避免 SQL 包和参数过大
- 整批使用一个 `ExecContext`
- 保留 `INSERT IGNORE`，使用 `msg_id` 主键提供幂等性
- 写失败时整批重试，不提交部分成功状态
- 当前重试耗尽后整批失败并告警；后续可按 MySQL 错误码识别数据错误，再二分批次隔离异常消息

消息表有两个二级索引，批量写仍需维护索引，但可以显著减少网络往返、事务提交和 redo fsync 次数。

### 4.3 Batcher

单个聚合 goroutine 从队列读取任务：

- 收到第一条后启动 10ms flush timer
- 累积到 200 条立即 flush
- timer 到期时 flush 当前非空批次
- 停止时 flush 剩余批次

批次交给 2 个 writer；压测确认数据库仍有余量后再调到 4 个。默认不建议超过 4 个，避免多个大事务竞争 MySQL 锁、buffer pool 和磁盘。

### 4.4 队列与背压

第一阶段建议：

- `queue_size=8192`
- 入队最多等待 `10ms`
- 超时后记录明确的 drop 指标和限频错误日志

8,192 条约可缓冲：

- 2,000 msg/s：约 4 秒
- 5,000 msg/s：约 1.6 秒

不建议一开始设为 65,536。消息包含 protobuf clone 和内容字符串，在 gateway 256Mi limit 下可能因积压本身造成 OOM。

仅增大队列不能提高持续吞吐，只能吸收短峰值。若要求数据库长时间故障期间也不丢消息，需要第二阶段增加 WAL / Kafka。

### 4.5 失败与重试

建议重试策略：

1. 批量写失败后重试 3 次
2. 退避时间 20ms、100ms、500ms，并加入随机抖动
3. 可重试错误：连接中断、deadlock、lock wait timeout、临时网络错误
4. 数据错误：当前整批告警；后续按错误码二分批次定位异常消息
5. 重试耗尽：
   - 第一阶段：记录失败计数与结构化错误，保留为明确告警
   - 第二阶段：写入本地 WAL / DLQ，后台补偿

不能继续像当前实现一样把“在线消息已投递”等同于“消息已持久化”。指标和日志必须分别表达这两个状态。

### 4.6 优雅关闭

关闭顺序调整为：

```text
停止接收新连接/新消息
  → 关闭持久化任务输入
  → Batcher 排空输入队列
  → flush 最后一个批次
  → 等待所有 writer 完成
  → 关闭 MySQL
```

排空受 `stability.shutdown_timeout` 限制。超时仍有任务时输出剩余数量和告警。

### 4.7 日志

删除每条消息的成功日志：

```text
[router] mysql save OK for msgId=...
```

改为每批日志或指标：

```text
batch_size=200 duration_ms=12 result=success queue_depth=135
```

成功日志使用 debug 或按 1% 采样；失败和丢弃日志保留，但必须限频，避免故障时日志反过来拖垮服务。

## 5. 配置建议

增加以下 Gateway 配置，旧的 `persist_concurrency` 保留一个版本用于兼容：

```json
{
  "gateway": {
    "persist_queue_size": 8192,
    "persist_batch_size": 200,
    "persist_flush_interval": "10ms",
    "persist_batch_workers": 2,
    "persist_enqueue_timeout": "10ms",
    "persist_max_retries": 3
  }
}
```

MySQL 连接池初始建议：

```text
MaxOpenConns    32
MaxIdleConns    16
ConnMaxLifetime 30m
ConnMaxIdleTime 5m
```

连接池也应配置化。批量 writer 只有 2–4 个，但 HTTP 查询、用户、群组和文件接口也会共享连接池，因此保留适当余量。

## 6. 可观测性

增加 Prometheus 指标：

```text
im_persist_queue_depth
im_persist_queue_capacity
im_persist_enqueue_wait_seconds
im_persist_batch_size
im_persist_batch_duration_seconds
im_persist_batch_success_total
im_persist_batch_fail_total
im_persist_retry_total
im_persist_queue_drop_total
```

生产环境应在内网开启 `/metrics`，不通过公网入口暴露。

告警建议：

- 队列使用率连续 1 分钟 > 70%
- 最老任务等待时间 > 5 秒
- 任意 `queue_drop`
- 批量写失败率 > 0.1%
- MySQL 连接池等待持续增长

## 7. 提升预估

### 7.1 估算方法

当前单行写入时，每条消息都支付一次数据库往返和事务提交成本。假设批量 200 条、10ms flush：

- 2,000 msg/s 时，平均每个时间窗约 20 条
- 5,000 msg/s 时，平均每个时间窗约 50 条
- 峰值更高或积压时，批次可接近 200 条

因此实际事务数量预计减少约 20–200 倍。最终吞吐不会同比增长，因为仍受索引维护、SQL 编码、MySQL CPU、磁盘和 gateway 资源约束。

### 7.2 预估区间

| 阶段 | 持续可靠落库 | 相对当前约 100 msg/s 零丢失基线 | 相对当前 300–500 msg/s 临界区 |
|------|-------------|---------------------------------|-------------------------------|
| 保守 | 1,500–2,000 msg/s | 15–20 倍 | 3–6 倍 |
| 目标 | 3,000–5,000 msg/s | 30–50 倍 | 6–15 倍 |
| 理想（升级主机/MySQL 后） | 5,000–10,000 msg/s | 50–100 倍 | 10–30 倍 |

对当前资源紧张的单机生产环境，方案承诺值应采用**保守区间 1,500–2,000 msg/s**。3,000–5,000 msg/s 是实施后需要压测验证的目标，不应在实测前作为容量承诺。

预计改善：

- 946 msg/s 场景：落库率从 29.7% 提升到 100%
- ACK 延迟：持久化仍在异步路径，正常情况下不会显著增加；入队等待只在队列接近满时出现
- MySQL 事务提交次数：降低约一个数量级以上
- gateway goroutine：从 64 个单写 worker 改为 1 个 batcher + 2–4 个 writer

### 7.3 资源限制

当前单机同时运行多项服务，可用内存较少，MySQL 容器内存也偏小。代码批量化后若要稳定达到 5,000 msg/s，建议至少：

- 主机 4 CPU / 8GiB
- MySQL 1–2GiB 内存
- SSD
- gateway 512Mi–1GiB

若不升级主机，应先以 2,000 msg/s 为验收目标，避免因提高数据库吞吐挤占其他线上服务资源。

## 8. 实施步骤

当前实现进度：

- ✅ `repo.BatchMessageStore` 与 `MySQLStore.SaveBatch`
- ✅ Gateway size/time 双触发 Batcher、重试、入队等待和优雅排空
- ✅ Kafka Producer `PublishBatch` 与 Logic Consumer 批量写复用
- ✅ 队列、批次、重试与耗时指标
- ✅ MySQL 连接池和批量参数配置化
- ⏳ 测试环境 2,000–5,000 msg/s 压测与生产验证
- ⏳ 本地 WAL / DLQ（数据库长时间故障时零丢失）

### Phase A — 批量接口

1. 增加 `repo.BatchMessageStore`
2. 实现 `MySQLStore.SaveBatch`
3. 添加 1/100/200/500 条批量写集成测试
4. 验证重复 `msg_id` 的幂等性

### Phase B — Gateway Batcher

1. 持久化参数配置化
2. 替换 64 个逐条 worker
3. 实现 size/time 双触发 flush
4. 实现带抖动的重试和优雅排空；按错误码二分隔离留待后续阶段
5. 删除逐消息成功日志

### Phase C — Logic Consumer 复用

1. `flushAll` 优先调用 `SaveBatch`
2. 批量成功后再提交 Kafka offset
3. 禁止 buffer 满时提交并丢弃最旧消息，改用暂停读取或可恢复队列

### Phase D — 压测与上线

依次测试：

```text
100 → 300 → 500 → 1,000 → 2,000 → 3,000 → 5,000 msg/s
```

每档至少 5 分钟，记录：

- 客户端发送 / ACK / 投递数
- MySQL 实际新增行数
- ACK P50/P95/P99
- queue depth 和 batch size
- gateway / MySQL CPU、内存和磁盘 IOPS
- 测试结束后队列归零时间

停止条件：

- 任意持久化丢失
- ACK P99 > 500ms
- 队列持续增长且无法回落
- gateway 或 MySQL 内存 > 80%
- 容器重启、OOM 或数据库不可用

先部署测试环境，通过 2,000 msg/s 零丢失和 30 分钟浸泡测试后，再进入生产维护窗口验证。生产首发参数使用 `batch_size=200`、`flush_interval=10ms`、`workers=2`、`queue_size=8192`。

## 9. 不建议的做法

- **只增加 `persist_concurrency`**：连接池只有 25，更多 worker 主要增加等待和竞争。
- **只增加队列容量**：只能延迟丢失，持续流量最终仍会填满。
- **把 MySQL 连接数直接调到数百**：小内存单机会因连接内存、锁和上下文切换变慢。
- **通过降低 InnoDB 持久性换吞吐**：`innodb_flush_log_at_trx_commit=2` 会在主机故障时丢失最近数据，不作为首选。
- **批次无限增大**：会增加单次 SQL 大小、事务延迟、锁持有时间和失败重试成本。

## 10. 验收标准

方案完成的最低验收标准：

- 2,000 msg/s 持续 30 分钟
- 实时投递率 100%
- MySQL 落库率 100%
- ACK P99 < 500ms
- `im_persist_queue_drop_total = 0`
- 队列最大使用率 < 70%，停止发压后 30 秒内归零
- gateway / MySQL 无重启、无 OOM
- 滚动重启前能排空队列；强制中断后的重复消息由 `INSERT IGNORE` 正确去重

达到以上条件后，再以 3,000–5,000 msg/s 作为下一阶段探顶目标。
