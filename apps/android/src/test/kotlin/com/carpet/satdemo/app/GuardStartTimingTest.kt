package com.carpet.satdemo.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **首启闪退的看护**。两台手机、100% 复现的那一次：`MainActivity.onCreate` 把权限对话框弹出去
 * （异步）之后，紧接着同一帧就把前台服务拉起来，而 Android 10 起
 * `startForeground(..., TYPE_LOCATION)` 要求调用那一刻定位权限已经批下来 —— 系统抛 SecurityException，
 * 进程当场没了。编译器和那 214 条 core 单测一条都拦不住它，因为它们都不在手机上跑。
 *
 * 所以这一条只能扫源码。它锁的是**结构**，不是那一句具体的代码：
 * 谁哪天把 `canHold` 那道门挪走、或者在别处又开一个直起服务的口子，这里立刻红。
 *
 * 工作目录是 `:app` 模块根（和 [UiCopyRedLineTest] 同一个前提）。
 */
class GuardStartTimingTest {

    private val appSrc = File("src/main/kotlin/com/carpet/satdemo/app")

    private fun src(name: String): List<String> =
        File(appSrc, name).readLines()

    /**
     * 从 [callLine] 往上找它所在的那个 `fun`，再取到该函数缩进回到同一层为止。
     * 只要函数体 —— 判据必须和调用点在同一个函数里，隔着一条调用链不算。
     */
    private fun enclosingFun(lines: List<String>, callLine: Int): String {
        val indent = lines[callLine].takeWhile { it == ' ' }.count()
        var head = callLine
        while (head > 0) {
            val l = lines[head]
            if (Regex("""^\s{0,8}(private |internal )?fun \w+""").containsMatchIn(l) &&
                l.takeWhile { it == ' ' }.count() <= indent
            ) break
            head--
        }
        val body = StringBuilder()
        var i = head
        while (i < lines.size) {
            body.appendLine(lines[i])
            if (i > head && lines[i].trim() == "}" && lines[i].takeWhile { it == ' ' }.count() <= indent) break
            i++
        }
        return body.toString()
    }

    @Test
    fun `起前台服务之前必须先问 canHold`() {
        assertTrue(appSrc.isDirectory, "找不到源码目录：${appSrc.absolutePath}")
        val offenders = appSrc.listFiles { f -> f.isFile && f.extension == "kt" }.orEmpty()
            .flatMap { f ->
                val lines = f.readLines()
                lines.indices
                    .filter { lines[it].contains("DemoGuardService.start(") }
                    .filterNot { lines[it].trimStart().startsWith("//") }
                    .filterNot { enclosingFun(lines, it).contains("canHold") }
                    .map { "${f.name}:${it + 1} ${lines[it].trim()}" }
            }
        assertTrue(
            offenders.isEmpty(),
            "这些调用点在起守护服务之前没问 canHold —— 权限没批就 startForeground 就是那次闪退：\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `startForeground 每一次都被包起来`() {
        val lines = src("DemoGuardService.kt")
        // 允许 runCatching 在上一两行（写成多行是排版，不是漏网）；离得再远就算裸调用。
        // 注释里出现这个词是在**解释为什么**，不算调用点
        fun call(i: Int) = lines[i].contains("startForeground(") &&
            !lines[i].trimStart().startsWith("*") && !lines[i].trimStart().startsWith("//")
        val bare = lines.indices
            .filter(::call)
            .filterNot { i -> (maxOf(0, i - 2)..minOf(lines.size - 1, i + 1)).any { j -> lines[j].contains("runCatching") } }
            .map { it + 1 }
        assertTrue(
            bare.isEmpty(),
            "DemoGuardService.kt 第 $bare 行的 startForeground 没被 runCatching 包住。" +
                "这一句在手机上会因权限、因清单声明、因系统版本而抛 —— 它抛了就等于把整个 App 带走",
        )
    }

    /**
     * 没有手机的日子，唯一读得到真机发生了什么的就是这一条。
     * 删掉它 = 下一次闪退还得靠用户一句"又闪退了"来报。
     */
    @Test
    fun `崩溃必须落到文件里`() {
        val app = src("AppRuntime.kt").joinToString("\n")
        assertTrue(
            app.contains("setDefaultUncaughtExceptionHandler") && app.contains("crash-"),
            "CarpetApp 里没装崩溃记录了。它必须写进 filesDir 并在下次启动读回运行时日志",
        )
    }
}
