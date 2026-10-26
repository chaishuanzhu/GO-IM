# 完整栈压测报告 (W3)

> 日期:2026-08-05
> 场景:S1 连接抖动、S2 单聊吞吐、S3 群聊扇出、S4 历史/搜索、S5 心跳浸泡
> 工具:`bench/kit`(客户端辅助库)+ `bench/loadtest`(压测主程序)

---

## 1. 测试环境

| 项 | 值 |
|----|----|
| 操作系统 | Windows 11 Home (22621),x86_64 |
| CPU/内存 | Docker Desktop 默认配额(2 vCPU / 8 GB 虚拟内存,本机共享) |
| Go | 1.26.5 (GOROOT=E:/develop/Golang1.26.5) |
| Gateway | 原生进程,configs/config.bench.json(限流关闭、pprof 开启) |
| Logic | 原生进程,同配置,Kafka consumer 批量落库 MySQL |
| 中间件 | Docker Compose 全栈:MySQL 8.4 (host:3307)、Redis 7、Kafka 3.9、MinIO |
| 压测客户端 | 与 Gateway 同机(本地回环 127.0.0.1),`go run ./bench/loadtest` |

**关键配置**(configs/config.bench.json):
- 传输:`both`(WebSocket :8080 + gnet TCP :18083)
- 限流:`enabled: false`(避免 10 msg/s/UID 令牌桶扭曲吞吐)
- 持久化:`mysql.enabled` + `kafka.enabled`(双写:Gateway 直写 MySQL 兜底 + Kafka→Logic→MySQL)
- dedup:`Redis durability enabled`,5 分钟 TTL
- pprof:`localhost:6060`

> ⚠️ **环境约束**:压测客户端与服务器同机(本地回环),CPU/网络由两者共享。这使绝对吞吐偏低(回环不代表生产网络),但对**相对校准**和**瓶颈定位**足够。Kafka 外部 listener 为宿主机可达的 `localhost:9094`(详见 §6 修复 5)。

---

## 2. 场景矩阵与结果汇总

| 场景 | 配置 | 核心指标 | 结果 |
|------|------|----------|------|
| **S1** 连接抖动 | 1000 目标连接,50 conn/s,60s | 连接成功率 | **3000/3000 成功 (100%)**,心跳全成功 |
| **S2** 单聊吞吐 | 1000 UID,20 msg/s/人,60s | 吞吐 + 延迟 | **19,900 msg/s**,100% 送达/ACK,投递 P99=16ms |
| **S3** 群聊扇出 | 1×500 人群,100 条消息 | 扇出延迟 | 87 条→43,413 次投递,**扇出 P99=25ms** |
| **S4** 历史/搜索 | 50 HTTP /search + 10 WS CmdHistory,60s | 读路径延迟 | WS 翻页 P99=73ms;**HTTP 搜索 P99=32s(瓶颈)** |
| **S5** 心跳浸泡 | 2000 连接,10 分钟,15s 心跳 | 长连接稳定性 | **2000/2000 保持,78,000 心跳零失败**,内存 44MB 稳定 |

---

## 3. 分场景明细

### S1 — 连接抖动(churn)

**目标**:验证高频连接/断开循环下服务端稳定性与资源回收。

```
时长: 60.0s
连接: 3000 成功 3000 (100.0%)
心跳: 3000 成功 / 0 失败
```

- 以 50 conn/s 速率循环"连接→登录→心跳→断开",60s 共 3000 次完整生命周期。
- **全部成功**,无句柄/goroutine 泄漏(结束后服务端 goroutines 回落基线)。

### S2 — 单聊吞吐(chat)

**目标**:单连接配对互发,测服务端消息吞吐上限与端到端投递/ACK 延迟。

```
时长: 60.0s
发送: 1,194,000 (19,900 msg/s)
投递: 1,194,000 (19,900 msg/s)  — 100% 送达
ACK:  1,194,000 (19,900 msg/s)  — 100% 确认,0 未确认
投递延迟 (ms): P50=2  P95=11  P99=16
ACK延迟  (ms): P50=8.7 P95=22  P99=25.8
服务端: 1000 连接,内存 321MB,goroutines 3,120(稳定)
```

- **20k msg/s 全量达成且零丢失**:100% 送达、100% ACK。
- 投递延迟 P99=16ms(服务端→客户端),ACK 往返 P99=25.8ms。
- goroutines 稳定 3,120,内存 321MB —— 双 worker 池修复后无泄漏(见 §6 修复 2/3)。

**结论**:热路径(在线投递 + ACK)在 2 万 msg/s 下健康。该吞吐受限于本机回环 + 单核压测客户端,不代表服务端理论上限。

### S3 — 群聊扇出(group)

**目标**:创建 500 人大群,测单条群消息扇出到全体成员的最差完成延迟。

