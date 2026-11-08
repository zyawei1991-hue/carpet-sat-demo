#!/usr/bin/env python3
"""Send console lines to the board in ONE serial session and print the replies.

One session matters: opening the port pulses DTR/RTS and resets the chip, so a
script that reconnects per line measures a fresh boot every time and loses
whatever state the previous line set up.

Usage: python serial_probe.py COM8 wait_s ">line" ">line" ...
       (the '>' is optional; it is sent as the console expects it)
"""
import sys
import time
import serial

port, gap = sys.argv[1], float(sys.argv[2])
lines = sys.argv[3:]
s = serial.Serial(port, 115200, timeout=0.2)


def rd(t):
    end = time.time() + t
    o = b""
    while time.time() < end:
        o += s.read(65536)
    return o.decode("utf-8", "replace")


rd(12)                      # the open reset the board; let it boot
for line in lines:
    if not line.startswith(">"):
        line = ">" + line
    print("\n=== %s" % line)
    s.write((line + "\n").encode())
    s.flush()
    print(rd(gap).strip())
s.close()
