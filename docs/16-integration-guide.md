# 16 — 把 GO-IM 作为 IM 通道接入其他应用

GO-IM 可以作为通用 IM 组件，为其他应用（下称"接入方"）提供聊天与实时信令通道。接入方有自己的用户体系和业务后端；
IM 只负责连接、消息、群和在线状态。两边的边界：

```
接入方客户端 ──(IM 令牌)──▶ GO-IM 网关（/ws、TCP、/history …）
     │                          ▲      │
     │ 业务请求（带 IM 令牌）     │      │ webhook（签名，异步 + 可选同步 before_send）
     ▼                          │      ▼
接入方业务后端 ──(app_id + HMAC)──▶ /server/v1/*   ◀── 接入方 /im/webhook
```

- 接入方后端通过**服务端 API**（`/server/v1/*`）导入用户、签发令牌、管理群、发消息和信令。
- 接入方客户端拿后端签发的 **IM 令牌**直接连网关，协议与 GO-IM 自己的客户端相同（`docs/07-api-reference.md`）。
- 接入方后端用 `tokens/verify` 校验客户端提交的 IM 令牌，以此认证自己的业务接口——不需要再发一套令牌。
- IM 侧的事件（消息、成员变化、上下线、举报）通过 **webhook** 推给接入方后端。

参考实现：Paircast（一起看，`cmd/paircast`）就是这样一个接入方，见文末。

## 1. 登记接入方

网关配置 `gateway.server_api`（需要 MySQL）：

```json
"server_api": {
  "max_token_ttl": "720h",
  "apps": [
    {
      "app_id": "paircast",
      "webhook_url": "http://paircast:8090/im/webhook",
      "webhook_events": ["group.member_left", "group.dismissed"],
      "before_send_url": "",
      "before_send_timeout": "500ms"
    }
  ]
}
```

- 密钥只用环境变量提供：`GOIM_APP_<ID>_SECRET`（API 签名）、`GOIM_APP_<ID>_WEBHOOK_SECRET`（webhook 签名，空则复用前者）。
  `<ID>` 为 app_id 大写、非字母数字转 `_`。没有密钥的接入方不会启用。
- `webhook_events` 为空 = 订阅全部事件。
- 启动时接入方会同步到 MySQL `apps` 表（只存密钥哈希做审计）；把某行的 `status` 改为 0 可以在不重启的情况下停用该接入方（30 秒内生效）。

## 2. 服务端 API 签名

所有接口都是 `POST /server/v1/<path>`，JSON 请求体，JSON 响应；错误为 `{"error":"..."}`。请求头：

| 头 | 说明 |
|----|------|
| `X-App-Id` | 接入方 ID |
| `X-Timestamp` | Unix 秒，与服务器时间相差不超过 5 分钟 |
| `X-Nonce` | 随机串（≤ 64 字符），5 分钟内不可重复（防重放；有 Redis 时多网关共享） |
| `X-Signature` | `hex(HMAC-SHA256(secret, METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + NONCE + "\n" + BODY))` |

`PATH` 为完整路径（如 `/server/v1/users/token`），`BODY` 为原始请求体字节。签名失败返回 401。

## 3. 用户与令牌

用户用 `{"uid": "..."}` 或 `{"external_id": "..."}` 引用（`external_id` 是接入方自己的用户 ID，按接入方隔离）。

| 接口 | 请求 | 响应 |
|------|------|------|
| `users/import` | `{"external_id","nickname"?,"avatar_file_id"?}` 或 `{"users":[...]}`（≤ 100） | 用户（批量为 `{"users":[...]}`），新建的带 `created: true` |
| `users/token` | `{uid\|external_id, "ttl_seconds"?}` | `{"uid","token","expires_at"}`；ttl 为 0 用 `max_token_ttl` |
| `users/revoke` | `{uid\|external_id}` | `{"token_version"}`：之前签发的令牌全部失效，在线连接被踢 |
| `users/disable` | `{uid\|external_id, "disabled": bool}` | 禁用后不能登录 / 连接，在线连接被踢 |
| `users/delete` | `{uid\|external_id}` | 删除账号及其好友、群成员关系 |
| `tokens/verify` | `{"token"}` | `{"valid":true,"uid","external_id"?,"nickname","role","expires_at"}` 或 `{"valid":false,"reason"}` |

典型令牌流程：

1. 用户登录接入方 → 接入方后端 `users/import`（首次）+ `users/token` → 把 `uid` 与 `token` 返回给客户端。
2. 客户端用它连网关（`/ws?uid=&token=` 或 TCP `CmdLogin`），以及调用接入方后端的业务接口。
3. 接入方后端收到业务请求时调 `tokens/verify`（建议缓存几十秒），得到 `uid`。
4. 用户在接入方退出 / 改密 / 封禁 → `users/revoke` 或 `users/disable`。

## 4. 群

| 接口 | 请求 | 说明 |
|------|------|------|
| `groups/create` | `{"name","owner":{...},"members":[{...}]}` | 返回 `{"group_id","name","owner_uid","members","created_at"}` |
| `groups/members/add` | `{"group_id","users":[...]}` | 已在群里的忽略；成员收到 `member_joined` 群通知 |
| `groups/members/remove` | `{"group_id","users":[...]}` | 不在群里的忽略；成员收到 `member_left` |
| `groups/dismiss` | `{"group_id"}` | 全体成员收到 `group_dismissed` 后移出，群随之删除 |

每次最多 500 人。接入方后端操作的群与用户在客户端自己建的群完全相同。

## 5. 消息与信令

**持久消息** `messages/send`：`{"from"?, "to":{...} | "group_id", "msg_type"?, "content"}`。

