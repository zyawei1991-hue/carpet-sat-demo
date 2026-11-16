package com.carpet.satdemo.core.protocol

/**
 * 答句的字面 → 这张脸。
 *
 * 为什么要单独有这一步：[CardPolicy] 原来那一句是「终端在线就笑」，它的前提是
 * "链路好就是好消息"。可 #36 实拍里观众先看的是**屏上那行字**，再看脸 ——
 * 一句「卫星终端离线」配一张笑脸，他怀疑的是屏坏了，而不是链路。所以现在脸跟着
 * 说出口的那句话走。
 *
 * 判的是关键词，不是语义，所以它一定会漏掉一些说法。这个方向是有意的：
 * **坏消息先判**，宁可把一张本该平静的脸做成担心的脸，也不要在说坏事时笑。
 * 真正该由模型报表情，但那条要同时改契约、固件和一个第三方端点的输出，
 * 这一条只要改 Kotlin 一边，而且换不了现场演示的那个 bug。
 */
object AnswerMood {

    /** 从上往下第一个命中的赢。词表只收"这句里最要紧的那个事实"的说法 */
    private val TABLE: List<Pair<UiEmotion, List<String>>> = listOf(
        UiEmotion.sad to listOf(
            "离线", "不在线", "断开", "发不出", "没发出去", "没收到",
            "失败", "丢了", "坏了", "不能发", "没法", "没信号",
        ),
        UiEmotion.worried to listOf(
            "越界", "危险", "电量低", "没有数据", "没数据", "不确定",
            "不知道", "不能替", "我不能", "拒绝", "还没", "超时", "未接",
        ),
        UiEmotion.excited to listOf("过境了", "到顶了", "窗口开了", "来了", "马上"),
        UiEmotion.happy to listOf(
            "在线", "已送达", "送达", "收到", "成功", "好消息",
            "可以发", "正常", "已连上", "已应答",
        ),
        UiEmotion.thinking to listOf("正在", "等待", "还有", "计算", "稍后", "待命", "排队"),
    )

    fun of(text: String): UiEmotion =
        TABLE.firstOrNull { (_, keys) -> keys.any { text.contains(it) } }?.first ?: UiEmotion.neutral

    /**
     * 2026-11-15 §3：情绪跟场景走。卫星那套词（「过境了」「离线」「发不出去」）
     * 在亲子/助手那两页不是好消息也不是坏消息 —— 是人家根本没在聊的东西。
     * 判法不变，只是先按场景把词表滤一遍：亲子页把卫星专属的那几个词摘掉，
     * 落不进剩下词表就是中性，不硬安一张紧张的脸。
     *
     * [satelliteWords] = 这一页还认不认卫星词表（[satelliteWords]）；true = 照旧全表判。
     */
    fun of(text: String, satelliteWords: Boolean): UiEmotion {
        if (satelliteWords) return of(text)
        val hits = TABLE.mapNotNull { (emotion, keys) ->
            val kept = keys.filterNot { it in SATELLITE_ONLY }
            if (kept.isEmpty()) null else emotion to kept
        }
        return hits.firstOrNull { (_, keys) -> keys.any { text.contains(it) } }?.first ?: UiEmotion.neutral
    }

    /** 只在车载守护语境里有表情价值的关键词。亲子/助手页碰到它们不该变脸 */
    val SATELLITE_ONLY: Set<String> = setOf(
        "离线", "不在线", "发不出", "没发出去", "没收到", "没信号",
        "过境了", "到顶了", "窗口开了", "在线", "已送达", "送达", "可以发",
    )

    /**
     * 这一页还认不认卫星词表。屏自己报的亲子/助手页不认；
     * 屏没报过页就照旧全表 —— 没信息的时候别自作聪明把脸都滤平了。
     */
    fun satelliteWords(page: ScreenPage?): Boolean = page != ScreenPage.kids && page != ScreenPage.avatar
}
