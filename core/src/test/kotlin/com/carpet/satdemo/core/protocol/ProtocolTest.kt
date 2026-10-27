package com.carpet.satdemo.core.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * 和 protocol/sos_frame.py 逐字节对齐。
 * GOLDEN 取自 Python 参考实现跑出来的实际输出，任何一侧改动都会在这里炸。
 */
class SosFrameTest {

    private val encoder = SosFrameEncoder(OWNER_ID)

    @Test
    fun `golden frame matches python reference`() {
        val frame = encoder.encode(
            msgType = SosFrame.MSG_SOS,
            lat = 30.2741,
            lon = 120.1551,
            whenSec = WHEN_SEC,
            batteryPct = 87,
            flags = SosFrame.FLAG_DUAL or SosFrame.FLAG_SRC_GT1005,
        )
        assertEquals(20, frame.size)
        assertEquals(GOLDEN, frame.toHex())
    }

    @Test
    fun `round trip preserves every field`() {
        val frame = encoder.encode(
            msgType = SosFrame.MSG_FENCE_BREACH,
            lat = -33.8688,
            lon = 151.2093,
            whenSec = WHEN_SEC + 3_600,
            batteryPct = 42,
            flags = SosFrame.FLAG_STALE_FIX,
            speedKmh = 63,
        )
        val d = decodeSosFrame(frame)
        assertEquals(SosFrame.MSG_FENCE_BREACH, d.msgType)
        assertEquals(OWNER_ID, d.ownerId)
        assertEquals(-33.8688, d.lat, 1e-7)
        assertEquals(151.2093, d.lon, 1e-7)
        assertEquals(WHEN_SEC + 3_600, d.whenSec)
        assertEquals(42, d.batteryPct)
        assertEquals(SosFrame.FLAG_STALE_FIX, d.flags)
        assertEquals(63, d.speedKmh)
    }

    @Test
    fun `crc catches a single flipped bit`() {
        val frame = encoder.encode(SosFrame.MSG_SOS, 30.2741, 120.1551, WHEN_SEC, 87)
        frame[9] = (frame[9].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) { decodeSosFrame(frame) }
    }

    @Test
    fun `battery and speed are clamped not wrapped`() {
        val frame = encoder.encode(SosFrame.MSG_HEARTBEAT, 30.0, 120.0, WHEN_SEC, batteryPct = 250, speedKmh = 300)
        val d = decodeSosFrame(frame)
        assertEquals(100, d.batteryPct)
        assertEquals(255, d.speedKmh)
    }

    @Test
    fun `refuses timestamps before the 2026 epoch`() {
        assertThrows(IllegalArgumentException::class.java) {
            encoder.encode(SosFrame.MSG_SOS, 30.0, 120.0, SosFrame.EPOCH_SEC - 60, 50)
        }
    }

    @Test
    fun `ext fragments reassemble even when they arrive out of order`() {
        val payload = "西湖景区围栏外86米，车辆被移动".toByteArray()
        val parts = encoder.encodeExt(payload, 30.2741, 120.1551, WHEN_SEC, 87)
        assertTrue(parts.size > 1, "该载荷应至少分成两片")
        assertTrue(parts.all { it.size >= 20 })
        assertContentEquals(payload, decodeExtFrames(parts))

        // total 写在每一片的 frag 高 4 位，所以乱序到达也能靠 index 复原
        assertContentEquals(payload, decodeExtFrames(parts.reversed()))
    }

    @Test
    fun `a dropped fragment is refused instead of returning a truncated payload`() {
        val payload = "西湖景区围栏外86米，车辆被移动".toByteArray()
        val parts = encoder.encodeExt(payload, 30.2741, 120.1551, WHEN_SEC, 87)
        assertThrows(IllegalArgumentException::class.java) {
            decodeExtFrames(parts.dropLast(1))
        }
    }

