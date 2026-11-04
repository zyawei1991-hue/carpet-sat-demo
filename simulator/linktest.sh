#!/usr/bin/env bash
# 电脑 + 真圆屏的联动测试台：电脑当手机（Kotlin 彩排台），一次跑完每一步并抓屏出图。
#
# 和 capture.sh 的分工要说清楚，别看名字像就当一个用：
#   capture.sh      —— 截 simulator/screen.html，那是同一份契约的浏览器冒充件，快且随便造；
#   linktest.sh(这份) —— 截的是那块 466x466 的 AMOLED。只有它能回答
#                       "56px 的数字压没压住中线""这一帧是不是整片黑"这类只能靠眼睛的问题。
#
# 前置条件（少一条就是白跑）：
#   1) 屏里烧的这版开了抓屏端口：本机 sdkconfig 里 CONFIG_SCREEN_SHOT_PORT=8444，
#      且 sdkconfig.defaults 没带这条 —— 量产镜像不认这个口。
#   2) 屏和电脑在同一个网，屏的 CONFIG_SCREEN_LINK_URL 指向这台电脑的 8443。
#
# 用法：
#   simulator/linktest.sh                # 11 步各抓一张
#   simulator/linktest.sh 5 6 11         # 只抓这几步
#   PORT=8444 SETTLE=5 simulator/linktest.sh
#
# 为什么要**每步单起一次驱动器**而不是让它连跑 11 步再定时抓：
#   脚本这边 sleep 到第 N 步是猜时间，彩排台跑得快慢和屏收帧、渲染、抓屏全在抢，
#   抓到的一定是"差不多那一步"。`--hold N` 是驱动器自己走到那一步再定格，
#   抓到的一定是那一步的界面。代价是一次全跑变成 11 次冷启动，几分钟而已，值。
set -u
cd "$(dirname "$0")/.." || exit 1

PY="${PY:-/d/dev/python311/python.exe}"
SHOT_TOOL="firmware/screen/tools/screen_shot.py"
PORT="${PORT:-8444}"
SETTLE="${SETTLE:-4}"       # 定格后再等几秒：屏的 state 是 1Hz，ui_tick 再 250ms 才画
OUT="shots/real"
SIM="simulator/build/install/simulator/bin/simulator.bat"
STEPS=("$@")
[ ${#STEPS[@]} -gt 0 ] || STEPS=($(seq 1 11))
mkdir -p "$OUT"

[ -f "$SIM" ] || { echo "先 ./gradlew :simulator:installDist（$SIM 不在）"; exit 1; }
[ -f "$SHOT_TOOL" ] || { echo "抓屏工具没找到：$SHOT_TOOL"; exit 1; }

kill_driver() {
  powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8443 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force }" >/dev/null 2>&1
  sleep 1
}

# wait_for <文件> <正则> <最多秒数>：驱动器把那一行打出来才算走到
wait_for() {
  local f="$1" pat="$2" n="${3:-120}" i
  for i in $(seq 1 "$n"); do
    grep -q "$pat" "$f" 2>/dev/null && return 0
    sleep 1
  done
  return 1
}

echo "== 先确认屏连得上：抓一张当前画面 =="
IP=$("$PY" "$SHOT_TOOL" wait --timeout 45 2>/dev/null) || {
  echo
  echo "45 秒里没有设备连到本机 8443。逐条核：屏的串口有没有 '拿到地址' / 'hello 已发'；"
  echo "屏连的 URL 是不是这台电脑（ipconfig 对一下）；热点是不是 2.4GHz（S3 不认 5GHz）。"
  exit 1
}
if ! "$PY" "$SHOT_TOOL" grab --ip "$IP" --port "$PORT" --out "$OUT/probe.png"; then
  echo
  echo "屏连上了（$IP）但抓不到图。核固件这版 CONFIG_SCREEN_SHOT_PORT 是不是 0，"
  echo "以及串口里有没有那条「抓屏已开」。"
  exit 1
fi

: > "$OUT/timeline.md"
{
  echo "# 真屏联动时间线（$(date '+%F %T')）"
  echo
  echo "每行是驱动器走到第 N 步末尾定格的帧。卡名后面是彩排台打印的判定依据。"
  echo
  echo "最后一列亮像素是从 PNG 里抽样的，整片黑会直接掉到 0 —— 上一版那种"
  echo "日志全正常、堆还剩 6.9MB、屏其实一帧都没画出去（每帧 ESP_ERR_NO_MEM）的坑，"
  echo "就靠这一列拦住。"
  echo
  echo "| 步 | 彩排台说的界面 | 亮像素 | 图 |"
  echo "|---|---|---|---|"
} >> "$OUT/timeline.md"

for n in "${STEPS[@]}"; do
  log="$OUT/step$(printf %02d "$n").log"
  png="$OUT/step$(printf %02d "$n").png"
  kill_driver
  "$SIM" --hold "$n" --record "$OUT/step$(printf %02d "$n").jsonl" > "$log" 2>&1 &
  if ! wait_for "$log" "定格在第 ${n} 步" 150; then
    echo "第 $n 步：驱动器 150 秒没定格，看 $log"
    tail -3 "$log"
    continue
  fi
  # 每步都重起驱动器，屏必然掉线重连；不等它连回来就抓，抓到的是上一步的旧画面
  ip=$("$PY" "$SHOT_TOOL" wait --timeout 45 2>/dev/null) || {
    echo "第 $n 步：屏 45 秒没重连回来，跳过（看 $log 和串口）"; continue; }
  sleep "$SETTLE"
  res=$("$PY" "$SHOT_TOOL" grab --ip "$ip" --port "$PORT" --out "$png" 2>&1) || { echo "第 $n 步：抓屏失败 $res"; continue; }
  line=$(grep "^step $n " "$log" | tail -1)
  lit=$(echo "$res" | grep -o '"lit_pct": *[0-9.]*' | cut -d: -f2)
  echo "步 $n  ->  ${line:-（日志里没有 step $n 那行）}  亮像素=${lit:-?}%  图=$png"
  echo "| $n | ${line#*屏=} | ${lit:-?} | [step$(printf %02d "$n").png](step$(printf %02d "$n").png) |" >> "$OUT/timeline.md"
done

kill_driver
echo
echo "时间线和 11 张图在 $OUT/。把 PNG 直接给助手看，它就能判断排版；"
echo "同目录的 stepNN.jsonl 是那一步手机发出去的原始帧，画错了先分清是手机没发对还是屏画错。"
