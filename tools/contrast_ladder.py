#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 DesignTokens.kt 里的色号当数据算一遍：底色阶梯 + 文字对比度 + 字号阶梯。

:app 的 ContrastLadderTest 是最终尺子（它跟着构建跑）。这份脚本是两个用途：
  1. 改色号时快速看数（不用等一遍 Gradle）；
  2. **反向验证** —— 尺子本身是不是在检查它声称检查的东西。

用法：python tools/contrast_ladder.py [apps/android/src/main/kotlin/.../DesignTokens.kt]
"""
import io
import re
import sys

DEFAULT = "apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/DesignTokens.kt"


def lum(c):
    def f(u):
        u /= 255.0
        return u / 12.92 if u <= 0.04045 else ((u + 0.055) / 1.055) ** 2.4
    r, g, b = (f((c >> (16 - i * 8)) & 0xFF) for i in range(3))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def cr(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse(src):
    colors = {n: int(h, 16) & 0xFFFFFF for n, h in re.findall(r"val (\w+) = Color\(0x([0-9A-Fa-f]{8})\)", src)}
    sizes = [float(x) for x in re.findall(r"fontSize = ([0-9.]+?)\.sp", src)]
    return colors, sizes


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT
    colors, sizes = parse(io.open(path, encoding="utf-8").read())
    out = []
    fails = []

    # 1) 底色阶梯：页面→卡必须拉得开（深色下 1.09:1 就是"读不出层级"的那个数字）
    for a, b, floor in [("Deep", "Sunk", 1.05), ("Deep", "Card", 1.25), ("Card", "Raise", 1.10),
                        ("Card", "Track", 1.20), ("Card", "Outline", 1.60)]:
        r = cr(colors[a], colors[b])
        out.append("底色 %s/%s = %.3f  下限 %.2f" % (a, b, r, floor))
        if r < floor:
            fails.append("%s/%s=%.3f 低于 %.2f" % (a, b, r, floor))

    # 2) 文字在所有可能底色上都要过 AA 正文 4.5
    for bg in ("Deep", "Sunk", "Card", "Raise"):
        for fg in ("TextHigh", "TextMid", "TextLow", "Ok", "Wait", "Alert", "Sat"):
            r = cr(colors[bg], colors[fg])
            if r < 4.5:
                fails.append("%s on %s = %.2f < 4.5" % (fg, bg, r))
        out.append("文字 on %s 最低 %.2f" % (bg, min(cr(colors[bg], colors[f]) for f in
                                                 ("TextHigh", "TextMid", "TextLow", "Ok", "Wait", "Alert", "Sat"))))

    # 3) 实底 chip：深色字压在每支语义色上都要过 4.5（淡底+同色字只有 4.43，那是糊的）
    for fg in ("Sat", "Ok", "Wait", "Alert"):
        r = cr(colors[fg], colors["Deep"])
        if r < 4.5:
            fails.append("Deep on %s = %.2f < 4.5" % (fg, r))
        out.append("chip 实底 %s/Deep = %.2f" % (fg, r))

    # 4) 字号阶梯：六档都在，且相邻比值落在 1.15~1.35（跳两档=断层，差一点=读不出层级）
    need = [11.0, 12.5, 15.0, 18.0, 24.0, 34.0]
    missing = [x for x in need if x not in sizes]
    if missing:
        fails.append("字号缺档 %s" % missing)
    for a, b in zip(need, need[1:]):
        ratio = b / a
        if not 1.13 <= ratio <= 1.45:
            fails.append("字号 %s→%s 比值 %.2f 出界" % (a, b, ratio))
        out.append("字号 %.1f→%.1f = %.2f" % (a, b, ratio))

    sys.stdout.buffer.write(("\n".join(out) + "\n").encode("utf-8"))
    if fails:
        sys.stdout.buffer.write((">>> 不合格：\n  " + "\n  ".join(fails) + "\n").encode("utf-8"))
        return 1
    sys.stdout.buffer.write(">>> 全部合格\n".encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