```
时长: 30.0s
发送: 87 (2.9 msg/s)         — 按 duration/msgs 间隔发送的群消息
投递: 43,413 (1,447 msg/s)   — 87 × 500 成员,扇出投递
扇出逐条最差 (ms): P50=15  P95=21  P99=25
服务端: 500 连接,内存 190MB,goroutines 1,618
```

- 500 人群,87 条消息扇出 43,413 次投递,**逐条最差完成延迟 P99=25ms**。
- 群成员全部在线时扇出高效,单个成员失败不影响其他成员。

### S4 — 历史/搜索(search)

**目标**:测读路径 —— HTTP 全文搜索 + WebSocket 历史翻页。

```
WS CmdHistory: 21,170 次查询 / 635,100 条消息 (352.8 qps)
历史翻页延迟 (ms): P50=26  P95=53  P99=73
HTTP /search: 211 成功 / 37 失败 (3.5 rps)
请求延迟   (ms): P50=8,014  P95=24,473  P99=32,077
单发搜索延迟: 0.56s(返回正确数据,794 条匹配)
```

- **WS 历史翻页(P99=73ms)健康** —— 走 MySQL 普通索引。
- **HTTP 全文搜索是明显瓶颈**:单发 0.56s,50 并发下 P50=8s、P99=32s。
  - 根因:`MATCH(content) AGAINST('...' IN BOOLEAN MODE)`(ngram FULLTEXT)+ 访问控制过滤 `(from_uid=? OR to_uid=?)`,在 3.7 万行 + 50 并发下无法有效命中索引。
  - 37 次失败为并发下超时/限流。

**结论**:历史翻页可用于生产;全文搜索需要专用方案(LIKE 前缀索引、Elasticsearch,或限制并发),当前不适合高并发在线搜索。

### S5 — 心跳浸泡(heartbeat)

**目标**:大量空闲连接长时间保持,验证长连接稳定性、心跳与资源占用。

```
时长: 600.0s (10分钟)
连接: 2000 成功 2000 (100.0%)
心跳: 78,000 成功 / 0 失败
心跳延迟 (ms): P50=0.08  P95=0.33  P99=0.61
服务端: 连接 2000/2000 全程保持,内存 44MB,goroutines 6,112
```

- **2000 空闲连接保持 10 分钟,78,000 次心跳零失败**。
- 服务端内存稳定 44MB,goroutines 6,112(≈2000×3),**无泄漏**。

---

## 4. 持久化链路验证

> 本节记录的是批量持久化改造前的基线结果。当前代码已完成批量 Kafka/MySQL 写入、背压、重试和优雅排空，改造后数据待测试环境复测，见[批量写入优化方案](11-persistence-batch-write-optimization.md)。

| 阶段 | 观测 |
|------|------|
| Gateway 在线投递 | 3k msg/s 下 100% 送达/ACK,投递 P99=8ms(健康) |
| Gateway → Kafka | 19,518 条发布,全部成功(Kafka topic offset 确认) |
| Kafka → Logic → MySQL | Logic consumer 全消费并落库,MySQL 消息数 37,588 |
| Gateway 直写 MySQL(兜底) | 成功,无失败 |
| **持久化率** | **89,700 消息中 19,518 落库(21.7%),70,182 被队列丢弃** |

**瓶颈**:`persistAsync` 双写(Kafka + MySQL)由 64 个固定 worker 处理,队列缓冲 1024。3k msg/s 时 worker 吞吐约 650 msg/s(19.5k/30s),队列溢出丢弃 78%。

**结论**:
- Kafka → Logic → MySQL 链路本身完整(19,518 条全消费全落库)。
- 单机默认 `persist_concurrency: 64` 双写配置下,持久化吞吐远低于在线投递。**这是生产容量问题,不是丢数据 bug** —— 在线消息已送达,持久化是异步尽力而为。
- 简历声明"持久化 >99.9%"**不成立**(实测 22%),需校准。提升方案:Gateway 只写 Kafka(放弃直写 MySQL 兜底)、提高 worker 并发、或由 Logic 批量消费。

---

## 5. 简历声明校准

| 简历声明 | 实测 | 处置 |
|----------|------|------|
| 50k msg/s 吞吐 | **19,900 msg/s**(单机回环,1000 连接满负荷) | 缩水为"单机实测 ~20k msg/s,受本机资源限制" |
| 端到端 P99 < 50ms | **投递 P99=16ms**(S2 满负荷) | ✅ 成立 |
| 10 万连接 | **2000 连接稳定**(10 分钟浸泡);更高未测 | 标"2000 连接验证,更高待多机/生产环境" |
| 500 人群 P99 < 200ms | **扇出 P99=25ms** | ✅ 成立,远优于声明 |
| 持久化 >99.9% | **21.7%**(3k msg/s 下,默认配置) | ❌ 不成立,改为"异步尽力而为,单机容量有限" |
| Prometheus + Grafana 监控 | 已实现:/metrics 端点(20+ im_ 指标)+ Grafana Cloud 采集(可选) | ✅ 成立,见 README 可观测性章节 |

