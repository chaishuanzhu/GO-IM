#!/usr/bin/env bash
# =============================================================================
# IM 项目 — 生产配置渲染脚本(docker-compose.prod.yml 单机部署用)
#
# 用途: 将 deploy/config.prod.json 模板中的占位符替换为真实值,
#       输出 configs/config.prod.generated.json(gitignore,不会误提交)。
#
# 依赖环境变量:
#   DOMAIN           站点域名 → check_origin(证书域名一致)
#   JWT_SECRET       登录令牌签名密钥(openssl rand -base64 64 生成)
#   MYSQL_PASSWORD   应用连 MySQL 的密码 → DSN(须与服务器 .env 的 MYSQL_PASSWORD 一致)
#   RUSTFS_SECRET_KEY RustFS 访问密钥 → object_storage.secret_key
#                   (须与服务器 .env 的 RUSTFS_SECRET_KEY 一致 —— 应用即用管理员凭据访问)
#   ADMIN_UID        管理员 UID → admin_uids(可选)
#   ADMIN_EMAIL      管理员邮箱 → admin_email(可选;首次启动自动建号,随机密码发到该邮箱)
#                   ADMIN_UID 与 ADMIN_EMAIL 至少填一个
#
# 注意:
#   1. 渲染目标为 gitignore 的 configs/config.prod.generated.json,
#      prod compose 的 CONFIG_PATH 即指向它。
#   2. dev 环境不受影响,仍用 configs/config.docker.json。
# =============================================================================

set -euo pipefail

SRC="deploy/config.prod.json"
DST="configs/config.prod.generated.json"

# 必填环境变量校验
REQUIRED=(DOMAIN JWT_SECRET MYSQL_PASSWORD RUSTFS_SECRET_KEY)
for v in "${REQUIRED[@]}"; do
    if [ -z "${!v:-}" ]; then
        echo "ERROR: 缺少环境变量 $v" >&2
        exit 1
    fi
done
ADMIN_UID="${ADMIN_UID:-}"
ADMIN_EMAIL="${ADMIN_EMAIL:-}"
if [ -z "$ADMIN_UID" ] && [ -z "$ADMIN_EMAIL" ]; then
    echo "ERROR: ADMIN_UID 与 ADMIN_EMAIL 至少设置一个" >&2
    exit 1
fi
if [ -n "$ADMIN_UID" ]; then
    ADMIN_UIDS_JSON="[\"$ADMIN_UID\"]"
else
    ADMIN_UIDS_JSON="[]"
fi

# 转义 sed 替换串中的特殊字符(& \ 与分隔符 |)
esc() {
    printf '%s' "$1" | sed 's/[&\\|]/\\&/g'
}

mkdir -p "$(dirname "$DST")"

sed \
    -e "s|YOUR_DOMAIN|$(esc "$DOMAIN")|g" \
    -e "s|\[\"YOUR_ADMIN_UID\"\]|$(esc "$ADMIN_UIDS_JSON")|g" \
    -e "s|YOUR_ADMIN_EMAIL|$(esc "$ADMIN_EMAIL")|g" \
    -e "s|CHANGE_ME_JWT_SECRET_USE_RANDOM_64_CHARS|$(esc "$JWT_SECRET")|g" \
    -e "s|CHANGE_ME_PASSWORD|$(esc "$MYSQL_PASSWORD")|g" \
    -e "s|CHANGE_ME_RUSTFS_SECRET|$(esc "$RUSTFS_SECRET_KEY")|g" \
    "$SRC" > "$DST"

echo "✅ 已生成 $DST"
echo "   check_origin: https://${DOMAIN} / https://www.${DOMAIN}"
echo "   admin_uids:   ${ADMIN_UIDS_JSON}"
echo "   admin_email:  ${ADMIN_EMAIL:-(未设置)}"
echo "   剩余占位符检查(应为空):"
grep -nE 'YOUR_DOMAIN|YOUR_ADMIN_UID|YOUR_ADMIN_EMAIL|CHANGE_ME' "$DST" || echo "   无 ✅"
