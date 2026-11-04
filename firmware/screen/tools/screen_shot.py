#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把圆屏的真实像素抓成一张 PNG。

为什么要有这个：显示通路只写不读，串口日志只能证明"固件没报错"，证明不了
"字有没有压住圆屏中线""是不是整片黑"。固件那边开 CONFIG_SCREEN_SHOT_PORT
之后会起一个 /shot.bmp（见 main/screen_shot.c），这个脚本把它取回来转成 PNG。

只用标准库：这台机器的 python 能不能装 Pillow 一直没验过，而这条链路是现场要用的，
不能依赖一个装不上的包。BMP 解码和 PNG 编码（zlib + struct + crc32）都手写在这里。

    python tools/screen_shot.py grab --out shots/01.png      # 自动找屏的 IP
    python tools/screen_shot.py grab --ip 192.168.43.161 --port 8444 --out x.png
"""
import argparse
import json
import os
import re
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib

DEFAULT_PORT = 8444
SERVER_PORT = 8443          # 屏连的那个 ws 服务端（电脑这边），用来反查屏的地址


def fetch_bmp(ip, port, timeout):
    url = "http://%s:%d/shot.bmp" % (ip, port)
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            data = r.read()
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return None, "%s: %s" % (url, e)
    if data[:2] != b"BM":
        return None, "%s 返回的不是 BMP（前 40 字节：%r）" % (url, data[:40])
    return data, None


def parse_netstat(out, server_port, mine):
    """从 netstat 的文本里挑出"连到本机 server_port 的那个已建立连接的远端"。
    单独拎出来是因为中文 Windows 的表头和 IPv6 那几行会干扰肉眼调试，
    而这段规则用一段固定文本就能验，不用真拉一台设备来连。"""
    ips = []
    for line in out.splitlines():
        m = re.match(r"\s*TCP\s+(\S+)\s+(\S+)\s+ESTABLISHED\s+(\S+)", line, re.I)
        if not m:
            continue
        local, remote = m.group(1), m.group(2)
        if local.rsplit(":", 1)[-1] != str(server_port) or ":" not in remote:
            continue
        ip = remote.rsplit(":", 1)[0]
        # 本机自己那几条排掉：浏览器冒充件和本机探针也连 8443，
        # 把它们当屏会白试一次，报的还是个看起来像屏的地址
        if ip.startswith("127.") or ip in mine or ip in ips:
            continue
        ips.append(ip)
    return ips


def candidates():
    """屏是 ws 客户端，主动连电脑这一侧的 8443 —— 所以 netstat 里那条已建立连接的
    远端地址就是屏。比让脚本再拿一份 IP 参数可靠，也避免了"屏重启换了租约"这种情况。"""
    try:
        r = subprocess.run(["netstat", "-ano", "-p", "tcp"], capture_output=True, timeout=10)
        # 中文 Windows 的 netstat 是 GBK。用 text=True 会让读取线程按 UTF-8 解，
        # 异常抛在另一个线程里，主线程只拿到 stdout=None —— 现场看就是"莫名找不到屏"
        out = r.stdout.decode("gbk", "ignore")
    except Exception as e:
        return [], "netstat 跑不起来：%s" % e
    try:
        mine = set(socket.gethostbyname_ex(socket.gethostname())[2])
    except Exception:
        mine = set()
    return parse_netstat(out, SERVER_PORT, mine), None


def decode_bmp(bmp):
    """只吃固件发的那一种：24 位 BI_RGB、自下而上。别的格式直接说清楚，别猜。"""
    if len(bmp) < 54:
        raise ValueError("BMP 短于 54 字节的头")
    off = struct.unpack_from("<I", bmp, 10)[0]
    hsz = struct.unpack_from("<I", bmp, 14)[0]
    w = struct.unpack_from("<i", bmp, 18)[0]
    h = struct.unpack_from("<i", bmp, 22)[0]
    planes = struct.unpack_from("<H", bmp, 26)[0]
    bits = struct.unpack_from("<H", bmp, 28)[0]
    comp = struct.unpack_from("<I", bmp, 30)[0]
    if hsz != 40 or bits != 24 or comp != 0 or planes != 1:
        raise ValueError("不支持的 BMP：位深 %d/压缩 %d/面 %d" % (bits, comp, planes))
    top_down = h < 0
    h = abs(h)
    row = ((w * 3 + 3) // 4) * 4
    px = bmp[off:off + row * h]
    if len(px) < row * h:
        raise ValueError("像素数据短了：要 %d 拿到 %d" % (row * h, len(px)))
    rows = []
    for y in range(h):
        # BMP 的行序是自下而上，PNG 要反过来；每个像素是 BGR，PNG 要 RGB
        src = px[(h - 1 - y if not top_down else y) * row:]
        out = bytearray(b"\x00")          # PNG 每行前面那个 filter type = 0（不过滤）
        for i in range(0, w * 3, 3):
            out.append(src[i + 2])
            out.append(src[i + 1])
            out.append(src[i])
        rows.append(bytes(out))
    return w, h, b"".join(rows)


def encode_png(w, h, raw):
    def chunk(tag, body):
        return (struct.pack(">I", len(body)) + tag + body
                + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


def stats(raw, w, h):
    """亮不亮这件事要在终端里就能看出来。上一版的坑是每帧 ESP_ERR_NO_MEM：
    日志一切正常、堆还有 6.9MB，屏却整片黑 —— 光看串口永远查不出来。
    所以这里按网格抽样（每行每隔若干点取一个），不逐像素扫，省得为这点统计多等一秒。"""
    stride = w * 3 + 1          # raw 每行开头带一个 filter 字节
    x_step = max(1, (w * 3) // 60)
    y_step = max(1, h // 60)
    lit = 0
    n = 0
    for y in range(0, h, y_step):
        row = raw[y * stride + 1: y * stride + 1 + w * 3]
        for i in range(0, len(row) - 2, x_step):
            n += 1
            if row[i] + row[i + 1] + row[i + 2] > 24:
                lit += 1
    return {"sampled": n, "lit_pct": round(100.0 * lit / n, 1) if n else 0.0}


def cmd_wait(a):
    """只等屏连上来（netstat 里出现 8443 的非本机对端），把地址打出来。
    联动台每步都要重起驱动器，屏会掉线重连；不等到它就连着抓，抓到的一定是上一步的旧画面。"""
    deadline = time.time() + a.timeout
    while time.time() < deadline:
        ips, err = candidates()
        if ips:
            print(ips[0])
            return 0
        time.sleep(1)
    print("等 %d 秒没有设备连到本机 %d" % (a.timeout, SERVER_PORT), file=sys.stderr)
    return 1


def cmd_grab(a):
    ips = [a.ip] if a.ip else candidates()[0]
    if not ips:
        print("找不到屏：netstat 里没有连到 %d 的对端。"
              "要么屏还没连上（看串口），要么用 --ip 直接指定。" % SERVER_PORT)
        return 2
    port = a.port
    for ip in ips:
        bmp, err = fetch_bmp(ip, port, a.timeout)
        if bmp is None:
            print("试 %s：%s" % (ip, err))
            continue
        w, h, raw = decode_bmp(bmp)
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, "wb") as f:
            f.write(encode_png(w, h, raw))
        if a.bmp:
            with open(a.bmp, "wb") as f:
                f.write(bmp)
        print(json.dumps({"ip": ip, "png": a.out, "size": [w, h],
                          "bmp_bytes": len(bmp), **stats(raw, w, h)}, ensure_ascii=False))
        return 0
    return 1


def main():
    p = argparse.ArgumentParser()
    g = p.add_subparsers(dest="cmd", required=True)
    s = g.add_parser("grab", help="抓一帧真实像素存成 PNG")
    s.add_argument("--ip", help="屏的地址；不给就从 netstat 的 8443 对端里找")
    s.add_argument("--port", type=int, default=DEFAULT_PORT,
                   help="固件 CONFIG_SCREEN_SHOT_PORT，默认 %d" % DEFAULT_PORT)
    s.add_argument("--out", required=True)
    s.add_argument("--bmp", help="顺手把原始 BMP 也留一份（调试用）")
    s.add_argument("--timeout", type=float, default=10.0,
                   help="整屏 65 万字节走 Wi-Fi，别把超时设太小")
    s.set_defaults(func=cmd_grab)
    w = g.add_parser("wait", help="只等屏连上本机 8443，把它的地址打到 stdout")
    w.add_argument("--timeout", type=float, default=40.0)
    w.set_defaults(func=cmd_wait)
    a = p.parse_args()
    sys.exit(a.func(a))


if __name__ == "__main__":
    main()