---

## 6. 压测中发现并修复的问题

压测暴露了 5 个真实问题(全部已修复或记录):

### 修复 1 — WebSocket 并发写 panic(服务端,严重)
`wsPingLoop` 直接 `conn.WriteMessage(PingMessage)`,与 `Client.WriteLoop` 并发写同一连接,gorilla/websocket 禁止并发写 → `panic: concurrent write to websocket connection`,服务端崩溃。
**修复**:`Transport` 加 `Ping()`,`Client` 加 `SendPing()` 经 `WriteLoop` 单写者串行化。所有写(数据帧 + ping 帧)统一由 WriteLoop 执行。
**文件**:`internal/gateway/server_ws.go`、`client.go`、`transport.go`、`transport_ws.go`、`transport_gnet.go`

### 修复 2 — persistAsync goroutine 爆炸(服务端,严重)
每条消息 spawn 2 个 goroutine(Kafka + MySQL)阻塞在 `persistSem`(容量 64),高吞吐下 goroutine 无限堆积:20k msg/s 时服务端 **138 万 goroutines、2 GB 内存**。
**修复**:固定 worker 池(64 个常驻 worker + 1024 缓冲队列),`persistAsync` 非阻塞提交,队列满丢弃。
**文件**:`internal/gateway/router.go`

### 修复 3 — dedup Redis 同步查询拖垮热路径(服务端,严重)
`IsDuplicate` 每个新消息内存 miss 后同步查 Redis GET(500ms 超时),20k msg/s 下每秒 2 万次同步查询 → **ACK 延迟从 5ms 恶化到 11s**。
**修复**:仅冷启动(`marks==0`,重启后内存为空)才查 Redis;进程处理过消息后,内存已与 Redis 通过 Mark 同步,不再查询。另将 Mark 的 Redis 写改为固定 worker 池(修复前同样存在 goroutine 堆积)。
**文件**:`internal/gateway/dedup.go`

### 修复 4 — 压测工具拨号/reader 缺陷(压测客户端,bench 专用)
- chat/group/heartbeat 场景初始一次性并发拨号上千连接 → 服务端 accept 队列溢出(连接被拒)。
- heartbeat 场景无常驻 reader → 错过服务端 ping,连接被按 pongWait 超时踢掉。
- 客户端 seq 从 1 开始 → 与历史 dedup 记录冲突(消息被判重,只回 ACK 不投递)。
- search 场景用户无会话数据 → 搜不到结果、延迟失真。
**修复**:信号量限并发拨号 / 串行拨号 + 立即启动 reader / 随机 seq 起点 / 指向会话种子用户;reader 超时 < 心跳间隔。
**文件**:`bench/loadtest/scenarios.go`、`main.go`

### 修复 5 — Kafka advertised listener 用容器名,宿主机客户端连不上(部署配置,重要)
`KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092` 只广播容器名,宿主机原生进程连上 localhost 后按元数据重连 `kafka:9092` → `lookup kafka: no such host`,全部持久化失败。
**修复**:Kafka 双 listener —— 容器内 `kafka:9093`(INTERNAL)、宿主机 `localhost:9094`(EXTERNAL)。`config.docker.json` 用内部、`config.bench.json` 用外部。
**文件**:`docker-compose.yml`、`configs/config.docker.json`、`configs/config.bench.json`

> 上述服务端修复(1/2/3)已通过完整 gateway 测试套件(187 测试)验证。

---

## 7. 结论

1. **热路径(在线投递 + ACK)是系统强项**:单机回环 2 万 msg/s、投递 P99=16ms、2000 长连接 10 分钟零失败。
2. **群聊扇出高效**:500 人群 P99=25ms。
3. **两大真实瓶颈**:
   - **异步持久化**:Gateway 直写 MySQL + Kafka 双写,64 worker 只撑 ~650 msg/s,持久化率 22%。需要 Gateway 只写 Kafka、提高并发、或接受"持久化尽力而为"。
   - **全文搜索**:ngram FULLTEXT + 访问过滤在并发下 P99=32s,不适合在线搜索。
4. **压测校准了简历声明**:50k→实测 20k、持久化>99.9%→实测 22%、100k 连接→实测 2000(稳定);P99<50ms 与 500 人群 P99<200ms 成立。
5. **压测价值**:发现并修复了 2 个崩溃级并发 bug(WS 并发写、goroutine 爆炸)和 1 个热路径性能 bug(dedup Redis 同步查询),这些在单元/集成测试下不会触发。

---

## 8. 复现方法

