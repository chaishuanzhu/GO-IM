#!/usr/bin/env bash
# 把贴纸包（scripts/build_*_packs.py 生成的 stickers-dist/）上传到对象存储 stickers/ 前缀，
# 网关通过 GET /stickers/… 公开提供。catalog.json / pack.json 中的绝对地址（cdn_base、base_url、zip_url）
# 会被去掉，客户端统一从网关取；{pack}.zip 不上传。
#
# 用法：
#   S3_ENDPOINT=http://127.0.0.1:9000 S3_ACCESS_KEY=… S3_SECRET_KEY=… \
#     scripts/upload_stickers.sh [stickers-dist]
#
# 环境变量：
#   S3_ENDPOINT    S3 兼容端点（path-style），如 http://rustfs:9000、https://ros.example.com
#   S3_BUCKET      桶名，默认 im-files（与网关 object_storage.bucket 一致）
#   S3_REGION      签名区域，默认 us-east-1
#   S3_ACCESS_KEY / S3_SECRET_KEY
#   DRY_RUN=1      只打印将要上传的键
set -euo pipefail

src="${1:-stickers-dist}"
endpoint="${S3_ENDPOINT:?S3_ENDPOINT required}"
bucket="${S3_BUCKET:-im-files}"
region="${S3_REGION:-us-east-1}"
prefix="stickers/"

if [[ ! -f "$src/catalog.json" ]]; then
  echo "missing $src/catalog.json" >&2
  exit 1
fi
if [[ -z "${DRY_RUN:-}" ]]; then
  : "${S3_ACCESS_KEY:?S3_ACCESS_KEY required}" "${S3_SECRET_KEY:?S3_SECRET_KEY required}"
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 与网关 stickerPath 的段规则一致。
seg_re='^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'

content_type() {
  case "${1##*.}" in
    json) echo "application/json" ;;
    gif) echo "image/gif" ;;
    png) echo "image/png" ;;
    jpg | jpeg) echo "image/jpeg" ;;
    webp) echo "image/webp" ;;
    *) echo "application/octet-stream" ;;
  esac
}

put() {
  local file="$1" key="$2"
  if [[ -n "${DRY_RUN:-}" ]]; then
    echo "would upload $key"
    return
  fi
  printf 'user = "%s:%s"\n' "$S3_ACCESS_KEY" "$S3_SECRET_KEY" |
    curl -fsS -K - --aws-sigv4 "aws:amz:${region}:s3" \
      -H "Content-Type: $(content_type "$file")" \
      -H "x-amz-content-sha256: UNSIGNED-PAYLOAD" \
      -T "$file" "${endpoint%/}/${bucket}/${key}" >/dev/null
  echo "uploaded $key"
}

strip_urls() {
  python3 - "$1" "$2" <<'PY'
import json, sys
src, dst = sys.argv[1], sys.argv[2]
data = json.load(open(src, encoding="utf-8"))
data.pop("cdn_base", None)
data.pop("base_url", None)
for p in data.get("packs", []):
    p.pop("base_url", None)
    p.pop("zip_url", None)
json.dump(data, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
}

count=0
for dir in "$src"/*/; do
  pack="$(basename "$dir")"
  if [[ ! -f "$dir/pack.json" ]]; then
    continue
  fi
  if [[ ! "$pack" =~ $seg_re ]]; then
    echo "invalid pack id: $pack" >&2
    exit 1
  fi
  for f in "$dir"*; do
    name="$(basename "$f")"
    [[ -f "$f" ]] || continue
    if [[ ! "$name" =~ $seg_re || "$name" == *..* ]]; then
      echo "skip invalid file name: $pack/$name" >&2
      continue
    fi
    if [[ "$name" == "pack.json" ]]; then
      strip_urls "$f" "$tmp/$pack.json"
      f="$tmp/$pack.json"
    fi
    put "$f" "${prefix}${pack}/${name}"
    count=$((count + 1))
  done
done

# 目录最后上传：客户端看到新包时，包内文件已就绪。
strip_urls "$src/catalog.json" "$tmp/catalog.json"
put "$tmp/catalog.json" "${prefix}catalog.json"
echo "done: $((count + 1)) objects -> ${bucket}/${prefix}"
