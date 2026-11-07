#!/usr/bin/env python3
# -*- coding: ascii -*-
"""Edge neural TTS -> 16k mono s16le WAV, one sentence per call.

Why this file exists: the System.Speech voice shipped with Windows (Huihui) sounds like a
sat-nav prompt, and the gadget is a small cute character. Edge's zh-CN-XiaoyiNeural is a
young female voice, free, no key. It needs network, so tts-say.ps1 keeps Huihui as the
fallback and the fallback is the reason a demo still talks when the hotel Wi-Fi is down.

Output contract (same line tts-say.ps1 prints, because VoiceTts.kt shows it verbatim):
    OK <wav bytes> bytes voice=<name> chars=<n>
Any failure: a short reason on stdout and a non-zero exit code. ASCII only on purpose:
the JVM reads stdout, and a Chinese reason printed under cp936 comes back as mojibake.
"""
import argparse
import array
import asyncio
import io
import sys
import wave


def synthesize(text, voice, rate):
    import edge_tts

    buf = io.BytesIO()
    com = edge_tts.Communicate(text, voice, rate=rate)
    loop = asyncio.new_event_loop()

    async def pump():
        async for chunk in com.stream():
            if chunk["type"] == "audio":
                buf.write(chunk["data"])

    try:
        loop.run_until_complete(pump())
    finally:
        loop.close()
    return buf.getvalue()


def to_pcm16k(mp3_bytes):
    """Decode mp3, mix to mono, linear-resample to 16000 Hz. Returns array('h')."""
    import miniaudio

    d = miniaudio.decode(mp3_bytes)
    if d.nchannels == 2:
        s = d.samples
        mono = array.array("h", [(s[i] + s[i + 1]) // 2 for i in range(0, len(s), 2)])
    else:
        mono = array.array("h", d.samples)
    src_rate = d.sample_rate
    if src_rate == 16000:
        return mono
    n_out = int(len(mono) * 16000 / src_rate)
    out = array.array("h", bytes(2 * n_out))
    step = src_rate / 16000.0
    for i in range(n_out):
        pos = i * step
        j = int(pos)
        if j + 1 < len(mono):
            frac = pos - j
            out[i] = int(mono[j] * (1 - frac) + mono[j + 1] * frac)
        else:
            out[i] = mono[j] if j < len(mono) else 0
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--text-file", required=True)
    ap.add_argument("--wav-out", required=True)
    ap.add_argument("--voice", default="zh-CN-XiaoyiNeural")
    ap.add_argument("--rate", default="+0%")
    a = ap.parse_args()

    with io.open(a.text_file, "r", encoding="utf-8") as f:
        text = f.read().strip()
    if not text:
        sys.stdout.write("EMPTY-TEXT\n")
        return 3

    try:
        mp3 = synthesize(text, a.voice, a.rate)
        if not mp3:
            sys.stdout.write("NO-AUDIO-FROM-EDGE\n")
            return 4
        pcm = to_pcm16k(mp3)
    except Exception as e:
        # network down / 403 / a decode miss all land here; the caller falls back offline.
        # str(e) can carry the sentence we asked for (Chinese), so scrub before printing.
        reason = ("%s: %s" % (type(e).__name__, str(e)[:120])).encode("ascii", "replace").decode("ascii")
        sys.stdout.write("EDGE-FAIL %s\n" % reason)
        return 1

    with wave.open(a.wav_out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(pcm.tobytes())

    import os
    sys.stdout.write("OK %d bytes voice=%s chars=%d\n"
                     % (os.path.getsize(a.wav_out), a.voice, len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
