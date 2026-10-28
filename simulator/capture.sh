#!/usr/bin/env bash
# 抓演示静帧。每帧单起一次驱动器：走到指定假秒定格，把出站消息录成 jsonl，
# 再用 headless Chrome 打开 ?replay= 的回放页截图 —— 不抢 ws 首帧，画面是确定的。
#
# 用法: simulator/capture.sh              # 抓全套
#       simulator/capture.sh 34 4-locked  # 只抓一帧
set -u
cd "$(dirname "$0")/.." || exit 1
# 工具链在仓库外，见 docs/DECISIONS.md D6
source /c/Users/Administrator/sdk/env.sh >/dev/null 2>&1
CHROME="/c/Program Files/Google/Chrome/Application/chrome.exe"
OUT=shots
mkdir -p "$OUT"

kill_driver() {
  powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force }" >/dev/null 2>&1
  sleep 1
}

# capture <假秒> <文件名> [驱动器参数...]
capture() {
  local sec="$1" name="$2"; shift 2
  kill_driver
  ( simulator/build/install/simulator/bin/simulator.bat --freeze-sec "$sec" --record "$OUT/$name.jsonl" "$@" > "$OUT/$name.log" 2>&1 ) &
  local i
  for i in $(seq 1 120); do
    grep -q "定格在" "$OUT/$name.log" 2>/dev/null && break
    sleep 1
  done
  grep "定格在" "$OUT/$name.log" | tail -1
  local url
  url=$(grep -o 'http://127.0.0.1:[0-9]*' "$OUT/$name.log" | head -1)
  [ -n "$url" ] || { echo "$name: 驱动器没起来，看 $OUT/$name.log"; return 1; }
  "$CHROME" --headless=new --disable-gpu --no-proxy-server --hide-scrollbars \
    --window-size=980,620 --timeout=15000 \
    --screenshot="C:\\Users\\Administrator\\.lark-channel-workspaces\\qoder\\default\\carpet-sat-demo\\$OUT\\$name.png" \
    "$url/?replay=$name.jsonl" 2>&1 | grep -o 'bytes written.*' || echo "$name: 截图失败"
}

if [ $# -ge 2 ]; then
  capture "$1" "$2" "${@:3}"
else
  # 冷启动那次 Chrome 要建 profile，回放 fetch 还没回来就截图，会得到一帧占位图。先空跑一次热好
  "$CHROME" --headless=new --disable-gpu --no-proxy-server --dump-dom about:blank >/dev/null 2>&1
  # 假秒对照脚本：0-6 蜂窝 / 11 切卫星 / 24 窗口打开锁定 / 38 按 SOS / 42 掉线 / 54 恢复
  capture 4  1-cell
  capture 14 2-sat-switch
  capture 22 3-countdown
  capture 27 4-locked
  capture 40 5-sos-acked
  capture 52 6-offline
  capture 61 7-recover
  capture 40 8-sos-noack --blocked
  kill_driver
fi
ls -l "$OUT"/*.png
