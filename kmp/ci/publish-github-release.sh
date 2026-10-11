#!/usr/bin/env bash
# 把 dist/ 下的产物发布到 GitHub Release（tag 不存在则在 GIT_COMMIT 上创建；已存在则覆盖同名产物）。
#
# 环境变量：GH_TOKEN、TAG、VERSION、GIT_COMMIT 必填；PRERELEASE=true 时标为预发布；GITHUB_REPO 默认 chaishuanzhu/GO-IM。
set -euo pipefail

: "${GH_TOKEN:?}" "${TAG:?}" "${VERSION:?}" "${GIT_COMMIT:?}"
REPO="${GITHUB_REPO:-chaishuanzhu/GO-IM}"
DIST="${DIST:-dist}"
ATTEMPTS=5

shopt -s nullglob
assets=("$DIST"/GO-IM-*)
if [ ${#assets[@]} -eq 0 ]; then
    echo "没有可发布的产物：$DIST" >&2
    exit 1
fi

(cd "$DIST" && shasum -a 256 GO-IM-* > SHA256SUMS.txt)
assets+=("$DIST/SHA256SUMS.txt")

# 到 GitHub 的连接偶发断开（EOF / HTTP 408 / SSL），每个请求都重试
backoff() {
    if [ "$1" -eq "$ATTEMPTS" ]; then
        echo "$2：已重试 $ATTEMPTS 次，放弃" >&2
        exit 1
    fi
    echo "$2 失败，$(($1 * 15))s 后重试（$1/${ATTEMPTS}）" >&2
    sleep $(($1 * 15))
}

flags=()
if [ "${PRERELEASE:-false}" = "true" ]; then flags+=(--prerelease); fi
notes="GO-IM KMP 客户端 ${VERSION}（commit ${GIT_COMMIT:0:7}，Jenkins #${BUILD_NUMBER:-local}）

| 文件 | 平台 |
|------|------|
| \`GO-IM-${VERSION}-android.apk\` | Android 8.0+ |
| \`GO-IM-${VERSION}-macos-arm64.dmg\` | macOS（Apple Silicon，未公证：首次打开需右键 → 打开） |
| \`GO-IM-${VERSION}-ios-unsigned.ipa\` | iOS 16+，未签名，需自行重签名或侧载 |"

# 查询失败也可能只是网络问题：创建失败（含「已存在」）时下一轮重新查询
for attempt in $(seq 1 $ATTEMPTS); do
    if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
        echo "Release $TAG 已存在，覆盖上传产物"
        break
    fi
    if gh release create "$TAG" -R "$REPO" --target "$GIT_COMMIT" \
        --title "GO-IM KMP ${VERSION}" --notes "$notes" ${flags[@]+"${flags[@]}"}; then
        break
    fi
    backoff "$attempt" "创建 Release $TAG"
done

for asset in "${assets[@]}"; do
    for attempt in $(seq 1 $ATTEMPTS); do
        if gh release upload "$TAG" "$asset" -R "$REPO" --clobber; then
            echo "已上传 $(basename "$asset")"
            break
        fi
        backoff "$attempt" "上传 $(basename "$asset")"
    done
done

echo "https://github.com/$REPO/releases/tag/$TAG"
