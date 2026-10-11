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
    gh release upload "$TAG" "${assets[@]}" -R "$REPO" --clobber
else
    flags=()
    if [ "${PRERELEASE:-false}" = "true" ]; then flags+=(--prerelease); fi
    notes="GO-IM KMP 客户端 ${VERSION}（commit ${GIT_COMMIT:0:7}，Jenkins #${BUILD_NUMBER:-local}）

| 文件 | 平台 |
|------|------|
| \`GO-IM-${VERSION}-android.apk\` | Android 8.0+ |
| \`GO-IM-${VERSION}-macos-arm64.dmg\` | macOS（Apple Silicon，未公证：首次打开需右键 → 打开） |
| \`GO-IM-${VERSION}-ios-unsigned.ipa\` | iOS 16+，未签名，需自行重签名或侧载 |"
    gh release create "$TAG" "${assets[@]}" -R "$REPO" --target "$GIT_COMMIT" \
        --title "GO-IM KMP ${VERSION}" --notes "$notes" ${flags[@]+"${flags[@]}"}
fi

gh release view "$TAG" -R "$REPO" --json url -q .url
