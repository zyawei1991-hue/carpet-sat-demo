package com.carpet.satdemo.app

import com.carpet.satdemo.core.ephemeris.EphResult
import com.carpet.satdemo.core.ephemeris.EphState
import com.carpet.satdemo.core.ephemeris.EphemerisSource
import com.carpet.satdemo.core.ephemeris.PassRequest
import com.carpet.satdemo.core.ephemeris.SatellitePass
import com.example.ephemerislibrary.StarManagerFactory
import com.example.ephemerislibrary.bean.CalculateResultBean
import com.example.ephemerislibrary.bean.RequestBean
import com.example.ephemerislibrary.bean.ResultBean
import com.example.ephemerislibrary.interfaces.StarManager
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * 星历 SDK（`ephemerislibrary-release.aar`）到 [EphemerisSource] 的适配（E.10 #7 / E.9）。
 *
 * **它和基础通信 SDK 的线程模型是反的**（附录 E.8）：星历要求**在主线程创建、主线程回调**，
 * 而基础通信的回调全在 RxJava 子线程。这条约束不会有任何编译期提示，
 * 违反它的症状是"calculate 从来不回调"——所以整个类只有一个入口 [onMain]，
 * 每次碰到 SDK 都从这里过，别在调用方"顺手"直调。
 *
 * core 那一层拿到的是 suspend 接口，`Looper` 一个字都没漏进去。
 */