```bash
# 1. 起中间件(需 Docker)
docker compose up -d redis mysql kafka minio

# 2. 起 gateway + logic(原生,用压测配置)
export GOROOT="E:/develop/Golang1.26.5"
export PATH="$GOROOT/bin:$PATH"
CONFIG_PATH="$(pwd)/configs/config.bench.json" go run ./cmd/logic/
CONFIG_PATH="$(pwd)/configs/config.bench.json" go run ./cmd/gateway/

# 3. 跑场景
go run ./bench/loadtest -scenario churn    -connections 1000 -conn-rate 50 -duration 60s
go run ./bench/loadtest -scenario chat     -users 1000 -rate 20 -duration 60s
go run ./bench/loadtest -scenario group    -group-size 500 -groups 1 -msgs 100 -duration 30s
go run ./bench/loadtest -scenario search   -workers 50 -history-workers 10 -duration 60s -query bench-chat
go run ./bench/loadtest -scenario heartbeat -connections 2000 -duration 10m -interval 15s
```

> 说明:场景命令中 `-interval 15s` 用于 S5。reader 的读截止时间设在压测结束后 3s(gorilla/websocket 读超时不可恢复,空闲超时会废掉连接,见 §10.6)。S2 的 dedup 需先确认 MySQL 已有数据(可先跑一次小规模 chat 种子)。

## 9. 附录

### 9.1 测试期间服务端修复清单(commit 建议)

