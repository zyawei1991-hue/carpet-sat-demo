#!/usr/bin/env python3
"""Take the settings-page screenshots in one serial session.

tools/muse/snap.py opens the port, sends a key, shoots, and closes - and on
Windows the open/close toggles DTR/RTS, which resets the board (its termios
HUPCL trick only works on POSIX). So every shot there starts from a cold boot
and lands in the half-second between the settings page being built and its
values being filled: the PNG shows a page with empty rows, which reads as a
translation miss when it is only a race.

Here the port is opened once, the board is given time to boot, and each page
is asked for and then waited out before 'p' is sent. decode_snapshot comes
from snap.py itself so the pixels are the same bytes that tool would check.

Usage: python zh_shot.py COM8 out_dir page page ...   (page = home wifi muse
ble sound sleep battery power; a "line:cmd" item sends that console command
instead and prints what came back, so `>zh` can be asked in the same boot as
the pages that fill its hit counters)
"""
import os
import struct
import sys
import time
import zlib

sys.path.insert(0, "D:/dev/projects/muse-gadget-sdk-main/esp32/tools/muse")
from snap import SCALE, decode_snapshot          # noqa: E402
import serial                                    # noqa: E402


def png(path, w, h, raw):
    img = b""
    for y in range(h):
        line = bytearray([0])
        for x in range(w):
            v = struct.unpack_from("<H", raw, (y * w + x) * 2)[0]
            line += bytes([(v >> 11) << 3, ((v >> 5) & 63) << 2, (v & 31) << 3]) * SCALE
        img += bytes(line) * SCALE

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data))

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", w * SCALE, h * SCALE, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(img)) + chunk(b"IEND", b""))


def main():
    port, out_dir = sys.argv[1], sys.argv[2]
    pages = sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)
    s = serial.Serial(port, 115200, timeout=0.2)

    def rd(t, *until):
        end = time.time() + t
        o = b""
        while time.time() < end and not any(u in o for u in until):
            o += s.read(65536)
        return o

    rd(12, b"muse:")            # the port open reset the board; let it boot
    free = 0
    for page in pages:
        if page.startswith("line:"):        # line:>zh - any console command, no shot
            s.write((">" + page[5:] + "\n").encode())
            s.flush()
            print(rd(6, b"\n\n").decode("utf-8", "replace"))
            continue
        if page.startswith("wait:"):        # the board's own seconds, not ours
            time.sleep(float(page[5:]))
            continue
        if page == "shot":                  # whatever is on screen right now
            page = "shot%d" % free
            free += 1
        else:
            s.write((">ui.settings=%s\n" % page).encode())
            s.flush()
            time.sleep(3.0)         # the page is built, then ticked, then shot
        s.write(b"p")
        log = rd(10, b"SNAP BEGIN")
        m = None
        import re
        m = re.search(rb"SNAP BEGIN (\d+) (\d+)", log)
        if not m:
            print("%s: no screenshot (%s)" % (page, log[-60:].decode("utf-8", "replace")))
            continue
        size = int(m.group(1)) * int(m.group(2)) * 2 * 4 / 3 * 1.01
        log += rd(10 + size * 10 / 115200, b"SNAP END")
        try:
            w, h, raw = decode_snapshot(log.decode("latin1"))
        except ValueError as error:
            print("%s: %s" % (page, error))
            continue
        out = os.path.join(out_dir, "zh_%s.png" % page)
        png(out, w, h, raw)
        print("%s: %dx%d -> %s" % (page, w, h, out))
    s.close()


if __name__ == "__main__":
    main()
