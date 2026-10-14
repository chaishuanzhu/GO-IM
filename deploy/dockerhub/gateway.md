# GO-IM Gateway

[GO-IM](https://github.com/chaishuanzhu/GO-IM) 的连接层：WebSocket / 原生 TCP 长连接、HTTP API（登录、注册、群组、文件上传、搜索）、消息路由与多网关转发。

- 源码：https://github.com/chaishuanzhu/GO-IM
- 客户端下载 / 项目介绍：https://blog.chaisz.com/GO-IM/
- 配套镜像：[`870027381/go-im-logic`](https://hub.docker.com/r/870027381/go-im-logic)（业务层）、[`870027381/go-im-web`](https://hub.docker.com/r/870027381/go-im-web)（Web 前端）

## 标签

| 标签 | 说明 |
|------|------|
| `latest` | 最新发布版本 |
| `1.0.0` 等 | 固定版本，与 GitHub Release `kmp-v1.0.0` 对应，生产环境建议使用 |
| `sha-<commit>` | 某次提交的构建 |

均为多架构镜像：`linux/amd64`、`linux/arm64`。

## 单独运行

```bash
docker run -d --name gateway \
  -p 8080:8080 -p 8081:8081 \
  -v $(pwd)/config.json:/etc/im/config.json:ro \
  870027381/go-im-gateway:latest
```

把 `config.json` 里 MySQL、Redis、Kafka、对象存储、Logic 的地址改成你自己的服务；不需要的依赖设 `enabled: false` 或留空地址，对应功能降级为内存实现。

<!-- DEPLOY -->
