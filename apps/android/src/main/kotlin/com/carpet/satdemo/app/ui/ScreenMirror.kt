package com.carpet.satdemo.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carpet.satdemo.core.protocol.DialogSource
import com.carpet.satdemo.core.protocol.LinkPrimary
import com.carpet.satdemo.core.protocol.SatPhase
import com.carpet.satdemo.core.protocol.ScreenPage
import com.carpet.satdemo.core.protocol.SosLevel
import com.carpet.satdemo.core.protocol.StatePayload
import com.carpet.satdemo.core.protocol.UiCard
import com.carpet.satdemo.core.protocol.UiEmotion
import kotlinx.coroutines.delay

/**
 * 圆屏镜像：**这一格画的就是那块屏此刻在画的东西**。
 *
 * 屏自己报页（`page_report`），所以这里不猜：它报哪一页，就按固件那张版式把同一页画出来
 * （L1 场景宫格 / L2 场景页 / 脸那一格）。屏没报页（老固件）或没接入时才退回上一版那张
 * 示意图 —— 那时候圆里是手机凭空算的，底下那行必须自己说清楚。
 *
 * 版式尺寸抄的是 `firmware/screen/muse/muse-carpet.patch`：面板 466 px、列宽 300、
 * 行高 52、格子 116、字号 28/22/30。这里按 `直径 / 466` 等比缩，**不另起一套数** ——
 * 两套数一改就会对不上，而这一格存在的理由恰恰是"手机上看到的和屏上一致"。
 *
 * 两处刻意的偏离：
 *  - **颜色走本端 token**（蓝 = 动作/强调、绿 = 正常、琥珀 = 等待、灰 = 结构）。
 *    固件那套紫色强调/橙色链路其实很好认，但 App 里已经有 [Tone] 这一支色表，
 *    在这一格里私加紫/橙，等于让"红=出事、绿=正常"这条再裂一条缝。
 *  - **屏上的整句失败文案不进圆**（星历那句）。圆里只放词和数，句子归下面的卡。
 *
 * 没屏接入时（或接入点起不来）屏底从真黑退到 [Ink.Sunk]，内环和字一起退成结构灰：
 * 上一版圆里照旧亮着彩色数字、倒计时还在跳，读起来就是"这块屏亮着"，而它其实没亮。
 */

/**
 * 屏收到的最后一帧下行对话。**屏上"手机在说 · 本地规则"那一行只由收到的帧决定**，
 * 所以手机这边也要照收帧的时点记，而不是照自己说了什么 —— 问句在屏起头时是不回镜的，
 * 拿"我说过什么"去算会和屏对不上。
 *
 * [source] 用线上的值：`DialogPayload.source` 等于默认 `local_rule` 时不上线
 * （[com.carpet.satdemo.core.protocol.ScreenLink] 的 `encodeDefaults = false`），
 * 屏那边就只画"手机在说"四个字，这里也照这个规则省掉尾巴。
 */
data class ScreenDialogLine(val source: DialogSource, val atMs: Long)

/** 固件那句对话只挂 5 秒（`age_s > 5` 就清） */
private const val DIALOG_HOLD_MS = 5_000L

/** 固件面板的物理尺寸，缩放的分母 */
private const val PANEL_PX = 466f

