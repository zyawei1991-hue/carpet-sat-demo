package com.carpet.satdemo.core.modem

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 串口那一路的传输骨架。指令表本身是猜的（见 [Gt1005Verbs]），所以这里断言的是
 * **和指令表无关的行为**：应答怎么配、没应答算什么、主动上报怎么分。
 * 真机文档到了只改 Gt1005Verbs，这些断言应该原样通过。
 */
class UartModemTest {

    private class FakePort(
        var open: Boolean = true,
        val scripted: ArrayDeque<String?> = ArrayDeque(),
    ) : LinePort {
        val written = ArrayList<String>()
        override val isOpen: Boolean get() = open
        override fun readLine(timeoutMs: Long): String? = scripted.removeFirstOrNull()
        override fun writeLine(line: String) {
            written += line
        }

        override fun close() {
            open = false
        }
    }

    @Test
    fun `mcq returns whatever result the module reports`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        port.scripted += "ICI=ACK"

        assertEquals(SatResult.COMPLETE, m.sendMcq("1100002F"))
        assertEquals(listOf("ICI=1100002F"), port.written)
    }

    @Test
    fun `a module that says nothing is a failure, not a missing ack`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        port.scripted += null

        // 展示端这两条红字不一样：「发送失败」和「星上未确认」
        assertEquals(SatResult.FAIL, m.sendMcq("1100002F"))
    }

    @Test
    fun `unsolicited downlink is routed instead of being taken for an answer`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        val got = ArrayList<String>()
        m.onDownlink { got += it }
        port.scripted += listOf("MCI=BEEF", "ICI=NO_ACK")

        assertEquals(SatResult.NO_ACK, m.sendMcq("1100002F"), "下行不能冒充发送结果")
        assertEquals(listOf("BEEF"), got)
    }

    @Test
    fun `satellite lock reported mid-transmission reaches the listener`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        val seen = ArrayList<SatLock?>()
        m.onSatelliteLock { seen += it }
        port.scripted += listOf("CEI=GW-07,42.5", "ICI=ACK")

        assertEquals(SatResult.COMPLETE, m.sendMcq("1100002F"))
        assertEquals(listOf(null, SatLock("GW-07", 42.5)), seen)
    }

    @Test
    fun `gnss read is the module's own fix`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        port.scripted += "GMO=120.15510,30.27410,2.5"

        val fix = m.readFix()
        assertEquals(120.15510, fix!!.lon, 1e-9)
        assertEquals(30.27410, fix.lat, 1e-9)
        assertEquals(2.5, fix.hdop!!, 1e-9)
    }

    @Test
    fun `a closed port is unavailable and sends nothing`() = runBlocking {
        val port = FakePort(open = false)
        val m = UartModem(port)

        assertFalse(m.available)
        assertEquals(SatResult.FAIL, m.sendMcq("1100002F"))
        assertFalse(m.enqueueSfq("1100002F"))
        assertNull(m.readFix())
        assertTrue(port.written.isEmpty(), "口没开就别往串口上写东西")
    }

    @Test
    fun `enqueue is confirmed by the module, not assumed`() = runBlocking {
        val port = FakePort()
        val m = UartModem(port)
        port.scripted += "SFQ=OK"
        assertTrue(m.enqueueSfq("1100002F"))

        port.scripted += null
        assertFalse(m.enqueueSfq("1100002F"), "模块没确认收下，这条就得留在 App 队列里")
    }
}
