package com.carpet.satdemo.core.protocol

import java.nio.ByteBuffer

/**
 * SOS 固定帧编解码。规范见 protocol/sos-payload.md。
 *
 * 与 protocol/sos_frame.py 逐字节对齐，两边共用 examples/event.json 里的 frame_hex 做回归。
 * 改这里必须同步改 Python 参考实现并升 [VERSION]。
 */
object SosFrame {
    const val VERSION = 1
    const val FRAME_LEN = 20

    /** 报文时间戳基准：2026-01-01T00:00:00Z，单位秒 */
    const val EPOCH_SEC = 1_767_225_600L

    const val MSG_SOS = 0x1
    const val MSG_SOS_CANCEL = 0x2
    const val MSG_HEARTBEAT = 0x3
    const val MSG_FENCE_BREACH = 0x4
    const val MSG_SENTINEL_MOVE = 0x5
    const val MSG_EXT_BEGIN = 0x6
    const val MSG_EXT_CHUNK = 0x7

    const val FLAG_DUAL = 0x01
    const val FLAG_SENTINEL_ARMED = 0x02
    const val FLAG_STALE_FIX = 0x04
    const val FLAG_SRC_GT1005 = 0x08

    /** 分片单片载荷。按 ICI 90 = hex 字符数的最坏情况算：90/2 - 21 = 24 */
    const val DEFAULT_MAX_CHUNK = 24

    private val CRC8_POLY_REVERSED = 0x8C
}

data class DecodedFrame(
    val msgType: Int,
    val ownerId: Long,
    val lat: Double,
    val lon: Double,
    /** unix 秒，已按分钟对齐 */
    val whenSec: Long,
    val batteryPct: Int,
    val flags: Int,
    val speedKmh: Int,
)

/** CRC-8/MAXIM：poly=0x31 init=0x00 refin/refout=true xorout=0x00 */
fun crc8Maxim(data: ByteArray, length: Int = data.size): Int {
    var crc = 0
    for (i in 0 until length) {
        crc = crc xor (data[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 1 != 0) (crc shr 1) xor 0x8C else crc shr 1
        }
    }
    return crc and 0xFF
}

class SosFrameEncoder(private val ownerId: Long) {

    fun encode(
        msgType: Int,
        lat: Double,
        lon: Double,
        whenSec: Long,
        batteryPct: Int,
        flags: Int = 0,
        speedKmh: Int = 0,
    ): ByteArray {
        require(msgType in 0..0xF) { "msg_type %#x out of 4-bit range".format(msgType) }
        require(ownerId in 0..0xFFFF_FFFFL) { "owner_id exceeds uint32" }
        require(lat in -90.0..90.0 && lon in -180.0..180.0) { "lat/lon out of range: $lat, $lon" }
        require(whenSec >= SosFrame.EPOCH_SEC) { "timestamp $whenSec before 2026-01-01 epoch" }

        val tsMin = (whenSec - SosFrame.EPOCH_SEC) / 60
        require(tsMin <= 0xFFFFFF) { "timestamp exceeds uint24 minute range" }

        val buf = ByteBuffer.allocate(19)
        buf.put((((SosFrame.VERSION and 0xF) shl 4) or (msgType and 0xF)).toByte())
        buf.putInt(ownerId.toInt())
        buf.putInt(Math.round(lat * 1e7).toInt())
        buf.putInt(Math.round(lon * 1e7).toInt())
        buf.put(((tsMin shr 16) and 0xFF).toByte())
        buf.put(((tsMin shr 8) and 0xFF).toByte())
        buf.put((tsMin and 0xFF).toByte())
        buf.put(batteryPct.coerceIn(0, 100).toByte())
        buf.put((flags and 0xFF).toByte())
        buf.put(speedKmh.coerceIn(0, 255).toByte())

        val body = buf.array()
        check(body.size == SosFrame.FRAME_LEN - 1)
        return body + crc8Maxim(body).toByte()
    }

    /** 扩展数据分片。首片承载 total，其余片只带 index。 */
    fun encodeExt(
        payload: ByteArray,
        lat: Double,
        lon: Double,
        whenSec: Long,
        batteryPct: Int,
        maxChunk: Int = SosFrame.DEFAULT_MAX_CHUNK,
    ): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val chunks = payload.asList().chunked(maxChunk).map { it.toByteArray() }
        require(chunks.size <= 15) { "${chunks.size} fragments exceeds 4-bit total field" }

        return chunks.mapIndexed { idx, chunk ->
            val body = encode(SosFrame.MSG_EXT_CHUNK, lat, lon, whenSec, batteryPct).copyOf(19)
            val frag = (((chunks.size and 0xF) shl 4) or (idx and 0xF)).toByte()
            val extended = body + frag + chunk
            extended + crc8Maxim(extended).toByte()
        }
    }
}

/** SDK 的 sendMsgMCQ 收的是 hex 字符串，不是字节数组，这里统一口径 */
fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun decodeSosFrame(frame: ByteArray): DecodedFrame {
    require(frame.size == SosFrame.FRAME_LEN) { "expected ${SosFrame.FRAME_LEN} bytes, got ${frame.size}" }
    require(crc8Maxim(frame, SosFrame.FRAME_LEN - 1) == (frame[SosFrame.FRAME_LEN - 1].toInt() and 0xFF)) { "crc8 mismatch" }

    val ver = (frame[0].toInt() and 0xFF) shr 4
    require(ver == SosFrame.VERSION) { "unsupported spec version $ver" }

    val buf = ByteBuffer.wrap(frame)
    buf.position(1)
    val tsMin = ((frame[13].toInt() and 0xFF) shl 16) or
        ((frame[14].toInt() and 0xFF) shl 8) or
        (frame[15].toInt() and 0xFF)

    return DecodedFrame(
        msgType = frame[0].toInt() and 0xF,
        ownerId = buf.int.toLong() and 0xFFFF_FFFFL,
        lat = buf.int / 1e7,
        lon = buf.int / 1e7,
        whenSec = SosFrame.EPOCH_SEC + tsMin * 60,
        batteryPct = frame[16].toInt() and 0xFF,
        flags = frame[17].toInt() and 0xFF,
        speedKmh = frame[18].toInt() and 0xFF,
    )
}

fun decodeExtFrames(frames: List<ByteArray>): ByteArray {
    require(frames.isNotEmpty()) { "no fragments" }
    val first = frames[0]
    require(first.size >= SosFrame.FRAME_LEN) { "fragment too short" }
    require(crc8Maxim(first, first.size - 1) == (first[first.size - 1].toInt() and 0xFF)) { "crc8 mismatch on fragment" }

    val total = (first[SosFrame.FRAME_LEN - 1].toInt() and 0xFF) shr 4
    require(total == frames.size) { "expected $total fragments, got ${frames.size}" }

    val seen = HashMap<Int, ByteArray>()
    for (f in frames) {
        require(f.size >= SosFrame.FRAME_LEN) { "fragment too short" }
        require(crc8Maxim(f, f.size - 1) == (f[f.size - 1].toInt() and 0xFF)) { "crc8 mismatch on fragment" }
        seen[f[SosFrame.FRAME_LEN - 1].toInt() and 0xF] = f.copyOfRange(SosFrame.FRAME_LEN, f.size - 1)
    }
    return (0 until total).flatMap { seen[it]!!.toList() }.toByteArray()
}