@Composable
fun ScreenMirror(
    snap: StatePayload?,
    online: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 224.dp,
    /** 屏自己报上来的那一页；null = 没报（老固件）或没接入 */
    page: ScreenPage? = null,
    /** 屏收到的最后一帧下行对话；用来画条目行和"在画"旁边那句 */
    line: ScreenDialogLine? = null,
) {
    val reduce = LocalReduceMotion.current
    val sat = snap?.sat
    val moduleOff = sat != null && !sat.moduleOk
    val left = sat?.nextPassInS
    val phase = sat?.phase ?: SatPhase.idle

    // 对话只挂 5 秒：链路断了、两边不说话了，这一行不该一直写着"手机在说"
    val dialogAlive = remember(line?.atMs) { mutableStateOf(line != null) }
    LaunchedEffect(line?.atMs) {
        if (line != null) {
            delay(DIALOG_HOLD_MS)
            dialogAlive.value = false
        }
    }
    val freshLine = if (dialogAlive.value) line else null

    // 能按屏报的页画，才叫镜像。屏没报页 / 没接入 / 接入点起不来，圆里只能是示意图
    val mirroringPage = if (online && snap != null) page else null

    val linkColor by animateColorAsState(
        mirrorLinkColor(snap, online),
        Motion.colorSpec(reduce),
        label = "mirrorLink",
    )
    val fill by animateFloatAsState(
        when (phase) {
            SatPhase.idle -> 0f
            SatPhase.searching -> 0.35f
            SatPhase.locked -> 0.85f
            SatPhase.tx_pending -> 0.55f
            SatPhase.tx_ok -> 1f
            SatPhase.tx_fail -> 0.3f
            SatPhase.tx_no_ack -> 0.2f
        },
        Motion.tweenSpec(reduce),
        label = "mirrorFill",
    )
    val phaseColor by animateColorAsState(
        mirrorPhaseColor(phase, online),
        Motion.colorSpec(reduce),
        label = "mirrorPhase",
    )
    val scale = diameter.value / PANEL_PX

    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val hub = Offset(size.width / 2f, size.height / 2f)
            val r0 = size.minDimension / 2f
            // OLED 熄了就是黑的；没接入时退到 [Ink.Sunk] —— 画一块纯黑的"亮着的屏"是这一格里最容易读假的一处
            drawCircle(if (online) Oled else Ink.Sunk, radius = r0 - 1.6.dp.toPx(), center = hub)
            drawCircle(
                color = Ink.Outline,
                radius = r0 - 0.8.dp.toPx(),
                center = hub,
                style = Stroke(width = 1.6.dp.toPx()),
            )
            // 两圈弧只在画示意图的时候有：屏真的报了页，它画的是那一页，不是这两个环
            if (mirroringPage == null) {
                val linkW = 3.dp.toPx()
                val linkR = r0 - 8.dp.toPx()
                val linkBox = Size(linkR * 2f, linkR * 2f)
                val linkAt = Offset(hub.x - linkR, hub.y - linkR)
                drawArc(
                    color = Ink.Track,
                    startAngle = 140.4f,
                    sweepAngle = 79.2f,
                    useCenter = false,
                    topLeft = linkAt,
                    size = linkBox,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
                )
                drawArc(
                    color = linkColor,
                    startAngle = -140.4f,
                    sweepAngle = 280.8f,
                    useCenter = false,
                    topLeft = linkAt,
                    size = linkBox,
                    style = Stroke(width = linkW, cap = StrokeCap.Round),
                )
                if (snap != null && online && snap.link.primary === LinkPrimary.sat) {
                    drawArc(
                        color = linkColor.copy(alpha = 0.55f),
                        startAngle = 140.4f,
                        sweepAngle = 39.2f,
                        useCenter = false,
                        topLeft = linkAt,
                        size = linkBox,
                        style = Stroke(width = linkW, cap = StrokeCap.Round),
                    )
                }
                val phaseW = 4.dp.toPx()
                val phaseR = r0 - 20.dp.toPx()
                val phaseBox = Size(phaseR * 2f, phaseR * 2f)
                val phaseAt = Offset(hub.x - phaseR, hub.y - phaseR)
                drawArc(
                    color = Ink.Track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = phaseAt,
                    size = phaseBox,
                    style = Stroke(width = phaseW),
                )
                if (fill > 0f) {
                    drawArc(
                        color = phaseColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fill,
                        useCenter = false,
                        topLeft = phaseAt,
                        size = phaseBox,
                        style = Stroke(width = phaseW, cap = StrokeCap.Round),
                    )
                }
            }
        }

        val ui = snap?.ui
        when {
            snap == null -> SkeletonColumn(diameter)
            mirroringPage != null -> ScreenPageView(mirroringPage, snap, freshLine, scale, diameter, online)
            ui?.card == UiCard.emotion -> PixelAvatar(
                emotion = ui.emotion,
                diameter = diameter * 0.62f,
                live = online,
            )
            else -> MirrorText(snap, moduleOff, left, diameter, online)
        }
    }
}

