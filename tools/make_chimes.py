"""Generates the soft listening chimes in res/raw (run again to retune; output is committed)."""
import math
import struct
import sys
import wave

RATE = 44100


def note(freq, start, length, amp, decay):
    """A glassy sine with a soft 6 ms attack, exponential decay and a touch of octave for warmth."""
    out = {}
    for i in range(int(length * RATE)):
        t = i / RATE
        env = min(1.0, t / 0.006) * math.exp(-t / decay)
        v = math.sin(2 * math.pi * freq * t) + 0.18 * math.sin(2 * math.pi * freq * 2 * t) + 0.05 * math.sin(2 * math.pi * freq * 3 * t)
        out[int(start * RATE) + i] = amp * env * v / 1.23
    return out


def write(path, notes, total):
    buf = [0.0] * int(total * RATE)
    for n in notes:
        for i, v in n.items():
            if i < len(buf):
                buf[i] += v
    fade = int(0.01 * RATE)  # no click at the end
    for k in range(fade):
        buf[-1 - k] *= k / fade
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in buf))


out = sys.argv[1]
# Listening: a quick rising fifth (A5 then E6), like a soft glass tap.
write(f"{out}/chime_listen.wav", [note(880.0, 0, 0.32, 0.20, 0.09), note(1318.5, 0.075, 0.30, 0.17, 0.10)], 0.40)
# Heard you: one soft note (C6), quieter.
write(f"{out}/chime_heard.wav", [note(1046.5, 0, 0.26, 0.13, 0.07)], 0.30)
# Didn't catch that: a gentle falling pair (E6 then A5).
write(f"{out}/chime_none.wav", [note(1318.5, 0, 0.25, 0.12, 0.07), note(880.0, 0.09, 0.28, 0.11, 0.09)], 0.40)
print("ok")
