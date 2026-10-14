# GO-IM Web

[GO-IM](https://github.com/chaishuanzhu/GO-IM) 的 Web 客户端（React + Vite），由 nginx 提供静态资源，并把 API 与 WebSocket 反向代理到 Gateway。

- 源码：https://github.com/chaishuanzhu/GO-IM
- 客户端下载 / 项目介绍：https://blog.chaisz.com/GO-IM/
- 配套镜像：[`870027381/go-im-gateway`](https://hub.docker.com/r/870027381/go-im-gateway)（连接层）、[`870027381/go-im-logic`](https://hub.docker.com/r/870027381/go-im-logic)（业务层）

## 标签

| 标签 | 说明 |
|------|------|
| `latest` | 最新发布版本 |
| `1.0.0` 等 | 固定版本，与 GitHub Release `kmp-v1.0.0` 对应，生产环境建议使用 |
| `sha-<commit>` | 某次提交的构建 |

均为多架构镜像：`linux/amd64`、`linux/arm64`。

## 单独运行

镜像内置的 nginx 把 `/ws`、`/login`、`/register`、`/upload`、`/file`、`/group/`、`/friend/` 等路径转发到 **`http://gateway:8080`**，所以必须和一个名为 `gateway` 的 Gateway 容器在同一个 Docker 网络里：

```bash
docker network create go-im
docker run -d --name gateway --network go-im \
  -v $(pwd)/config.json:/etc/im/config.json:ro \
  870027381/go-im-gateway:latest
docker run -d --name web --network go-im -p 80:80 \
  870027381/go-im-web:latest
```

Gateway 地址不同时，挂载自己的 nginx 配置覆盖 `/etc/nginx/conf.d/default.conf`，模板见 [`web/nginx.conf`](https://github.com/chaishuanzhu/GO-IM/blob/main/web/nginx.conf)。

<!-- DEPLOY -->