    @Test
    fun `chunk size honours the worst case 90 hex chars`() {
        val payload = ByteArray(70)
        val parts = encoder.encodeExt(payload, 30.0, 120.0, WHEN_SEC, 50)
        // 每片 hex 长度必须 <= 90，否则 ICI 口径按字符数算时发不出去
        assertTrue(parts.all { it.size * 2 <= 90 }, "分片超出 90 hex 字符上限")
        assertEquals(70, decodeExtFrames(parts).size)
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "expected=${expected.toHex()} actual=${actual.toHex()}")
    }

    companion object {
        const val OWNER_ID = 12_061L
        const val WHEN_SEC = 1_795_200_000L
        const val GOLDEN = "1100002F1D120B7608479E3698071D405709009D"
    }
}

/**
 * 契约对拍：protocol/examples 目录下的 JSON 是三方（App / 固件 / 模拟器）共用的样例，
 * 这里保证 Kotlin 侧能解开每一个样例，且解出来再编回去语义不变。
 */
class ScreenLinkContractTest {

    @TestFactory
    fun `every published example decodes`() = examples().map { (name, text) ->
        dynamicTest(name) {
            val msg = ScreenMessage.decode(text)
            assertTrue(msg.encode().isNotEmpty())
            // 再解一次，保证自产报文可回放
            ScreenMessage.decode(msg.encode())
        }
    }.toTypedArray()

    @Test
    fun `hello carries the negotiated contract version`() {
        val msg = ScreenMessage.decode(readExample("hello.json"))
        assertEquals(1, msg.v)
        assertEquals(MsgType.hello, msg.t)
        assertFalse(msg.hello!!.screenId.isBlank())
    }

    @Test
    fun `state example exposes the fields the screen draws`() {
        val state = ScreenMessage.decode(readExample("state.json")).state!!
        assertEquals(LinkPrimary.sat, state.link.primary)
        assertFalse(state.link.cellOk)
        assertEquals(SatPhase.locked, state.sat.phase)
        assertEquals("GW-03", state.sat.satName)
        assertEquals(143, state.sat.nextPassInS)
        assertEquals(GnssSource.gt1005, state.gnss.source)
        assertEquals(30.2741, state.gnss.lat, 1e-9)
        assertFalse(state.fence.inside)
        assertEquals(-86.3, state.fence.distM!!, 1e-9)
        assertEquals(SosLevel.sent, state.sos.level)
        assertTrue(state.demoMode)
    }

    @Test
    fun `event example frame decodes with the python golden hex`() {
        val event = ScreenMessage.decode(readExample("event.json")).event!!
        val hex = event.payload["frame_hex"]!!.toString().trim('"')
        assertEquals(SosFrameTest.GOLDEN, hex.uppercase())
        assertEquals(SosFrameTest.OWNER_ID, decodeSosFrame(hexToBytes(hex)).ownerId)
    }

    @Test
    fun `payload key must match message type`() {
        // t=state 但只带了 event -> 应炸，而不是静默发到屏幕上什么都不显示
        val bogus = """{"v":1,"t":"state","seq":1,"event":{"kind":"sos_triggered","event_id":"x"}}"""
        assertThrows(IllegalArgumentException::class.java) { ScreenMessage.decode(bogus) }
    }

    @Test
    fun `unknown contract version is rejected`() {
        val v2 = """{"v":2,"t":"bye","seq":1}"""
        assertThrows(Exception::class.java) { ScreenMessage.decode(v2) }
    }

    @Test
    fun `constructing without required payload fails fast`() {
        assertThrows(IllegalArgumentException::class.java) { ScreenMessage(t = MsgType.event, seq = 1) }
    }

    private fun examples(): List<Pair<String, String>> {
        val dir = examplesDir()
        return Files.list(dir).filter { it.toString().endsWith(".json") }
            .sorted().toList().map { it.fileName.toString() to it.readText() }
    }

    private fun readExample(name: String): String = examplesDir().resolve(name).readText()

    /** gradle 测试工作目录是 core/，协议样例在上一级；从仓库根跑时则直接命中 */
    private fun examplesDir(): Path {
        val candidates = listOf(
            Paths.get("protocol", "examples"),
            Paths.get("..", "protocol", "examples"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("找不到 protocol/examples，测试必须在仓库内运行")
    }
}
