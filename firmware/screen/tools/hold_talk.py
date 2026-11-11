import sys, time, serial

# Hold the talk button for N seconds WITHOUT resetting the board.
#
# 'd' / 'u' on the raw console are the bench stand-ins for the TALK button going
# down / up (muse_input.c, serial_task), so this drives the *press* path --
# carpet_mic_armed() -> talk_to_phone() -> voice_task feeding frames -> release ->
# the sender task -- which >mictest deliberately does not cover (it synthesizes PCM
# and skips the microphone and the button).
#
# Opening the port with DTR/RTS low first is what keeps EN from pulsing: a reset
# here would wipe the RAM counters mid-utterance and we'd be measuring nothing.
port, hold, secs, out = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), sys.argv[4]
s = serial.Serial()
s.port = port
s.baudrate = 115200
s.timeout = 0.2
s.dtr = False
s.rts = False
s.open()
time.sleep(0.3)
s.reset_input_buffer()
s.write(b"d")          # press
s.flush()
time.sleep(hold)
s.write(b"u")          # release: this is what sets the whole segment going
s.flush()
end = time.time() + secs
buf = bytearray()
while time.time() < end:
    buf.extend(s.read(4096))
s.close()
open(out, "wb").write(bytes(buf))
print("bytes=%d" % len(bytes(buf)))
