package com.carpet.satdemo.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 线格式的**入站**方向。其余契约测试都用 Kotlin 自己编码再解码，那条路永远对称 ——
 * 而明天那块屏是固件按 schema 手写 cJSON 发过来的，字段顺序、缺省、空对象都可能和我们想的不一样。
 *
 * 这一份全部用**手写字符串**，就是为了在有人把固件接进来之前，先把"别人发的东西我们收不收下"这件事测掉。
 */
class ScreenLinkInboundWireTest {

    @Test
    fun `hello 帧按固件会发的最小形状收下`() {
        val m = ScreenMessage.decode("""{"v":1,"t":"hello","seq":0,"hello":{"screen_id":"WS175B-A3F7","fw":"0.1"}}""")
        assertEquals(MsgType.hello, m.t)
        assertEquals("WS175B-A3F7", m.hello?.screenId)
        // protocols 省略 = 用当前版本。固件第一版一定会省，这里不能把它判成"版本不认识"
        assertEquals(listOf(ScreenCodec.CONTRACT_VERSION), m.hello?.protocols)
    }

    @Test
    fun `command 帧带空 args 也要能解`() {
        val m = ScreenMessage.decode("""{"v":1,"t":"command","seq":7,"command":{"cmd":"sos_press","args":{}}}""")
        assertEquals(CommandType.sos_press, m.command?.cmd)
        assertEquals(7L, m.seq)
    }

    @Test
    fun `command 帧不带 args 也要能解`() {
        // cJSON 里"没有这个键"和"空对象"是两种写法，两种都得认，否则现场表现为
        // "屏上按了键，App 毫无反应"，而这句话听起来像链路坏了
        val m = ScreenMessage.decode("""{"v":1,"t":"command","seq":8,"command":{"cmd":"sos_cancel"}}""")
        assertEquals(CommandType.sos_cancel, m.command?.cmd)
        assertNotNull(m.command)
    }

    @Test
    fun `bye 帧是唯一允许没有负载的`() {
        val m = ScreenMessage.decode("""{"v":1,"t":"bye","seq":9}""")
        assertEquals(MsgType.bye, m.t)
    }

    @Test
    fun `ack 帧的下划线字段名是契约，不是驼峰`() {
        val m = ScreenMessage.decode("""{"v":1,"t":"ack","seq":1,"ack":{"for_seq":0,"for_event_id":"e1"}}""")
        assertEquals(0L, m.ack?.forSeq)
        assertEquals("e1", m.ack?.forEventId)
    }

    @Test
    fun `t 与负载不匹配必须拒绝，不能悄悄收下`() {
        // 这条是 init 里那段 require 的来处：发了 t=state 却没带 state，
        // 收下就是让展示端画一屏"--"，没人知道是链路问题还是数据问题
        val e = assertFailsWith<IllegalArgumentException> {
            ScreenMessage.decode("""{"v":1,"t":"state","seq":2}""")
        }
        assertTrue(e.message!!.contains("state"), e.message!!)
    }

    @Test
    fun `契约版本不认识时必须拒绝`() {
        val e = assertFailsWith<IllegalArgumentException> {
            ScreenMessage.decode("""{"v":2,"t":"bye","seq":3}""")
        }
        assertTrue(e.message!!.contains("contract"), e.message!!)
    }

    @Test
    fun `未知指令名不能变成一个默认动作`() {
        assertFailsWith<Exception> {
            ScreenMessage.decode("""{"v":1,"t":"command","seq":4,"command":{"cmd":"launch_missiles"}}""")
        }
    }
}
