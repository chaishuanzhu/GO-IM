#!/bin/bash
# 生产环境部署脚本，服务器上的位置：/root/docker/go-im/deploy.sh
# Jenkins go-im 用专用 SSH 密钥调用；该密钥在 /root/.ssh/authorized_keys 中被限制为只能执行本脚本：
#   command="/root/docker/go-im/deploy.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty ssh-ed25519 ... jenkins-goim-prod
# 唯一参数是版本号 X.Y.Z（经 SSH 传入时在 SSH_ORIGINAL_COMMAND 中）。
# 流程：拉取镜像 → 改 .env 的 GOIM_TAG → 重建 gateway → 健康检查；失败则恢复原版本。
# 改了这里需同步到服务器（scp 到上述路径）。
set -euo pipefail

DIR=/root/docker/go-im
ver="${SSH_ORIGINAL_COMMAND:-${1:-}}"
if ! [[ "$ver" =~ ^[1-9][0-9]*\.[0-9]+\.[0-9]+$ ]]; then
    echo "版本号须为 X.Y.Z：$ver" >&2
    exit 2
fi

cd "$DIR"
exec 9>"$DIR/.deploy.lock"
flock -n 9 || { echo "已有部署在进行" >&2; exit 3; }

old=$(sed -n 's/^GOIM_TAG=//p' .env)
echo "生产部署：${old:-?} → $ver"

set_tag() { sed -i "s/^GOIM_TAG=.*/GOIM_TAG=$1/" .env; }

healthy() {
    local out
    for _ in $(seq 1 24); do
        out=$(docker compose exec -T gateway wget -qO- -T 5 http://127.0.0.1:8080/health 2>&1) &&
            echo "$out" | grep -q '"status":"ok"' && { echo "$out"; return 0; }
        sleep 5
    done
    echo "健康检查失败：$out" >&2
    return 1
}

GOIM_TAG="$ver" timeout 900 docker compose pull -q gateway

set_tag "$ver"
docker compose up -d gateway
if healthy; then
    docker compose ps gateway --format '{{.Image}} {{.Status}}'
    docker image prune -f >/dev/null
    exit 0
fi

docker compose logs gateway --tail 50 || true
if [ -n "$old" ] && [ "$old" != "$ver" ]; then
    echo "回滚到 $old" >&2
    set_tag "$old"
    docker compose up -d gateway
    healthy || true
fi
exit 1
