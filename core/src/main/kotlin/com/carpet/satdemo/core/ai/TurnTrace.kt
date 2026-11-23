package com.carpet.satdemo.core.ai

/**
 * §5.1 的四段打点：**识别完 / 请求发出 / 首字回 / 首帧音频推屏**。
 *
 * 方案文档那条说得直白：现在只有识别那一段掐了表，模型一段没有数，
 * 现场说"AI 变慢了"就分不清是转写慢、模型慢还是合成+推屏慢 —— 这三段要改的地方完全不同。
 * 所以这里只是**日志**：不改协议、不加下行 frame、不碰契约（4 个点全在手机上可观测）。
 * 之后模型改流式（§5.1 第 1 条，最大收益）时，"首字回"和"首帧推屏"这两个数就是改前改后的对照。
 *
 * 住 core 的理由和 [DialogBridge] 一样：彩排台和手机跑同一条线，日志形状也得是同一份。
 */
class TurnTrace(
    private val sink: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private class Dots {
        var asrDoneMs: Long? = null
        var requestMs: Long? = null
        var firstTokenMs: Long? = null
    }

    // 有锁的一张小表：打点来自三个线程（IO 转写、桥的协程、screen-voice worker），
    // 而"纯文字答句永远等不到首帧"的那一轮必须能被挤掉，不能攒成泄漏
    private val dots = LinkedHashMap<String, Dots>()

    private fun touch(id: String): Dots = synchronized(this) {
        dots.getOrPut(id) {
            while (dots.size >= CAP) dots.remove(dots.keys.first())
            Dots()
        }
    }

    /** 屏上那句话转写成文字的那一刻（手机起头的一轮没有这一段，日志就不假装它有） */
    fun asrDone(turnId: String) {
        touch(turnId).asrDoneMs = nowMs()
    }

    fun requestSent(turnId: String) {
        touch(turnId).requestMs = nowMs()
    }

    /** 模型整段回完（非流式下"首字"==整句）；流式下第一句早到时就报，**每轮只认第一下** */
    fun firstToken(turnId: String) {
        val t = nowMs()
        val d = synchronized(this) {
            touch(turnId).also {
                if (it.firstTokenMs != null) return
                it.firstTokenMs = t
            }
        }
        val a = d.asrDoneMs
        val r = d.requestMs ?: return
        sink(
            if (a != null) "打点 $turnId：识别完→发出 ${r - a} ms，发出→首字回 ${t - r} ms"
            else "打点 $turnId：发出→首字回 ${t - r} ms",
        )
    }

    /** 这一轮第一块音频真广播给屏的那一刻 */
    fun firstAudio(turnId: String) {
        val t = nowMs()
        val d = synchronized(this) { dots.remove(turnId) } ?: return
        val r = d.firstTokenMs ?: return
        val tail = d.asrDoneMs?.let { "，全程（识别完→首帧上屏）${t - it} ms" } ?: ""
        sink("打点 $turnId：首字回→首帧推屏 ${t - r} ms$tail")
    }

    companion object {
        private const val CAP = 16
    }
}
