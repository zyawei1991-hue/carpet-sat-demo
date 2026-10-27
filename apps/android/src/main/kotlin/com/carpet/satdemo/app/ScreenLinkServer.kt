package com.carpet.satdemo.app

import com.carpet.satdemo.core.protocol.ScreenMessage
import java.net.InetSocketAddress
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

/**
 * 手机当服务端，微雪圆屏当客户端。契约见 protocol/screen-link.md。
 *
 * 明文 ws 而非 wss，是有意的偏离，理由记在 docs/DECISIONS.md：
 * 圆屏侧要连自签证书得在固件里塞 TLS 栈和证书校验旁路，演示 LAN 上换来的实际安全性接近 0，
 * 但工程量会把 W2 直接吃掉。替代控制是三层：默认拒绝接入 + screen_id 白名单 + 只在演示时开。
 * 量产前这条必须重新评估，不能当既成事实带过去。
 */
class ScreenLinkServer(
    private val whitelist: MutableSet<String> = mutableSetOf(DEFAULT_SCREEN_ID),
) : WebSocketServer(InetSocketAddress(PORT)) {

    @Volatile
    var accepting: Boolean = false
        set(value) {
            field = value
            if (!value) closeClients()
        }

    @Volatile
    var onMessage: (ScreenMessage) -> Unit = {}

    val clientCount: Int get() = connections.count { it.isOpen }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (!accepting) {
            conn.close(CLOSE_POLICY, "demo lock")
            return
        }
        // 首帧必须是 hello，否则拿不到 screen_id 没法校验白名单
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = runCatching { ScreenMessage.decode(message) }.getOrElse {
            conn.close(CLOSE_POLICY, "bad contract")
            return
        }
        val hello = msg.hello
        if (hello != null && hello.screenId !in whitelist) {
            conn.close(CLOSE_POLICY, "unknown screen")
            return
        }
        onMessage(msg)
    }

    override fun onStart() {
        // Java-WebSocket 1.5 要求实现；接入日志由 DemoRuntime 打，这里不重复
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit

    override fun onError(conn: WebSocket?, ex: Exception?) {
        ex?.printStackTrace()
    }

    fun broadcast(messages: List<ScreenMessage>) {
        if (!accepting || messages.isEmpty()) return
        // 一条消息一帧，圆屏那边不用拆包
        connections.forEach { conn ->
            if (conn.isOpen) messages.forEach { conn.send(it.encode()) }
        }
    }

    private fun closeClients() {
        connections.forEach { runCatching { it.close(CLOSE_POLICY, "demo lock") } }
    }

    companion object {
        const val PORT = 8443
        const val CLOSE_POLICY = 1000
        const val DEFAULT_SCREEN_ID = "WS175B-A3F7"
    }
}