class StarManagerEphemeris(
    context: Context,
    /** 一次 calculate 的等待上限。厂商把 TIME_OUT 定义成 SDK 内部超时，这里是**外面**那层保险 */
    private val calculateTimeoutMs: Long = 20_000,
    /** [update] 等数据状态回调的上限。星历要联网下载，给到 30 秒 */
    private val updateTimeoutMs: Long = 30_000,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : EphemerisSource {

    private val appContext = context.applicationContext

    /**
     * 星历数据时间戳。来源是 `getStarManager(context, Consumer<Long>)` 那个回调（〔SDK文 §4〕），
     * **不是** calculate 的结果 —— 所以它可能比会话老，展示端要显示"星历是几点的数据"就靠它。
     */
    private val dataTimestampMs = AtomicReference<Long?>(null)

    /** 数据状态回调同时是 update() 的完成信号：无界通道接，谁先到都不漏 */
    private val dataStateChannel = Channel<Long>(Channel.UNLIMITED)

    private var manager: StarManager? = null

    /**
     * 创建 + 首次回调注册必须在主线程（〔SDK文 §6〕）。双检锁在这个类里不必要：
     * 所有路径都过 [onMain]，天然串行。
     */
    private suspend fun onMain(): StarManager = withContext(main) {
        manager ?: StarManagerFactory.getStarManager(appContext) { ms ->
            // 〔SDK文 §4〕-1 = 无数据；0 = 未更新；>0 = 数据时间戳
            if (ms != null && ms > 0L) {
                dataTimestampMs.set(ms)
                dataStateChannel.trySend(ms)
            }
        }.also { manager = it }
    }

    /**
     * 拉取新星历（〔SDK文 §5.2 / §8〕每日在有网时一次）。
     *
     * ⚠️ `StarManager.update()` **返回 void**：成功与否只能从数据状态回调反推。
     * 所以这里等的是一个"新的、大于 0 的时间戳"，等不到就返回 null 让上层显示 NETWORK_ERROR。
     *
     * 这里 catch 的是 **Throwable 而不是 Exception**：星历 aar 带着 `libdecodeutil.so`，
     * ABI 不匹配或系统 WebView 版本不够时抛的是 `UnsatisfiedLinkError`/`NoClassDefFoundError`
     * —— 那属于"插件边界"，降级成"星历暂时算不出来"比把演示弄崩更有用。
     * 边界以内的代码不许这么写。
     */
    override suspend fun update(): Long? {
        val before = dataTimestampMs.get()
        runCatching { onMain().update() }.onFailure { return null }
        val arrived = withTimeoutOrNull(updateTimeoutMs) {
            var next: Long? = null
            while (next == null) {
                val ts = dataStateChannel.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                if (ts != before) next = ts
            }
            next
        }
        return arrived ?: dataTimestampMs.get().takeIf { it != null && it != before }
    }

    /**
     * 算未来几小时的过境窗口。
     *
     * 参数顺序按〔SDK文 §5.1.1〕：`(lon, lat, alt, elevation, hour)` —— **经度在前**，
     * 和基础通信那条 `$CC TPI` 语句（纬度在前）正好相反。这里靠 [PassRequest] 的具名字段兜住，
     * 到了这一行已经是唯一一处可能写反的地方，出问题只看这一行。
     *
     * `hour` 是 Int：不足 1 小时的窗口没有意义，所以向上取整而不是截断。
     */
    override suspend fun calculate(request: PassRequest): EphResult = withContext(main) {
        val bean = RequestBean(
            request.position.longitude.degrees,
            request.position.latitude.degrees,
            request.altitudeM,
            request.minElevationDeg,
            request.horizonHours.toInt().coerceAtLeast(1),
        )
        val ch = Channel<ResultBean>(Channel.UNLIMITED)
        // 主线程回调（〔SDK文 §6〕）：通道无界，回调里不挂起
        runCatching { onMain().calculate(bean) { result -> ch.trySend(result) } }
            .onFailure { return@withContext EphResult(EphState.CALCULATION_FAILED, emptyList(), dataTimestampMs.get()) }
        val result = withTimeoutOrNull(calculateTimeoutMs) { ch.receiveCatching().getOrNull() }
            ?: return@withContext EphResult(EphState.TIME_OUT, emptyList(), dataTimestampMs.get())
        result.toEphResult(dataTimestampMs.get())
    }

    /**
     * 〔SDK文 §5.3 / §7〕不用时释放，省电也省内存。
     *
     * 这里**没有**跳主线程：`release()` 不发网络请求、也不回调任何人，
     * 厂商那条"主线程"约束针对的是创建和 calculate 回调（〔SDK文 §6〕）。
     * 真机若在这里崩，就把它挪进 [onMain] —— 但别提前写一个没人验证过的分支。
     */
    override fun release() {
        val m = manager ?: return
        manager = null
        runCatching { m.release() }
        dataTimestampMs.set(null)
    }

    companion object {
        /**
         * ResultBean → EphResult。**四个状态一个都不合并**（〔SDK文 §5.1.2〕）：
         * NETWORK_ERROR 该去联网，CALCULATION_FAILED 该去调仰角阈值，
         * 合并成一句"星历不可用"就把用户往错的方向支使了（E.9）。
         */
        internal fun ResultBean.toEphResult(timestampMs: Long?): EphResult {
            val state = when (resultState) {
                ResultBean.State.SUCCESS -> EphState.SUCCESS
                ResultBean.State.NETWORK_ERROR -> EphState.NETWORK_ERROR
                ResultBean.State.TIME_OUT -> EphState.TIME_OUT
                ResultBean.State.CALCULATION_FAILED -> EphState.CALCULATION_FAILED
                null -> EphState.CALCULATION_FAILED
            }
            val passes = if (state == EphState.SUCCESS) {
                // 厂商保证按开始时间排序（〔SDK文 §5.1.3〕），这里再排一次是防御性的：
                // 排序错了展示端会显示一个"已经过去的窗口"，那比报错更糟
                result.orEmpty().sortedBy { it.startTime }.map { it.toPass() }
            } else {
                emptyList()
            }
            return EphResult(state, passes, timestampMs)
        }

        internal fun CalculateResultBean.toPass(): SatellitePass = SatellitePass(
            starName = starName ?: "?",
            startMs = startTime,
            endMs = endTime,
            startAzimuthDeg = startAzimuth,
            endAzimuthDeg = endAzimuth,
            maxElevationDeg = maxElevation,
        )
    }
}
