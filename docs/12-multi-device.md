# 12 — 多端同时在线

> 状态：第一阶段已实现（设备身份、多连接、按端互踢、己方多端同步、Redis 未读数）；第二阶段（同步时间线）、第三阶段（在线注册表 / 设备管理）待做

## 1. 目标

同一账号可在手机、电脑、网页同时在线：

- 发给我的消息，我所有在线设备都收到；
- 我在任一设备发出的消息、撤回、编辑、转发，我的其他在线设备同步看到；
- 我在任一设备读完一个会话，其他设备的未读同步清零；
- 同类设备是否允许并存由配置决定，冲突时旧设备收到 `CmdKick` 后被断开。

## 2. 设备身份

| 传输 | 上报方式 |
|------|----------|
| WebSocket | `GET /ws?token=…&device_id=…&platform=…` |
| gnet TCP | 首帧 `CmdLogin` 的 content 为 JSON `{"token","device_id","platform"}`；裸 JWT 仍兼容 |

- `device_id`：客户端安装时生成的 UUID，KMP 存在 `GOIM/Shared/device.json` 的 `deviceId`，同一台机器上切换账号不变。只允许字母数字与 `-_.`，最长 64。
- `platform`：小写，最长 16。分组：`ios` / `ipados` / `android` / `harmony` 为移动端，`desktop` / `macos` / `windows` / `linux` 为桌面端，`web` 单独一组，空值视为旧客户端（`legacy` 组）。

## 3. 互踢策略

配置项 `gateway.auth.multi_device`：

| 值 | 行为 |
|----|------|
| `single`（默认） | 每个账号只保留一个连接，与改造前一致 |
| `per_platform` | 移动端、桌面端、网页各保留一个 |
| `unlimited` | 不限设备数 |

所有策略下，同一 `device_id` 重连都会**静默替换**自己的旧连接（不发 `CmdKick`），避免网络切换时把自己踢下线。被踢的客户端收到 `CmdKick` 后立即停止自动重连，否则会反过来把新设备踢掉。

## 4. 网关投递

`Hub` 现在是 `uid → []*Client`：

- `Get(uid)` 返回最新连接（兼容只需要一个连接的旧调用点）；
- `GetAll(uid)` 返回全部连接（`ClientRegistry` 新增方法）；
- `Count()` 统计在线用户数，不是连接数。

`Router` 的三个投递原语：

| 函数 | 作用 |
|------|------|
| `sendLocal` | 推给本网关上该用户的所有连接，可排除一个连接 |
| `deliverToUser` | 本地所有连接 → 哈希环转发 → 离线队列 |
| `syncToOwnDevices` | 推给发送者本网关上的其他连接（不含发送连接） |

覆盖范围：单聊、群聊扇出、撤回、编辑、转发、已读回执、好友通知、群通知、跨网关 `ForwardMessage`。ACK 只回给发送连接。

去重键从 `uid:seq` 改为 `uid/device_id:seq`：客户端 seq 取毫秒时间戳，两台设备可能撞号。

## 5. 未读数

配置了 Redis 时使用 `RedisUnreadTracker`：Hash `im:unread:{uid}`，field 为对端 UID 或群 ID，30 天 TTL；Redis 失败时回退内存。多台网关、多台设备看到同一份未读数。

已读同步：`handleReadReceipt` 把回执（`From` = 阅读者）同时发给阅读者的其他设备，客户端收到 `from == 自己` 的 `CmdReadReceipt` 即把对应会话未读清零（群会话按 `chat_type` 或 `g_` 前缀判断）。

## 6. 客户端（KMP）

- `ServerSettingsRepository.deviceId` + `devicePlatform`（expect/actual）在建连时上报；
- `CmdDispatchHandler`：自己的已读回执 → `InboundEvent.ReadSynced`；`CmdKick` 先同步回调 `onKick` 停止重连；
- `MessageDao.upsertMerged` / `markStatusMerged`：按 client_seq 合并外发行时，要求同会话，且目标行是同一行或尚无（或相同的）server_msg_id；ACK 只更新还没有 server_msg_id 或 server_msg_id 相同的行。防止其他设备同步来的消息因 seq 撞号覆盖本机消息。

## 7. 已知限制（第一阶段）

- **离线设备收不到己方同步**：`syncToOwnDevices` 只推在线连接；离线队列是“取出即删除”，用户的某台设备拉走后其他设备拿不到。
- **跨网关的己方同步**：发送者其他设备若连在别的网关上，本阶段不会同步。
- 两者都由第二阶段解决。

## 8. 后续阶段

**第二阶段：每用户同步时间线**

- `im:seq:{uid}` 自增序号，`im:inbox:{uid}` ZSET（score = seq）保存发给该用户及该用户发出的消息；
- `Message` 增加 `sync_seq` 字段，客户端记录每台设备的已同步位置；
- 新命令 `CmdSync(since_seq)` 替代 `CmdOffline` 拉取增量，多设备各自游标、互不影响；
- 撤回 / 编辑 / 已读作为时间线事件写入，离线设备上线后补齐。

**第三阶段：在线注册表与设备管理**

- Redis 在线注册表（`uid → {device_id: node}`），跨网关精确投递与己方同步；
- JWT 携带 `did`，支持单设备注销、“已登录设备”列表与远程下线。
