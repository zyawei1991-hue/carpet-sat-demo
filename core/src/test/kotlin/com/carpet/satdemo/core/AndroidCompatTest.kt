package com.carpet.satdemo.core

import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **手机有没有这个类，比编译器有没有说过。**
 *
 * V1.8 那个 APK 在两台手机上点开就闪退，一行界面都没画出来。根因是 `Providers` 用了
 * JDK 11 的 `java.net.http.HttpClient` —— **Android 到今天都没有这个包**。而它在
 * `Application.onCreate` 里就被构造（`AppRuntime` 建 provider），类初始化一碰到那个缺失的类
 * 就是 NoClassDefFoundError。
 *
 * 那一刻所有的尺子都是绿的：编译通过、`:core` 214 条单测全绿、桌面彩排台跑着同一份代码完美演示。
 * 因为**那些都在 JDK 17 上跑**。`core/` 是个纯 JVM 模块，编译器不会因为"这是 Android 的 App"
 * 而拒绝一个 JDK-only 的 API。这条看护补的就是这个洞：它不看编译目标，只看源码里出现了谁。
 *
 * 清单只收**确定没有**的，不收"要新版本才有"的：`java.time`（26+）、`java.util.function`（24+）、
 * stream（24+）这些在 minSdk 26 上都真能用，禁了只会逼人写反射绕行。
 */
class AndroidCompatTest {

    /** 从 `:core` 或 `:app` 的模块目录往上找到仓库根，两个模块的源码都扫 */
    private val repoRoot: File by lazy {
        var d = File("").absoluteFile
        while (d != null && !File(d, "settings.gradle.kts").exists()) d = d.parentFile
        requireNotNull(d) { "找不到仓库根（settings.gradle.kts），工作目录是 ${File("").absolutePath}" }
    }

    private val sources = listOf(
        File(repoRoot, "core/src/main"),
        File(repoRoot, "apps/android/src/main"),
        File(repoRoot, "simulator/src/main"),
    )

    private val banned = mapOf(
        "java.net.http." to "Android 没有这个包（JDK 11）。用 HttpURLConnection",
        "java.awt." to "Android 没有 AWT",
        "javax.swing." to "Android 没有 Swing",
        "javax.imageio." to "Android 没有 ImageIO",
        "java.util.concurrent.Flow." to "JDK 9，Android 要 API 36",
        "java.lang.StackWalker" to "JDK 9，Android 没有",
        "java.lang.ProcessHandle" to "JDK 9，Android 没有",
        "java.util.Optional." to "JDK 8，Android 只到 24，且 Kotlin 里本就不该用它",
    )

    private fun kt(f: File): List<File> = if (f.isDirectory) {
        f.walk().filter { it.isFile && it.extension == "kt" }.toList()
    } else {
        emptyList()
    }

    @Test
    fun `源码里不许出现 Android 没有的 JDK 类`() {
        val scanned = sources.filter { it.isDirectory }
        assertTrue(scanned.isNotEmpty(), "一个源码目录都没找到，工作目录：${File("").absolutePath}")
        val hits = scanned.flatMap { root ->
            kt(root).flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trimStart()
                    // 注释里提到某个类名是在解释"为什么不用它"，正是这一轮加进来的那种行
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@mapIndexedNotNull null
                    val bad = banned.entries.firstOrNull { line.contains(it.key) } ?: return@mapIndexedNotNull null
                    "${f.relativeTo(repoRoot)}:${i + 1}  ${bad.key} —— ${bad.value}"
                }
            }
        }
        assertTrue(hits.isEmpty(), "这些 API 在手机上不存在，编译期没人拦你：\n" + hits.joinToString("\n"))
    }

    /**
     * 这一条是那次闪退的直接锁：**不许在 `Application.onCreate` 的构造链上出现任何
     * "桌面有、手机不一定有"的东西**。测试能做的有限（真没有手机），但至少能要求
     * 崩溃记录器排在 runtime 之前装好 —— 否则装配现场炸的时候，连一份栈都留不下。
     */
    @Test
    fun `崩溃记录必须比 runtime 装配更早`() {
        val app = File(repoRoot, "apps/android/src/main/kotlin/com/carpet/satdemo/app/AppRuntime.kt").readText()
        val installer = app.indexOf("setDefaultUncaughtExceptionHandler")
        val wiring = app.indexOf("appRuntime.wireCommands()")
        assertTrue(installer > 0 && wiring > 0, "AppRuntime.kt 里既没装崩溃记录，也没接指令线")
        assertTrue(
            installer < wiring,
            "崩溃记录器必须在 appRuntime 装配之前装上：装配本身崩的时候，才留得下那一份栈",
        )
    }

    /** `HttpURLConnection` 是这次换的传输，形状错了等于没接模型，所以断言到字节 */
    @Test
    fun `模型请求的体是合法的 OpenAI 形状`() {
        val seen = ArrayList<Triple<String, String, Map<String, String>>>()
        val p = com.carpet.satdemo.core.ai.OpenAiCompatProvider(
            com.carpet.satdemo.core.ai.AiEndpoint("https://x.test/v1", "k", "m"),
        ) { url, body, headers ->
            seen += Triple(url, body, headers)
            """{"choices":[{"message":{"content":"你好"}}]}"""
        }
        val out = kotlinx.coroutines.runBlocking { p.complete("事实", emptyList(), "链路怎么样") }
        assertTrue(out == "你好", "解不出 choices：$out")
        val (_, body, headers) = seen.single()
        assertTrue(headers["Authorization"] == "Bearer k" && headers["Content-Type"] == "application/json", "$headers")
        val parsed = com.carpet.satdemo.core.ai.OpenAiCompatProvider.parse(body)
        assertTrue(parsed == null, "请求体不是返回体，parse 应当解不出内容：$parsed")
        assertTrue(
            body.startsWith("{\"model\":\"m\"") && body.contains("\"role\":\"user\"") && body.endsWith("}"),
            "请求体形状不对：$body",
        )
    }
}
