# GO-IM Logic

[GO-IM](https://github.com/chaishuanzhu/GO-IM) 的业务层：消费 Kafka 中的消息并批量写入 MySQL，同时通过 gRPC 为 Gateway 提供历史消息、用户查询等接口。

- 源码：https://github.com/chaishuanzhu/GO-IM
- 客户端下载 / 项目介绍：https://blog.chaisz.com/GO-IM/
- 配套镜像：[`870027381/go-im-gateway`](https://hub.docker.com/r/870027381/go-im-gateway)（连接层）

Logic 只在**全量部署**中使用，不对外提供服务；最小化部署不需要它。

## 标签

| 标签 | 说明 |
|------|------|
| `latest` | 最新发布版本 |
| `1.5.0` 等 | 固定版本，与 GitHub Release `v1.5.0` 对应，生产环境建议使用 |
| `sha-<commit>` | 某次提交的构建 |

均为多架构镜像：`linux/amd64`、`linux/arm64`。

## 单独运行

和 Gateway 共用同一份配置文件，读取其中的 `logic` 段（`listen_addr`、`mysql`、`kafka`、`worker_id`）。

```bash
docker run -d --name logic \
  -p 50051:50051 \
  -v $(pwd)/config.json:/etc/im/config.json:ro \
  870027381/go-im-logic:latest
```

<!-- DEPLOY -->