/** 屏真的报了页：画那一页。脸那一格归 Muse 自己，这边用同一张像素脸顶上 */
@Composable
private fun ScreenPageView(
    page: ScreenPage,
    snap: StatePayload,
    line: ScreenDialogLine?,
    scale: Float,
    diameter: Dp,
    live: Boolean,
) {
    when (page) {
        ScreenPage.face -> PixelAvatar(
            emotion = snap.ui?.emotion,
            diameter = diameter * 0.62f,
            live = live,
        )
        ScreenPage.picker -> PickerPage(scale, live)
        // 数字分身这一页屏上画的是那张像素脸（脸那一格归 Muse，页里只有答句/情绪两行）。
        // 镜像里两行字读不出"分身"，直接拿同一张像素脸顶上，情绪跟着 ui.emotion 走。
        ScreenPage.avatar -> PixelAvatar(
            emotion = snap.ui?.emotion,
            diameter = diameter * 0.62f,
            live = live,
        )
        else -> ScenePage(page, snap, line, scale, live)
    }
}

/** 固件字号：条目名/标题 28、按钮/值/副行 22、宫格那颗字 30 */
private fun big(scale: Float, color: Color) = TextStyle(
    fontSize = (28 * scale).sp,
    lineHeight = (34 * scale).sp,
    fontWeight = FontWeight.Medium,
    color = color,
)

private fun small(scale: Float, color: Color) = TextStyle(
    fontSize = (22 * scale).sp,
    lineHeight = (26 * scale).sp,
    color = color,
)

