# 部署指南

三种方式都使用 Docker Hub 上的预构建镜像（`870027381/go-im-gateway`、`870027381/go-im-logic`、`870027381/go-im-web`，`linux/amd64` + `linux/arm64`），无需拉源码编译。

| 方式 | 组件 | 适用 | 建议配置 |
|------|------|------|----------|
| [Docker 最小化](#docker-最小化部署) | Gateway、Web、MySQL、Redis、RustFS | 个人 / 小团队单机 | 2 核 2 GB |
| [Docker 全量](#docker-全量部署) | 最小化 + Kafka + Logic | 单机完整架构，消息经 Kafka 异步落库 | 2 核 4 GB |
| [Kubernetes 全量](#kubernetes-全量部署) | 同 Docker 全量 | 已有 k8s 集群 | 3 核 4 GB 可分配 + 默认 StorageClass |

最小化部署由 Gateway 直接写 MySQL，聊天、群聊、历史、搜索、文件全部可用；全量部署把写库交给 Logic 消费 Kafka，Gateway 热路径更轻。两者用户可见功能一致，数据库表结构相同。

部署完成后浏览器打开站点地址注册账号；[桌面端 / 移动端客户端](https://blog.chaisz.com/GO-IM/)的服务器地址填同一个地址。

## Docker 最小化部署

需要 Docker 20.10+ 和 Docker Compose v2。

```bash
mkdir go-im && cd go-im
base=https://raw.githubusercontent.com/chaishuanzhu/GO-IM/main/deploy/docker
curl -fsSLO $base/docker-compose.minimal.yml
curl -fsSLO $base/config.minimal.json

# 必改：JWT 签名密钥
sed -i.bak "s|CHANGE_ME_run_openssl_rand_base64_48|$(openssl rand -base64 48 | tr -d '\n/+=')|" config.minimal.json

docker compose -f docker-compose.minimal.yml up -d
docker compose -f docker-compose.minimal.yml ps
```

打开 `http://<服务器IP>/`。

## Docker 全量部署

```bash
mkdir go-im && cd go-im
base=https://raw.githubusercontent.com/chaishuanzhu/GO-IM/main/deploy/docker
curl -fsSLO $base/docker-compose.yml
curl -fsSLO $base/config.json

sed -i.bak "s|CHANGE_ME_run_openssl_rand_base64_48|$(openssl rand -base64 48 | tr -d '\n/+=')|" config.json

docker compose up -d
docker compose ps
```

打开 `http://<服务器IP>/`。Kafka 首次启动约需 30 秒，Logic、Gateway 会等它健康后再启动。

### Docker 部署的常用操作

以下命令以全量为例，最小化部署加上 `-f docker-compose.minimal.yml`。

```bash
docker compose logs -f gateway                  # 查看日志
docker compose pull && docker compose up -d     # 升级到 latest
GOIM_TAG=1.0.0 docker compose up -d             # 固定 / 回滚到指定版本
docker compose down                             # 停止（保留数据卷）
docker compose down -v                          # 停止并删除所有数据
```

可写进同目录 `.env` 的变量：

| 变量 | 默认 | 说明 |
|------|------|------|
| `GOIM_TAG` | `latest` | 镜像版本，生产建议固定（如 `1.0.0`） |
| `WEB_PORT` | `80` | Web 端口 |
| `TCP_PORT` | `8081` | 原生 TCP 长连接端口 |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | `im-root` / `im-dev` | 改 `MYSQL_PASSWORD` 需同步改配置里两处 `dsn` |
| `RUSTFS_SECRET_KEY` | `rustfsadmin` | 对象存储密钥，需同步改配置里的 `object_storage.secret_key` |

数据库密码只在**首次**创建数据卷时生效，之后修改需进 MySQL 手动改。

## Kubernetes 全量部署

需要 kubectl 1.27+（内置 kustomize）、默认 StorageClass 和一个 Ingress 控制器。清单在 [`deploy/k8s`](https://github.com/chaishuanzhu/GO-IM/tree/main/deploy/k8s)，默认部署到命名空间 `go-im`。

```bash
git clone --depth 1 https://github.com/chaishuanzhu/GO-IM.git
cd GO-IM/deploy/k8s

# 1. 必改：JWT 签名密钥
sed -i.bak "s|CHANGE_ME_run_openssl_rand_base64_48|$(openssl rand -base64 48 | tr -d '\n/+=')|" config.json
# 2. ingress.yaml：把 im.example.com 换成你的域名，按需设置 ingressClassName / tls
# 3. kustomization.yaml：按需修改 goim-infra 中的密码（同步改 config.json）和镜像版本 newTag

kubectl apply -k .
kubectl -n go-im get pods -w
```

所有 Pod 就绪后访问 `http://<你的域名>/`。

| 资源 | 类型 | 存储 |
|------|------|------|
| mysql / redis / kafka / rustfs | StatefulSet | PVC 10Gi / 1Gi / 5Gi / 20Gi |
| logic / gateway / web | Deployment | — |
| go-im | Ingress | `/ws`、`/upload` → gateway，其余 → web |
| goim-config | Secret（由 `config.json` 生成） | 挂载到 gateway、logic 的 `/etc/im/config.json` |
| goim-infra | Secret | MySQL、RustFS 的账号密码 |

常用操作：

```bash
kubectl -n go-im logs deploy/gateway -f
# 升级：改 kustomization.yaml 的 newTag 后重新 apply；或直接
kubectl -n go-im set image deploy/gateway gateway=870027381/go-im-gateway:1.0.1
kubectl -n go-im rollout undo deploy/gateway        # 回滚
kubectl delete -k .                                 # 删除（PVC 需手动删除）
```

已有 MySQL / Redis / Kafka / 对象存储时，从 `kustomization.yaml` 的 `resources` 中去掉对应文件，把 `config.json` 中的地址改成外部服务即可。

## 配置说明

Gateway 和 Logic 只读一个 JSON 配置文件（容器内 `/etc/im/config.json`，路径可用 `CONFIG_PATH` 覆盖），没有其它环境变量。完整字段见 [`configs/config.example.json`](https://github.com/chaishuanzhu/GO-IM/blob/main/configs/config.example.json)。

| 字段 | 说明 |
|------|------|
| `jwt.secret` | **必须修改**，登录令牌签名密钥 |
| `gateway.check_origin` | 允许的 WebSocket Origin，空数组 = 允许全部；公网建议填 `["https://你的域名"]` |
| `gateway.auth.dev_mode` | `true` 时登录无需密码，仅限开发 |
| `gateway.mysql` / `gateway.redis` / `gateway.object_storage` | 存储依赖；`enabled: false` 或地址为空时降级为内存实现，重启丢数据 |
| `gateway.kafka.enabled` + `gateway.logic.addr` | 全量部署为 `true` + `logic:50051`；最小化部署为 `false` + 空 |
| `gateway.object_storage.max_upload` | 单文件上限，默认 10 MB |
| `admin_uids` | 管理员 UID 列表 |
| `logic.worker_id` | 雪花 ID 机器号，须与 `snowflake.worker_id` 不同 |

## 端口

| 端口 | 服务 | 说明 |
|------|------|------|
| `80` | web | 页面 + API / WebSocket 反向代理，对外入口 |
| `8080` | gateway | HTTP API + WebSocket（`/ws`），健康检查 `GET /health` |
| `8081` | gateway | 原生 TCP（4 字节大端长度前缀 + protobuf），客户端可选 |
| `50051` | logic | gRPC，仅集群内部 |

## 生产建议

- 前面加 HTTPS（Caddy、Traefik、nginx、云负载均衡或 Ingress TLS），WebSocket 需透传 `Upgrade` 头。
- 修改 JWT 密钥和所有默认密码，`check_origin` 填写实际域名。
- 固定镜像版本，不要在生产使用 `latest`。
- 定期备份 MySQL 和对象存储数据卷。
