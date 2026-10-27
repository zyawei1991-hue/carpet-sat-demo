"""SOS 固定帧参考实现 —— 20 字节。

Android(Kotlin) 和固件(C) 两端都照这个文件对齐。改这里必须同步改两端并升 SPEC_VERSION。
规范见 sos-payload.md。

用法:
    frame = encode(MSG_SOS, owner_id=12061, lat=30.2741, lon=120.1551,
                   when=1795200000, battery_pct=87, flags=FLAG_DUAL|FLAG_SRC_GT1005)
    hex_str = frame.hex().upper()          # SDK sendMsgMCQ(cardId, hex, cb) 要的就是这个
    decoded = decode(frame)
"""
from __future__ import annotations

import struct
from dataclasses import dataclass
from datetime import datetime, timezone

SPEC_VERSION = 1
FRAME_LEN = 20
EPOCH_MIN = 1_767_225_600  # 2026-01-01T00:00:00Z

MSG_SOS, MSG_SOS_CANCEL, MSG_HEARTBEAT = 0x1, 0x2, 0x3
MSG_FENCE_BREACH, MSG_SENTINEL_MOVE = 0x4, 0x5
MSG_EXT_BEGIN, MSG_EXT_CHUNK = 0x6, 0x7

FLAG_DUAL = 0x01            # bit0 0=仅卫星 1=双发
FLAG_SENTINEL_ARMED = 0x02  # bit1
FLAG_STALE_FIX = 0x04       # bit2 0=定位在30s内 1=已过期
FLAG_SRC_GT1005 = 0x08      # bit3 0=手机 1=GT1005


def crc8_maxim(data: bytes) -> int:
    """CRC-8/MAXIM: poly=0x31, init=0x00, refin/refout=True, xorout=0x00."""
    crc = 0
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0x8C if crc & 1 else crc >> 1
    return crc & 0xFF


@dataclass(frozen=True)
class Frame:
    msg_type: int
    owner_id: int
    lat: float
    lon: float
    when: int          # unix 秒，解析后已按分钟对齐
    battery_pct: int
    flags: int
    speed_kmh: int


def encode(msg_type: int, owner_id: int, lat: float, lon: float, when: int,
           battery_pct: int, flags: int = 0, speed_kmh: int = 0) -> bytes:
    if not 0 <= msg_type <= 0xF:
        raise ValueError(f"msg_type {msg_type:#x} out of 4-bit range")
    if not 0 <= owner_id <= 0xFFFFFFFF:
        raise ValueError("owner_id exceeds uint32")
    if not -90.0 <= lat <= 90.0 or not -180.0 <= lon <= 180.0:
        raise ValueError(f"lat/lon out of range: {lat}, {lon}")
    if when < EPOCH_MIN:
        raise ValueError(f"timestamp {when} before 2026-01-01 epoch")

    lat_i = round(lat * 1e7)
    lon_i = round(lon * 1e7)
    ts_min = (when - EPOCH_MIN) // 60
    if ts_min > 0xFFFFFF:
        raise ValueError("timestamp exceeds uint24 minute range")

    body = b"".join([
        bytes([((SPEC_VERSION & 0xF) << 4) | (msg_type & 0xF)]),
        struct.pack(">I", owner_id),
        struct.pack(">i", lat_i),
        struct.pack(">i", lon_i),
        struct.pack(">I", ts_min)[1:],
        bytes([min(max(battery_pct, 0), 100)]),
        bytes([flags & 0xFF]),
        bytes([min(max(speed_kmh, 0), 255)]),
    ])
    assert len(body) == FRAME_LEN - 1, len(body)
    return body + bytes([crc8_maxim(body)])


def decode(frame: bytes) -> Frame:
    if len(frame) != FRAME_LEN:
        raise ValueError(f"expected {FRAME_LEN} bytes, got {len(frame)}")
    if crc8_maxim(frame[:-1]) != frame[-1]:
        raise ValueError("crc8 mismatch")

    ver, msg_type = frame[0] >> 4, frame[0] & 0xF
    if ver != SPEC_VERSION:
        raise ValueError(f"unsupported spec version {ver}")

    return Frame(
        msg_type=msg_type,
        owner_id=struct.unpack(">I", frame[1:5])[0],
        lat=struct.unpack(">i", frame[5:9])[0] / 1e7,
        lon=struct.unpack(">i", frame[9:13])[0] / 1e7,
        when=EPOCH_MIN + int.from_bytes(frame[13:16], "big") * 60,
        battery_pct=frame[16],
        flags=frame[17],
        speed_kmh=frame[18],
    )


def encode_ext(msg_type: int, payload: bytes, owner_id: int, lat: float, lon: float,
               when: int, battery_pct: int, max_chunk: int = 24) -> list[bytes]:
    """扩展数据分片。单片载荷默认 24B —— 对应 ICI 90 是「hex 字符数」的最坏情况。

    若时空道宇确认 90 指二进制字节，把 max_chunk 提到 74，分片数直接少三倍。
    见 sos-payload.md「一个必须先问清楚的问题」。
    """
    if msg_type not in (MSG_EXT_BEGIN, MSG_EXT_CHUNK):
        raise ValueError("ext frames must use MSG_EXT_BEGIN / MSG_EXT_CHUNK")
    if not payload:
        return []

    chunks = [payload[i:i + max_chunk] for i in range(0, len(payload), max_chunk)]
    if len(chunks) > 15:
        raise ValueError(f"{len(chunks)} fragments exceeds 4-bit total field")

    out = []
    for idx, chunk in enumerate(chunks):
        body = encode(msg_type, owner_id, lat, lon, when, battery_pct)[:-1]
        frag = (((len(chunks) & 0xF) << 4) | (idx & 0xF))
        extended = body + bytes([frag]) + chunk
        out.append(extended + bytes([crc8_maxim(extended)]))
    return out


def decode_ext(frames: list[bytes]) -> bytes:
    total = (frames[0][FRAME_LEN - 1] >> 4) & 0xF
    if total != len(frames):
        raise ValueError(f"expected {total} fragments, got {len(frames)}")
    seen = {}
    for f in frames:
        if len(f) < FRAME_LEN:
            raise ValueError("fragment too short")
        if crc8_maxim(f[:-1]) != f[-1]:
            raise ValueError("crc8 mismatch on fragment")
        idx = f[FRAME_LEN - 1] & 0xF
        seen[idx] = f[FRAME_LEN:-1]
    return b"".join(seen[i] for i in range(total))


if __name__ == "__main__":
    import json
    import pathlib

    f = encode(MSG_SOS, owner_id=12061, lat=30.2741, lon=120.1551,
               when=1795200000, battery_pct=87,
               flags=FLAG_DUAL | FLAG_SRC_GT1005)
    hex_str = f.hex().upper()
    print(f"frame  = {hex_str}  ({len(f)} bytes / {len(hex_str)} hex chars)")
    print(f"decode = {decode(f)}")

    example = pathlib.Path("examples/event.json")
    if example.exists():
        want = json.loads(example.read_text())["event"]["payload"]["frame_hex"]
        assert hex_str == want, f"examples/event.json out of date:\n  code={hex_str}\n  file={want}"
        print("examples/event.json matches reference codec")

    payload = "西湖景区围栏外86米，车辆被移动".encode()
    parts = encode_ext(MSG_EXT_CHUNK, payload, 12061, 30.2741, 120.1551, 1795200000, 87)
    print(f"ext    = {len(parts)} fragments for {len(payload)} bytes")
    assert decode_ext(parts) == payload
    print("all checks passed")
