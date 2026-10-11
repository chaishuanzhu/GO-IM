#!/usr/bin/env bash
# 把 dist/ 下的产物发布到 GitHub Release（tag 不存在则在 GIT_COMMIT 上创建；已存在则覆盖同名产物）。
#
# 环境变量：GH_TOKEN、TAG、VERSION、GIT_COMMIT 必填；PRERELEASE=true 时标为预发布；GITHUB_REPO 默认 chaishuanzhu/GO-IM。
set -euo pipefail

: "${GH_TOKEN:?}" "${TAG:?}" "${VERSION:?}" "${GIT_COMMIT:?}"
REPO="${GITHUB_REPO:-chaishuanzhu/GO-IM}"
DIST="${DIST:-dist}"

shopt -s nullglob
assets=("$DIST"/GO-IM-*)
if [ ${#assets[@]} -eq 0 ]; then
    echo "没有可发布的产物：$DIST" >&2
    exit 1
fi

(cd "$DIST" && shasum -a 256 GO-IM-* > SHA256SUMS.txt)
assets+=("$DIST/SHA256SUMS.txt")

if gh release view "$TAG" -R "$REPO" >/dev/null 2>&1; then
    echo "Release $TAG 已存在，覆盖上传产物"
else
    flags=()
    if [ "${PRERELEASE:-false}" = "true" ]; then flags+=(--prerelease); fi
    notes="GO-IM KMP 客户端 ${VERSION}（commit ${GIT_COMMIT:0:7}，Jenkins #${BUILD_NUMBER:-local}）

| 文件 | 平台 |
|------|------|
| \`GO-IM-${VERSION}-android.apk\` | Android 8.0+ |
| \`GO-IM-${VERSION}-macos-arm64.dmg\` | macOS（Apple Silicon，未公证：首次打开需右键 → 打开） |
| \`GO-IM-${VERSION}-ios-unsigned.ipa\` | iOS 16+，未签名，需自行重签名或侧载 |"
    gh release create "$TAG" -R "$REPO" --target "$GIT_COMMIT" \
        --title "GO-IM KMP ${VERSION}" --notes "$notes" ${flags[@]+"${flags[@]}"}
fi

# 到 GitHub 的上传偶发超时（HTTP 408 / SSL 断开），逐个上传并重试
for asset in "${assets[@]}"; do
    for attempt in 1 2 3 4 5; do
        if gh release upload "$TAG" "$asset" -R "$REPO" --clobber; then
            echo "已上传 $(basename "$asset")"
            break
        fi
        if [ "$attempt" -eq 5 ]; then
            echo "上传失败：$asset" >&2
            exit 1
        fi
        echo "上传 $(basename "$asset") 失败，$((attempt * 15))s 后重试（$attempt/5）" >&2
        sleep $((attempt * 15))
    done
done

gh release view "$TAG" -R "$REPO" --json url -q .url
