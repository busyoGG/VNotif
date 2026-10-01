#!/usr/bin/env bash
# 把刚构建好的 APK 发到 ~/Dev/VNotif/dist/，并把 vnotif.apk 指向它。
# 手机浏览器直接开下面的链接即可下载：
#   http://192.168.1.215:8080/apk/            （局域网，HTTP）
#   https://192.168.1.215:8443/apk/           （局域网，自签证书）
#   https://frp-hat.com:37070/apk/            （公网，走 dsh 那条 frp 隧道）
#
# 用法：
#   packaging/publish-apk.sh                  # 用 app/build/outputs/apk/debug/app-debug.apk
#   packaging/publish-apk.sh <apk 路径>        # 指定别的产物
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dist="$repo/dist"
src="${1:-$repo/android/app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$src" ]]; then
  echo "publish-apk: 找不到 $src —— 先在 android/ 里跑 ./gradlew assembleDebug" >&2
  exit 1
fi

# 文件名用源码里的 BUILD_TAG，这样"手机上报的 UA"和"下载的文件名"永远对得上
tag="$(grep -oP 'BUILD_TAG = "\K[^"]+' \
  "$repo/android/app/src/main/java/dev/busyo/vnotif/BridgeService.java" || true)"
if [[ -z "$tag" ]]; then
  echo "publish-apk: 从 BridgeService.java 取不到 BUILD_TAG" >&2
  exit 1
fi

# BUILD_TAG 形如 report14：文件名沿用构建目录里 rN 的习惯
ver="$tag"
[[ "$ver" == report* ]] && ver="r${ver#report}"

name="vnotif-1.0-$ver.apk"
mkdir -p "$dist"
cp -f "$src" "$dist/$name"
ln -sfn "$name" "$dist/vnotif.apk"

echo "已发布：$dist/$name（BUILD_TAG=$tag）"
echo "最新版软链：vnotif.apk -> $name"
sha256sum "$dist/$name" | sed 's/^/sha256: /'
