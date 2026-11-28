# 圆屏侧状态盘点（2026-11-24，2026-11-28 复核）

> 屏侧 agent 整理。回答两个问题：**当前固件里已有哪些屏侧能力**、**之前挂着的
> 屏侧欠账**。口径：源码已实装、已刷、台架已称、真链路已称是四件不同的事；
> 没有哪一级证据就只写到哪一级，不拿补丁源码代填 owner 装机版本。

## 1. 当前圆屏版本

- **载体**：`firmware/screen/muse/muse-carpet.patch`，基于 muse-gadget-sdk `cdc169d`。
  11-28 当前补丁为 45 节 / 1,001,062 B / 10,903+ / 159- / SHA-256
  `a44b20d851cb685fc39f3f8b47feb7a47b12f7e40fb0a96479fbff6e1259e9ef`；空仓 `git apply` 与逐字节比较 45/45 通过。
  当前补丁源码申报 `hello.protocols=[1,3,4]`；**owner 手上那块屏未回读**。
- **形象**：小墨 v4 定稿——圆球身 + 炭黑下垂长耳（2.6×4.4 竖椭圆、外摆 0.15rad、
  本体 `#10151E` + 1px `#1B2330` 外沿），四足实验版全部回滚。规格见
  `AVATAR-SHEEP-DRAFT.md`，渲染在 `esp32/avatar/muse_pixel.c`。
- **上一轮本机构建**（11-27，COM8）：IDF 6.0.1 两档全量构建均通过，`CONFIG_MUSE_CARPET_CHAT_WINDOW=y` 与未设置各产出一个
  4,067,328 B 应用，4M app 槽均余 3%；y 档已按 bootloader/分区表/ota/app/model 的明确偏移全片刷入，
  五段均 `Hash of data verified` 并 RTS 硬复位。
- **本轮 P0 修复**（11-28）：实体键强制回连、全页收图自动跳、Avatar/触摸起头开窗的源码与 17 条聚焦守卫已完成；
  最终串行无缓存回归为 core **422/422** + App **84/84**，共 506 条、0 失败/错误/跳过（App 数字钥匙 8/8、core 数字钥匙 18/18），
  日志 `D:/dev/tmp/carpet-final-regression-20261128-final3.log`，XML 内部时间戳均为本轮新鲜结果。
  `CHAT_WINDOW=y` 档已干净重编并全片刷入 COM8：应用 4,067,328 B（14:43，SHA-256
  `038dafb5980a3d2aa33a1cc8d0b57a00202e42b420054b0d9543be6689699589`），五段均哈希校验并硬复位。
  `CHAT_WINDOW=n` 独立干净构建也通过：应用 4,067,328 B（14:40，SHA-256
  `e875207eddb2f155f83fa23bcf9c8cad85d7b8a09f2c5ab6f9eab3cc8ad5a19d`）。最终固件复跑生产收图路径，
  仍读到 `@photo auto page=photo awake=1 gen=1`，466×466 截图实看为相册测试图；`>winbench` 已称到开窗、起播、
  空档等待、熄屏收窗，`>audtest` 96,000/96,000 B 放完、level 0.95。真屏按钮、真 App 推图与连续对话仍未复称。
- 此前 11-22 已并入：会话窗口、收图自动跳相册、Spotpear 移植、#34、#86。

## 2. owner 11-28 复测三项：根因已修，新固件台架部分已称，现场仍欠

| 项 | 本轮根因与修法 | 当前证据 / 仍欠 |
|---|---|---|
| #34「按屏上按键还是不能快速回连」 | 旧按键只踢 Wi-Fi 看守，Screen-Link 自己仍睡在 1/2/5/10/30 秒退避。现任一实体键同时 `muse_wifi_power(FULL)` + `muse_wifi_apply()` + `carpet_link_retry()`；`KEEP_WIFI` 重置阶梯与 `next_try`，`force_look` 绕过近期无热点缓存；Screen-Link `BIT_RETRY` 打断三条失败等待，手动后下一失败从 1 秒起。 | 新固件已刷。台架 `>retry` 回读 `kicked=0 retries=0`，同刻 `wifi=0 linked=0`：Wi-Fi 未起来时 gate 还没创建 Screen-Link 事件组，所以没有退避可打断；**这不是通过也不是反证**。已连着时不拆健康 WS，也不强杀正在进行的连接尝试。仍需 Wi-Fi 已连/退避中的实测与真手指计时。 |
| 「新版固件还是没收图跳页」 | `photo_refresh()` 原在脸/设置/详情早退之后，新图被当前页挡住。现移到所有页早退之前；紧急遮罩仍最先返回；醒屏才自动跳相册。`>phototest` 删掉手工 `goto(photo)`，不再掩盖生产路径。 | 新固件 COM8 已称到：`>phototest` 8 块 / 16,125 B 全收，UI 自己打 `@photo auto page=photo awake=1 gen=1`，466×466 截图实看为相册测试图。**真 App 推图仍未称。** |
| 「还是不能连续对话」 | 唤醒词起头会开窗，Avatar/触摸起头漏了同一扇门。现两种起头都只在 `s_turn_sent` 时开窗；`talk_to_phone()` 入口先清旧成功标记。 | 新固件 `>winbench` 已称开窗、起播、空档等待、熄屏收窗；`>audtest` 96,000/96,000 B、level 0.95。断链台架的 Avatar 注入停在导航（`phone_v4=0 mic armed=0`），未到 `talk_to_phone()`，不能拿它判开窗成败。**真 App/WebSocket 多轮仍未称。** #76 非零 PCM 未修，答句 12 秒不起播仍会按设计收窗。 |
| 断链提示上屏 | `carpet_page.c`（链路 down 10s → want= wifi_up?2:1 → 「手机不在身边」/「连不上手机 App」，`s_link_hint_shown` 0/1/2，配 ESP_LOGI"断链提示上屏"可 grep） | 已实装，**未现场验收** |
| 守护灯点（#69 屏侧渲染） | `carpet_page.c`（`carpet_link_event_pending()` 控制 `s_guard_dot` 显隐） | 已实装，11-19 台架点过假帧；**真监护帧未到，欠端到端验收** |

## 3. 现场验收单（需 owner 带手机到场）

1. **热点开→关**：10 s 内屏出「手机不在身边」或「连不上手机 App」；
2. **实体键快速回连**：断链退避中按任一实体键，串口 `@retry kicked=1`、`retries` 增加，随后 1 秒档重拨；
   同时记录亮屏/睡着两种情形的真实耗时，不用串口 `>retry` 冒充手指；
3. **真人「你好小安」唤醒**：称 #24 唤醒成功率（屏侧本地唤醒词跑通）；
4. **mic peak 读数**：对着屏说话，grep 日志确认解码 PCM 非零（配合 App `answer_peak=` 契约分家）；
5. **收图 / 连续对话**：真 App 推一张图，从脸/设置/详情页都观察自动跳相册；Avatar 或触摸起头后看
   `@window open`，真 App 连续两轮且屏上音频非零，才算两项现场通过；
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
