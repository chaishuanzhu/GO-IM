#!/usr/bin/env bash
# 对 createDistributable 产出的未签名 .app 做 Developer ID 签名，打成 DMG，公证并装订。
#
# 用法：macos-sign-notarize.sh <GO-IM.app> <输出 .dmg>
# 环境变量：DEVID_P12（Developer ID Application 证书 + 私钥）、DEVID_P12_PASSWORD 必填；
#           NOTARIZE=false 时只签名不公证。公证用 asc 当前登录的 App Store Connect API Key。
#
# 不用 Compose / jpackage 自带的签名：它们对每个文件只调用一次 codesign --timestamp，
# Apple 时间戳服务偶尔超时（约 3% 的请求）就会让整次打包失败，这里逐个文件重试。
set -euo pipefail

APP="${1:?用法：$0 <GO-IM.app> <输出 .dmg>}"
DMG="${2:?用法：$0 <GO-IM.app> <输出 .dmg>}"
: "${DEVID_P12:?}" "${DEVID_P12_PASSWORD:?}"
APP="$(cd "$APP" && pwd)"
ENTITLEMENTS="$(cd "$(dirname "$0")/.." && pwd)/app/desktop/macos/entitlements.plist"
NAME="$(basename "$APP" .app)"

WORK=$(mktemp -d)
KC="$WORK/signing.keychain-db"
KC_PASSWORD=$(uuidgen)
ORIG_KEYCHAINS=$(security list-keychains -d user | tr -d '"')
cleanup() {
    # shellcheck disable=SC2086
    security list-keychains -d user -s $ORIG_KEYCHAINS || true
    security delete-keychain "$KC" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

security create-keychain -p "$KC_PASSWORD" "$KC"
security set-keychain-settings -lut 3600 "$KC"
security unlock-keychain -p "$KC_PASSWORD" "$KC"
security import "$DEVID_P12" -k "$KC" -f pkcs12 -P "$DEVID_P12_PASSWORD" -T /usr/bin/codesign -T /usr/bin/security
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KC_PASSWORD" "$KC" > /dev/null
# codesign 只在搜索列表里找证书链
# shellcheck disable=SC2086
security list-keychains -d user -s "$KC" $ORIG_KEYCHAINS
IDENTITY=$(security find-identity -v -p codesigning "$KC" | sed -n 's/.*"\(Developer ID Application: .*\)"$/\1/p' | head -1)
[ -n "$IDENTITY" ] || { echo "DEVID_P12 里没有 Developer ID Application 证书" >&2; exit 1; }
echo "签名身份：$IDENTITY"

sign() {
    local i
    for i in 1 2 3 4 5 6; do
        codesign --force --timestamp --options runtime --keychain "$KC" --sign "$IDENTITY" "$@" && return 0
        echo "codesign 第 $i 次失败，重试：${*: -1}" >&2
        sleep $((i * 2))
    done
    return 1
}

is_macho() { file -b "$1" | grep -q 'Mach-O'; }

# 1. jar 内的原生库（JNA、SQLite 等）：公证会检查 jar 里的 Mach-O
for jar in "$APP"/Contents/app/*.jar; do
    entries=$(unzip -Z1 "$jar" | grep -E '\.(dylib|jnilib)$' || true)
    [ -n "$entries" ] || continue
    dir="$WORK/jar"; rm -rf "$dir"; mkdir -p "$dir"
    # shellcheck disable=SC2086
    unzip -q "$jar" $entries -d "$dir"
    while IFS= read -r e; do sign "$dir/$e"; done <<< "$entries"
    # shellcheck disable=SC2086
    (cd "$dir" && zip -q "$jar" $entries)
done

# 2. 其余 Mach-O（运行时的 dylib、jspawnhelper、解出的 skiko 等），可执行文件带 entitlements；主程序最后随 .app 一起签
while IFS= read -r -d '' f; do
    [ "$f" = "$APP/Contents/MacOS/$NAME" ] && continue
    is_macho "$f" || continue
    if [ -x "$f" ] && ! [[ "$f" == *.dylib || "$f" == *.jnilib ]]; then
        sign --entitlements "$ENTITLEMENTS" "$f"
    else
        sign "$f"
    fi
done < <(find "$APP/Contents" -type f -print0)

# 3. 运行时 bundle 和 .app 本身
[ -d "$APP/Contents/runtime" ] && sign "$APP/Contents/runtime"
sign --entitlements "$ENTITLEMENTS" "$APP"
codesign --verify --deep --strict --verbose=2 "$APP"

# 4. DMG：.app + 「应用程序」快捷方式
STAGE="$WORK/dmg"; mkdir -p "$STAGE"
ditto "$APP" "$STAGE/$NAME.app"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -quiet -volname "$NAME" -srcfolder "$STAGE" -fs HFS+ -format UDZO -ov "$DMG"
sign "$DMG"

[ "${NOTARIZE:-true}" = "true" ] || { echo "跳过公证"; exit 0; }

# 5. 公证 + 装订
# 上传到 Apple 的 S3 偶尔断开（EOF）：没拿到 Invalid / Rejected 结论就重新提交
STATUS=""
for i in 1 2 3 4; do
    asc notarization submit --file "$DMG" --wait --poll-interval 20s --timeout 30m --output json > "$WORK/notarize.json" || true
    cat "$WORK/notarize.json"; echo
    STATUS=$(jq -r '.status // .data.attributes.status // empty' "$WORK/notarize.json" 2>/dev/null || true)
    case "$STATUS" in
        Accepted) break ;;
        Invalid|Rejected)
            ID=$(jq -r '.submissionId // .id // .data.id // empty' "$WORK/notarize.json" 2>/dev/null || true)
            [ -n "$ID" ] && asc notarization log --id "$ID" || true
            echo "公证未通过：$STATUS" >&2
            exit 1 ;;
    esac
    echo "公证提交第 $i 次未完成（${STATUS:-无结果}），重试" >&2
    sleep $((i * 15))
done
[ "$STATUS" = "Accepted" ] || { echo "公证未通过：${STATUS:-unknown}" >&2; exit 1; }
for i in 1 2 3; do xcrun stapler staple "$DMG" && break; [ "$i" = 3 ] && exit 1; sleep 10; done
xcrun stapler validate "$DMG"
spctl --assess --type open --context context:primary-signature -v "$DMG"