| 文件 | 修复 |
|------|------|
| internal/gateway/server_ws.go, client.go, transport*.go | WS ping 并发写 → 单写者 |
| internal/gateway/router.go | persistAsync goroutine 爆炸 → worker 池 |
| internal/gateway/dedup.go | Redis 同步查询 + goroutine 堆积 → 冷启动 + worker 池 |
| docker-compose.yml, configs/*.json | Kafka 双 listener |

### 9.2 原始数据

各场景完整输出保留于压测时终端,关键数字已摘录于 §3。服务端 `go tool pprof` 截图(goroutine 爆炸)位于 §6 修复 2 的观测记录。

---

## 10. 测试环境(go-im-test,k8s)压测 — 2026-10-16

前 9 节是本机单进程压测。本节在 k8s 测试环境上实测,回答两个问题:测试环境能撑多少在线、多高的消息并发。

### 10.1 环境与方法

| 项 | 值 |
|----|----|
| 被测版本 | gateway / logic 镜像 `sha-4115a92`(v1.4.0 + 滚动更新) |
| gateway | 1 副本,limits 1 CPU / 512Mi,`max_connections: 1000`,限流 10 msg/s·burst 20,`dedup_ttl: 5m`,`persist_concurrency: 64` |
| logic | 1 副本,limits 1 CPU / 256Mi |
| 依赖 | 集群外 MySQL / Redis / Kafka(同一台局域网主机),Kafka 持久化开启 |
| 入口 | Traefik Ingress |
| 压测机 | A/B 轮:ci-01(6 核 / 1.8 GB,局域网直连 Traefik,绕开代理和公网);C 轮:集群内临时 Pod(k8s-node2,直连 `gateway:8080` Service,gateway 在 k8s-node1) |
| 令牌 | 压测工具用测试环境 JWT 密钥本地签发(测试环境关闭了 dev_mode),UID 前缀 `bench-` |
| 观测 | 每 5–10s 采样 `kubectl top pod` + `/health`;每步前后抓 `/metrics`;每步结束等 15–30s 后统计 MySQL 中 `bench-%` 消息行数 |
| 停止条件 | gateway 内存 > 420Mi(limit 的 ~80%)或 Pod 重启,watchdog 自动停压 |

延迟只看 **ACK 往返**(客户端发出 → 收到服务端 ACK)。工具里的"投递延迟"是客户端时钟减服务端时间戳,跨主机受时钟偏差影响,本节不采用。

B、C 两轮为测连接上限,临时把 `max_connections` 改成 20000 并滚动重启 gateway,结束后由 trap 恢复为 1000 并再次滚动重启(已核对)。全部 `bench-` 数据(MySQL 436,086 条消息、3 个群 / 1,400 条成员、Redis 182,867 个 dedup key)已清理。

### 10.2 结果汇总

| 维度 | 实测 | 瓶颈 |
|------|------|------|
| 在线连接(当前配置) | **1,000**(第 1,001 个起被拒) | 配置 `max_connections: 1000` |
| 在线连接(gateway 本身) | 15,000 连接 30s 内建完并保持;内存涨到 425Mi 时停压 → 安全值约 **12k** | 内存 512Mi,约 25 KB/连接 |
| 在线连接(经 Traefik) | 约 **3.8k** 后连接被成批断开,之后新连接 `connection refused` | Traefik 入口(无权限查看,待运维确认) |
| 单聊投递 | **~7,000 msg/s** 零丢失;ACK P99 88ms(900 人)/ 257ms(4,000 人) | gateway CPU 打满 1 核 |
| 群聊扇出 | 2 × 450 人群 **6,810 投递/s**,每条 449/449 全到 | 未到瓶颈(群主受 10 msg/s 限流) |
| 消息落库 | **~1.2–1.7k msg/s**;超过即**静默丢历史**,突发流量下 885 msg/s 已丢 12% | gateway → Kafka 同步逐条发送 |

结论:当前测试环境 **≤1,000 在线(配置上限)、实时消息 ~7k msg/s,但聊天记录只能可靠保存 ~1k msg/s**。真正需要优先修的是落库链路,其次是 Traefik 入口。

### 10.3 分轮明细

**A 轮 — 当前配置,经 Traefik(ci-01)**

| 步骤 | 发送速率 | 送达 / ACK | ACK P50 / P95 / P99 (ms) | 落库 | gateway CPU 峰值 | gateway 内存 |
|------|---------|-----------|--------------------------|------|-----------------|-------------|
| 心跳 1,000 连接 90s | — | 心跳 2,005 / 0 失败 | 0.51 / 0.70 / 0.96 | — | 0.01 核 | 38Mi |
| 心跳 1,100 连接 | — | 1,000 成功,**100 被拒** | 0.55 / 0.70 / 0.85 | — | — | — |
| 单聊 900 人 × 1/s | 885 msg/s | 100% / 100% | 16.3 / 29.7 / 38.0 | 46,701 / 53,100(**88%**) | 0.27 | 62Mi |
| 单聊 900 人 × 3/s | 2,685 msg/s | 100% / 100% | 18.6 / 37.4 / 49.9 | 79,153 / 161,100(49%) | 0.79 | 96Mi |
| 单聊 900 人 × 5/s | 4,470 msg/s | 100% / 100% | 24.2 / 68.9 / 80.5 | 74,385 / 268,200(28%) | 0.89 | 179Mi |
| 单聊 900 人 × 8/s | 7,170 msg/s | 100% / 100% | 27.4 / 66.9 / 87.9 | 72,247 / 430,200(17%) | **1.00**(打满) | 250Mi |
| 群聊 1 × 500 人,群主 7.8 条/s | 3,867 投递/s | 每条 499/499 | — | 466 / 465 | 0.13 | 212Mi |
| 群聊 2 × 450 人,群主各 7.6 条/s | 6,810 投递/s | 每条 449/449 | — | 912 / 910 | 0.20 | 223Mi |

全程 0 投递失败、0 限流拒绝、0 Pod 重启。群聊落库行数多出的 1–2 条是建群通知。

**B 轮 — 连接上限临时放开,经 Traefik(ci-01)**

心跳场景顺序建 15,000 个连接。客户端侧拨号成功 10,323 个,但 gateway 最多只看到 3,782 个,随后掉到 1,445 个并保持;心跳 72,468 次失败。紧接着的 4,000 人单聊有 1,539 个连接直接 `connection refused`(Traefik 端口拒绝),另有少量 `unexpected EOF` / `connection reset`。gateway 自身此时内存仅 ~127Mi,无重启 —— 断连发生在 Traefik 这一层。CI 账号无权查看 Traefik 所在命名空间,无法进一步确认是重启、资源限制还是连接数限制。

**C 轮 — 连接上限临时放开,集群内直连 gateway Service**

| 步骤 | 结果 | ACK P50 / P95 / P99 (ms) | 落库 | gateway CPU / 内存 |
|------|------|--------------------------|------|-------------------|
| 心跳 15,000 连接 | 30s 内全部建立并保持;内存 358 → 412Mi 持续上升,425Mi 时 watchdog 停压(~2 分钟) | — | — | 0.31 核 / 425Mi |
| 单聊 4,000 人 × 1/s | 3,733 msg/s,100% 送达 / ACK | 57.7 / 183 / 210 | 51,445 / 224,000(23%) | 0.58 / 300Mi |
| 单聊 4,000 人 × 1.75/s | 6,867 msg/s,100% 送达 / ACK | 81.3 / 179 / 257 | 104,977 / 412,000(25%) | 0.89 / 417Mi |

### 10.4 发现的问题

**问题 1(严重，已完成代码改造，待复测)— 落库上限 ~1.2–1.7k msg/s,超出部分静默丢失。** 每一步的 MySQL 新增行数都恰好等于 gateway 的 `im_persist_success_total`(Kafka 发送成功数),丢的部分全部计入 `im_persist_queue_drop_total`,即丢在 gateway 的持久化队列(容量 1024)。根因是 `internal/mq/producer.go` 同步逐条发送:`BatchSize: 1`、`BatchTimeout: 10ms`、`Async: false`,64 个 worker 每次发送约 50ms → 上限 ≈ 64 / 0.05 ≈ 1,280 msg/s,与实测吻合。当前实现已改为默认 200 条/10ms 批量聚合、8192 队列、2 个 batch writer 和失败重试；目标值见[批量写入优化方案](11-persistence-batch-write-optimization.md)。

**问题 2(重要)— 经 Traefik 的连接数约 3.8k 后不稳定。** 见 B 轮。gateway 本身在集群内能撑 15k 连接,因此这是入口层的限制,需要运维查看 Traefik 的资源限制、重启记录和连接数相关配置。

**问题 3(中)— gateway 单核 CPU 是实时消息上限。** ~7k msg/s 时 CPU 打满 1 核 limit;连接数越多,同样速率下 ACK 延迟越高(900 人 P99 88ms,4,000 人 P99 257ms)。

**问题 4(中,推算)— 去重缓存占内存。** 每条消息在内存去重表里保留 `dedup_ttl`(5 分钟)。C 轮结束、连接已全部断开后,gateway 堆仍有 237MB。按 7k msg/s 持续 5 分钟推算约 210 万条记录,加上连接本身占用,在 512Mi limit 下有 OOM 风险。高峰时 gateway 还打出了少量 `dedup redis queue full`(写 Redis 去重记录是尽力而为,只影响跨网关重试去重)。

**问题 5(已修复，待复测)— logic 消费端报 `buffer at capacity (10000), dropping oldest message`(约 1.9 万次)和 `commit error for dropped msg`。** 本次实测 MySQL 行数与 gateway 发送成功数一致；当前 consumer 在缓冲区满时同步 flush 形成背压，失败时整批重新入队，不再提交并丢弃最旧消息。

### 10.5 建议(按优先级)

1. **Kafka producer 批量化**:改为 `Async: true`(或 `BatchSize` 100–500 + 每个 worker 批量 `WriteMessages`),预计落库吞吐提升一个数量级;同时检查 logic 消费端缓冲区满时的丢弃逻辑,改为背压而不是丢最旧的消息。
2. **排查 Traefik**:确认 3.8k 连接附近断开的原因(资源限制 / 重启 / 连接上限)。
3. **按目标在线数调整 gateway 配置**:`max_connections` 和内存 limit 一起调(约 25 KB/连接 + 去重缓存);例如 5k 在线可保持 512Mi,10k 以上建议 1Gi。
4. **实时吞吐不够时**:调高 gateway CPU limit,或多副本(滚动更新已就绪,多副本需配置哈希环 / 服务发现)。
5. **去重缓存加上限**(按条数或内存),或缩短 `dedup_ttl`。

### 10.6 压测工具改动

- `-scheme http|https`:选择 HTTP / WS 或 HTTPS / WSS。
- `-uid-prefix`:所有生成的 UID 加前缀,便于事后清理。
- 设置环境变量 `GOIM_BENCH_JWT_SECRET` 时本地签发令牌,不再依赖 dev_mode 的 `/login`。
- 修复 reader:gorilla/websocket 读超时不可恢复(超时后每次读都返回同一错误),原来 1s 空闲就会废掉连接,且 `isTimeout` 对包装过的错误判断失效。低速率(≤1 msg/s)下 reader 全部提前退出、投递计数为 0。现在读截止时间设为压测结束后 3s,并用 `errors.As` 判断超时。

复现(在能访问测试环境的机器上):

```bash
export GOIM_BENCH_JWT_SECRET=<测试环境 jwt.secret>
go run ./bench/loadtest -scenario heartbeat -scheme https -addr go-im.test.chaisz.com -uid-prefix bench- -connections 1000 -interval 30s -duration 90s
go run ./bench/loadtest -scenario chat      -scheme https -addr go-im.test.chaisz.com -uid-prefix bench- -users 900 -rate 3 -duration 60s -workers 50
go run ./bench/loadtest -scenario group     -scheme https -addr go-im.test.chaisz.com -uid-prefix bench- -groups 2 -group-size 450 -msgs 480 -duration 60s -workers 50
```

压测后清理 `messages` / `groups` / `group_members` / `unread` 中 `bench-%` 的数据,以及 Redis 中 `*bench-*` 的 key。

---

## 11. 生产环境渐进压测 — 2026-10-19

本轮经真实公网 HTTPS/WSS 入口发压。生产环境为单机最小栈:2 vCPU / 约 1.7GiB 内存,gateway 单副本(256Mi limit),Traefik 入口,gateway 直接异步写 MySQL,不启用 Kafka / Logic。测试采用专用 `prodbench-20261019-` UID,完成后已删除 MySQL 压测消息、未读记录和 Redis dedup key。

### 11.1 在线连接

| 连接数 | 建连 | 心跳 | ACK P99 | gateway 峰值 | 结论 |
|--------|------|------|---------|--------------|------|
| 500 | 500 / 500(100%) | 1,356 成功 / 0 失败 | 11.85ms | CPU 4.0%,内存 10.7% | 稳定 |
| 1,000(建连并发 20) | 993 / 1,000(99.3%) | 2,767 成功 / 0 失败 | 110ms | CPU 12.8%,内存 15.7% | 已建立连接稳定,7 条握手失败 |
| 2,000 | 未完成 | — | — | 已采样部分 CPU 11.4%,内存 10.9% | 单一来源约 453 条后,到 VPS 的新连接和 SSH 监控一起超时;终止该档后服务立即恢复 |

2,000 档首先触发的是单源网络/NAT/云防护路径限制,不是 gateway 资源上限。该结果不能代表分布式真实用户的总连接上限;需要在多台压测机上分散源地址复测。全过程 gateway 重启数为 0。

### 11.2 单聊吞吐与落库

| 发送速率 | 实时投递 / ACK | ACK P50 / P95 / P99 | gateway CPU / 内存 | MySQL 落库 |
|----------|-----------------|----------------------|----------------------|------------|
| 95.6 msg/s | 4,300 / 4,300,100% | 36 / 80 / 94ms | 15.6% / 11.1% | 4,300 / 4,300(100%) |
| 283.3 msg/s | 8,500 / 8,500,100% | 34 / 80 / 172ms | 25.1% / 14.2% | 与下一档合计约 98.4% |
| 470.0 msg/s | 14,100 / 14,100,100% | 38 / 92 / 129ms | 33.8% / 13.0% | 与上一档合计约 98.4% |
| 946.7 msg/s | 28,400 / 28,400,100% | 57 / 261 / 457ms | 44.9% / 15.3% | 8,441 / 28,400(29.7%) |

累计发送 55,300 条,MySQL 最终只有 34,969 条(63.2%);等待 30 秒后数量不再增长,确认不是消费延迟。实时投递全程无丢失,但未落库的消息不会出现在历史记录和搜索中。约 947 msg/s 时 ACK P99 已接近 500ms 停止线,且持久化丢失 70.3%,因此没有继续升到 1,500–5,000 msg/s。

### 11.3 结论

- 当前生产环境建议控制在 **500 个长连接以内**,1,000 连接已有 0.7% 握手失败;更高连接数需多源压测确认入口/云防护限制。
- 实时消息在约 **950 msg/s** 下仍能 100% 投递,但 ACK P99 已到 457ms。
- 若要求历史消息可靠,当前安全速率只能保守定为 **约 100 msg/s**;283–470 msg/s 已出现约 1.6% 落库缺口。
- 第一优先级仍是把持久化改为批量写 / Kafka 完整链路,队列满时不得静默丢弃;其次才是扩 gateway CPU 或副本。单机批量写设计、参数与提升预估见 [11-persistence-batch-write-optimization.md](11-persistence-batch-write-optimization.md)。

---

## 12. v1.5.0 批量持久化生产复测 — 2026-10-19

本轮在 v1.5.0 上复测 §11 的单聊场景，重点验证 Gateway → MySQL 批量写。每档持续 30 秒，使用独立 UID 前缀；测试结束后等待队列排空，再统计 MySQL 实际新增行数。

### 12.1 结果

| 实际发送速率 | 发送 / 投递 / ACK | ACK P50 / P95 / P99 | gateway CPU / 内存峰值 | MySQL 落库 |
|-------------|-------------------|----------------------|-------------------------|------------|
| 约 93 msg/s | 2,800 / 2,800 / 2,800 | — | — | 2,800 / 2,800（100%） |
| 476.7 msg/s | 14,300 / 14,300 / 14,300 | — / — / 103ms | 13.0% / 10.4% | 14,300 / 14,300（100%） |
| 956.7 msg/s | 28,700 / 28,700 / 28,700 | — / — / 124ms | 27.8% / 13.3% | 28,700 / 28,700（100%） |
| 1,833.3 msg/s | 55,000 / 35,948 / 35,957（客户端观测） | 928ms / 14.4s / 24.8s | — | 16,466 / 55,000（29.9%） |

1,833 msg/s 档触发停止条件：端到端延迟快速累积，压测客户端在结束前只观测到约 65% 的投递与 ACK；该数字不能直接等同于服务端永久丢失，但 MySQL 最终仅有 16,466 行，确认当前整机在该负载下无法可靠持久化。

### 12.2 与改造前对比

- 约 950 msg/s：落库率由 **29.7%（8,441 / 28,400）提升到 100%（28,700 / 28,700）**。
- 同档 ACK P99 由 **457ms 降至 124ms**。
- 约 500 msg/s：改造前 283–470 msg/s 合计落库约 98.4%，改造后 **100%**。
- 约 1,833 msg/s：落库率回落到 **29.9%**，且 ACK P99 达 24.8s，未达到 2,000 msg/s 验收目标。

### 12.3 结论与容量建议

批量写优化已显著生效，当前小规格生产单机的可靠持久化能力已从约 100 msg/s 提升到实测约 **1,000 msg/s**，约提升 10 倍；但原方案预估的 1,500–2,000 msg/s 保守区间对这台 2 vCPU / 约 1.7GiB 的整机仍偏乐观。

上线容量暂按 **≤1,000 msg/s** 控制，并补做至少 30 分钟浸泡测试后再形成正式容量承诺。若要达到 2,000 msg/s，应先升级主机和 MySQL 资源，或启用 Kafka → Logic 解耦链路，再按 1,200、1,500、1,800、2,000 msg/s 小步复测。

测试后已清除 62,266 条压测消息及相关未读记录；Redis 压测 key 已自然过期。清理后压测消息和未读记录均为 0，gateway 重启数为 0，MySQL、Redis、对象存储健康。

---

## 13. v1.5.0 测试环境(go-im-test,k8s)复测 — 2026-10-26

按 §10 A 轮同样的方法复测 v1.5.0:ci-01 经 Traefik 入口发压,900 人单聊,每档 60 秒,结束后等待 30 / 60 秒统计 MySQL 新增行数。

| 项 | 值 |
|----|----|
| 被测版本 | gateway / logic 镜像 `1.5.0` |
| gateway | 1 副本,limits 1 CPU / 512Mi,`max_connections: 1000`,限流 10 msg/s·burst 20 |
| 持久化 | 默认批量参数(队列 8192、每批 200、10ms、2 个 writer);Gateway 同时写 Kafka 和 MySQL,Logic 消费 Kafka 后批量写 MySQL |
| 停止条件 | gateway 内存 > 420Mi 或 Pod 重启(未触发) |

### 13.1 结果

| 发送速率 | 送达 / ACK | ACK P50 / P95 / P99 (ms) | MySQL 落库(+30s) | 队列丢弃 | gateway CPU / 内存峰值 |
|---------|-----------|--------------------------|-------------------|---------|------------------------|
| 885 msg/s | 100% / 100% | 18.4 / 30.3 / 34.8 | 53,100 / 53,100(**100%**) | 0 | 0.08 核 / — |
| 2,685 msg/s | 100% / 100% | 19.5 / 33.1 / 41.0 | 161,100 / 161,100(**100%**) | 0 | — / 95Mi |
| 4,485 msg/s | 100% / 100% | 19.5 / 32.2 / 40.3 | 269,100 / 269,100(**100%**) | 0 | 0.39 核 / 94Mi |
| 7,170 msg/s | 100% / 100% | 16.7 / 27.6 / 33.6 | 430,200 / 430,200(**100%**) | 0 | 0.62 核 / 241Mi |

CPU / 内存来自 `kubectl top` 每 5 秒采样,metrics-server 有窗口延迟,部分档位峰值偏低,仅供参考。

累计 913,500 条全部在 30 秒内落库,`im_persist_queue_drop_total`、`im_persist_fail_total`、`im_persist_batch_fail_total` 均为 0,停止发压后队列深度归零,全程无 Pod 重启。

### 13.2 与 v1.4.0(§10 A 轮)对比

| 发送速率 | v1.4.0 落库率 | v1.5.0 落库率 | v1.4.0 ACK P99 | v1.5.0 ACK P99 |
|---------|--------------|--------------|----------------|----------------|
| ~885 msg/s | 88% | **100%** | 38.0ms | 34.8ms |
| ~2,685 msg/s | 49% | **100%** | 49.9ms | 41.0ms |
| ~4,470 msg/s | 28% | **100%** | 80.5ms | 40.3ms |
| ~7,170 msg/s | 17% | **100%** | 87.9ms | 33.6ms |

测试环境的可靠落库能力从约 1.2–1.7k msg/s 提升到**至少 7,170 msg/s**(本轮最高档,受 900 连接 × 限流 8 msg/s 的发压方式限制,尚未探到上限),达到并超过 [优化方案](11-persistence-batch-write-optimization.md) 第一阶段 2,000 msg/s 和第二阶段 5,000 msg/s 的落库目标(时长为 60 秒,30 分钟浸泡仍待补)。

### 13.3 发现的问题

**问题 1(中,已修复,待复测)— Kafka 与 MySQL 同时开启时双写,引发 MySQL 死锁重试。** `Router.doPersistBatch` 在 Kafka 和 MySQL 都配置时,每批消息既发 Kafka 又直写 MySQL;Logic 消费 Kafka 后对同一批 `msg_id` 再执行一次多行 `INSERT IGNORE`。两边并发写同一主键,Logic 日志出现大量 `Error 1213 Deadlock found`,gateway `im_persist_retry_total` 累计 621 次。重试后全部成功、无数据丢失,但每条消息写库两次,且 gateway 在 MySQL 失败时会整批重发 Kafka,产生重复消息。已修复:Kafka 开启时 gateway 只发 Kafka,由 Logic 负责落库;未开启 Kafka 时仍由 gateway 直接批量写 MySQL。

**问题 2(低)— 7k msg/s 时 `dedup redis queue full`。** 与 §10.4 问题 4 相同,写 Redis 去重记录是尽力而为,只影响跨网关重试去重,不影响投递和落库。

### 13.4 清理

已删除 913,500 条压测消息(UID 前缀 `bench-v150-1026-`),剩余 0;ci-01 压测目录和临时 Jenkins 任务已删除。测试后 gateway / logic 无新增重启,`/health` 各依赖正常。
