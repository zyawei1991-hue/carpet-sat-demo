package com.carpet.satdemo.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import com.carpet.satdemo.core.link.CellStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 蜂窝侧观测。产出 [CellStatus] 喂给链路状态机。
 *
 * 按 protocol/link-policy.md 的口径：**不看 dBm 绝对值**。
 * 各厂商 `SignalStrength.getLevel()` 标定不一样，演示机型号还没定，
 * 拿它当阈值会在现场抖出尴尬画面。所以主判据是"能不能真通"，等级只做辅助。
 */
class NetworkMonitor(
    context: Context,
    private val probeUrl: String = DEFAULT_PROBE_URL,
    private val onSample: (CellStatus) -> Unit,
) {
    private val ctx = context
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var job: Job? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var caps: NetworkCapabilities? = null

    @Volatile
    private var failures = 0

    @Volatile
    private var signalLevel = 0

    fun start(scope: CoroutineScope) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) {
                caps = null
                emit()
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                caps = nc
                emit()
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        refresh()
        job = scope.launch { pollForever() }
    }

    fun stop() {
        job?.cancel()
        job = null
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private suspend fun pollForever() {
        while (true) {
            // 探测周期跟状态机的 30s 对齐；蜂窝探测不该比卫星心跳还密
            delay(PROBE_INTERVAL_MS)
            val ok = runCatching { probe() }.getOrDefault(false)
            failures = if (ok) 0 else failures + 1
            refresh()
        }
    }

    /**
     * VALIDATED 有缓存，不能信，必须真发一个包。
     * 2s 超时口径来自 link-policy.md 对 cell_good 的定义。
     */
    private fun probe(): Boolean {
        val conn = URL(probeUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private fun refresh() {
        caps = cm.getNetworkCapabilities(cm.activeNetwork)
        readLevel()?.let { signalLevel = it }
        emit()
    }

    /**
     * 等级只当辅助判据，主判据是"能不能真通"（capability + 实测探测）。
     * `TelephonyManager.getSignalStrength` 要 API 28，26/27 上拿不到就保留上一次估值，
     * 不阻塞主判据 —— 低信号那条提前切换在老设备上退化成"等 capability 掉了才切"，可接受。
     */
    @Suppress("DEPRECATION")
    private fun readLevel(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            ctx.getSystemService(TelephonyManager::class.java)?.signalStrength?.level
        }.getOrNull()
    }

    private fun emit() {
        val nc = caps
        onSample(
            CellStatus(
                hasInternetCapability = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                validated = nc?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                signalLevel = signalLevel,
                consecutiveProbeFailures = failures,
            ),
        )
    }

    companion object {
        /**
         * 探测点必须国内可达。gstatic / connectivitycheck 那套在国产 ROM 上各家自用，
         * 跨机型不可靠，所以用一个全国都通的首页做 HEAD。
         * 现场若只允许运营商白名单域名，构造参数里换掉即可，不用改判据。
         */
        const val DEFAULT_PROBE_URL = "https://www.baidu.com/"
        const val PROBE_INTERVAL_MS = 30_000L
    }
}
