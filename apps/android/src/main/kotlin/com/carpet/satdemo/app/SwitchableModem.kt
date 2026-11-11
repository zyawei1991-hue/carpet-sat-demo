package com.carpet.satdemo.app

import com.carpet.satdemo.core.modem.Fix
import com.carpet.satdemo.core.modem.ModemGate
import com.carpet.satdemo.core.modem.RawLine
import com.carpet.satdemo.core.modem.SatSend
import com.carpet.satdemo.core.modem.SatelliteModem
import com.carpet.satdemo.core.modem.SatLock
import com.carpet.satdemo.core.protocol.GeodeticPosition
import com.carpet.satdemo.core.protocol.ModuleCaps
import com.carpet.satdemo.core.diagnostic.PassthroughLog

/**
 * 模组热切换门面：演示默认跑 [SimulatedModem]，BLE 连上 GT1005 后
 * 工程页开关把内部实现换成真模组（VendorSdkModem on BleBytePipe）。
 * DemoEngine / TpiTracker 持有的是这个门面，切换对它们零感知。
 */
class SwitchableModem(
    private val sim: SatelliteModem,
    private val log: (String) -> Unit,
) : SatelliteModem {

    @Volatile
    private var impl: SatelliteModem = sim

    val isReal: Boolean get() = impl !== sim

    fun switchTo(next: SatelliteModem, reason: String) {
        if (impl === next) return
        runCatching { impl.close() }
        impl = next
        log(reason)
    }

    override val available: Boolean get() = impl.available
    override val caps: ModuleCaps? get() = impl.caps
    override val supportsImmediateSend: Boolean get() = impl.supportsImmediateSend
    override val gate: ModemGate get() = impl.gate

    // 演示开关仍打在模拟模组上：BLE 真模组接通后这些彩排开关对它没有意义，
    // 但 UI 还在引用，保持不崩即可。
    var reachable: Boolean
        get() = (sim as? com.carpet.satdemo.core.modem.SimulatedModem)?.reachable ?: true
        set(value) {
            (sim as? com.carpet.satdemo.core.modem.SimulatedModem)?.reachable = value
        }

    var passWindowMs: Long
        get() = (sim as? com.carpet.satdemo.core.modem.SimulatedModem)?.passWindowMs ?: 0L
        set(value) {
            (sim as? com.carpet.satdemo.core.modem.SimulatedModem)?.passWindowMs = value
        }

    override suspend fun readFix(): Fix? = impl.readFix()
    override suspend fun sendMcq(hex: String): SatSend = impl.sendMcq(hex)
    override suspend fun enqueueSfq(hex: String): SatSend = impl.enqueueSfq(hex)
    override suspend fun updateLocation(position: GeodeticPosition): Boolean =
        impl.updateLocation(position)

    override suspend fun secondsToNextPass(): Long? = impl.secondsToNextPass()
    override fun drainDropped(): List<String> = impl.drainDropped()
    override fun onDownlink(handler: (String) -> Unit) = impl.onDownlink(handler)
    override fun onSatelliteLock(handler: (SatLock?) -> Unit) = impl.onSatelliteLock(handler)
    override fun attachPassthrough(log: PassthroughLog) = impl.attachPassthrough(log)
    override fun close() = impl.close()
}
