package com.carpet.satdemo.core.protocol

import java.nio.file.Paths
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约漂移守卫。
 *
 * `protocol/screen-link.schema.json` 是固件侧照着**手写 cJSON** 的那份唯一事实来源，
 * Kotlin DTO 加了字段而 schema 没跟，展示端不会报错，只会静默读到 null ——
 * 于是「位置已过期 N 分钟」这种要命的红字永远不出现，得到外场才发现。
 * 所以这里不测行为，只测两边的**字段名列表一模一样**。
 */
class SchemaDriftTest {

    @Test
    fun `schema 的字段名和 Kotlin DTO 一一对得上`() {
        val root = schema()
        val cases = listOf(
            "message" to emptyList<String>(),
            "hello" to listOf("hello"),
            "state" to listOf("state"),
            "event" to listOf("event"),
            "command" to listOf("command"),
            "ack" to listOf("ack"),
            "dialog" to listOf("dialog"),
        )
        val descs = listOf<SerialDescriptor>(
            ScreenMessage.serializer().descriptor,
            HelloPayload.serializer().descriptor,
            StatePayload.serializer().descriptor,
            EventPayload.serializer().descriptor,
            CommandPayload.serializer().descriptor,
            AckPayload.serializer().descriptor,
            DialogPayload.serializer().descriptor,
        )
        // state 里再往里一层：这几块才是圆屏真正画的东西
        val inner = listOf(
            "link" to LinkState.serializer().descriptor,
            "sat" to SatState.serializer().descriptor,
            "gnss" to GnssState.serializer().descriptor,
            "fence" to FenceState.serializer().descriptor,
            "sos" to SosState.serializer().descriptor,
            "sentinel" to SentinelState.serializer().descriptor,
        )

        for (i in cases.indices) {
            checkBlock(root, cases[i].first, cases[i].second, descs[i])
        }
        for ((name, desc) in inner) {
            checkBlock(root, name, listOf("state", name), desc)
        }
    }

    @Test
    fun `schema 说的 required 在 Kotlin 侧真的没有默认值`() {
        val state = at(schema(), listOf("state"))!!
        val required = state["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val desc = StatePayload.serializer().descriptor
        val defaulted = (0 until desc.elementsCount).filter { desc.isElementOptional(it) }.map { desc.getElementName(it) }
        for (key in required) {
            assertTrue(key in props(state).keys, "state 的 required 说了个不存在的字段：$key")
            assertTrue(key !in defaulted, "$key 有默认值，App 漏发也不会报错，不该出现在 required 里")
        }
    }

    @Test
    fun `schema 说的 t 枚举和契约版本必须和 Kotlin 一致`() {
        // 固件照 schema 手写 if-else 分派，所以"代码里加了个类型但 schema 没写"= 那条类型永远解不出来。
        // dialog 就是这么差点漏掉的：Kotlin 有 MsgType.dialog、schema 没有，
        // 表现是"屏能说话、App 装了没反应"，而现场只会怀疑网络
        val root = schema()
        val types = root["properties"]!!.jsonObject["t"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        assertEquals(MsgType.values().map { it.name }.toSet(), types.toSet(), "t 的两边取值不一致")

        val versions = root["properties"]!!.jsonObject["v"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content.toInt() }
        assertEquals(ScreenCodec.SUPPORTED_VERSIONS, versions.toSet(), "schema 收的版本和 SUPPORTED_VERSIONS 不一样")
        assertTrue(ScreenCodec.CONTRACT_VERSION in versions)
    }

    @Test
    fun `dialog 的必填项在 Kotlin 侧真的没有默认值`() {
        // turn_id / dir / text 缺任何一个，这条对话都钉不回那一轮：
        // 少 turn_id 去重失效（屏上按两下出两条回答），少 dir 分不清是问还是答
        val d = at(schema(), listOf("dialog"))!!
        val required = d["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        val desc = DialogPayload.serializer().descriptor
        val defaulted = (0 until desc.elementsCount).filter { desc.isElementOptional(it) }.map { desc.getElementName(it) }
        for (key in required) {
            assertTrue(key in props(d).keys, "dialog 的 required 说了个不存在的字段：$key")
            assertTrue(key !in defaulted, "$key 有默认值，$key 就不该出现在 required 里")
        }
    }

    private fun checkBlock(
        root: JsonObject,
        name: String,
        path: List<String>,
        desc: SerialDescriptor,
    ) {
        val declared = at(root, path)?.let { props(it) }?.keys.orEmpty()
        assertTrue(declared.isNotEmpty(), "schema 里找不到 $name 的 properties，路径写错了？")
        val kotlin = (0 until desc.elementsCount).map { desc.getElementName(it) }
        assertEquals(kotlin.toSet(), declared.toSet(), "$name 两边字段名不一致，固件会照旧的那份手写解析")
    }

    /** 从根一路走到某个 schema 节点本身（不是它的 properties），空路径就是根 */
    private fun at(
        root: JsonObject,
        path: List<String>,
    ): JsonObject? {
        var node: JsonObject = root
        for (segment in path) {
            val children = node["properties"]?.jsonObject ?: return null
            node = children[segment]?.jsonObject ?: return null
        }
        return node
    }

    private fun props(o: JsonObject): JsonObject = o["properties"]?.jsonObject ?: o

    private fun schema(): JsonObject {
        val candidates = listOf(
            Paths.get("protocol", "screen-link.schema.json"),
            Paths.get("..", "protocol", "screen-link.schema.json"),
        )
        val file = candidates.firstOrNull { it.toFile().exists() }
            ?: throw IllegalStateException("找不到 protocol/screen-link.schema.json，测试必须在仓库内运行")
        return Json.parseToJsonElement(file.toFile().readText()).jsonObject
    }
}
