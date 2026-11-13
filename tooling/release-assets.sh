#!/bin/sh
# 把构建出的 Release 包整理成发布资产名,供上传到 GitHub 发布(wangzan101/Mika,tag yuan-v<版本>)。
#
# 更新机制按名字挑包(见 app/.../about/Releases.kt 的 ABI_APK_SUFFIX):
#   如愿-<版本>-arm64-v8a-release.apk  → 如愿-v<版本>-arm64.apk   (arm64 设备专用包,更小)
#   如愿-<版本>-universal-release.apk  → 如愿-v<版本>.apk          (全架构包,兜底)
#
# 用法: tooling/release-assets.sh <版本号> [输出目录]
#   例: tooling/release-assets.sh 0.1.45   # 版本号与 app/build.gradle.kts 的 ruyiVersionName 一致
set -eu

VER="${1:?用法: release-assets.sh <版本号,如 0.1.45>}"
SRC="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/release"
OUT="${2:-$SRC}"

for f in "如愿-$VER-arm64-v8a-release.apk" "如愿-$VER-universal-release.apk"; do
    [ -f "$SRC/$f" ] || { echo "缺产物: $SRC/$f (先 ./gradlew :app:assembleRelease)" >&2; exit 1; }
done

mkdir -p "$OUT"
cp "$SRC/如愿-$VER-arm64-v8a-release.apk" "$OUT/如愿-v$VER-arm64.apk"
cp "$SRC/如愿-$VER-universal-release.apk" "$OUT/如愿-v$VER.apk"

echo "发布资产已就绪(上传到 wangzan101/Mika 的 yuan-v$VER 发布):"
ls -lh "$OUT/如愿-v$VER-arm64.apk" "$OUT/如愿-v$VER.apk"
