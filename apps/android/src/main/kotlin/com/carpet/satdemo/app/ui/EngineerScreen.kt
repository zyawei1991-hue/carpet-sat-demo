package com.carpet.satdemo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.carpet.satdemo.app.AppRuntime
import com.carpet.satdemo.app.BuildConfig
import com.carpet.satdemo.core.protocol.DialogSource

/**
 * 工程模式。**这就是原来那个演示控制台，整体搬进来的，能力一条没丢**：
 * 彩排开关、星历源切换、固件升级演示、SOS、事件日志、线路原文窗。
 *
 * 它和产品界面共用同一份状态渲染：那几行字仍然出自 `DemoRuntime.consoleText()`，
 * 和桌面彩排台读的是同一个函数（§5.2）。产品页给人看结论，这一页给人看为什么。
 */
@Composable
fun EngineerScreen(app: AppRuntime, onBack: () -> Unit) {
    val status by app.status.collectAsState()
    val flags by app.flags.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(Ink.Deep)
            .padding(Grid.x16),
        verticalArrangement = Arrangement.spacedBy(Grid.x12),
    ) {
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextMid),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("返回产品界面") }

        // T5 装机版本核对：工程页顶就是版本号，不用翻构建日志（owner 11-25：原来这里没版本号）
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val installTime = remember(ctx) {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime }.getOrDefault(0L)
        }
        Text(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 装机 " +
                android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", installTime),
            style = Type.Caption,
            color = Ink.TextHigh,
        )

        // 手机、桌面彩排台、这一页，三处读同一个渲染函数，彩排时看到的才等于装机看到的
        Text(
            app.runtime.consoleText(status),
            style = Type.Mono.copy(color = Ink.TextHigh),
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink.Card, androidx.compose.foundation.shape.RoundedCornerShape(Grid.x8))
                .padding(Grid.x12),
        )

        Text("彩排开关", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        EngRow("模拟断网（彩排用）", flags.simulatedOffline) {
            app.setFlag(flags.copy(simulatedOffline = it))
        }
        EngRow("模拟卫星未过顶", flags.noPassWindow) { app.setFlag(flags.copy(noPassWindow = it)) }
        EngRow("模拟 GT1005 掉线", flags.gt1005Offline) { app.setFlag(flags.copy(gt1005Offline = it)) }
        EngRow("星历：用厂商 SDK（重启生效）", app.vendorEph) { app.setVendorEph(it) }

        Text("GT1005 BLE 通道", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        val bleState by app.bleState.collectAsState()
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = Grid.x4),
        ) {
            Button(onClick = { app.bleConnect() }) { Text(if (bleState == "linked") "已连接" else "连接 GT1005") }
            Spacer(Modifier.width(Grid.x8))
            Text("状态：$bleState", style = Type.Body)
        }
        // 现场排障就靠这几行：扫描失败码（2=应用未注册 1=不支持）、found 的地址、CCCD 状态
        val bleEvents = remember(bleState) { app.blePipe.events.toList().takeLast(6) }
        bleEvents.forEach { ev ->
            Text(ev, style = Type.Caption, color = Ink.TextLow, maxLines = 2)
        }
        if (bleState == "linked") {
            EngRow("走 BLE 真模组（替换模拟模组）", flags.bleModem) { app.setFlag(flags.copy(bleModem = it)) }
        }

        // 演示口径：产品页那颗 SOS 钮默认置灰（未开通），彩排要用时在这里打开
        EngRow("SOS 已开通（产品页钮才响应长按）", flags.sosActivated) {
            app.setFlag(flags.copy(sosActivated = it))
        }

        Button(
            onClick = { app.pressSos() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Alert, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("SOS", style = Type.Hero) }

        Button(
            onClick = { if (!status.running) app.runtime.start() else app.runtime.stop() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.TextHigh),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (status.running) "停止服务" else "启动服务") }

        Button(
            onClick = { app.runUpgrade() },
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Track, contentColor = Ink.Wait),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("演一次固件升级（占住写锁）") }

        // 数字钥匙（风控在线开放平台）：只走 App↔云端，不碰圆屏下行。
        // owner 11-28 拍板「直接做，做完去真机实测」—— 键位全用对方文档枚举原文。
        Text(
            "数字钥匙（风控在线 · 只走 App↔云端）",
            style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        )
        val dk by app.digitalKey.collectAsState()
        var dkImei by remember { mutableStateOf(app.dkImei) }
        var dkLicense by remember { mutableStateOf(app.dkLicense) }
        androidx.compose.material3.OutlinedTextField(
            value = dkImei,
            onValueChange = { dkImei = it },
            placeholder = { Text("设备号 IMEI（寻车/状态/定位要用）", style = Type.Caption) },
            textStyle = Type.Caption,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = dkLicense,
            onValueChange = { dkLicense = it },
            placeholder = { Text("车牌号（发指令可用车牌或 IMEI，二选一）", style = Type.Caption) },
            textStyle = Type.Caption,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        EngButton("存设备号/车牌") {
            app.dkImei = dkImei
            app.dkLicense = dkLicense
            app.say("数字钥匙：设备号已存本机（不落仓库）")
        }
        EngButton(if (dk.probing) "自检出网中…" else "端点自检（鉴权→状态→定位）", Ink.Track) {
            app.probeDigitalKey()
        }
        // 状态一行话：取到啥说啥，空字段（对方不返回）就写「无」，不许填 0 冒充
        Text(
            "ACC=${dk.accOn?.let { if (it) "点火" else "熄火" } ?: "无"} · " +
                "电量=${dk.chargePercent?.toString() ?: "无"}% · " +
                "油量=${dk.oilVolumeMl?.let { "%.1fL".format(it / 1000.0) } ?: "无(该型号无油感则正常)"} · " +
                "外压=${dk.extVoltageDv?.let { "%.1fV".format(it / 10.0) } ?: "无"}",
            style = Type.Caption,
            color = Ink.TextMid,
        )
        Text(
            "位置：${dk.parkAddress ?: "还没取到"}" +
                (if (dk.parkLat != null) "（${"%.5f".format(dk.parkLat)},${"%.5f".format(dk.parkLng)} wgs84）" else ""),
            style = Type.Caption,
            color = Ink.TextMid,
        )
        dk.lastInstrMsg?.let { Text("指令：$it", style = Type.Caption, color = Ink.Wait) }
        // 非空=上次失败，且是哪一种失败（凭据/出网/对方码/形状）写得明明白白
        dk.issue?.let { Text("没通：$it", style = Type.Caption, color = Ink.Alert) }
        dk.lastOkMs?.let {
            Text("上次取数成功：${android.text.format.DateFormat.format("HH:mm:ss", it)}", style = Type.Caption, color = Ink.Ok)
        }
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            EngButton("解锁", Ink.Track, compact = true) { app.sendKeyCmd("UNLOCK DOOR") }
            EngButton("闭锁", Ink.Track, compact = true) { app.sendKeyCmd("LOCK DOOR") }
            EngButton("尾箱", Ink.Track, compact = true) { app.sendKeyCmd("UNLOCK TRUNK") }
        }
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Grid.x8),
        ) {
            EngButton("一键启动", Ink.Wait, compact = true) { app.sendKeyCmd("ENGINE START") }
            EngButton("一键熄火", Ink.Alert, compact = true) { app.sendKeyCmd("ENGINE STOP") }
        }

        Text(
            "对话链路自检",
            style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        )
        // 这三颗键走的是真链路里同一段代码（入账 → 过文案闸 → 触发技能 → 镜像回屏），
        // 不是另写一遍好让页面上有东西可点。圆屏到货前先按一遍，能当场分清是链路的问题还是逻辑的问题
        Text(
            "圆屏现在" + if (app.screenDialog) "可对话（契约 v2）" else "只能看状态（没申报 v2，服务端会滤掉对话帧）",
            style = Type.Caption,
            color = if (app.screenDialog) Ink.Ok else Ink.Wait,
        )
        EngButton("模拟圆屏问一句（该触发技能并回到屏上）", Ink.Track) {
            app.simulateScreenQuestion("车到哪了")
        }
        EngButton("模拟小智云代答（只入账，不许触发任何技能）", Ink.Track) {
            app.simulateScreenQuestion("今晚有几点过境", source = DialogSource.xiaozhi_cloud)
        }
        EngButton("演一条越界的话（看文案闸拦不拦）", Ink.Alert) {
            // 这颗键存在的唯一理由就是把它送进闸里，屏上画出来的必须是替换文案
            app.simulateScreenQuestion("救援已在路上", source = DialogSource.xiaozhi_cloud)   // 红线样本
        }

        Text(
            "模型与声音（都不需要圆屏）",
            style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        )
        // 这两颗键把"模型通不通"和"引擎有没有声"从圆屏那条链里拆出来。
        // 之前只能靠真问一句去猜，而那句若命中技能就根本轮不到模型
        EngButton("模型端点自检（直接发一句最小请求）", Ink.Track) { app.probeModel() }
        val engine by app.voiceEngine.collectAsState()
        Text("答句引擎：${engine ?: "还没报上来（初始化中或不可用）"}", style = Type.Caption, color = Ink.TextMid)
        EngButton("在手机上试听一句（不经过圆屏）", Ink.Track) { app.previewVoice() }

        EngRow("工程模式日志窗", flags.passthrough) { app.setFlag(flags.copy(passthrough = it)) }

        Text("组件目录", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        // 注册表驱动：26 张卡逐张报档并画真身，加卡删卡这里自动跟着变
        ComponentCatalogue(app, status)

        if (flags.passthrough) {
            Text(app.runtime.engineeringWindowHeader(), style = Type.Mono)
            app.runtime.passthrough.snapshot().forEach { line ->
                Text(line.render(), style = Type.Mono.copy(color = Color.LightGray), maxLines = 1)
            }
        }

        Text("事件日志", style = Type.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
        status.log.takeLast(20).forEach { line ->
            Text(line, style = Type.Caption.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun EngRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Type.Body)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 一行一颗键。文案长到必须折行，所以按 Button 默认的 letterSpacing 反而读不动。compact=true 用于一行多键 */
@Composable
private fun EngButton(
    label: String,
    color: Color = Ink.Track,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink.TextHigh),
        modifier = if (compact) Modifier else Modifier.fillMaxWidth(),
    ) { Text(label, style = Type.Caption) }
}

