package com.carpet.satdemo.core.messages

/**
 * 卫星文本消息的**预设短语**（11-20 方案 §2.2 L3，对齐自研 APP 的 12 条）。
 *
 * 为什么是预设而不是自由输入：卫星带宽按字节算，自研 APP 的主消费形态就是点一条发一条；
 * 我们做自由输入会很别扭，做预设会又快又能演。语音里说"发一条一切正常"同样落回这张表 ——
 * **发送本身是不可逆动作，和 SOS 同一档，对话只许指到屏上按，不替用户按下**（§6.1 同一条规矩）。
 */
enum class PresetGroup(val label: String) {
    /** 报平安 */
    Report("报平安"),

    /** 求救 —— 文案必须不含「已在路上」这类平台承诺（SosCopy.BANNED 同一把尺） */
    Rescue("求救"),

    /** 应答对方 */
    Reply("应答"),

    /** 链路测试与问讯 */
    Test("测试/问讯"),
}

data class PresetPhrase(val id: String, val group: PresetGroup, val text: String) {
    /** UTF-8 上线字节数。汉字 3 字节/个，这就是"剩余 M 字节"那格账的分子 */
    val utf8Bytes: Int get() = text.toByteArray(Charsets.UTF_8).size
}

object Presets {

    /** 自研 APP 的 12 条，原样收录 —— 合作方拿自研 APP 对词，这一排必须一字不差 */
    val ALL: List<PresetPhrase> = listOf(
        PresetPhrase("test.link", PresetGroup.Test, "卫星消息测试"),
        PresetPhrase("test.status", PresetGroup.Test, "最新情况如何"),
        PresetPhrase("report.ok", PresetGroup.Report, "一切正常，请放心"),
        PresetPhrase("report.onroad", PresetGroup.Report, "正在路上，请放心"),
        PresetPhrase("report.arrived", PresetGroup.Report, "已到达目的地"),
        PresetPhrase("rescue.danger", PresetGroup.Rescue, "遇到危险，请求救援"),
        PresetPhrase("rescue.sick", PresetGroup.Rescue, "身体不适，急需救助"),
        PresetPhrase("rescue.trapped", PresetGroup.Rescue, "被困住了，急需救援"),
        PresetPhrase("reply.got", PresetGroup.Reply, "好的，收到"),
        PresetPhrase("reply.wait", PresetGroup.Reply, "好的，在原地等待"),
        PresetPhrase("reply.hurry", PresetGroup.Reply, "好的，请加快救援"),
        PresetPhrase("reply.undercontrol", PresetGroup.Reply, "好的，目前情况可控"),
    )

    val ORDERED_GROUPS: List<PresetGroup> = listOf(PresetGroup.Test, PresetGroup.Report, PresetGroup.Rescue, PresetGroup.Reply)

    fun byId(id: String): PresetPhrase? = ALL.firstOrNull { it.id == id }

    /**
     * 从一句话里认短语：先整句相等，再"那句话里包含这条短语"，最后退到关键词头几字。
     * 只做认取，不做自由发挥 —— 认不出就返回 null，让调用方把 12 条念回去。
     */
    fun match(q: String): PresetPhrase? {
        val s = q.trim()
        if (s.isEmpty()) return null
        ALL.firstOrNull { it.text == s }?.let { return it }
        ALL.firstOrNull { s.contains(it.text) }?.let { return it }
        return ALL.firstOrNull { s.contains(it.text.take(4)) }
    }

    fun of(group: PresetGroup): List<PresetPhrase> = ALL.filter { it.group == group }
}
