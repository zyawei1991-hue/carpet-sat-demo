# 1.29.14 断联提醒（遗留车内告警第一版）

> 出处：owner 在飞书清单文档（VnyZdUbJDoHF9RxAITCcitkEnjT）「遗留车内告警」那行的评论
> 「先做一个断联提醒吧」。设计口径见 `docs/APP产品架构.md` §7「走散 / 遗留车内告警 P0」。
> 规矩不变：下行不加新 frame `t`、不假造数据源、文案红线（不许说「孩子还在车里」）。

## 1. 这一版做的是什么

原设计要「两个定位源做差」：人带手机离开、车内那台设备还停在车的 GPS 范围内不动 → 报警。
但**第二个定位源今天不存在**（圆屏没有 GNSS，手环硬件没买），所以这一版换用一个手上真有的信号：
**链路本身就是距离证据**。手机是 WebSocket 服务端，屏连的是车这侧的网络（`ScreenLinkServer`，
端口 8443），人带着手机走远，这条链路必然先断。

判定条件是两个 AND（`core/guard/LeaveBehindWatch.kt`）：

1. 车上那台设备（`DisplayScreen` / `ChildDevice` / `Wearable` / `Vehicle`）从在线掉到离线，
   且**连续断满宽限期 60 秒**；
2. 手机已经离开**布防那一刻画下的停车圈**（半径 80 米）。

能说的与不能说的：可以说「你离开 130 米、那台设备 65 秒前就断了、它没跟着你走」；
不可以说「车内有人/有孩子」。前者是判定结果，后者是推断，误报一次这个开关就废了。

## 2. 误报抑制（这一节是重点，全部有单测钉住）

| 情况 | 处置 | 为什么 |
|-|-|-|
| 屏自己重启 / 路由换手，十几秒内接回来 | 不报，日志留一行「重新连上，没报警」 | 现场最常见的掉线原因根本不是人走了 |
| 断满 60 秒但人还在圈内 | 只记日志「不算遗留」 | 说明是设备侧掉线，不是离开 |
| 契约不匹配（`DeviceLink.Degraded`） | 不算断联 | 「不能说」不等于「不在」 |
| 台账里整条没了（被移出白名单 / `drop`） | 算断联 | 一样是"没跟上" |
| GT1005 卫星模组掉线 | **不算**，它跟着人走 | 拿它当判据等于每次下车都报警 |
| 手机没定位（地库、冷启动） | 布防直接被拒并说明原因 | 开一个永远不会响的开关比不开更危险 |

## 3. 改了哪些文件

- `core/guard/LeaveBehindWatch.kt`（新）—— 纯判定，只吃 `Device` 列表和「离开多少米」，
  时间全由调用方喂。挂 Android 之外，所以桌面彩排台也能跑同一份。
- `core/runtime/DemoRuntime.kt` —— 停车圈（`lbCenter`/`lbRadiusM`）、每拍喂一次、报警分发；
  `armLeaveBehind(radiusM, holdMs)` 返回 null 或**给用户念的拒绝原因**。
- `core/engine/DemoEngine.kt` —— 补 `currentFix` 读口（之前 App 侧拿不到定位，
  这也是「App 上打开围栏却永远越不了界」的根因）；`sendPreset` 的分片发送抽成 `sendFrames`，
  新增 `sendAlarm()` 走 `OutboxClass.Alarm` 档。
- `apps/android/LeaveBehindNotifier.kt`（新）—— 独立通知 channel（`IMPORTANCE_HIGH`），
  常驻那条是 LOW、不会响，两件事的打扰等级不同。
- `apps/android/AppRuntime.kt` —— 开关入口、开之前先把守护常驻拉起来、断联回调落通知。
- `apps/android/ui/Tabs.kt` + `ui/viewmodel/MeViewModel.kt` —— 「我的 → 守护开关」加一行。
- `apps/android/build.gradle.kts` —— 1.29.14 / versionCode 129140。

## 4. 三条明确没做

1. **高温联动**：要车内温度探头，没有数据源。前装 OBD/OEM 通道等吉利。
2. **体征联动**：要 BLE 手环/戒指硬件，`DeviceKind.Wearable` 这个槽位已经在台账里等着，
   硬件到货直接 `upsert` 就接进这套判定，不用再改判据。
3. **进程被杀之后不判**：判定挂在守护服务的 1Hz 上。守护没能常驻（定位权限没批）时，
   布防那一下就会说明「锁屏后可能停判」。系统级 geofence 唤醒是下一步，不在这一版。

## 5. 与屏侧的关系

**不需要屏侧动一帧**：报这件事的时候屏正是那台断联的设备，往它推一条弹不出来的横幅，
只会让人以为"通知发出去了"。所以没有新增 `EventKind`，契约 v1~v4 都不变。

## 6. 验收怎么做（owner 侧一步）

装 1.29.14 → 「我的 → 守护开关 → 断联提醒（遗留车内）」打开 →
让屏连在手机热点上，人带手机走开（>80 米，或直接把屏断电模拟掉线）→ 60 秒后通知栏响。
走回去让屏重连，通知自动消失。日志里逐拍能看到：布防 → 断联计时 → 报警 / 或「不算遗留」。

单测：`core/test/.../guard/LeaveBehindWatchTest.kt`（9 条判据）+
`core/test/.../runtime/LeaveBehindRuntimeTest.kt`（4 条接线，含"最坏一档一片装得下"和
"没报警时卫星一个字节都不写"）。`:core:test` 全绿。
