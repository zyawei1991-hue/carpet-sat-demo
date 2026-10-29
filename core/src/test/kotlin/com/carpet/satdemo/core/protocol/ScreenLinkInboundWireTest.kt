package com.carpet.satdemo.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        // protocols 省略 → null，**不能**当成"它支持最新档"：默认给当前版本的话，
        // 服务端会往一个画不出对话的固件上推 dialog。null 的语义是"按 v1 待，只发状态"
        assertNull(m.hello?.protocols)
        assertEquals(1, ScreenCodec.negotiated(m.hello?.protocols))
        assertTrue(ScreenCodec.compatible(m.hello?.protocols))
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
        // v1 和 v2 都在窗口内（v2 只多了 dialog），所以这一条测的是**第三个版本**：
        // 固件报了一个我们没实现的契约，必须当场炸，不能"尽力解解看"
        val e = assertFailsWith<IllegalArgumentException> {
            ScreenMessage.decode("""{"v":3,"t":"bye","seq":3}""")
        }
        assertTrue(e.message!!.contains("contract"), e.message!!)
        assertFailsWith<IllegalArgumentException> { ScreenMessage.decode("""{"v":0,"t":"bye","seq":3}""") }
    }

    @Test
    fun `dialog 帧的手写线格式`() {
        // 固件那边是 cJSON 手拼，所以这里也手写。dir/origin/source 都是字符串枚举，
        // 拼错一个字母的表现是"屏能说话但 App 装了没反应"，必须在这层就拦住
        val m = ScreenMessage.decode(
            """{"v":2,"t":"dialog","seq":11,"ts":900,"dialog":{"turn_id":"t-1","dir":"up","text":"车在哪","source":"asr","origin":"screen"}}""",
        )
        assertEquals(MsgType.dialog, m.t)
        assertEquals("车在哪", m.dialog?.text)
        assertEquals(DialogDir.up, m.dialog?.dir)
        assertEquals(DialogSource.asr, m.dialog?.source)
        assertEquals(DialogOrigin.screen, m.dialog?.origin)
        assertNull(m.dialog?.skill)
    }

    @Test
    fun `dialog 帧缺 turn_id 或 dir 时必须拒绝`() {
        assertFailsWith<Exception> {
            ScreenMessage.decode("""{"v":2,"t":"dialog","seq":1,"dialog":{"text":"车在哪"}}""")
        }
        // t 与负载不符（dialog 帧没带 dialog）也要炸：这条是所有对话测试的地基
        val miss = assertFailsWith<IllegalArgumentException> {
            ScreenMessage.decode("""{"v":2,"t":"dialog","seq":1,"command":{"cmd":"sos_press"}}""")
        }
        assertTrue(miss.message!!.contains("dialog"), miss.message!!)
    }

    @Test
    fun `未知指令名不能变成一个默认动作`() {
        assertFailsWith<Exception> {
            ScreenMessage.decode("""{"v":1,"t":"command","seq":4,"command":{"cmd":"launch_missiles"}}""")
        }
    }
}
