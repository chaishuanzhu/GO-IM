# GO-IM Paircast

Paircast（一起看）业务服务：媒体库（`/media/*`，S3 兼容视频源、播放直链、字幕、贴纸）与观影房间（`/room/*`）。
它作为 [GO-IM](https://github.com/chaishuanzhu/GO-IM) 的一个**接入方**运行：用 IM 用户令牌认证，通过服务端 API（`/server/v1/*`）建群、拉人、发房间信令，通过 webhook 同步退群与解散。

- 源码：https://github.com/chaishuanzhu/GO-IM（`cmd/paircast`）
- 接入说明：`docs/16-integration-guide.md`
- 配套镜像：[`870027381/go-im-gateway`](https://hub.docker.com/r/870027381/go-im-gateway)（IM 连接层，必需）

## 标签

| 标签 | 说明 |
|------|------|
| `latest` | 最新发布版本 |
| `1.7.0` 等 | 固定版本，与 GitHub Release 对应，生产环境建议使用 |
| `sha-<commit>` | 某次提交的构建 |

均为多架构镜像：`linux/amd64`、`linux/arm64`。

## 运行

配置文件参考仓库里的 `configs/paircast.example.json`；密钥用环境变量提供。IM 网关的 `gateway.server_api.apps` 中需登记同一个 `app_id`，
`webhook_url` 指向本服务的 `/im/webhook`，订阅 `group.member_left` 与 `group.dismissed`。

```bash
docker run -d --name paircast \
  -p 8090:8090 \
  -v $(pwd)/paircast.json:/etc/paircast/paircast.json:ro \
  -e PAIRCAST_IM_SECRET=... \
  -e PAIRCAST_IM_WEBHOOK_SECRET=... \
  -e PAIRCAST_MYSQL_DSN='im:...@tcp(mysql:3306)/im?parseTime=true' \
  870027381/go-im-paircast:latest
```

反向代理把 `/media` 与 `/room` 转到本服务（端口 8090），其余路径仍转到 Gateway。

<!-- DEPLOY -->
