#!/usr/bin/env python3
"""给 Muse 那份圆屏车联页造中文字库子集。

Muse 的 CJK 字体只有 16 px 一档（components/muse/fonts/muse_font_cjk_noto_16.c），
车联页要点按、要隔着两米读，16 px 不够；而 LVGL 的 tiny_ttf 能吃 TTF，所以要的
不是位图字体而是"一份够小的 TTF"。整片 Noto 子集是 2.18MB，装不进 app 分区剩下的
1.04MB，所以这里只收车联页真会画出来的字：扫源码里的中文 + ASCII + 常用标点。

规矩：**改了车联页的文案就要重跑这一条**，否则新字落回 16 px 那份全量字体（不是豆腐块，
但一行字里会大小不一）。

用法（这台机器上裸 python 是坏的别名，用 D:\dev\python311）：
    D:/dev/python311/python.exe firmware/screen/tools/muse_font_subset.py \
        D:/dev/tmp/carpet-noto.ttf \
        D:/dev/projects/muse-gadget-sdk-main/esp32/components/muse/carpet \
        D:/dev/tmp/carpet-muse.ttf
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

# 排版要用的标点与符号：中文句子外的全都列出来，别指望源码里恰好出现过
EXTRA = (
    "0123456789"
    " .,:;!?/-–—·・()[]{}<>'\"`~@#$%&*+=_|\\\\"
    "°℃"
    "，。、；：？！""''（）《》【】—…·"
    "秒分时空网络已接入未卡掉线重连首帧"
    "中开心兴奋难过担心犯困思考未知"
    "正常待命待发送已发出确认"
    "你说的本地规则模型答的兜底话术被拦住小智"
    "状态卫星对话表情钟面呼救链路"
    "上一张下交回自动戳一"
)


def collect(root: Path) -> set:
    cps = set()
    for f in sorted(root.rglob("*.c")) + sorted(root.rglob("*.h")):
        text = f.read_text(encoding="utf-8", errors="replace")
        for ch in text:
            o = ord(ch)
            if o > 0x7F:
                cps.add(o)
    for ch in EXTRA:
        cps.add(ord(ch))
    cps.update(range(0x20, 0x7F))   # 可打印 ASCII
    return cps


def coverage(path: Path) -> set:
    f = TTFont(path, lazy=True)
    cmap = f.getBestCmap()
    f.close()
    return set(cmap.keys())


def main() -> int:
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    src, roots, out = (Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
    want = collect(roots)
    have = coverage(src)
    missing = sorted(want - have)
    keep = want & have
    print(f"要 {len(want)} 个码位，源字体里有 {len(keep)} 个")
    if missing:
        print("源字体也造不出的字（这些会在屏上落回缺字，必须换源字体或改文案）：")
        print("  " + "".join(chr(c) for c in missing))

    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["GSUB", "GPOS", "GDEF", "morx", "kern"]
    font = TTFont(str(src))
    ss = subset.Subsetter(options=opts)
    ss.populate(unicodes=sorted(keep))
    ss.subset(font)
    font.save(str(out))
    print(f"写出 {out} = {out.stat().st_size:,} 字节（源 {src.stat().st_size:,}）")
    print(f"app 分区余量 1,044,480 字节 ⇒ 占比 {out.stat().st_size * 100 // 1044480}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
