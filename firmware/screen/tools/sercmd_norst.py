import sys, time, serial

# Read one Muse console line WITHOUT resetting the board.
# sercmd.py opens the port with pyserial's default DTR/RTS=True and only clears them
# afterwards, and COM8 has RTS wired to EN -- that edge is a reset, which wipes the
# RAM-only counters we are trying to read. Setting both lines False *before* open()
# makes pyserial apply them as part of opening, so nothing pulses.
port, line, settle, secs, out = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
s = serial.Serial()
s.port = port
s.baudrate = 115200
s.timeout = 0.2
s.dtr = False
s.rts = False
s.open()
time.sleep(settle)
s.reset_input_buffer()
s.write(("%s\n" % line).encode())
s.flush()
end = time.time() + secs
buf = bytearray()
while time.time() < end:
    buf.extend(s.read(4096))
s.close()
open(out, "wb").write(bytes(buf))
print("reset?", "reset" in bytes(buf).decode("utf-8", "replace").lower())
