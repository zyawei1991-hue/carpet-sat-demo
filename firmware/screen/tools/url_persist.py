#!/usr/bin/env python3
"""R6's persistence half: type an access point, reboot the board, ask what it
dials now.

`>url ws://...` writes NVS and re-dials without a restart, so the row's whole
point - not reflashing to change an address - is only proved if the address is
still there after a cold boot and `>carpet` says src=nvs rather than
src=factory. The reset is the DTR/RTS pulse snap.py uses, done on purpose.

Whether the link then comes up (linked=1) is a different question: it needs a
server on the same subnet as the board, which the PC is not - see HANDOFF.

Usage: python url_persist.py COM8 ws://192.168.1.17:8443
"""
import sys
import time
import serial

port, url = sys.argv[1], sys.argv[2]
s = serial.Serial(port, 115200, timeout=0.2)


def rd(t):
    end = time.time() + t
    o = b""
    while time.time() < end:
        o += s.read(65536)
    return o.decode("utf-8", "replace")


print("--- before:", rd(2))
s.write((">url %s\n" % url).encode())
s.flush()
print("--- after >url:", rd(6))
s.write(b">carpet\n")
s.flush()
print("--- >carpet:", rd(4))

s.dtr = False
s.rts = True          # DTR low with RTS high holds the chip in reset
time.sleep(0.3)
s.rts = False         # released: it boots from here
print("--- reset pulsed")
print("--- boot log:", rd(15))
s.write(b">carpet\n")
s.flush()
print("--- >carpet after reboot:", rd(6))
s.close()
