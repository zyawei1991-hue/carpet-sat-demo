package com.carpet.satdemo.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Adb
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.CardMembership
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DataSaverOn
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Nature
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PinDrop
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VoiceChat
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 语义 → 图标的唯一一张表。
 *
 * 图标全部来自官方 **`androidx.compose.material:material-icons-extended`**（Apache-2.0，
 * Material Symbols 的 Compose 端口）—— V1.6 之前那些自绘 vector 已废弃：自己画的图标既没有
 * 官方那套 24dp 网格和线宽，也撑不起"卫星/围栏/待补发"这种语义，观众只会觉得"这是随手画的"。
 *
 * **加图标的规矩和加颜色一样**：先说清它代表哪个语义，答不上来就不该有这一行。
 */
object MIcons {
    /** 卫星链路。RssFeed 表示"正在收/发"，SatelliteAlt 表示"链路这条通道"，CellTower 表示蜂窝 */
    val satellite = Icons.Outlined.SatelliteAlt
    val satelliteTx = Icons.Outlined.RssFeed
    val cellular = Icons.Outlined.CellTower
    val signal = Icons.Outlined.Sensors

    /** 圆屏/底座。Watch=那块圆表，BluetoothSearching=还没握手，AltRoute=过顶路径 */
    val dock = Icons.Outlined.Watch
    val pairing = Icons.Outlined.BluetoothSearching
    val pass = Icons.Outlined.AltRoute

    /** AI 主理人。Chat=对话，SmartToy=本机规则（不是真模型），VoiceChat=语音，SupportAgent=主动提醒 */
    val chat = Icons.Outlined.Chat
    val model = Icons.Outlined.SmartToy
    val voice = Icons.Outlined.VoiceChat
    val mic = Icons.Outlined.Mic
    val agent = Icons.Outlined.SupportAgent

    /** 安全件。Sos 只在真求救相关的地方出现 */
    val sos = Icons.Outlined.Sos
    val fence = Icons.Outlined.Security
    val position = Icons.Outlined.MyLocation
    val warning = Icons.Outlined.Warning

    /** 队列与用量。HourglassBottom=待发，List=清单，BatteryFull=电 */
    val queue = Icons.Outlined.HourglassBottom
    val ledger = Icons.AutoMirrored.Outlined.List
    val battery = Icons.Outlined.BatteryFull
    val terminal = Icons.Outlined.Adb

    /** 场景与人物 */
    val vehicle = Icons.Outlined.DirectionsCar
    val family = Icons.Outlined.Groups
    val kid = Icons.Outlined.ChildCare
    val health = Icons.Outlined.MonitorHeart
    val send = Icons.AutoMirrored.Outlined.Send
    val settings = Icons.Outlined.Settings
    val speech = Icons.Outlined.Chat

    /**
     * 第二批（V1.7 排版收口时补）。名字全部在这份 aar 的 classes.jar 里逐个核对过：
     * 图标名猜错不会在写的时候发现，只会让编译反复 —— 所以先验再写。
     */
    /** GT1005 那个盒子：Router 比 SatelliteAlt 准，后者已经被"链路"这个语义占了 */
    val hardware = Icons.Outlined.Router

    /** 额度。DataSaverOn=流量计量，Savings 那类"钱"的语义会读成扣费已确认 */
    val quota = Icons.Outlined.DataSaverOn
    val plan = Icons.Outlined.CardMembership
    val privacy = Icons.Outlined.PrivacyTip
    val share = Icons.Outlined.Badge
    val sentry = Icons.Outlined.Visibility
    val guard = Icons.Outlined.Alarm
    val ticket = Icons.Outlined.Notes
    val parking = Icons.Outlined.PinDrop
    val growth = Icons.Outlined.Nature
    val checkin = Icons.Outlined.Today
    val task = Icons.Outlined.Task

    /** 底部导航四个。Rounded 一档，和卡片里的 Outlined 拉开层级 */
    val navHome = Icons.Rounded.Home
    val navVehicle = Icons.Rounded.DirectionsCar
    val navFamily = Icons.Rounded.Groups
    val navMe = Icons.Rounded.Person
}
