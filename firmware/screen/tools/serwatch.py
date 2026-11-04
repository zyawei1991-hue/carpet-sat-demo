import sys, time, serial

# Capture COM8 for N seconds WITHOUT resetting the board and WITHOUT typing a
# command. Same trick as sercmd_norst.py: DTR/RTS must be lowered *before* open(),
# otherwise the RTS->EN line pulses and the board reboots -- which would erase the
# very watchdog counters we are trying to observe.
port, secs, out = sys.argv[1], float(sys.argv[2]), sys.argv[3]
s = serial.Serial()
s.port = port
s.baudrate = 115200
s.timeout = 0.2
s.dtr = False
s.rts = False
s.open()
s.reset_input_buffer()
end = time.time() + secs
buf = bytearray()
while time.time() < end:
    buf.extend(s.read(4096))
s.close()
data = bytes(buf)
open(out, "wb").write(data)
print("bytes=%d reset=%s" % (len(data), b"boot:" in data or b"esp_rom_print" in data))
