package com.carpet.satdemo.core.devices

/**
 * 设备总线：所有外接硬件在 App 里的唯一登记处。
 *
 * 为什么要这一层（§5.3 硬要求 #2、§8 R1 第一件事）：圆屏、GT1005、手机自身定位、
 * 以后的戒指/手环，原来各自有一套生命周期，各自在 UI 里解释"我现在的状态"。
 * 越加硬件越乱，digivita 就是在这儿长歪的（附录 D）。
 * 收进来之后有三件事变得可能：**切场景不断链**（总线不属于任何场景）、
 * 「设备管理」那一页有真数据（不再是桩）、**卡片按能力出现**有一条统一的判据。
 */
enum class DeviceKind {
    /** GT1005 卫星终端 */
    SatelliteModule,

    /** 微雪 1.75 圆屏等展示端 */
    DisplayScreen,

    /** 手机自己：定位 + 蜂窝，不是外设，但要上台账，否则用户不知道位置从哪来 */
    PhoneSensor,

    /** 手环/戒指（§3.4 健康助手） */
    Wearable,

    /** 儿童手表/陪伴终端（§3.3 亲子） */
    ChildDevice,

    /** 前装车（§5.1 车载；本轮只有桩，但槽位先留出来） */
    Vehicle,
}

/**
 * 链路怎么建立的。这个字段是给用户看的，不是给调试用的：
 * "BLE 已连"和"串口在跑"在演示现场是完全不同的处置方式（后者动一下线就没了）。
 */
enum class Transport(val label: String) {
    Uart("串口"),
    Ble("蓝牙"),
    LanWs("局域网"),
    Internal("本机"),
    Unknown("未知"),
}

/**
 * 设备的在线状态。**四态，不是布尔** —— 这是本模块存在的理由。
 *
 * [Offline] 和 [Absent] 必须分开：GT1005 掉线时用户最需要看到那张卡（§5.3 注），
 * 而"这台设备我们从来没见过来"不该占一行。
 */
sealed interface DeviceLink {
    /** 登记过、但此刻链路不在。要上屏，并说清最后一次见到是什么时候 */
    data class Offline(val lastSeenMs: Long, val why: String? = null) : DeviceLink

    /** 正在握手：串口在开、WebSocket 在连、蓝牙在扫 */
    data class Connecting(val since: Long) : DeviceLink

    /** 链路通，且已经交换过身份信息 */
    data class Online(val since: Long) : DeviceLink

    /**
     * 链路通，但**不能当正常设备用**：契约版本不匹配、无效卡、没授权。
     * 这一态存在的意义是"别把它画成绿的"。
     */
    data class Degraded(val since: Long, val why: String) : DeviceLink

    /** 这条链路在本机根本不存在（没有该硬件、或该场景不配）。卡片据此不渲染 */
    object Absent : DeviceLink
}

/**
 * 台账那一行的颜色语义。**只能由 [Device.tone] 出**，别在卡片里再判一次 ——
 * 三处界面（App、圆屏、彩排台）各判一次的话，同一台设备会同时是绿的和黄的。
 */
enum class Tone { Ok, Wait, Alert, Neutral }

/**
 * 一台设备的台账条目。
 *
 * [title] 和 [detail] 由登记方给（不要让 UI 拼 —— 圆屏那三行字在固件、App、彩排台上会出现三次）。
 */
data class Device(
    val id: String,
    val kind: DeviceKind,
    val title: String,
    val transport: Transport,
    val link: DeviceLink,
    /** 型号、固件版本、接入点这类"现场要念出来"的话，一条一行 */
    val detail: List<String> = emptyList(),
    /** 可撤销的动作。空列表 = UI 不画按钮 */
    val actions: List<String> = emptyList(),
) {
    val online: Boolean get() = link is DeviceLink.Online || link is DeviceLink.Degraded

    /** 台账上那一行的颜色语义。红/黄/绿只能由这里出，别在卡片里再判一次 */
    val tone: Tone
        get() = when (link) {
            is DeviceLink.Online -> Tone.Ok
            is DeviceLink.Degraded -> Tone.Wait
            is DeviceLink.Connecting -> Tone.Wait
            is DeviceLink.Offline -> Tone.Alert
            DeviceLink.Absent -> Tone.Neutral
        }
}

