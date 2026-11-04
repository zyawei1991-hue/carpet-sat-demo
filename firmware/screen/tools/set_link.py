#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""改 sdkconfig 里那四条"现场值"，不用开 menuconfig 翻三层菜单。

为什么要有这个脚本：`sdkconfig` 是 gitignored 的、里面装着演示 Wi-Fi 的明文密码，
所以它**永远不进仓库**，每次换网络都得手工改一遍；而手工改正体 SSID 最容易出错的是
引号和转义（`"吨吨吨的Mate 30 5G"` 这种带空格的必须整对引号），错了的现象是屏一直连不上
却看不出为什么。这里只动那四行，别的一个字不碰，改完打印"旧值 → 新值"（密码只打印长度）。

密码不要写进任何会被提交的文件，包括这份脚本的调用历史以外的地方。
    <idf venv python> tools/set_link.py --print-only
    <idf venv python> tools/set_link.py --url ws://10.10.1.216:8443 --ssid 247-Membe --psk 'xxxx'
改完要 `idf.py build` 才生效（kconfig 会把这四行当已有值继承，不会退回 defaults）。
"""
import argparse
import re
import sys
from pathlib import Path

SDK = Path(__file__).resolve().parents[1] / "app" / "sdkconfig"

# (Kconfig 名, 允许的值形状)
KEYS = {
    "CONFIG_SCREEN_LINK_URL": r'"[^"]*"',
    "CONFIG_SCREEN_WIFI_SSID": r'"[^"]*"',
    "CONFIG_SCREEN_WIFI_PSK": r'"[^"]*"',
    "CONFIG_SCREEN_SHOT_PORT": r"\d+",
}


def show(name, value):
    """密码只报长度：这台机器的终端记录会进日志，全文没必要。"""
    if "PSK" in name:
        return "«%d 字符»" % len(value) if value else "(空)"
    return value


def main():
    p = argparse.ArgumentParser(description="改 sdkconfig 的现场四值（URL / SSID / PSK / 抓屏口）")
    p.add_argument("--url", help='形如 ws://10.10.1.216:8443，必须是 ws 不是 wss')
    p.add_argument("--ssid")
    p.add_argument("--psk")
    p.add_argument("--shot", type=int, help="抓屏端口：验像素时 8444，上展台前必须回 0")
    p.add_argument("--print-only", action="store_true")
    a = p.parse_args()

    if not SDK.exists():
        sys.exit("没有 %s —— 先 idf.py build 生成一份，或检查脚本是不是被搬出工程了" % SDK)

    lines = SDK.read_text(encoding="utf-8").splitlines(keepends=True)
    found = {}
    for i, ln in enumerate(lines):
        m = re.match(r"^(CONFIG_\w+)=(.*)$", ln.rstrip("\n"))
        if m and m.group(1) in KEYS:
            found[m.group(1)] = (i, m.group(2))

    if a.print_only:
        for k in KEYS:
            print("%-28s = %s" % (k, show(k, found.get(k, (0, ""))[1].strip('"'))))
        print("\n(缺的键 = 这条在 sdkconfig 里还没被生成，跑一次 idf.py build 或 menuconfig 会补上)")
        return

    if not any([a.url, a.ssid, a.psk, a.shot is not None]):
        sys.exit("没给要改的值。想先看现状用 --print-only")
    if a.url and not a.url.startswith("ws://"):
        sys.exit("URL 必须是 ws:// 开头。写成 wss:// 会卡在 TLS 握手，报错很难看（HANDOFF §0 第三条）")

    changed = []
    for name, val in (("CONFIG_SCREEN_LINK_URL", a.url), ("CONFIG_SCREEN_WIFI_SSID", a.ssid),
                      ("CONFIG_SCREEN_WIFI_PSK", a.psk)):
        if val is None:
            continue
        if '"' in val:
            sys.exit("%s 里不能带英文双引号，会把 sdkconfig 那行截断" % name)
        changed.append((name, '"%s"' % val))
    if a.shot is not None:
        changed.append(("CONFIG_SCREEN_SHOT_PORT", str(a.shot)))

    for name, new in changed:
        old = found.get(name, (None, ""))[1]
        if old and old == new:
            print("%s 本来就是 %s" % (name, show(name, new.strip('"'))))
            continue
        if old:
            lines[found[name][0]] = "%s=%s\n" % (name, new)
        else:
            lines.append("%s=%s\n" % (name, new))
        print("%s: %s → %s" % (name, show(name, old.strip('"')), show(name, new.strip('"'))))

    SDK.write_text("".join(lines), encoding="utf-8")
    print("\n写好了。生效还要 idf.py build + app-flash（sdkconfig 不进仓库，另一台电脑得自己设一遍）")
    if a.shot and a.shot != 0:
        print("注意：抓屏口 %d 是未鉴权的 /shot.bmp，同网段任何人都能读走整屏 —— 验完改回 0" % a.shot)


if __name__ == "__main__":
    main()
