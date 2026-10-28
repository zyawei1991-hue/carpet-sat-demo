package com.carpet.satdemo.core.modem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RS232 直连 GT1005。BLE 协议文档还没给，这条是 D7 里写明的 Plan B：
 * 一根 USB 转 TTL 就能把整条业务链路在真机上验一遍，不用等文档。
 *
 * 这里只写**和指令表无关**的那部分：一问一答怎么配、超时算哪种失败、
 * 没问就推上来的下行怎么分。具体每条指令长什么样全在 [Gt1005Verbs] 里，
 * 文档到了只改那一个对象，本类的单测不用动。
 */
class UartModem(
    private val port: LinePort,
    private val verbs: Gt1005Verbs = Gt1005Verbs(),
    /** 发出去之后等星上 ACK。星上没确认 ≠ 没送达，见 [SatResult.NO_ACK] */
    private val ackTimeoutMs: Long = 60_000,
    /** 本地指令（读定位、入队这类）的应答超时，不该按分钟等 */
    private val cmdTimeoutMs: Long = 3_000,
) : SatelliteModem {

    private val downlink = ArrayList<(String) -> Unit>()
    private val lockListeners = ArrayList<(SatLock?) -> Unit>()
    private val io = Mutex()

    /** 最近一次锁星信息。MCI 之类的主动上报随时会把它刷新 */
    private var lastLock: SatLock? = null

    override val available: Boolean get() = port.isOpen

    override suspend fun readFix(): Fix? =
        ask(verbs.readFix(), cmdTimeoutMs) { verbs.fix(it) != null }?.let { verbs.fix(it) }

    override suspend fun sendMcq(hex: String): SatResult {
        if (!port.isOpen) return SatResult.FAIL
        // 半工口一次只能压一条在飞，否则应答会串到别的命令上
        return io.withLock {
            runCatching {
                port.writeLine(verbs.sendImmediate(hex))
                awaitSendResult()
            }.getOrDefault(SatResult.FAIL)
        }
    }

    override suspend fun enqueueSfq(hex: String): Boolean {
        if (!port.isOpen) return false
        return io.withLock {
            runCatching {
                port.writeLine(verbs.enqueue(hex))
                askLocked(cmdTimeoutMs) { verbs.accepted(it) } != null
            }.getOrDefault(false)
        }
    }

    override suspend fun updateLocation(lon: Double, lat: Double) {
        if (!port.isOpen) return
        io.withLock {
            runCatching { port.writeLine(verbs.pushLocation(lon, lat)) }
        }
    }

    override fun onDownlink(handler: (String) -> Unit) {
        downlink += handler
    }

    override fun onSatelliteLock(handler: (SatLock?) -> Unit) {
        lockListeners += handler
        handler(lastLock)
    }

    override suspend fun secondsToNextPass(): Long? =
        ask(verbs.ephemerisAge(), cmdTimeoutMs) { verbs.secondsToNextPass(it) != null }
            ?.let { verbs.secondsToNextPass(it) }

    override fun close() {
        port.close()
    }

    /** 发一条并等它自己的应答，中途冒出来的主动上报顺手分掉 */
    private suspend fun ask(cmd: String, timeoutMs: Long, accept: (String) -> Boolean): String? {
        if (!port.isOpen) return null
        return io.withLock {
            runCatching {
                port.writeLine(cmd)
                askLocked(timeoutMs, accept)
            }.getOrNull()
        }
    }

    private suspend fun askLocked(timeoutMs: Long, accept: (String) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val line = withContext(Dispatchers.IO) { port.readLine(left) } ?: return null
            if (accept(line)) return line
            route(line)
        }
    }

    /** 等发送结果。星上 ACK 是分钟级的，中途的锁星上报要一路吃掉 */
    // 完全没等到模块回话是 FAIL（模块没应答），等回了"星上未确认"才是 NO_ACK，
    // 这两个在展示端是不同红字，不能混
    private suspend fun awaitSendResult(): SatResult =
        askLocked(ackTimeoutMs) { verbs.sendResult(it) != null }?.let { requireNotNull(verbs.sendResult(it)) } ?: SatResult.FAIL

    private fun route(line: String) {
        when (val u = verbs.unsolicited(line)) {
            is Unsolicited.Locked -> {
                lastLock = u.lock
                lockListeners.forEach { it(lastLock) }
            }
            is Unsolicited.Downlink -> downlink.forEach { it(u.hex) }
            null -> Unit
        }
    }
}

/** 主动上报只有这两类，别在实现里塞第三种概念 */
sealed class Unsolicited {
    data class Downlink(val hex: String) : Unsolicited()
    data class Locked(val lock: SatLock?) : Unsolicited()
}

/**
 * GT1005 串口指令表。
 *
 * **这一整个类都是按 SDK 里那批动词名（COP/DII/CII/ICI/PDI/GMO/TPI/CEQ/CEI/CES/MCI/SFQ/SFI/SFP）
 * 猜的一版，没有一条经过真机确认。** 时空道宇给串口文档后，只改这里：
 * 动词、分隔符、应答前缀、字段顺序。[UartModem] 的行为和它的单测都不该跟着动。
 *
 * 之所以值得先写：硬件到货那天，接上 USB 转 TTL 就能验，不用先花一天把
 * 传输层、超时、下行分发从零搭起来。
 */
class Gt1005Verbs {

    fun readFix(): String = "GMO"
    fun sendImmediate(hex: String): String = "ICI=$hex"
    fun enqueue(hex: String): String = "SFQ=$hex"
    fun pushLocation(lon: Double, lat: Double): String = "TPI=${"%.5f".format(lon)},${"%.5f".format(lat)}"
    fun ephemerisAge(): String = "CEQ"

    /** 模块收下并入了队列 */
    fun accepted(line: String): Boolean = line.endsWith("OK")

    fun fix(line: String): Fix? {
        val f = fields(line, "GMO") ?: return null
        if (f.size < 2) return null
        val lon = f[0].toDoubleOrNull() ?: return null
        val lat = f[1].toDoubleOrNull() ?: return null
        return Fix(lon, lat, hdop = f.getOrNull(2)?.toDoubleOrNull(), ageSec = null)
    }

    /** 星历还能用多久 → 换算成距下次过境。真机口径待确认，见 sos-payload.md 的开放问题 */
    fun secondsToNextPass(line: String): Long? = fields(line, "CEQ")?.firstOrNull()?.toLongOrNull()

    fun sendResult(line: String): SatResult? = when {
        // NO_ACK 以 ACK 结尾，这个顺序反了就会把"星上没确认"读成"已送达"
        line.endsWith("NO_ACK") -> SatResult.NO_ACK
        line.endsWith("ACK") -> SatResult.COMPLETE
        line.endsWith("FAIL") || line.endsWith("ERR") -> SatResult.FAIL
        else -> null
    }

    fun unsolicited(line: String): Unsolicited? = when {
        line.startsWith("MCI=") -> Unsolicited.Downlink(line.removePrefix("MCI=").trim())
        line.startsWith("CEI") -> {
            val f = fields(line, "CEI")
            Unsolicited.Locked(if (f == null || f.firstOrNull() == "NONE") null else SatLock(f[0], f.getOrNull(1)?.toDoubleOrNull() ?: 0.0))
        }
        else -> null
    }

    private fun fields(line: String, verb: String): List<String>? {
        if (!line.startsWith(verb)) return null
        return line.removePrefix(verb).trim().removePrefix("=").split(',', ' ')
            .map { it.trim() }.filter { it.isNotEmpty() }
    }
}
