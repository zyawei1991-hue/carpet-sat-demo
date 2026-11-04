import sys, time
import serial

# Send one Muse console line and capture what comes back.
# COM8 is a USB-UART bridge with RTS wired to EN and DTR to IO0 (see sercap.py), so
# opening the port pulses a reset: wait out the boot before writing, or the command
# lands while the console task isn't up yet and gets dropped.
port, line, secs = sys.argv[1], sys.argv[2], float(sys.argv[3])
s = serial.Serial(port, 115200, timeout=0.2)
s.dtr = False
s.rts = False
s.reset_input_buffer()
time.sleep(6.0)
s.reset_input_buffer()
s.write(("%s\n" % line).encode())
s.flush()
end = time.time() + secs
buf = bytearray()
while time.time() < end:
    buf.extend(s.read(4096))
s.close()
out = bytes(buf)
open(sys.argv[4], "wb").write(out)
sys.stdout.write(out.decode("utf-8", "replace")[-1800:])
