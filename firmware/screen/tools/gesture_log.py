#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""抓圆屏串口、并且只把手势那几行变成数字。

为什么不用 `idf.py monitor`，也不用管道给 grep：
Windows 控制台把 Python 的 stdout 按 GBK 重编码，UTF-8 的中文日志过这道手要么变问号
要么整条命令静默零输出（上一轮 240 秒采集就是这么"什么都没抓到"的）。
所以这里 **capture 只往文件写原始字节**，解码放到 report 那一步再做。

用带 pyserial 的那份 python（裸 python 和 D:/dev/python311 都没装）：
    D:/dev/esp/idf-tools/python_env/idf5.5_py3.11_env/Scripts/python.exe tools/gesture_log.py capture
"""
import argparse
import re
import sys
import time

# 固件里 swipe_cb 打的那两行（改日志文案要连着这里一起改，不然 report 会报"一行都没抓到"）
RX_PRESS = re.compile(r"按下卡面 \((-?\d+),(-?\d+)\) 当前卡=(\S+)")
RX_RELEASE = re.compile(r"抬手 dx=(-?\d+) dy=(-?\d+) held=(\d+)ms → 判为(\S+?)（阈值 (\d+)px）")
RX_CMD = re.compile(r"command\{(\w+)\} → (\S+)")
RX_DROP = re.compile(r"链路没连上，command\{(\w+)\} 不发")

HEXDUMP_HINT = """按下（按下卡面）没有配抬手（抬手 …）的次数，就是"手指抬起前触摸流断了"的次数；
这两个数只在驱动/坐标出问题时才不相等。判定为"单击"里最大的 |位移| 是阈值的**下界**，
你有意滑动里最小的 |位移| 是阈值的**上界** —— 区间落在哪，SWIPE_MIN_PX 就该定在哪。"""


def capture(port, baud, seconds, out):
    import serial  # 只有 IDF 那份 venv 有；放函数里，report 模式不需要装东西
    deadline = time.time() + seconds
    with serial.Serial(port, baud, timeout=0.1) as ser, open(out, "wb") as f:
        print("[capture] %s @%d，%d 秒 → %s（原始字节，别用文本方式打开）"
              % (port, baud, seconds, out))
        n = 0
        while time.time() < deadline:
            b = ser.read(4096)
            if b:
                f.write(b)
                f.flush()
                n += len(b)
                print("  +%d 字节，累计 %d\r" % (len(b), n), end="", flush=True)
    print("\n[capture] 结束，共 %d 字节" % n)
    return n


def report(raw, quiet):
    with open(raw, "rb") as f:
        text = f.read().decode("utf-8", errors="replace")

    releases = [(int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4), int(m.group(5)))
                for m in RX_RELEASE.finditer(text)]
    presses = RX_PRESS.findall(text)
    cmds = RX_CMD.findall(text)
    drops = RX_DROP.findall(text)

    print("触摸：按下 %d 次 / 抬手 %d 次 %s" % (
        len(presses), len(releases),
        "  ← 两个数不等，说明有手势没走到松手，先查驱动" if len(presses) != len(releases) else ""))
    print("command 发出去 %d 条，链路没通被挡 %d 条" % (len(cmds), len(drops)))
    if drops and not cmds:
        print("  ↑ 全是挡掉的：这一次采集里屏压根没连上手机，滑得再对也不会换卡（那是 #43，不是手势 bug）")

    if not releases:
        print('\n一行「抬手」都没抓到。要么这段串口里没人碰屏，要么日志文案和这里的正则漂了。')
        return 1

    print("\n%-6s %8s %8s %8s %8s" % ("判定", "次数", "最小|dx|", "最大|dx|", "最大|dy|"))
    for verdict in ("横滑", "竖滑", "长按", "单击"):
        rows = [r for r in releases if r[3] == verdict]
        if not rows:
            continue
        ax = [abs(r[0]) for r in rows]
        ay = [abs(r[1]) for r in rows]
        print("%-6s %8d %8d %8d %8d" % (verdict, len(rows), min(ax), max(ax), max(ay)))
    if not quiet:
        print("\n逐条（按 |dx| 降序，方便对着你刚才滑了几下看）：")
        for dx, dy, held, verdict, thr in sorted(releases, key=lambda r: -max(abs(r[0]), abs(r[1])))[:40]:
            print("  dx=%-5d dy=%-5d held=%-6d → %s" % (dx, dy, held, verdict))
        print("\n" + HEXDUMP_HINT)
    return 0


def main():
    p = argparse.ArgumentParser(description="圆屏手势串口：抓原始字节 / 把位移变成阈值建议")
    p.add_argument("--stdout-utf8", action="store_true", help=argparse.SUPPRESS)
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--port", default="COM9")
    c.add_argument("--baud", type=int, default=115200)
    c.add_argument("--seconds", type=int, default=60)
    c.add_argument("--out", default="D:/dev/tmp/serial-raw.bin")
    r = sub.add_parser("report")
    r.add_argument("--raw", default="D:/dev/tmp/serial-raw.bin")
    r.add_argument("--quiet", action="store_true")
    a = p.parse_args()

    # 只改 stdout，不动文件：capture 那条永远不经过这里
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if a.cmd == "capture":
        capture(a.port, a.baud, a.seconds, a.out)
    else:
        sys.exit(report(a.raw, a.quiet))


if __name__ == "__main__":
    main()
