import sys, time, serial

# Open COM8 the way sercmd.py does -- pyserial's default DTR/RTS pulse on EN is a
# board reset -- but keep everything from the moment of that reset. That is what we
# want right after a flash: the boot log, including the lines that prove the
# PSRAM segment buffers and the sender task actually came up (the OOM lesson from
# the audio playback task says "built" and "running" are different claims).
port, secs, out = sys.argv[1], float(sys.argv[2]), sys.argv[3]
# Same open as sercmd.py: pyserial brings DTR/RTS up as part of opening, and on this
# bridge RTS is wired to EN -- that edge is the reset. Lowering them right after lets
# the chip come back out of reset on its own. (Opening with both already low, the way
# serwatch.py does, reads the running board without rebooting it -- which is the other
# tool's point, not this one's.)
s = serial.Serial(port, 115200, timeout=0.2)
s.dtr = False
s.rts = False
end = time.time() + secs
buf = bytearray()
while time.time() < end:
    buf.extend(s.read(4096))
s.close()
data = bytes(buf)
open(out, "wb").write(data)
print("bytes=%d" % len(data))
