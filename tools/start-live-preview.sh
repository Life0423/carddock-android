#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
    printf '用法: %s <adb 设备序列号>\n' "$0" >&2
    exit 2
fi

device="$1"
package='com.life0423.carddock.prototype'
adb_bin='adb'
if [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/adb" ]]; then
    adb_bin="${ANDROID_HOME}/platform-tools/adb"
fi
apk=$("$adb_bin" -s "$device" shell pm path "$package" | tr -d '\r' |
    sed -n 's/^package:\(\/[[:alnum:]_\/~.=-]*\/base\.apk\)$/\1/p')
if [[ -z "$apk" || "$apk" == *$'\n'* ]]; then
    printf '未找到设备上的卡坞 Debug 安装包。\n' >&2
    exit 1
fi
# 设备上同名的安装包不一定来自当前构建。授权 shell 执行前核对已安装 APK 的内容。
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
local_apk="$repo/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$local_apk" ]]; then
    printf '请先构建卡坞 Debug APK。\n' >&2
    exit 1
fi
local_digest=$(shasum -a 256 < "$local_apk")
installed_digest=$("$adb_bin" -s "$device" shell sha256sum "$apk")
if [[ "${local_digest%% *}" != "${installed_digest%% *}" ]]; then
    printf '设备上的 APK 与当前 Debug 构建不一致，请安装刚构建的 APK 后重试。\n' >&2
    exit 1
fi
"$adb_bin" -s "$device" shell "CLASSPATH=$apk nohup app_process /system/bin --nice-name=carddock-preview com.life0423.carddock.platform.mika.LivePreviewHelper $package </dev/null >/data/local/tmp/carddock-preview.log 2>&1 &"
