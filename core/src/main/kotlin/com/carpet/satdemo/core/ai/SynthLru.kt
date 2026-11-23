package com.carpet.satdemo.core.ai

/**
 * §5.1-3 合成缓存：**同一句话不重复合成**。
 *
 * 存在的理由很具体：主动播报那几句（「额度快满」「该看卫星了」…）每次冷却到点都会原样再来一轮，
 * 而现在的链路是每轮重走一遍云端、合成完就删 —— 演示一晚上同样的话念十几遍，就白打十几趟网络合成。
 * 答句基本不重复，命中主力就是这几条固定提醒和短应答。
 *
 * **按字节封顶的 LRU，不是按条数**：一句 MP3 从几十 KB 到近 1 MB（[120 字上限] 那一档），
 * 按条数要么装不下真话要么虚占。超限从最久没被用的那条挤。
 *
 * 只住内存：落盘就要管系统清理、跨进程失效、和 `local.properties` 换 key 后的旧文件 ——
 * 代价比"重启后第一条重合成一次"大得多。纯 Kotlin 放 core，是为了淘汰这一半能被真单测扫着
 * （App 侧那半挂在 `android.media` 上，JVM 测不了）。
 */
class SynthLru(private val maxBytes: Long) {
    class Entry(val bytes: ByteArray, val source: String)

    // accessOrder=true：get 也算"用过"，被反复点到的那句不会先被挤
    private val items = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var bytes = 0L

    fun get(key: String): Entry? = synchronized(this) { items[key] }

    /** 只缓存合成成功的那份；失败不占位 —— 失败重来才该真重试 */
    fun put(key: String, entry: Entry) {
        synchronized(this) {
            val old = items.put(key, entry)
            bytes += entry.bytes.size - (old?.bytes?.size ?: 0)
            while (bytes > maxBytes && items.isNotEmpty()) {
                bytes -= items.remove(items.keys.first())?.bytes?.size ?: 0
            }
        }
    }

    fun size(): Int = synchronized(this) { items.size }

    fun totalBytes(): Long = synchronized(this) { bytes }
}