/** 一列：宽 300、按内容长、在圆里居中（固件 `make_col`） */
@Composable
private fun ScenePage(
    page: ScreenPage,
    snap: StatePayload,
    line: ScreenDialogLine?,
    scale: Float,
    live: Boolean,
) {
    val colW = (300 * scale).dp
    val worried = snap.ui?.card == UiCard.maint && snap.ui?.emotion == UiEmotion.worried
    val rows = pageRows(page)
    Column(
        modifier = Modifier.width(colW),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((6 * scale).dp),
    ) {
        Pill("返回", colW, 44 * scale, small(scale, Ink.Sat))
        Band(colW, 34 * scale) { Text(pageTitle(page), style = big(scale, Ink.TextHigh), maxLines = 1) }
        rows.forEach { row ->
            RowCard(
                name = row.first,
                value = rowValue(row.second, snap, line),
                scale = scale,
                valueColor = rowColor(row.second, snap, live),
                dot = worried && (row.second == Field.DIALOG || row.second == Field.EMOTION),
            )
        }
        when {
            page == ScreenPage.gallery -> Band(colW, 26 * scale) {
                Text(GALLERY_NOTE, style = small(scale, Ink.TextLow), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            pageCardBar(page) -> Band(colW, 26 * scale) {
                Text(
                    "在画 ${snap.ui?.card?.let(::cardCn) ?: "—"}",
                    style = small(scale, if (live) Ink.TextHigh else Ink.TextLow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (pageCardBar(page)) {
            Row(
                modifier = Modifier.width(colW),
                horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
            ) {
                listOf("上一张", "下一张", "交回").forEach {
                    Pill(it, (92 * scale).dp, 44 * scale, small(scale, Ink.Sat))
                }
            }
        }
    }
}

/** 固件 `build_picker`：一行三个、116 方卡、字是实色、底托只有 20%（没接的那格 10%） */
@Composable
private fun PickerPage(scale: Float, live: Boolean) {
    val gridW = (364 * scale).dp
    val gap = (8 * scale).dp
    Column(
        modifier = Modifier.width(gridW),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Band(gridW, 26 * scale) {
            Text(
                if (live) "已接入" else "未接入",
                style = small(scale, if (live) Ink.Sat else Ink.Wait),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height((35 * scale).dp))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            PickerCell("车载", "车", Ink.Sat, dim = false, scale = scale)
            PickerCell("亲子", "亲", Ink.Ok, dim = false, scale = scale)
            PickerCell("数字分身", "分", Ink.Sat, dim = false, scale = scale)
        }
        Spacer(Modifier.height(gap))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            PickerCell("时钟", "钟", Ink.Wait, dim = false, scale = scale)
            PickerCell("图片播放", "图", Ink.TextLow, dim = true, scale = scale)
        }
        Spacer(Modifier.height((9 * scale).dp))
        Pill("返回", (300 * scale).dp, 44 * scale, small(scale, Ink.Sat))
    }
}

@Composable
private fun PickerCell(name: String, glyph: String, hue: Color, dim: Boolean, scale: Float) {
    Box(
        modifier = Modifier
            .size((116 * scale).dp)
            .clip(RoundedCornerShape((26 * scale).dp))
            .background(Ink.Card),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = (12 * scale).dp)
                .size((56 * scale).dp)
                .clip(CircleShape)
                .background(hue.copy(alpha = if (dim) 0.10f else 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = (30 * scale).sp, color = hue, maxLines = 1)
        }
        Text(
            name,
            style = small(scale, if (dim) Ink.TextLow else Ink.TextHigh),
            modifier = Modifier.padding(top = (78 * scale).dp),
            maxLines = 1,
        )
    }
}

/** 条目行：名实色、值吃满剩下的宽右对齐并省略号、行首那枚琥珀点只在养护到点时亮 */
@Composable
private fun RowCard(
    name: String,
    value: String,
    scale: Float,
    valueColor: Color,
    dot: Boolean,
) {
    Row(
        modifier = Modifier
            .width((300 * scale).dp)
            .height((52 * scale).dp)
            .clip(RoundedCornerShape((18 * scale).dp))
            .background(Ink.Card)
            .padding(horizontal = (16 * scale).dp),
        horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = big(scale, Ink.TextHigh), maxLines = 1)
        if (dot) {
            Box(Modifier.size((12 * scale).dp).clip(CircleShape).background(Ink.Wait))
        }
        Text(
            value,
            style = small(scale, valueColor),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 定高条：标题和副行借它，免得 label 自己长高把列顶歪（固件 `make_band`） */
@Composable
private fun Band(widthDp: Dp, heightDp: Float, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.width(widthDp).height(heightDp.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Pill(label: String, widthDp: Dp, heightDp: Float, style: TextStyle) {
    Box(
        modifier = Modifier
            .width(widthDp)
            .height(heightDp.dp)
            .clip(RoundedCornerShape((18 * heightDp / 44f).dp))
            .background(Ink.Card),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style, maxLines = 1)
    }
}

/** 行里装的是契约的哪一项（固件 `F_*`） */
private enum class Field { SAT, DIALOG, SOS, CARD, EMOTION, NONE }

private fun pageRows(page: ScreenPage): List<Pair<String, Field>> = when (page) {
    ScreenPage.vehicle -> listOf("卫星" to Field.SAT, "对话" to Field.DIALOG, "呼救" to Field.SOS)
    ScreenPage.kids -> listOf("对话" to Field.DIALOG, "过境" to Field.SAT, "安全" to Field.SOS)
    ScreenPage.avatar -> listOf("答句" to Field.DIALOG, "情绪" to Field.EMOTION)
    ScreenPage.clock -> listOf("钟面" to Field.CARD)
    ScreenPage.gallery -> listOf("图片" to Field.NONE)
    else -> emptyList()
}

private fun pageTitle(page: ScreenPage): String = when (page) {
    ScreenPage.vehicle -> "车载"
    ScreenPage.kids -> "亲子"
    ScreenPage.avatar -> "数字分身"
    ScreenPage.clock -> "时钟"
    ScreenPage.gallery -> "图片播放"
    else -> ""
}

private fun pageCardBar(page: ScreenPage): Boolean = page == ScreenPage.vehicle || page == ScreenPage.kids

private const val GALLERY_NOTE = "还没接上 · 不放假数据"

private fun rowValue(field: Field, snap: StatePayload, line: ScreenDialogLine?): String = when (field) {
    Field.SAT -> satText(snap)
    Field.DIALOG -> dialogText(line)
    Field.SOS -> sosCn(snap.sos.level)
    Field.CARD -> snap.ui?.card?.let(::cardCn) ?: "—"
    Field.EMOTION -> snap.ui?.emotion?.let(::emotionCn) ?: "中性"
    Field.NONE -> "还没接"
}

private fun rowColor(field: Field, snap: StatePayload, live: Boolean): Color = when {
    // 只有"不正常"的呼救抢强调色；正常那一值不该一直晃人眼（固件 `field_color`）
    field == Field.SOS && snap.sos.level != SosLevel.normal -> Ink.Sat
    live -> Ink.TextHigh
    else -> Ink.TextLow
}

/** `"已锁定 下次3:05"`：倒计时是数字且 ≥0 才拼；否则只写相位 */
private fun satText(snap: StatePayload): String {
    val cn = satPhaseCn(snap.sat.phase)
    val left = snap.sat.nextPassInS ?: return cn
    if (left < 0) return cn
    return "%s 下次%d:%02d".format(cn, left / 60, left % 60)
}

private fun dialogText(line: ScreenDialogLine?): String {
    if (line == null) return "没人说话"
    // source 等于契约默认值 local_rule 时不上线，屏上也就只剩"手机在说"
    if (line.source == DialogSource.local_rule) return "手机在说"
    return "手机在说 · ${dialogSrcCn(line.source)}"
}

/** 固件那张表里没有 muse_cloud → 落"未知"（`kv_lookup` 的兜底），这里照抄 */
private fun dialogSrcCn(src: DialogSource): String = when (src) {
    DialogSource.asr -> "你说的"
    DialogSource.local_rule -> "本地规则"
    DialogSource.model -> "模型答的"
    DialogSource.local_fallback -> "兜底话术"
    DialogSource.denied -> "被拦住"
    DialogSource.xiaozhi_cloud -> "小智云"
    DialogSource.muse_cloud -> "未知"
}

/** 固件的 SAT_PHASE；和 App 卡片正文那套措辞故意不是同一串 —— 屏上就印这几个字 */
private fun satPhaseCn(phase: SatPhase): String = when (phase) {
    SatPhase.idle -> "空闲"
    SatPhase.searching -> "找星"
    SatPhase.locked -> "已锁定"
    SatPhase.tx_pending -> "发送中"
    SatPhase.tx_ok -> "星上已应答"
    SatPhase.tx_fail -> "发送失败"
    SatPhase.tx_no_ack -> "无回执"
}

private fun sosCn(level: SosLevel): String = when (level) {
    SosLevel.normal -> "正常"
    SosLevel.armed -> "待命"
    SosLevel.pending -> "待发送"
    SosLevel.sent -> "已发出"
    SosLevel.acked -> "已确认"
}

private fun cardCn(card: UiCard): String = when (card) {
    UiCard.status -> "状态卡"
    UiCard.sat -> "卫星卡"
    UiCard.dialog -> "对话卡"
    UiCard.emotion -> "表情卡"
    UiCard.clock -> "钟面"
    UiCard.maint -> "养车卡"
}

private fun emotionCn(emotion: UiEmotion): String = when (emotion) {
    UiEmotion.neutral -> "中性"
    UiEmotion.happy -> "开心"
    UiEmotion.excited -> "兴奋"
    UiEmotion.sad -> "难过"
    UiEmotion.worried -> "担心"
    UiEmotion.sleepy -> "犯困"
    UiEmotion.thinking -> "思考"
}

@Composable
private fun SkeletonColumn(diameter: Dp) {
    // 首帧：五条垫的就是最终那五行字的形状和高度。少一条，真值落地时整摞字就往上一跳
    Column(
        modifier = Modifier.width(diameter * 0.60f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Grid.x4),
    ) {
        SkeletonBar(width = 56.dp, height = 11.dp)
        SkeletonBar(width = 84.dp, height = 15.dp)
        SkeletonBar(width = 64.dp, height = 24.dp)
        SkeletonBar(width = 96.dp, height = 13.dp)
        SkeletonBar(width = 104.dp, height = 11.dp)
    }
}

/** 外环该是什么颜色。抽出来是因为圆底下那行图例要用同一支色，两处不许各挑一套 */
internal fun mirrorLinkColor(snap: StatePayload?, online: Boolean): Color = when {
    snap == null || !online -> Ink.Track
    snap.link.primary === LinkPrimary.sat -> Ink.Sat
    else -> Ink.Ok
}

/** 内环该是什么颜色。没屏接入时语义色（琥珀/红）一律不给，只留结构上更亮的那档灰 */
internal fun mirrorPhaseColor(phase: SatPhase, online: Boolean): Color = when {
    !online -> Ink.Outline
    phase === SatPhase.tx_fail || phase === SatPhase.tx_no_ack -> Ink.Alert
    else -> Ink.Wait
}

/**
 * 圆里那一摞字（示意图）。**宽 = 直径的 0.60**：最宽那行（坐标）落在离圆心最远的高度上，
 * 那个高度的弦长就这么多，再宽一点字尾就被相位环切掉。
 * 每行 maxLines=1：这一格宁可裁，也不能折行把圆撑破。
 *
 * [live] 为假时整摞字退成灰：彩色只属于"真的有屏在看这一帧"，否则这屏数字就是我自己编的，
 * 不该借用卫星蓝和开窗绿。
 */
@Composable
private fun MirrorText(snap: StatePayload, moduleOff: Boolean, left: Int?, size: Dp, live: Boolean) {
    val sat = snap.sat
    val cnr = sat.cnr
    Column(
        modifier = Modifier.width(size * 0.60f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Grid.x4),
    ) {
        Text(
            if (moduleOff) "GT1005" else (sat.satName ?: "—"),
            style = Type.Overline.copy(
                color = when {
                    !live -> Ink.TextLow
                    moduleOff -> Ink.Alert
                    else -> Ink.Sat
                },
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            if (moduleOff) "终端离线" else phaseLabel(sat.phase),
            style = Type.Body.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (live) Ink.TextHigh else Ink.TextMid,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            when {
                moduleOff -> "${sat.queuePending}${sat.queueCapacity?.let { "/$it" } ?: ""} 条"
                left == null -> "—"
                left <= 0 -> "开窗"
                else -> clockFace(left)
            },
            style = Type.Numeric.copy(
                color = when {
                    !live -> Ink.TextMid
                    moduleOff -> Ink.Alert
                    left != null && left <= 0 -> Ink.Ok
                    else -> Ink.TextHigh
                },
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(
            when {
                moduleOff -> "补发等终端回来"
                cnr != null -> "CNR %.1f dB".format(cnr)
                else -> "未锁定"
            },
            style = Type.Caption.copy(color = if (live) Ink.TextHigh else Ink.TextMid),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        // 等宽：坐标有一位小数错位会被读成"位置变了"，而这行恰恰是拿来核对的。
        // 11sp 是这一摞里唯一在字号 1.3 下仍装得下的写法 —— 再大一号就会被圆裁掉尾巴
        Text(
            "%.4f, %.4f".format(snap.gnss.lat, snap.gnss.lon),
            style = Type.Mono.copy(fontSize = 11.sp, color = if (live) Ink.TextLow else Ink.TextLow.copy(alpha = 0.7f)),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 圆底下那几行。
 *
 * **两种真相只能有一种说法**：屏报了页就把那一页的页名写出来（"屏上此刻就是这一页"）；
 * 没报页就是示意，图例（外环链路 / 内环过境）也只在示意的时候有意义 ——
 * 屏真画着场景页的时候它没有这两圈弧，配一条图例等于在解释不存在的东西。
 */
@Composable
fun MirrorCaption(
    snap: StatePayload?,
    online: Boolean,
    serverError: String?,
    page: ScreenPage? = null,
    modifier: Modifier = Modifier,
) {
    val mirroring = serverError == null && online && snap != null && page != null
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (!mirroring) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Grid.x8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendDot(mirrorLinkColor(snap, online), "信号")
                LegendDot(mirrorPhaseColor(snap?.sat?.phase ?: SatPhase.idle, online), "卫星")
            }
        }
        Row(
            modifier = Modifier.padding(top = Grid.x4),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (serverError != null) Tone.Alert else if (online) Tone.Ok else Tone.Neutral)
            Text(
                when {
                    serverError != null -> "圆屏这一帧还没接上，稍等一下"
                    mirroring -> "屏上此刻就是这一页 · ${screenPageLabel(page!!)}"
                    online -> "屏还没说自己在哪一页，圆里先按默认画"
                    // 不再写"还没有屏接入"：卡标题右边那格已经写着"未连接"，下面那行又给做法，
                    // 同一屏三次陈述同一个负状态，第三句就是噪音。这句只负责承认数据来源
                    else -> "这是手机上的预览，还没连屏"
                },
                style = Type.Caption,
                color = if (serverError != null) Ink.Alert else Ink.TextMid,
            )
        }
    }
}

/** 页名给人读的那一个词。屏报的是英文 id，车主不需要认识 id */
fun screenPageLabel(page: ScreenPage): String = when (page) {
    ScreenPage.face -> "那张脸"
    ScreenPage.picker -> "选场景"
    ScreenPage.vehicle -> "车载"
    ScreenPage.kids -> "亲子"
    ScreenPage.avatar -> "数字分身"
    ScreenPage.clock -> "时钟"
    ScreenPage.gallery -> "图片"
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Grid.x4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        Text(label, style = Type.Overline, color = Ink.TextLow)
    }
}

private val Oled = Color(0xFF000000)

/**
 * 钟表写法。和车辆页那串数字同一套理由：24sp 等宽在圆里怎么都不会折，
 * "9 分 21 秒"六个汉字宽在这儿量不出来。
 */
private fun clockFace(sec: Int): String {
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