- `from` 为空时以接入方系统身份 `sys:<app_id>` 发送（系统消息不受屏蔽和群成员校验限制）；指定 `from` 时按该用户身份发送，与客户端发送的规则相同（被对方屏蔽返回 403，不是群成员返回 403）。
- 消息走正常的投递、离线、未读、持久化与 webhook 流程。`content` ≤ 64 KiB。
- 自定义业务消息：约定一个 `msg_type`（避开 GO-IM 已用的类型，见 `api/proto/message.go`），`content` 放 JSON；客户端按 `msg_type` 渲染。

**瞬时信令** `signals/send`：`{"from"?, "to" | "group_id", "type":"<ns>.<name>", "data":{...}}`。

- 只投递给在线连接（含跨网关），不持久化、不进离线队列、不计未读。适合房间状态、播放进度、输入中等。
- 客户端收到 `CmdSignal`，`content` 为 `{"type":"<ns>.<name>","data":...}`；客户端之间也可以直接发（单聊或群，群信令只有成员能发）。
- `type` 必须带命名空间（如 `paircast.room_updated`），避免与其他接入方冲突。

## 6. Webhook

接入方配置了 `webhook_url` 时，网关把订阅的事件异步推送过去：

```
POST <webhook_url>
X-IM-Event: group.member_left
X-IM-Delivery: <事件 ID，重试时不变，用于去重>
X-IM-Timestamp: <Unix 秒>
X-IM-Signature: hex(HMAC-SHA256(webhook_secret, TIMESTAMP + "\n" + BODY))

{"id":"...","type":"group.member_left","app_id":"paircast","timestamp":1700000000,"data":{"group_id":"g_1","uid":"100001"}}
```

| 事件 | data |
|------|------|
| `message.sent` | `{"msg_id","from","to","chat_type","msg_type","content","timestamp"}` |
| `message.recalled` | `{"msg_id","by","to","chat_type"}` |
| `group.member_joined` / `group.member_left` | `{"group_id","uid"}` |
| `group.dismissed` | `{"group_id"}`（最后一名成员离开、群被删除时） |
| `user.online` / `user.offline` | `{"uid"}`（该用户在本网关的第一个 / 最后一个连接） |
| `report.created` | 举报记录 |

投递语义：**至少一次**。2xx 视为成功；其它状态或超时（5 秒）按 2^n 秒退避重试（最长间隔 1 小时），从首次入队起 24 小时仍失败则进入死信（Redis `im:webhook:<app>:dead`）。
有 Redis 时事件存 Redis Stream（`im:webhook:<app>`），多个网关共享、重启不丢；没有 Redis 时在进程内存中排队。
接收方应校验签名与时间戳（5 分钟窗口）、按 `X-IM-Delivery` 去重，并尽快返回（耗时处理放后台）。

**before_send（可选，同步）**：配置 `before_send_url` 后，用户发单聊 / 群聊 / 转发消息前，网关同步 POST 消息内容（与 `message.sent` 的 data 相同，`X-IM-Event: message.before_send`，签名同上）。
响应 `{"allow":false,"reason":"..."}` 则拒绝，发送方收到 ACK `{"error":"rejected","reason":...}`；超时（默认 500ms）、非 2xx 或其他响应一律放行。

## 7. Go SDK

`pkg/imserver` 封装了签名、全部服务端 API 与 webhook 校验：

```go
im := imserver.New("http://gateway:8080", "paircast", os.Getenv("PAIRCAST_IM_SECRET"))

u, _ := im.ImportUser(ctx, imserver.ImportUser{ExternalID: "u-42", Nickname: imserver.String("Alice")})
tok, _ := im.IssueToken(ctx, imserver.External("u-42"), 24*time.Hour)

grp, _ := im.CreateGroup(ctx, "Movie night", imserver.UID(u.UID), nil)
_ = im.SendSignal(ctx, imserver.Signal{GroupID: grp.ID, Type: "paircast.room_updated", Data: room})

info, _ := im.VerifyToken(ctx, tokenFromClient) // info.Valid, info.UID

http.HandleFunc("/im/webhook", func(w http.ResponseWriter, r *http.Request) {
    ev, err := imserver.ParseWebhook(r, os.Getenv("PAIRCAST_IM_WEBHOOK_SECRET"))
    if err != nil { http.Error(w, "bad signature", 401); return }
    switch ev.Type {
    case imserver.EventGroupMemberLeft: /* ... */
    }
    w.WriteHeader(http.StatusNoContent)
})
```

`imserver.IsNotFound(err)` 判断 404（用户 / 群不存在）；其它错误为 `*imserver.APIError`（带状态码与消息）。

## 8. 参考实现：Paircast

`cmd/paircast` 是独立部署的一起看服务（媒体库 `/media/*` + 观影房间 `/room/*`），完全通过上面的能力使用 IM：

- 认证：客户端带 IM 令牌调用 Paircast，Paircast 用 `tokens/verify` 校验（缓存 60 秒）。
- 房间 = 一个 IM 群：建房 `groups/create`，进出房 `groups/members/add|remove`，房间清理 `groups/dismiss`。
- 房间变化用群信令 `paircast.room_updated` 推送；播放同步由客户端之间直接发 `paircast.*` 信令。
- 订阅 `group.member_left` / `group.dismissed`，用户在 IM 里退群或群被解散时同步房间成员。

部署：`Dockerfile.paircast`（镜像 `go-im-paircast`）、`deploy/k8s/paircast/`（可选叠加层，Ingress 把 `/media`、`/room` 转给它）、
`docker compose --profile paircast up -d`。配置见 `configs/paircast.example.json`。

网关内置的旧版实现（`gateway.media`）在本版本仍可用，作为过渡；它与独立服务共用 `internal/paircast` 的接口代码，下个版本移除。
