#!/bin/bash
# 部署到 Galaxy Watch 7。
#
# 为什么这么绕：这台 Mac 到手表的网络不通（实测 Mac→表 必超时，NAS→表 正常），
# 所以 adb 必须在飞牛 NAS 上跑，Mac 只负责构建和驱动。
# 手表息屏后 Wi-Fi 睡死、adbd 不响应，而且无线调试端口每次重启都变，
# 所以要先发现再连。
#
# 用法：
#   ./deploy.sh          构建 + 等待手表 + 安装 + 启动
#   ./deploy.sh log      把表上的文件日志抓回来（蓝牙盲测后用这个）
#   ./deploy.sh session  把桌面会话注入到表里（登录走不通时的兜底）
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
NAS=feiniu
PKG=dev.liji.mihome
APK="$DIR/wear/build/outputs/apk/debug/wear-debug.apk"
WAIT_MIN=${WAIT_MIN:-15}

nas() { /usr/bin/ssh "$NAS" "export PATH=\$HOME/tools/platform-tools:\$PATH; $*"; }

# 发现并连接手表。返回时 adb 已有一台在线设备。
connect() {
    # get-state 恰好在有且仅有一台在线设备时输出 device，省掉解析 adb devices 的引号地狱
    if [ "$(nas 'adb get-state 2>/dev/null' 2>/dev/null || true)" = device ]; then
        echo '✓ 手表已连接'; return 0
    fi

    echo "等待手表上线（最多 ${WAIT_MIN} 分钟）……"
    echo "请确认：① 表放上充电器或保持亮屏  ② 设置→开发者选项→无线调试 已开"
    local deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local target
        target=$(nas "timeout 10 adb mdns services 2>/dev/null | grep _adb-tls-connect | awk '{print \$3}' | head -1" || true)
        if [ -n "$target" ]; then
            echo "发现 $target，连接中……"
            if nas "adb connect $target" | grep -qE 'connected'; then
                echo '✓ 已连接'
                return 0
            fi
        fi
        printf '.'
        sleep 20
    done
    echo; echo '✗ 超时：手表始终没上线'; return 1
}

case "${1:-deploy}" in
deploy)
    echo '=== 构建 ==='
    JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}" \
        "$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.14.5-bin/*/gradle-8.14.5/bin/gradle | head -1)" \
        -p "$DIR" :wear:assembleDebug --console=plain -q
    ls -lh "$APK"

    connect
    echo '=== 传输 ==='
    /usr/bin/scp -q "$APK" "$NAS:/tmp/mi-watch.apk"

    echo '=== 安装 ==='
    # --no-streaming 是必需的：流式安装在这块表上会以空错误信息失败。
    # -r 保留数据：卸载重装会清掉已存的 passToken。
    nas "adb install -r -t --no-streaming /tmp/mi-watch.apk"

    echo '=== 启动 ==='
    nas "adb shell am start -n $PKG/.MainActivity"
    echo '✓ 完成'
    ;;

log)
    connect
    nas "adb shell run-as $PKG cat files/log.txt" 2>/dev/null || {
        echo '（run-as 取不到，试 logcat）'
        nas "adb logcat -d -s mi-watch:* | tail -100"
    }
    ;;

session)
    BLOB=$("$DIR/mi" session)
    [ -z "$BLOB" ] && { echo '✗ 桌面还没有会话，先跑 ./mi login-qr'; exit 1; }
    connect
    nas "adb shell am start -n $PKG/.MainActivity --es session '$BLOB'"
    echo '✓ 会话已注入'
    ;;

*)
    echo "用法: $0 [deploy|log|session]"; exit 1 ;;
esac