/**
 * 设备台账。**只认最新一次登记，不做缓存也不做历史** —— 历史属于日志窗（PassthroughLog），
 * 台账只回答一个问题："现在手上有什么、各自处于什么状态"。
 *
 * 线程约定：App 侧从主线程和 WebSocket 线程两边写，所以内部用同步块而不是并发容器 ——
 * 我们要的是"整份快照一起换"，逐条原子没有意义（快照半新半旧会画出一台不存在的设备）。
 */
class DeviceBus {

    private val entries = LinkedHashMap<String, Device>()
    private val listeners = ArrayList<(List<Device>) -> Unit>()

    @Volatile
    private var snapshot: List<Device> = emptyList()

    /** 当前快照。给 StateFlow 用，免得像 UI 那样订阅 */
    fun current(): List<Device> = snapshot

    fun subscribe(listener: (List<Device>) -> Unit) {
        synchronized(entries) { listeners += listener }
        listener(snapshot)
    }

    fun unsubscribe(listener: (List<Device>) -> Unit) {
        synchronized(entries) { listeners -= listener }
    }

    /** 登记/覆盖一台设备。id 相同就是同一台，整条换掉（不做字段合并 —— 合并会留下上一次的 why） */
    fun upsert(device: Device) {
        synchronized(entries) {
            entries[device.id] = device
            publishLocked()
        }
    }

    /**
     * 每秒同步用的登记：**状态没变就不动 `since`，也不通知订阅者**。
     *
     * 为什么不能直接用 [upsert]：台账里的在线时长（"已连 3 分 12 秒"）是现场判断
     * "这台屏是不是在反复掉线重连"的唯一依据，而 1Hz 的重新登记会把它永远刷成 0 秒。
     */
    fun merge(device: Device) {
        synchronized(entries) {
            val old = entries[device.id]
            if (old != null && old.link.javaClass == device.link.javaClass &&
                (old.detail == device.detail || old.detail.isEmpty()) && old.title == device.title
            ) {
                val kept = device.copy(link = keepSince(old.link, device.link))
                entries[device.id] = kept
                if (old == kept) return
                publishLocked()
                return
            }
            entries[device.id] = device
            publishLocked()
        }
    }

    private fun keepSince(old: DeviceLink, next: DeviceLink): DeviceLink = when {
        old is DeviceLink.Online && next is DeviceLink.Online -> old
        old is DeviceLink.Degraded && next is DeviceLink.Degraded && old.why == next.why -> old
        else -> next
    }

    /** 只改状态，其余字段留着（掉线时用户还要看得见型号和最后一次过顶） */
    fun relink(id: String, link: DeviceLink) {
        synchronized(entries) {
            val old = entries[id] ?: return
            entries[id] = old.copy(link = link)
            publishLocked()
        }
    }

    /** 摘掉。[DeviceKind.DisplayScreen] 断开要用它，否则台账会留一排"永远离线"的幽灵屏 */
    fun drop(id: String) {
        synchronized(entries) {
            entries.remove(id)
            publishLocked()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
            publishLocked()
        }
    }

    fun byKind(kind: DeviceKind): List<Device> = snapshot.filter { it.kind == kind }

    fun find(id: String): Device? = snapshot.firstOrNull { it.id == id }

    private fun publishLocked() {
        // 排序规则固定：卫星终端永远第一（它是这个产品的主轴），其余按类别再按 id
        snapshot = entries.values.sortedWith(
            compareBy({ it.kind.ordinal }, { it.id }),
        )
        val shot = snapshot
        listeners.forEach { it(shot) }
    }
}
