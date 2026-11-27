# 圆屏侧状态盘点（2026-11-24，2026-11-27 复核）

> 屏侧 agent 整理。回答两个问题：**当前固件里已有哪些屏侧能力**、**之前挂着的
> 屏侧欠账**。口径：源码已实装、已刷、台架已称、真链路已称是四件不同的事；
> 没有哪一级证据就只写到哪一级，不拿补丁源码代填 owner 装机版本。

## 1. 当前圆屏版本

- **载体**：`firmware/screen/muse/muse-carpet.patch`，基于 muse-gadget-sdk `cdc169d`。
  11-27 最终补丁为 45 节 / 997,394 B / SHA-256
  `896ff7d2ca26a2389951c5c6300c326b00b900aaef9a5d6179dafcd3aca74f0d`；空仓 `git apply` 与逐字节比较 45/45 通过。
  当前补丁源码申报 `hello.protocols=[1,3,4]`；**owner 手上那块屏未回读**。
- **形象**：小墨 v4 定稿——圆球身 + 炭黑下垂长耳（2.6×4.4 竖椭圆、外摆 0.15rad、
  本体 `#10151E` + 1px `#1B2330` 外沿），四足实验版全部回滚。规格见
  `AVATAR-SHEEP-DRAFT.md`，渲染在 `esp32/avatar/muse_pixel.c`。
- **本机构建**（11-27，COM8）：IDF 6.0.1 两档全量构建均通过，`CONFIG_MUSE_CARPET_CHAT_WINDOW=y` 与未设置各产出一个
  4,067,328 B 应用，4M app 槽均余 3%；y 档已按 bootloader/分区表/ota/app/model 的明确偏移全片刷入，
  五段均 `Hash of data verified` 并 RTS 硬复位。`:core:test --rerun-tasks --no-build-cache`
  实跑 49 suites / 400 tests / 0 失败 / 0 错误 / 0 跳过。
- 此前 11-22 已并入：会话窗口、收图自动跳相册、Spotpear 移植、#34、#86。

## 2. 三项"屏侧欠账"核实结论：代码均已实装，欠的是现场验收

| 项 | 实装位置 | 状态 |
|---|---|---|
| #34 退避封顶 + 快速重扫 | `muse_glue.c`（RETRY_MIN_US 5s / RETRY_AWAKE_MAX_US 15s / AWAY_MAX_US 30s 封顶，11-20 从 600s 改下；DIAL_STALL_FAILS=4 重入网；`muse_input.c:211` 按键立即踢一次，绕过阶梯） | 已实装，**未现场验收** |
| 断链提示上屏 | `carpet_page.c:1695-1723`（链路 down 10s → want= wifi_up?2:1 → 「手机不在身边」/「连不上手机 App」，`s_link_hint_shown` 0/1/2，配 ESP_LOGI"断链提示上屏"可 grep） | 已实装，**未现场验收** |
| 守护灯点（#69 屏侧渲染） | `carpet_page.c:1695-1698`（`carpet_link_event_pending()` 控制 `s_guard_dot` 显隐） | 已实装，11-19 台架点过假帧；**真监护帧未到，欠端到端验收** |

## 3. 现场验收单（需 owner 带手机到场）

1. **热点开→关**：10 s 内屏出「手机不在身边」或「连不上手机 App」；
2. **热点关→开**：15 s 内回连（退避阶梯生效，不超 30 s 深睡档）；
3. **真人「你好小安」唤醒**：称 #24 唤醒成功率（屏侧本地唤醒词跑通）；
4. **mic peak 读数**：对着屏说话，grep 日志确认解码 PCM 非零（配合 App `answer_peak=` 契约分家）；
5. **拍照跳转 / 窗口四收口 / CHAT_WINDOW 关闭**：三项功能级回归；
6. **守护灯点**：等 App 发真监护 event 帧后端到端称（#69 剩余部分）。

## 4. 仍可独立推进的屏侧代码活

- **SOS 事件全屏抢占**：源码已实装，COM8 串口状态、文案、手势与顶层像素台架均已过。仅
  `sos_triggered/sos_acked/sos_failed/sos_cancelled/fence_exit` 与 `state.sos.level=acked` 的平台回执抢占；
  顶层遮罩覆盖设置和车宠页，显示时冻结四方向手势，「知道了」可关。脸页、设置页、钟面三条路径各截
  466×466 真实像素，均为黑底 59.9% + 2,604 个琥珀色像素的完整遮罩；截图目标已从活动屏修到 `lv_layer_top()`。
  组件原有 ACK/TTL/去重不变，不新增 frame `t`；卫星回执与平台接报仍分开。真 WebSocket/App 仍未称到。
- **`sos_acked` 文案**：已改为「卫星已确认求救」，COM8 串口回读逐字通过。它只表示卫星响应/MCQ COMPLETE，
  不能暗示平台接报或救援已派出。
- **fake-phone 三个独立 turn 的 WebSocket 自动场景**：`--chat-turns 3 --chat-turn-gap` 已实现；本地 WebSocket
  harness 已称三个唯一 `turn_id`、每轮 96,000 B 非零 PCM、轮间静默和同轮分段。真屏/真 App 仍未跑。
- **双配置构建**：`MUSE_CARPET_CHAT_WINDOW=y` 与 `=n` 已在独立 build 目录全量构建通过；关闭档曾暴露窗口 VAD 辅助函数的条件编译遗漏，已修复。两档 app 均 `0x3e1000`、4M 槽余 3%。

其余项确实依赖现场或 App：#76 非零 PCM、真人唤醒与空档说话、真 App 三轮、真监护帧、
owner 装机固件回读、Spotpear 麦克风/外接音箱方向。App 侧 1.29.14 的 SOS 默认关/待开通与
About 页已落地，但屏侧抢占不能据此算完成。
