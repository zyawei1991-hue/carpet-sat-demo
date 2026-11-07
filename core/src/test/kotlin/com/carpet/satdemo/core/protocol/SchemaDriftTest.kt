package com.carpet.satdemo.core.protocol

import java.nio.file.Paths
import org.junit.jupiter.api.Assumptions
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
            "ui" to UiState.serializer().descriptor,
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
        val root = schema()
        val blocks = listOf(
            listOf("state") to StatePayload.serializer().descriptor,
            // ui 一旦带上来就必须说清画哪张卡；整个 ui 缺失是另一回事（那边允许，屏画总览）
            listOf("state", "ui") to UiState.serializer().descriptor,
        )
        for ((path, desc) in blocks) {
            val node = at(root, path)!!
            val required = node["required"]!!.jsonArray.map { it.jsonPrimitive.content }
            val defaulted = (0 until desc.elementsCount).filter { desc.isElementOptional(it) }.map { desc.getElementName(it) }
            for (key in required) {
                assertTrue(key in props(node).keys, "$path 的 required 说了个不存在的字段：$key")
                assertTrue(key !in defaulted, "$key 有默认值，App 漏发也不会报错，不该出现在 required 里")
            }
        }
    }

    @Test
    fun `ui 的 card 和 emotion 取值两边一模一样`() {
        // 固件照 schema 那串枚举手写「这张卡画哪几行、这张脸画几道弧」的分支表。
        // App 的枚举多一个值，屏上不会报错，只会**默默落回兜底那一支** ——
        // 现场看到的就是「手机点了没反应」，然后去怀疑网络。dialog 当年差点就是这么漏的
        val ui = at(schema(), listOf("state", "ui"))!!["properties"]!!.jsonObject
        assertEquals(UiCard.values().map { it.name }.toSet(), enumValues(ui, "card"), "card 的两边取值不一致")
        assertEquals(UiEmotion.values().map { it.name }.toSet(), enumValues(ui, "emotion"), "emotion 的两边取值不一致")
    }

    @Test
    fun `页表三份必须同字：ScreenPage、ui 的回声、dialog 的跳页`() {
        // 这是**同一张表的三个抄本**，不是三个概念：屏报上来的页名、手机回声出去的页名、
        // 答句要求跳去的页名，必须是同一串字符串。它们各自演化一次，现场就多一种
        // "屏上已经在亲子页，手机那一格写着不认识" 的分叉 —— 而两侧都不会报错
        val ui = at(schema(), listOf("state", "ui"))!!["properties"]!!.jsonObject
        val dialog = at(schema(), listOf("dialog"))!!["properties"]!!.jsonObject
        val want = ScreenPage.entries.map { it.name }.toSet()
        assertEquals(want, enumValues(ui, "page"), "ui.page 和 ScreenPage 不一致")
        assertEquals(want, enumValues(dialog, "action"), "dialog.action 和 ScreenPage 不一致")
        // 跳页只能是页，不能是一条技能名或一个动作：这条一旦松，
        // "云端代答的一句话替车主按下了呼救"就从"构造不出来"变成"要看模型今天怎么写"
        assertTrue("sos_press" !in want && "sos" !in want, "action 的取值表里混进了呼救，它只该是页")
    }

    @Test
    fun `page_report 在契约里，set_demo_mode 不许当场景通道`() {
        // 两条一起写，是因为它们是一件事的两面：另一台机器提议复用 set_demo_mode 来报"屏进了哪个场景"，
        // 而那个名字在手机上已经是 Tab4 那个「演示模式」开关（把等待和窗口压到几分钟）。
        // 一个名字在两侧各说一件事，正是这套测试一直在防的分叉，所以这里给它立一条**有名字的**替代通道
        val cmd = enumValues(at(schema(), listOf("command"))!!["properties"]!!.jsonObject, "cmd")
        assertTrue("page_report" in cmd, "屏报页的通道不在契约里，那固件发的就是野指令")
        // 手机侧必须有页名可解：CommandType 里没有它，Kotlin 解整帧时会直接失败并关掉这条连接
        assertTrue(CommandType.entries.any { it.name == "page_report" }, "CommandType 没有 page_report")
        // 而页名的合法取值是 ScreenPage，不是"任意字符串"。schema 里 args 是个自由 object，
        // 所以这一条只能靠手机端的校验兜住：不认识的页名一律拒收
        val page = at(schema(), listOf("state", "ui"))!!["properties"]!!.jsonObject
        assertEquals(ScreenPage.entries.map { it.name }.toSet(), enumValues(page, "page"))
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
    fun `v3 那三样必须两边同名：maint 卡、audio_chunk、hello_screen`() {
        // 一条 v3 拆成三处，是为了让它能在**任何一处漏抄**时当场炸：
        // maint 漏在固件那张表里 = 手机上点了没反应（屏默默落回 status），
        // audio_chunk 漏在 CommandType = 手机解整帧失败、直接关掉这条连接，
        // hello.screen 只在 schema 写了而 Kotlin 没这个字段 = 屏报了，手机看不见，等于没报
        val cmd = enumValues(at(schema(), listOf("command"))!!["properties"]!!.jsonObject, "cmd")
        assertTrue("audio_chunk" in cmd, "语音通道不在契约里，屏收到的就是野指令")
        assertTrue(CommandType.entries.any { it.name == "audio_chunk" }, "CommandType 没有 audio_chunk")

        val ui = at(schema(), listOf("state", "ui"))!!["properties"]!!.jsonObject
        assertTrue("maint" in enumValues(ui, "card"), "schema 的 card 没有 maint，UiCard 多出来的那档没人认")

        val hello = props(at(schema(), listOf("hello"))!!)
        assertTrue("screen" in hello.keys, "hello 没申报屏幕几何，产品架构 §4 那条实现要求还挂着")
        val screenRequired = at(schema(), listOf("hello", "screen"))!!["required"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        for (key in screenRequired) {
            assertTrue(key in props(at(schema(), listOf("hello", "screen"))!!).keys, "hello.screen 的 required 说了个不存在的字段：$key")
        }
        val desc = ScreenGeom.serializer().descriptor
        val wireNames = (0 until desc.elementsCount).map { desc.getElementName(it) }
        assertTrue(wireNames.containsAll(screenRequired), "ScreenGeom 少了解析 hello.screen 必填项的能力：$wireNames")
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

    @Test
    fun `健康态那一帧里 module_ok 和 queue_pending 必须真的在线上`() {
        // 2026-11-02 真机 11 步实拍：卫星卡每一步都画「卫星终端离线 / --:--」，而手机那边
        // sat_ok=true、星锁着 GW-07。串口一个错都没报，因为屏读不到键就不动缓存，
        // 而缓存是开机 memset 出来的 false。根因就是这个字段带 `= true` 默认值，
        // 被 encodeDefaults=false 省掉了。这类错只有把像素读回来才看得见
        val wire = ScreenCodec.json.encodeToString(
            SatState.serializer(),
            SatState(phase = SatPhase.idle, moduleOk = true, queuePending = 0),
        )
        assertTrue("\"module_ok\":true" in wire, "终端健康时 module_ok 没上线，屏会永远停在离线文案：$wire")
        assertTrue("\"queue_pending\":0" in wire, "队里 0 条时省掉这个键，屏会画「待补发 -」（= 手机还没算）：$wire")
    }

    @Test
    fun `屏用「缺了等于不知道」读的字段，schema 列了必填 Kotlin 就必须真的必填`() {
        // 固件的 bool_of / int_of 找不到键就不写出参，而出参缓存来自 memset(0)。
        // schema 是固件手写 cJSON 的唯一事实来源，所以钉这一对：schema 说必填的，
        // Kotlin 那边就不许带默认值 —— 带了就等于"等于默认值的那一帧它整个不上线"
        val mustRequire = mapOf(
            "sat" to listOf("module_ok", "queue_pending"),
        )
        val descs = mapOf(
            "sat" to SatState.serializer().descriptor,
        )
        for ((block, keys) in mustRequire) {
            val required = at(schema(), listOf("state", block))!!["required"]!!
                .jsonArray.map { it.jsonPrimitive.content }
            val desc = descs[block]!!
            for (key in keys) {
                assertTrue(key in required, "schema 里 $block.$key 不是必填，固件就不该依赖它；要依赖得先补 schema")
                val idx = (0 until desc.elementsCount).firstOrNull { desc.getElementName(it) == key }
                    ?: error("$block 里找不到 $key")
                assertTrue(!desc.isElementOptional(idx), "$block.$key 带默认值，encodeDefaults=false 会在最该有它的那一帧省掉它")
            }
        }
    }

    @Test
    fun `固件手写的枚举手必须和 schema 的取值一字不差`() {
        // app_main.c 里那几张 `static const char *const *_NAMES` 是**照这份 schema 手抄**的分支表：
        // App 加了个取值而表没跟，屏不会报错，只会默默落回兜底那一支，
        // 画出来是一个没人认识的词或者一句不相干的话（#31 那条实拍就是这么来的）。
        // 光有 Kotlin↔schema 的比对拦不住这一环，所以这里直接把那份 C 读回来比
        val root = schema()
        val legacy = firmwareSource()
        Assumptions.assumeTrue(legacy != null,
            "老固件树 firmware/screen/app/ 不在仓库里，这台机器比不了（Muse 那一路见「屏要发的指令」那条）")
        val src: String = legacy ?: return
        val ui = at(root, listOf("state", "ui"))!!["properties"]!!.jsonObject
        val link = at(root, listOf("state", "link"))!!["properties"]!!.jsonObject
        val sat = at(root, listOf("state", "sat"))!!["properties"]!!.jsonObject
        val event = at(root, listOf("event"))!!["properties"]!!.jsonObject
        val cases = listOf(
            "CARD_NAMES" to enumValues(ui, "card"),
            "EMOTION_NAMES" to enumValues(ui, "emotion"),
            "PHASE_NAMES" to enumValues(sat, "phase"),
            "PRIMARY_NAMES" to enumValues(link, "primary"),
            "EVENT_NAMES" to enumValues(event, "kind"),
        )
        for ((table, want) in cases) {
            val got = cStringTable(src, table).toSet()
            if (table == "CARD_NAMES") {
                // v3 之后这一张**允许少档**：契约新加的那档（maint）老固件本来就该画不出来，
                // 而 v3 的规矩是"收到不认识的档退回 status" —— 少一档是设计，多一档才是漂移。
                // 老树不在主线上了（板上跑的是 Muse），所以它不该按住整条守卫；
                // **全表相等那一条挪到下面那条 Muse 的比里**，因为那才是现在上板的那份固件
                assertTrue(got.all { it in want }, "CARD_NAMES 里有 schema 没有的档：${got - want}")
            } else {
                assertEquals(want, got, "$table 和 schema 对不上：固件多的是 ${got - want}，缺的是 ${want - got}")
            }
        }
        // 中文那一列的条数必须和键名列一样：少一格就是有取值没配文案。
        // 那种漏法在屏上表现为"永远画兜底那句"，而固件一条日志都不报
        val cnPairs = listOf(
            "PHASE_CN" to "PHASE_NAMES",
            "PRIMARY_CN" to "PRIMARY_NAMES",
            "EMOTION_CN" to "EMOTION_NAMES",
        )
        for ((cn, names) in cnPairs) {
            assertEquals(cStringTable(src, names).size, cStringTable(src, cn).size,
                "$cn 的条数和 $names 不一样，有相位/链路/表情没配中文")
        }

        // 上行指令是**四张**表而不是一张：schema 的 enum、Kotlin 的 CommandType、
        // 屏上真会发的那几条（COMMAND_NAMES），再加组件里那道合法值闸（ALLOWED）。
        // 屏发的必须是 schema 的子集（track_pull / set_fence / reboot_ui 是手机侧和工程侧用的，屏不发；
        // set_demo_mode 是 Tab4 那个「演示模式」开关，**不是场景通道**，报场景走 page_report），
        // 但子集里任何一条过不了组件那道闸就压根发不出去 ——
        // 那种漏法的现场现象是"按了没反应"，而串口里一条契约错都没有，是最难查的那一类
        val cmd = enumValues(at(root, listOf("command"))!!["properties"]!!.jsonObject, "cmd")
        assertEquals(CommandType.values().map { it.name }.toSet(), cmd, "CommandType 和 schema 的 cmd 对不上")
        val screenSends = cStringTable(src, "COMMAND_NAMES").toSet()
        assertTrue(screenSends.all { it in cmd }, "屏发的指令不在 schema 里：${screenSends - cmd}")
        val gated = cStringTable(componentSource(), "ALLOWED").toSet()
        assertTrue(screenSends.all { it in gated }, "组件那道闸挡掉了屏要发的指令：${screenSends - gated}")
        // 屏只能"请求"，不能指定画哪张卡。契约里出现 card_set 就是有人把定卡权下放给了屏，
        // 那一放，同一辆车会在手机和屏上讲两件事，而文案红线只约束得过网关那一路
        assertTrue("card_set" !in cmd, "契约里不该出现让屏直接定卡的取值")
        // 名字进表、组件放行，但固件里一处都没发 —— 那种指令在屏上是死的，而三份文档都写着"支持"。
        // #39 加那三条上行时正好就停在这个状态（表齐全、手势没有），所以这条比表本身更该钉住
        for (gesture in listOf("CMD_CARD_NEXT", "CMD_CARD_AUTO", "CMD_POKE")) {
            assertTrue("send_cmd($gesture" in src, "固件分支表里有 $gesture 但没有一处真的发出去：屏上那个动作是死的")
        }
    }

    @Test
    fun `Muse 补丁里屏要发的指令必须既在契约里又过得了组件那道闸`() {
        // 上一条靠老 app_main.c，那棵树不在仓库里，换机器就比不动。这一条读的是**仓库里就有的**
        // `muse-carpet.patch`：换底座之后屏真会发的指令写在其代码块里（CMD_NAMES 只放交互那三条，
        // 组件的 ALLOWED 还含 sos_press 等六条）。两边都是仓库内的文件，谁 clone 下来都拦得住
        val cmd = enumValues(at(schema(), listOf("command"))!!["properties"]!!.jsonObject, "cmd")
        val patch = musePatch()
        val screenSends = cStringTable(patch, "CMD_NAMES").toSet()
        assertTrue(screenSends.isNotEmpty(), "补丁里没读到 CMD_NAMES，那这一条等于没比")
        assertTrue(screenSends.all { it in cmd }, "屏要发的指令不在 schema 里：${screenSends - cmd}")
        val gated = cStringTable(componentSource(), "ALLOWED").toSet()
        assertTrue(screenSends.all { it in gated }, "组件那道闸挡掉了屏要发的指令：${screenSends - gated}")
        assertTrue("card_set" !in cmd, "契约里不该出现让屏直接定卡的取值")
        assertTrue("page_set" !in cmd, "契约里不该出现让手机直接定页的取值（定页权已经交给屏，见 DECISIONS D10）")

        // 屏侧那张页表**现在还不存在**，所以这里不能 assumeTrue 跳过（多一条常年 skip 等于没有守卫，
        // 而且交接文档里"至多 N 条 skip"那条判据会被它拖脏）。写法是：它一旦出现就必须一模一样。
        // 表名定死 `PAGE_NAMES` —— 交接手册里给的就是这个名字，改了名这条会静默失效，所以顺手钉一下
        val table = Regex("static const char \\*const PAGE_NAMES\\[[^\\]]*]\\s*=\\s*\\{(.*?)};", RegexOption.DOT_MATCHES_ALL)
            .find(patch)
        if (table != null) {
            val pages = Regex("\"([^\"]*)\"").findAll(table.groupValues[1]).map { it.groupValues[1] }.toSet()
            val want = ScreenPage.entries.map { it.name }.toSet()
            assertEquals(want, pages, "屏侧 PAGE_NAMES 和 ScreenPage 对不上：多的是 ${pages - want}，缺的是 ${want - pages}")
        }

        // 板上跑的是这份补丁，所以**全表相等那一条在这儿**（老树那半边只要求"不多不少档"）：
        // 少一格 maint 不会报错，只会让手机推来的那条建议永远落回状态总览 ——
        // 现场看到的就是"手机上明明列了该补气，屏上一个字没提"
        val cardTable = Regex("static const kv_t UI_CARD\\[\\][^=]*=\\s*\\{(.*?)};", RegexOption.DOT_MATCHES_ALL)
            .find(patch)
        if (cardTable != null) {
            val keys = Regex("\\{\\s*\"([^\"]+)\"").findAll(cardTable.groupValues[1])
                .map { it.groupValues[1] }.toSet() - "NULL"
            val wantCards = enumValues(at(schema(), listOf("state", "ui"))!!["properties"]!!.jsonObject, "card")
            assertEquals(wantCards, keys, "补丁里 UI_CARD 那张表和 schema 的 card 对不上：多的是 ${keys - wantCards}，缺的是 ${wantCards - keys}")
        }
    }

    @Test
    fun `schema 让 hello 带的电量，固件那手写的一帧里必须真的有`() {
        /* 上面第一条比的是 schema ↔ Kotlin 的字段名，管不到 C：C 侧是手搓 JSON 的，
           少写一行不会编译错、不会契约错，手机那边只是永远显示「外接供电」——
           而那句和"这块屏没装电池"长得一模一样，靠眼睛在外场分辨不出来 */
        val declared = props(at(schema(), listOf("hello"))!!).keys
        assertTrue("battery_mv" in declared, "schema 的 hello 里没有 battery_mv，那固件发它就是在发野字段")

        val hello = componentSource()
            .substringAfter("static void send_hello")
            .substringBefore("static void send_bye")
        assertTrue("battery_mv" in hello, "固件的 send_hello 里没带 battery_mv：手机会一直显示「外接供电」")
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

    /**
     * schema 的 enum 里允许写 `null` 这个取值（意思是「这一项没定」），
     * Kotlin 那边对应的是可空类型本身而不是一个叫 null 的枚举名，比对时摘掉。
     * JsonNull 的 content 就是字符串 "null"，所以直接按内容滤
     */
    private fun enumValues(parent: JsonObject, key: String): Set<String> {
        val field = parent[key]?.jsonObject ?: error("schema 里 $key 不是个对象")
        val list = field["enum"]?.jsonArray ?: error("schema 里 $key 没有 enum")
        return list.map { it.jsonPrimitive.content }.filterNot { it == "null" }.toSet()
    }

    private fun schema(): JsonObject {
        val candidates = listOf(
            Paths.get("protocol", "screen-link.schema.json"),
            Paths.get("..", "protocol", "screen-link.schema.json"),
        )
        val file = candidates.firstOrNull { it.toFile().exists() }
            ?: throw IllegalStateException("找不到 protocol/screen-link.schema.json，测试必须在仓库内运行")
        return Json.parseToJsonElement(file.toFile().readText()).jsonObject
    }

    /**
     * 老固件那棵树 **不在仓库里**（`firmware/screen/app/` 从没提交过，只在写它的那台机器上）。
     * 所以这台机器、CI、新机上都读不到 —— 读不到就明着跳过，别让它把整条构建染红：
     * 红在没人能修的地方，下一个人的反应是 `-x` 掉它，那才是真丢了守卫。
     * Muse 底座那一路可比的部分走仓库里的 `muse-carpet.patch`，见「屏要发的指令」那条。
     */
    private fun firmwareSource(): String? = cSourceOrNull("app", "main", "app_main.c")

    /** 仓库里唯一一份 Muse 侧 C 源码：那份 patch 的正文字面量。加号前缀剥掉再比 */
    private fun musePatch(): String {
        val tail = listOf("firmware", "screen", "muse", "muse-carpet.patch").joinToString("/")
        val file = listOf(Paths.get("..", tail), Paths.get(tail)).firstOrNull { it.toFile().exists() }
            ?: error("找不到 $tail —— Muse 那一路的分支表没人比了")
        return file.toFile().readLines().asSequence()
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .map { it.substring(1) }
            .joinToString("\n")
    }

    /** 组件那份也读回来比：`screen_link_send_command` 里有一道合法值闸，它和 schema 分叉比表分叉更糟 ——
     *  屏发一条合法但被闸挡下的指令，现象是"按了没反应"，而串口里连一条契约错都没有 */
    private fun componentSource(): String =
        cSource("components", "screen_link", "screen_link.c")

    private fun cSource(vararg parts: String): String =
        cSourceOrNull(*parts) ?: throw IllegalStateException(
            "找不到 firmware/screen/${parts.joinToString("/")}，那份手抄的分支表没人比")

    private fun cSourceOrNull(vararg parts: String): String? {
        val tail = (listOf("firmware", "screen") + parts.toList()).joinToString("/")
        return listOf(Paths.get("..", tail), Paths.get(tail))
            .firstOrNull { it.toFile().exists() }?.toFile()?.readText()
    }

    /** 抠出 `static const char *const NAME[...] = { … };` 里那串字面量，按出现顺序返回 */
    private fun cStringTable(src: String, name: String): List<String> {
        val decl = Regex("static const char \\*const $name\\[[^\\]]*]\\s*=\\s*\\{(.*?)};", RegexOption.DOT_MATCHES_ALL)
            .find(src)
            ?: error("分支表里没读到 $name 这张表")
        return Regex("\"([^\"]*)\"").findAll(decl.groupValues[1]).map { it.groupValues[1] }.toList()
    }
}
