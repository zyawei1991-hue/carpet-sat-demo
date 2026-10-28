# 车宠 · 手机 App 产品架构 V1.0

> 面向：吉利 / 时空道宇 项目沟通 + 我们自己排期用。
> 读完应该能回答三件事：**这个 App 到底提供什么价值**、**功能怎么分层、哪些是底座哪些是场景**、**圆屏换了底座之后 App 该怎么跟着变**。

---

## 0. 为什么要写这份

现在的 App（`apps/android`）只有一个页面：链路阶段、卫星状态、定位、SOS、一堆"模拟 xx"开关。它验证的是**卫星链路能不能通**，不是一个产品。演示时可以，用户拿到手会觉得"这 App 只会发 SOS"，是因为我们确实只做了发 SOS。

这份文档把范围拉开：**App 是"车 + 宠物 + 孩子"三类随身对象的统一守护与用车服务入口，卫星只是其中一条兜底通道，圆屏是一个可以换底座的呈现外设。**

---

## 1. 产品定位

一句话：**把车里那个没人看的东西，变成一个一直有人看的东西。**

三条判据（任何功能上线前拿这三条量一下）：

| 判据 | 含义 | 反例 |
| --- | --- | --- |
| **离线可用** | 没网、没卫星时，本地已有的信息必须还能看能用 | 把车况页做成纯 H5，进地库就白屏 |
| **一条不丢** | 关键时刻（SOS、围栏越界、抛锚）的消息，链路断了要进队列、恢复后补发 | 断网就静默丢弃，只弹个 toast |
| **换底座不换 App** | 圆屏换成亲子底座、桌面底座，App 侧只改"投哪张卡"，不改业务逻辑 | 每加一个硬件形态就复制一套 Activity |

差异化不靠"有卫星"（华为、苹果都有），靠**卫星 + 车 + 宠物/孩子这三件事在同一台手机上、同一个账号、同一条兜底链路里闭环**。

---

## 2. 场景与用户

### 2.1 三大场景

| 场景 | 谁在用 | 高频时刻 | 低频但致命的时刻 | 谁付费 |
| --- | --- | --- | --- | --- |
| **车载**（用车养车 + 卫星通信） | 车主本人，副驾偶尔 | 上车看车况、停车看位置、保养提醒、违章/事故处理 | 地库失联、高速抛锚、无人区/海上、被盗 | 车主（订阅 + 流量包） |
| **亲子**（AI 育儿） | 家长设置、孩子使用 | 讲故事、问答、成长记录、接送途中陪伴 | 走散、被遗留车内、内容越界 | 家长（订阅，客单价高于车载） |
| **综合**（数字分身 / A2A 社交） | 车主本人 + 家庭其他成员 | 消息代回、日程、行程分享、宠物/车的人设化互动 | — | 少量高粘性用户 |

优先级不是并列的：**车载是生存场景（有硬件刚需、有交付方），亲子是变现场景（订阅意愿已被 Miko/BubblePal 验证），综合是叙事场景（讲得通、暂时不能当营收支柱）。**

### 2.2 用户画像（用来砍需求的）

- **P1 首购车主 30–45 岁，一二线，有宠物或 3–10 岁孩子**：愿意为"放心"付年费，不愿意为"炫技"付费。要的是"我看不到的时候你替我看着"。
- **P2 长途/越野/营运司机**：真会用卫星，会用存发队列，会算流量条数。付费点明确。
- **P3 家庭第二使用者（老人带孩子）**：不会配网络、不会授权。任何需要他操作的流程都是 bug。

---

## 3. 功能架构分层

分五层。**层与层之间只通过下面的接口说话，不允许跨层调用。** 这么分的原因是：卫星协议、圆屏固件、大模型这三样东西的变更速度完全不同，必须能把震荡关在各自的层里。

```
L5  账号与运营层    账号 / 设备绑定 / 家庭组 / 订阅与配额 / 埋点 / 远程配置
L4  呈现与形态层    手机 UI（Compose） / 圆屏投放（screen-link） / 语音输入输出
L3  场景服务层      用车养车 / 卫星守护 / 亲子陪伴 / 分身与社交  ← 四大场景包
L2  能力层          链路状态机 / 消息队列 / 定位融合 / 设备总线 / AI 网关 / 规则引擎
L1  连接层          GT1005(UART/BLE) / 车辆(OBD/BLE/车机) / 圆屏(WebSocket) / 云
```

### L1 连接层

| 通道 | 现状 | 说明 |
| --- | --- | --- |
| GT1005 RS232 | `UartModem` + `LinePort` 已写，指令表待厂商确认 | **指令表目前是猜的**，到货前必须换成实测表 |
| GT1005 BLE | 未开始 | 协议文档未给，不排 P0 |
| 车辆 | 未开始 | 两条路：OBD-II 蓝牙适配器（后装，能读到 SOC/电压/故障码）或车机侧 API（前装，需要吉利开放程度） |
| 圆屏 | 已通，`:8443` WebSocket 服务，state 1Hz + event 带 ack | 明文 ws 是 D8 决策，量产前重评 |
| 云 | 未开始 | 只做三件事：星历、平台下行、账号同步。业务数据不默认上云 |

### L2 能力层（**底座，与场景无关**）

这一层是整个产品的重心，也是我们现在唯一真正动过的地方。

- **链路状态机** `LinkStateMachine`：CELL → PROBE → SAT → RECOVER，不对称滞回。SOS 绕过它。
- **可靠消息队列** `pending`：断链入队、恢复按序补发、`queue_pending` 直接给 UI 显示。**这是"一条不丢"的实现，所有场景共用。**
- **定位融合** `LocationMonitor`：GPS/网络/模块 GNSS 三个源，带 `source` 和 `hdop`。地库里模块 GNSS 是唯一来源，反过来车内金属屏蔽时手机 GPS 更好 —— 必须按精度选，不是按优先级硬排。
- **设备总线**（未开始）：把"圆屏""GT1005""OBD 适配器""亲子底座麦克风"统一成 `Device`，带发现、连接、能力描述、电量。**没有它，每加一个硬件都要改一遍 UI。**
- **AI 网关**（未开始）：ASR/LLM/TTS 三家可换，统一超时与降级；**本地优先，不可用时明确告知，不假装在听**。
- **规则引擎**（未开始）：围栏、超时、电量阈值、"孩子 20 分钟没说话"这类触发。**场景包只声明规则，不写 if**。

### L3 场景服务层（四个场景包，互不依赖）

#### 3.1 用车养车（车载 · 高频）

| 模块 | 优先级 | 现状 | 说明 |
| --- | --- | --- | --- |
| 车辆状态卡（电量/油量/胎压/门窗/空调） | P0 | 无 | 数据源取决于 L1 车辆通道，前装读不到就先做"手动 + 拍照识别仪表盘" |
| 停车位置与反向寻车 | P0 | 有定位，无寻车 UI | 地库场景要记楼层和车位号，纯经纬度在 B3 层没用 |
| 故障码解读 + 该去哪修 | P1 | 无 | OBD DTC → 人话 → 附近门店。对标途虎的"线上工单 + 线下履约" |
| 保养提醒与工单 | P1 | 无 | 里程/时间双触发 |
| 用电与充电计划 | P1 | 无 | 新能源车专用，峰谷电价 |
| 行车记录与事故留证 | P2 | 无 | 涉及隐私和存储，放后面 |

#### 3.2 卫星守护（车载 · 致命）

| 模块 | 优先级 | 现状 | 说明 |
| --- | --- | --- | --- |
| SOS 一键（长按 + 三段倒计时可取消） | P0 | **已实现**（引擎层） | 倒计时可取消是 Garmin/Apple 的通行做法，UI 层还没做 |
| 无 ACK 自动重发 + 下次过顶倒计时 | P0 | **已实现** | 展示端已经会画"距下次过顶 Ns" |
| 存发队列（SFQ）离线待补发 | P0 | **已实现** | 补发数量已经上屏 |
| 卫星流量包用量与续费 | P0 | 无 | **没有这个就没有订阅** |
| 位置自动上报（定时 + 事件） | P1 | 引擎有 heartbeat，CELL 阶段直接 return | 要区分"演示心跳"和"产品级轨迹上报" |
| 紧急联系人 / 救援协调方 | P1 | 只有 `owner_id` | 谁是救援接收方是**项目要问时空道宇/吉利的商务问题，不是技术问题** |
| 星历管理与失效提示 | P1 | 有 `secondsToNextPass()` 抽象 | 星历过期要直说"请联网更新"，不能只显示"无信号" |

#### 3.3 亲子陪伴（AI 育儿）

| 模块 | 优先级 | 现状 | 说明 |
| --- | --- | --- | --- |
| 家长端管控（时长 / 内容分级 / 用途授权） | P0 | 无 | **必须先于任何孩子能用的功能上线**，否则过一次监管就下线 |
| 讲故事 / 问答（圆屏上的人设角色） | P1 | 无 | 圆屏是这个场景最好的一块屏：表情 + 进度 + 谁在说话 |
| 成长记录与记忆 | P1 | 无 | 留存靠"它记得我上次说的话"，不靠内容库 |
| 走散 / 遗留车内告警 | P0 | 可复用围栏 + 卫星 | **遗留车内是这产品最有社会价值的单点**，且我们真的有独立位置源（模块 GNSS 不依赖手机） |
| 儿童语音数据最小化 | P0 | 无 | 见 §6 |

#### 3.4 数字分身与 A2A

| 模块 | 优先级 | 现状 | 说明 |
| --- | --- | --- | --- |
| 车主/宠物人设（圆屏上可换头像与称呼） | P2 | 圆屏已有 `avatar`/`owner` 字段 | 低成本、能立刻让展示端"像个产品" |
| 消息代回 / 行程播报分身 | P2 | 无 | 手机侧 AI 网关建好后再排 |
| A2A（设备/代理之间互发消息） | P3 | 无 | **消费侧 A2A 社交没有跑通的真实案例**，按前瞻规划，不写进营收模型 |

---

## 4. 场景适配矩阵：圆屏换底座，App 怎么跟着变

这是"可拓展"的落点，也是 `dock_type` 这个字段真正的用途 —— 它不是用来打日志的，是**呈现层的路由键**。

| 形态 | 底座 | 主屏内容 | 可用模块 | 输入 | 隐私档位 |
| --- | --- | --- | --- | --- | --- |
| **车载守护** | 充电 + 支架 | 地图轨迹 + 链路状态 + 一键 SOS | 卫星、车辆、围栏 | 圆屏按键、语音 | 普通 |
| **育儿陪伴** | 桌面 + 麦克风阵列 | 角色脸 + 对话气泡 + 时长 | AI 网关、成长记录、告警 | 双麦语音、家长手机 | **儿童档**（不录不存、仅本地） |
| **宠物看家** | 桌面 + 摄像头（后续） | 实时状态 + 喂食/活动统计 | 规则引擎、事件推送 | 按键 | 普通 |
| **桌面副屏** | 无 | 时钟 + 日程 + 消息摘要 | 通知镜像 | 按键 | 需显式授权，默认不投内容 |

实现要求（写进协议契约，不靠约定）：

1. App 侧 **不判断硬件型号，只读 `dock_type` + `capabilities`**，能力缺失就降级成一张说明卡，而不是空白或崩溃。
2. 卡片以"订阅"为单位投放：`{card_id, scene, min_priority, ttl}`，而不是 App 直接推像素。
3. 隐私档位由**底座 + 家长设置共同决定**，取更严的那个（详见 §6）。

---

## 5. App 信息架构与设计语言

### 5.1 导航

```
Tab1 首页（守护台）   —— 状态优先：一张链路/位置/SOS 卡 + 场景卡片流（可排序、可隐藏）
Tab2 车辆            —— 车况、寻车、养车工单、能耗
Tab3 家人            —— 亲子管控、成长记录、共享对象
Tab4 我的            —— 设备与底座管理、订阅与流量包、隐私中心
```

原则：**SOS 和链路状态在 Tab1 首屏，不在设置里。** 一个需要三层菜单才找到的求救按钮，等于没有。

### 5.2 设计语言

- 技术选型：**Jetpack Compose + Material 3**，跟随系统动态取色；深色为默认（车载夜间场景是主场景之一）。
- 组件不自己造：优先用官方 Compose 组件与现成 UI kit（开源清单见附录 B），**自己只维护"卡片流 + 状态徽标 + 倒计时环"三类**。
- 文案：状态用陈述句 + 下一步动作，不用形容词。
  - ✗ "信号不佳" → ✓ "蜂窝已断，卫星将在 3 分 12 秒后过顶，消息已进入待发队列（2 条）"
- 圆屏 UI 与 App UI **共享一套状态语义**（同一个 `StatePayload` 渲染），现在 `DemoRuntime.consoleText()` 已经是这么做的：手机和桌面用同一个渲染函数，避免两边说法不一致。这个做法要推广到所有状态文案。

---

## 6. 数据、隐私与合规

这一块现在是空的，而它是亲子场景的**上线门槛**，不是加分项。

| 数据 | 采集 | 存储 | 上传 | 留存 |
| --- | --- | --- | --- | --- |
| 车辆位置轨迹 | 事件 + 定时 | 本地优先（加密） | 仅用户显式分享时 | 30 天滚动 |
| 孩子位置 | 仅告警所需 | 本地 | 不下云，除非触发 SOS | 7 天 |
| 孩子语音 | **默认不保存** | 会话内内存 | ASR 需要时明示，可选纯本地 | 会话结束即销毁 |
| 图片/仪表盘 | 用户主动 | 本地 | 否 | 用户控制 |
| SOS 报文 | 含位置 | 必须落盘（断链时不能丢） | 卫星 | 直到平台确认 |

红线（来自近期监管动作，见附录 C）：儿童智能设备被点名的是**内容安全、防沉迷、语音数据滥用**三件事。所以：

- 儿童相关功能一律"关一档默认"：默认不录音、默认不联网、默认限时。
- 内容审核必须在链路里，不能指望模型自觉。
- 任何"分身/A2A"不得与未成年人角色自动互通。

---

## 7. 商业化

对标结论（详见附录 C）：

1. **SOS 不能因欠费失效** —— Garmin 从 2026 年起暂停套餐仍保留 12 个月免费 SOS，Apple 卫星 SOS 随新机送多年。**我们把 SOS 做成需付费的功能，会在发布会当天被截图挂出来。**
2. **纯诊断工具订阅制挨骂，买断制活得好** —— Carly 年费被车主社区围攻，Torque/BimmerLink 买断口碑更好。订阅必须捆**持续交付的价值**（云端健康报告、二手检测、卫星流量），不能捆"解锁本来就在硬件里的能力"。
3. **国内 AI 育儿付费意愿弱、情绪陪伴反而卖得动** —— BubblePal 499 元送 3 年会员（之后 99 元/年，销量 25 万台）；芙崽半年 12 万台走的是潮玩情绪路线；牛听听 2025 年 9 月直接停会员内容全免费（收不上来）。
4. **模块化要学 M5Stack / CMF Phone，别学 Project Ara / Moto Mods** —— 核心机独立可用、接口对第三方开放、配件便宜热插拔；死于私有协议、模块定价高、SKU 碎片化。
5. **华为畅连北斗消息免费但限 16 次回复 / 60 秒间隔** —— 用硬限制控信道是行业做法。我们的流量包设计可以直接借这个形态。

收入结构（建议，非承诺）：

```
硬件   圆屏底座 + GT1005 终端 + 支架/线材       一次性毛利
订阅   守护会员（云健康报告 + 轨迹 + 家庭组）     年费，主力
流量   卫星消息条数包（含紧急 SOS 免费打底）      按条/按月
内容   亲子内容包（可选，第三方分成）            谨慎，验证过收不上来
```

---

## 8. 差距分析与路线图

现状与目标之间最大的缺口不是功能数量，是**三个底座能力还没建：设备总线、AI 网关、账号与订阅**。

| 阶段 | 目标 | 交付判据 |
| --- | --- | --- |
| **R0 演示**（现在） | 链路可信、彩排可重复 | ✅ 43 条 core 单测、8 帧彩排、`v0.3-rehearsal`；⚠️ GT1005 指令表未实测 |
| **R1 车宠守护 1.0** | 车载场景闭环，能给吉利真机看 | 设备总线 + 账号 + 真实 UART 通信 + SOS 全流程（长按倒计时可取消、队列补发、用量页）+ 首页卡片流 |
| **R2 亲子底座** | 形态切换可用，家长管控先上线 | `dock_type` 路由 + 儿童隐私档 + AI 网关 + 遗留车内告警 |
| **R3 分身与社交** | 叙事与留存 | 人设、消息代回；A2A 只做技术储备 |

**近期该做的三件事（不需要新硬件）**：

1. 把 `SimulatedModem` 和 `UartModem` 的切换做成运行时选项 + 一份 UART 实测对练清单（到货当天就能测）。
2. 建 **设备总线**：现在圆屏、卫星模块、手机自身定位三者的连接逻辑分散在 `DemoRuntime` / `LocationMonitor` / `NetworkMonitor` 里，越加硬件越乱。
3. 把 App 从"演示控制台"改成 §5 的四 Tab 骨架，**演示功能收进一个"工程模式"入口**（不删，现场救场要用）。

---

## 9. 开放问题（需要别人回答，不是我们能查的）

| # | 问题 | 问谁 | 卡住哪块 |
| --- | --- | --- | --- |
| 1 | RS232 指令表（`ICI` 是二进制还是 hex、`MCQ` ACK 超时多久、`SFQ` 队列容量与出队时机） | 时空道宇 | R1 全部卫星功能 |
| 2 | `owner_id` 段位怎么分配、平台是否自加校验、救援接收方是谁 | 时空道宇 + 吉利 | SOS 能否真的被接到 |
| 3 | 前装车数据能开放到什么程度（车况 / 里程 / DTC） | 吉利 | 用车养车场景有没有数据 |
| 4 | 1.75 圆屏带外壳版是否随盒带电池、发货时间 | 微雪 | 亲子/桌面底座的形态与 BOM |
| 5 | 儿童语音是否需要单独备案/认证路径 | 吉利法务 | R2 能不能上线 |
| 6 | 卫星流量包的定价与结算主体 | 商务 | 收入结构 |
| 7 | **我们买的这块 1.75 到底是 ESP32-S3 还是 RP2040/RP2350？** V2.0 方案文档 §4 写的是「微雪 ESP32‑S3 Avatar 1.75」，但微雪 1.75 圆屏 wiki 的官方 demo 全是 **RP2040 / RP2350** 两版 | 微雪 + 我们自己核对实物 | **决定语音陪伴的可行性**：ESP-Brocast / 小智（xiaozhi-esp32）整套 AEC 语音栈只跑 ESP32-S3；是 Pico 就得自研或把 ASR 挪到手机侧（见附录 A.6） |

---

## 附录 A · 圆屏可拓展场景与开源生态调研

### A.1 场景范围（按「有没有人天天看」排序）

| 优先级 | 场景 | 为什么适合圆屏 |
| --- | --- | --- |
| **P0** | 桌面时钟/日历 + 天气 + 倒计时/番茄钟 | 200px 能放 3–4 行字 + 一个图形，这是所有圆屏项目的默认归宿 |
| **P0** | **车辆状态仪表盘**（SOC/续航/胎压/充电功率） | 圆环=表盘是零学习成本隐喻；我们已有车 + 已有链路，唯一有商业闭环的场景 |
| **P0** | 家庭中枢状态卡（在/离家、安防、温控） | Home Assistant 生态直接可取，别自己写协议 |
| **P1** | 养成型桌宠 / AI 玩具的"脸"（表情 + 语音指示） | **与「车宠」定位正面相撞，是本次演示最该抄的一类** |
| **P1** | 专注状态灯 / 消息提醒（PC 副屏、Server 监控） | 小尺寸做状态灯刚好 |
| **P2** | 音乐控制 / 播客 / 翻页器 / NFC 门卡 | 需要 BLE HID 与触控，1.75 寸没触控，只能靠底座按键 |

### A.2 固件/中间件层（决定我们能不能少写一半代码）

| 项目 | 能复用进 GT1005 + 1.75 这块屏 |
| --- | --- |
| **LVGL** v9（Zephyr / RT-Thread / ESP-IDF / Arduino 移植） | ✅ 唯一事实标准。`lv_arc`/`meter` 正好是圆形表盘；`LV_USE_ROTATE_DISPLAY` 自带软旋；9.1 起有 SVG 填充与 NES 调色板 dither。**我们的 200×200 + 无触控必须走 `lv_indev` 按键方案**（encoder/group API） |
| **M5Stack M5GFX**（Apache-2.0） | 面板驱动抽象思路可借鉴；` autodetect` 对多面板形态有意义；M5Burner 是「浏览器 Web Serial 刷机 + 应用商店」的好范本 |
| **ESP-Brocast**（乐鑫官方，ESP32-S3） | ✅ **音频走线已官方化**：I2S 接 ES8388 / ES7210（带 AEC 回消）的 1mic/2mic/3mic TX-RX 现成参考，正是 4.1 双麦那件事。**注意：仅 ESP32-S3** |
| **xiaozhi-esp32**（Open-source AI chatbot，已有 2026.01 技术白皮书） | ✅ 目前最完整的「小智 AI 玩具」开源栈：VAD（vadf/sipeed）+ AEC + 多板型（含 M5Stack CoreS3、S3 Box、M5Stamp、ESP32-S3-LCD-1.47/2.41）。**若走语音陪伴，这是最短路径，别自研打断链路** |
| **esp_event_network_examples** | ✅ 与本项目形态几乎同构：STA 配网失败自动起 SoftAP + 内置 Web UI，配网完自动关。可直接搬「手机是 AP、屏是 STA」那套 |
| **RT-Thread / Lumminex** | ❌ 不建议为 GUI 引入 RTOS 重写；仅当 GT1005 侧已有 RTOS 约束时参考其图形栈 |

### A.3 与本项目现有设计的三条硬对应（**不是建议，是缺口**）

1. **`dock_type` 是错的抽象。** ESP32 侧没有 device tree，`dock_type` 只能靠 ADC 分压电阻或 1-WID 芯片识别。行业做法是 **LVGL 侧按「朝向 + 可用输入设备 + 面板几何」三个参数自适应**，把逻辑抽成一次 `panel_profile` 协商 —— 这恰好是我们现在 `screen-link` hello 里缺的字段（现有 `avatar_mode` / `dock_type` 不解决布局问题）。
2. **`wifilibrary` 方向反了。** 它假设屏是 ESP32、手机是中心。我们的设计里 **屏是 SoftAP / 手机是 STA，或反之**，两种都可行，但要么统一到 ESP-Brocast 的 UDP 多流音频，要么明确保留 WebSocket 文本 JSON。**协议必须择一，不能并存。**
3. **双麦 AEC 的验证成本被低估，但屏侧驱动不是问题。** 微雪 1.75 官方 demo 是 **RP2040/RP2350**（与方案文档写的 ESP32-S3 冲突，§9 #7 要去核实物）。若是 Pico 系，ESP-Brocast 和 xiaozhi 那套现成语音栈全部用不上，AEC/ASR 只能自研或走手机侧。**好消息**：LVGL 9.5 已官方提供 RP2040/RP2350 驱动（Pico Display 2 例程，DMA 双缓冲 SPI + scan-detect 防撕裂），**GC9A01 只是换 init 表和 offsets，移植量不大**。
4. **无参考实现：BLE 圆屏 ↔ 手机服务 这个精确组合，GitHub 上零结果**（`M5Stack`+`Android`+`BLE` 搜不到、`watchface`+`phone companion` 搜不到、`Pico Display 2`+`Android` 也搜不到）。所以 `protocol/screen-link.md` 的 `dock_type` / `avatar_mode` **没有上游可抄，只能我们自己维护** —— 好消息是唯一性，坏消息是文档漂移的风险全在我们身上。

### A.4 可白嫖的表盘视觉参考

| 项目 | 结论 |
| --- | --- |
| **ESP32-C3 Round Dashboard**（241⭐，1.47 GC9A01 200×200，ST7789 + LVGL） | 尺寸/分辨率与我们完全一致 —— **SOC + 电压 + 电流 + 功率 + 容量五项，直接改名即可当「车辆状态表盘」** |
| **WT-OBD**（OBD-II + ELM327 + ESP32 + LVGL 8.3） | ⚠️ LVGL 8.3 老；价值在 **ELM327 的 PID ↔ 我们协议字段的映射表**，可抄成「车宠」数据源规范 |
| **center-cluster-esp32-p4**（480² 圆 LCD 数字仪表，双核 + RP2040 遥测） | ⚠️ 非商用授权（source-available）；**只读代码，不复制**，抄布局与刷新策略 |
| **ESP32 Auto Gauge Cluster**（ELM327→蓝牙→Web 实时显示） | ✅ Web 路线参考：演示日可用浏览器当第二块屏 |
| **LilyGO T-Display-S3-Long Dash**（OBD2、硬件旋转） | ✅ 现成硬件 + OBD2 生态，若要做独立车载子设备可直接选型 |
| **ESP32-Home-Assistant-Smart-Display**（Round/2.8”） | ✅ HA 状态卡形态可借；⚠️ 非圆屏专用 |
| **DIY round digital dash**（128px，弧指针） | 128px 弧指针做法 → 200px 更宽松，但**证明圆弧表盘在小尺寸仍可读**，指针粗细可按 1.5 倍放大 |

### A.5 对「手机 App 当网关」这条路线的直接支持

| 项目 | 意义 |
| --- | --- |
| **android-observableandroidpad / DeviceConnectManager**（Apache-2.0，AOSP 官方） | Android 端 USB 串口连外设的官方参考，GT1005 RS232 走 USB 转 TTL 时可读它的权限与热插拔处理 |
| **BLE GAIGO dashboard + ESP32 Bridge**（MIT） | ✅ **「ESP32 桥接 BLE 设备 + 屏显仪表盘」与我们「手机桥接 GT1005 + 圆屏显示」是同构设计**，其 BLE 状态机与断连重试可直接参考 |
| **carDash**（MIT）/ Ulanzi OpenMQTTGateway（GPL-3.0）/ D-STVY DIY Smartwatch（CERN-OHL-S）/ ESP32-OpenIPC（TSCLA） | ⚠️ 后三个含 copyleft，**只能读思路、不能复制代码** |
| **ESP32-BLE-Gamepad**（GPL-2.0，有商业授权渠道） | HID 路线；**GPL-2.0，用前买授权，别当免费组件** |
| **LVGL 自定义屏 + 实体旋钮** / LVGL Rotary Encoder with BLE HID | 无触控时**旋钮比按键的信息密度高一个量级**，底座按键应优先做旋钮（圆屏场景几乎一致结论） |

### A.6 与本项目 MCU 的匹配度（**微雪 1.75 官方 demo 是 RP2040/RP2350，与方案文档写的 ESP32-S3 不一致，见 §9 #7**）

| 项目 | 与我们的相关性 |
| --- | --- |
| **Waveshare 官方 demo wiki**（13.51" Circular LCD on RP2040 / RP2350，含 GC9A01 例程） | ✅ **同 MCU、同面板、同工具链** —— 启动工程照抄即可，比看通用文档快 |
| **watchga**（MIT，1.39" GC9A01 240² + **三按键** + LVGL + MicroPython） | ✅ 输入模型与 1.75 圆屏一致：**「按键驱动 LVGL 焦点导航」是底座无触控时最直接的参考实现**；⚠️ 它是 BLE  Peripheral 连官方 App，不是我们的架构，**只抄输入与表盘，不抄协议** |
| **LVGL 9.5.1**（GitHub release） | ✅ **版本下限定在 9.5.1** —— 9.5 才官方提供 RP2040/RP2350 驱动（Pico Display 2，240×240 ST7789，30MHz QSPI DMA 双缓冲 + scan-detect 防撕裂），并且**9.5.1 专门修了 GC9A01 的显示旋转 bug**。换 init 表 + offsets 即可复用到我们的屏 |
| **Raspberry Pi Pico W 手机 App 控制 LED**（Adafruit Learn，非 GPL） | ✅ **手机 ⇄ MicroPython/Python 设备的双向 JSON 命令示范，正是 `screen-link` 的形态**，可作协议示例参照 |
| Pico Zero Mini Dashboard（MIT，RP2040 + ST7789 + 三按键 + 电池 + 桩针充电） | 尺寸/按键数完全一致；⚠️ 非圆形且是本地传感器，**只借按键→LVGL 导航的映射** |
| RT-Thread `dev/rt_gpu`（LVGL 7.4） | ❌ 不要基于它起步，LVGL 7 已被 9.5 的 RP2040 DMA + 旋转方案全面超越 |

### A.7 三条必须避开的坑

1. **没有现成「车 + BLE + 圆屏」端到端开源项目**。最近的是 OBD-II ESP32 + LVGL 一族（WT-OBD / carDash / ESP32-C3 Round Dashboard）和 BLE→ESP32→屏一族（GAIGO），**两条线的拼接处（协议适配层）必须我们自己维护** —— 也就是现在 `core` 里那层的价值所在。
2. **`wifilibrary` / SoftAPWebServer 假设屏是「中心」，与手机网关架构相反。** 选它等于把协议方向倒过来，成本 > 收益。
3. **LVGL 8.x → 9.x 迁移是隐性成本**，WT-OBD / 多数 2024 年前的项目在 8.3，`lv_arc` 与显示旋转 API 有 breaking change，抄代码前先对齐版本。

### A.8 建议抄的三件事

1. 抄 ESP-C3-ROUND-DASHBOARD 的**表盘布局与 LVGL 配置**（我们同为 200×200 GC9A01，面板驱动可直接对照）。
2. 抄 **ESP-Brocast 的音频走线**（ES8388/ES7210 AEC），把双麦验证周期压到最短 —— **前提是把展示端换成 ESP32-S3**（§9 #7）。继续用微雪 RP2040/RP2350 这块板，这条抄不了。
3. 抄 **esp_event_network_examples 的 SoftAP 配网状态机**，把「手机热点 + 屏做 STA」那条路做扎实。

来源：
- [LVGL 官网](https://lvgl.io/) · [LVGL GitHub](https://github.com/lvgl/lvgl) · [LVGL 9 移植文档](https://docs.lvgl.io/9.3/porting/index.html)
- [traviscea/center-cluster-esp32-p4](https://github.com/traviscea/center-cluster-esp32-p4) · [s0lness/awesome-esp32](https://github.com/s0lness/awesome-esp32) · [M5ez/M5ez](https://github.com/M5ez/M5ez) · [LVGL 论坛：Waveshare ESP32-S3 1.46 圆屏 UI](https://forum.lvgl.io/t/waveshare-esp32-s3-1-46-round-lcd/24008)
- [ESP-Brocast](https://docs.espressif.com/projects/esp-adf-ids-release/en/main-esp32/esp-brocast/index.html) · [ESP-Brocast GitHub](https://github.com/espressif/esp-brocast) · [ESP32-S3-USB-Audio 官方说明](https://dl.espressif.com/dl/speech/V4/zh_cn/esp-va/vadf/zh_CN/latest/_downloads/77b4797d44162380b7e904447969187a/ESP32-S3-USB-Audio.pdf)
- [xiaozhi-esp32（小智 AI 聊天机器人）](https://github.com/78/xiaozhi-esp32) · [xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)
- [espressif/esp_event_network_examples](https://github.com/espressif/esp_event_network_examples) · [esp-event-net 文档](https://espressif.github.io/esp-event-net/)
- [adafruit/ESP32-R4D-2200-02W-DASH](https://github.com/adafruit/ESP32-R4D-2200-02W-DASH) · [mrfuchsia/esp32-turbo-9](https://github.com/mrfuchsia/esp32-turbo-9) · [Lumminex（RT-Thread 圈）](https://device.cirotim.com/zh-CN/docs/rt-smart_display/latest/)
- [Waveshare 圆屏 RP2040/RP2350 demo wiki](https://www.waveshare.com/wiki/13.51inch_round LCD_(0401906012)#Demo_and_Application?@/13.51inch_round_LCD_(0401906012)/) · [LVGL 9.5.1 release](https://github.com/lvgl/lvgl/releases/tag/v9.5.1) · [watchga（GitHub Topics）](https://github.com/topics/watchga) · [Adafruit：用 Raspberry Pi Pico W 连接移动设备（AppBridge）](https://www.adafruit.com/license?product=4889) · [AppBridge 文档](https://appbridge.dev/documentation)

---

## 附录 B · App UI 与业务参照的开源项目

> 选型原则：**架构抄 Apache-2.0 的，GPL/AGPL 的只抄信息架构不抄代码**。下面每条都标了许可，因为这直接决定能不能进闭源 App。

### B.1 Compose 架构骨架（**这是最值得动刀的一处**）

| 项目 | 许可 | 用法 |
| --- | --- | --- |
| **Now in Android**（`android/nowinandroid`，约 22k⭐） | Apache-2.0 | ✅ **直接 fork 改名当骨架**。`core/{designsystem,data,ui,model,network}` + `feature/{bookmarks,interests,search,settings,topics}` + `sync/` + `widgets/`（Glance 桌面卡片，正好是"卡片流"的现成参考）；单 Activity + Navigation Compose 类型安全路由 + Material 3 自适应双栏。还带官方 Figma 设计文件，token 体系可直接借 |
| **Tivi**（`chrisbanes/tivi`） | Apache-2.0 | 多模块 + 设计系统组织的老牌参照 |
| Mindscape（Philipp Lackner 课程 App） | — | ❌ **没查到公开仓库**，别当作可依赖项 |

我们的四 Tab（§5.1）和 NiA 的 `feature/` 切分几乎一一对应 —— **不必自研工程结构**。

### B.2 组件库（对应 §5.2「自己只维护三类」）

| 需求 | 项目 | 许可 | 备注 |
| --- | --- | --- | --- |
| 环形进度 / 倒计时环 | `ehsannarmani/ComposeCharts` | Apache-2.0 | 纯 Compose，SOS 倒计时环用它 |
| 曲线/柱状（能耗、里程、成长） | `patrykandpatrick/vico`（约 3.2k⭐） | Apache-2.0 | 油耗曲线、活动统计 |
| 仪表盘 / 速度表 | `anastr/SpeedView` | Apache-2.0 | View 体系，需 `AndroidView` 包装 |
| 时间轴（成长记录、工单历史） | `pushpalroy/JetLime`（KMP） | MIT | |
| 地图与反向寻车 | `googlemaps/android-maps-compose` / 备选 `osmdroid` | Apache-2.0 | 国内地图服务需另谈 |
| 毛玻璃/模糊分层 | `chrisbanes/haze` | Apache-2.0 | |
| 现成"卡片流/feed"组件 | — | — | ❌ **没查到成熟的纯 Compose 方案**，基于 NiA 的 item 组合自建，这也是我们唯一该自己维护的容器 |

### B.3 车载业务参照

- **AndrOBD**（`fr3ts0n/AndrOBD`，约 2.2k⭐）：**GPL-3.0 —— 不能抄代码进闭源 App，只抄信息架构**：PID 分组、实时读数仪表、DTC 读取与清除、日志导出。§3.1 的故障码模块照它的 IA 设计。
- **ReDrive**（`iUnreallx/ReDrive`）：Flutter 写的现代 OBD2 仪表盘，UI 观感可截图参照；⚠️ 许可是非标 `NOASSERTION`，复用前先谈授权。
- **LubeLogger**（`hargata/lubelog`，MIT）：Web 端保养记录，但**「服务记录 / 油耗 / 费用 / 提醒」的数据模型可以直接照它建**，这是 §3.1 保养模块最省事的一条路。
- AARE（`prototux/AARE`，Android Auto 手机侧重实现）：概念验证，参考价值低。

### B.4 亲子业务与合规

- **KidSafe**（`xMansour/KidSafe`，MIT）：应用屏蔽、时长管控、定位的家长端 IA，**可复用代码**，对应 §3.3 家长管控 P0。
- ChildScreenTime/cst：**无 license 文件，只能看不能抄**。
- CDC「Milestone Tracker」：闭源，但**里程碑内容规范是权威参照**，别自己编育儿话术。
- 语音玩具的 **App 侧配套开源实现没查到**（开源方案基本都在 ESP32 设备端，如 ElatoAI / xiaozhi 一族）→ 意味着亲子场景 App 侧没有捷径。
- **COPPA 硬约束**（FTC FAQ）：中性年龄门、13 岁以下需可验证家长同意、默认不收集、语音/图像须承诺即删、禁止行为广告。→ §6 的数据表就是按这条清单写的，**家长账号体系 + 儿童模式独立数据域 + 语音本地或临时处理**，三件缺一不可。

### B.5 数字分身与 A2A

- **`a2aproject/A2A`**（Apache-2.0）+ 官方 `a2a-java` / `a2a-python` / `a2a-js`；⚠️ **Kotlin SDK 仍在提案讨论阶段（A2A discussion #1049），没有移动端 SDK** → 移动端按 JSON-RPC/HTTP 裸写客户端即可，工作量比想象小。
- 个人 agent 开源：**Second Me**（`mindverse/Second-Me`，Apache-2.0，约 15.7k⭐，数字分身训练）、**Letta**（Apache-2.0，记忆型 agent）、**Mem0**（Apache-2.0，agent 记忆层）、Khoj（**AGPL-3.0，传染性，只读思路**）。
- "Agent 间社交"在消费 App 里**没有验证过的交互形态**。业界现状是 ChatList + AgentCard 发现 —— 可把 AgentCard JSON 呈现成 App 里的"分身名片卡"，这是 §3.4 唯一可落地的形态。

### B.6 明日就能动手的清单

1. fork **Now in Android**，Tab 改成本文档 §5.1 的四栏，designsystem 沿用 NiA token + 官方 Figma。
2. 车辆卡片用 **Vico + SpeedView**，SOS 倒计时环用 **ComposeCharts**，成长时间轴用 **JetLime**。
3. 保养数据模型抄 **LubeLogger**，故障码 IA 抄 **AndrOBD**（不抄代码）。
4. 亲子域按 **COPPA 清单**做家长同意流，管控 IA 参考 **KidSafe**。
5. A2A 先写一个轻量 JSON-RPC 客户端 + AgentCard 卡片 UI，不做社交图谱。

来源：
- [Now in Android](https://github.com/android/nowinandroid) · [Tivi](https://github.com/chrisbanes/tivi) · [Material 3 自适应布局](https://developer.android.com/jetpack) · [Compose 状态提升实践](https://proandroiddev.com/state-hoisting-in-jetpack-compose-common-mistakes-that-kill-performance-44a1c3bef908)
- [ComposeCharts](https://github.com/ehsannarmani/ComposeCharts) · [Vico](https://github.com/patrykandpatrick/vico) · [SpeedView](https://github.com/anastr/SpeedView) · [JetLime](https://github.com/pushpalroy/JetLime) · [Haze](https://github.com/chrisbanes/haze)
- [AndrOBD](https://github.com/fr3ts0n/AndrOBD) · [ReDrive](https://github.com/iUnreallx/ReDrive) · [LubeLogger](https://github.com/hargata/lubelog) · [AARE](https://github.com/prototux/AARE)
- [KidSafe](https://github.com/xMansour/KidSafe) · [FTC COPPA FAQ](https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions)
- [A2A 协议仓库](https://github.com/a2aproject/A2A) · [Second Me](https://github.com/mindverse/Second-Me) · [Letta](https://github.com/letta-ai/letta) · [Mem0](https://github.com/mem0ai/mem0)

---

## 附录 C · 场景化产品对标（调研原文摘要）

### C.1 卫星兜底类

| 产品 | 收费 | 关键机制 | 对我们的启示 |
| --- | --- | --- | --- |
| Apple 卫星 SOS | 随新机免费 2 年并多次续期 | 硬件补贴获客 | 免费打底是市场教育结果，不是慈善 |
| 华为畅连北斗消息 | 免费申请 | 单条限 16 次回复、间隔 60 秒 | **用硬限制控信道**，可直接借形态 |
| Garmin inReach | 套餐制，可暂停 | SOS 全套餐不限；2026 起暂停套餐仍保留 12 个月免费 SOS | **"欠费不停 SOS"已成行业共识** |
| Zoleo | $19.99 激活费 + 最短 3 个月合约 | 被用户诟病 | 别学 |
| Bullitt | 首年免费 SOS 套餐 | 降门槛 | 可采用 |

SOS 交互：三段倒计时可取消 + 响应中心双向确认（GEOS）。我们引擎层已有三次重发与无 ACK 处理，**UI 层的倒计时可取消还没做**。

### C.2 车载养车类

- **Carly**（德国）：入门扫描器 + 按品牌年费（约 $68.89/年起）。订阅制被 BMW 车主社区集中攻击（"annual subscription is a joke"）。
- **BimmerLink / BimmerCode**（SG Software）：按功能买断，口碑更好。
- **Torque Pro**：$4.95 买断，靠社区插件生态。
- **途虎养车**：验证了"线上工单 + 线下门店履约 + 会员"闭环；坑是门店现场推销加价导致投诉。

结论：**买断能活，订阅必须捆持续价值（云端健康报告、二手检测、流量），不能捆"解锁硬件里已有的能力"。**

### C.3 亲子 AI 类

| 产品 | 价格 | 结果 | 启示 |
| --- | --- | --- | --- |
| Miko 3（以色列） | $249 硬件 + $14.99/月 | kidSAFE + COPPA 认证 | **合规认证本身就是卖点**，家长为"过审"付费 |
| BubblePal（跃然创新） | 499 元，送 3 年会员，之后 99 元/年 | 25 万台 | "套在玩偶上"的轻量形态成立；会员靠首年免费铺量 |
| 芙崽 Fuzozo（珞博智能） | 潮玩定位 | 半年 12 万台 | **国内"AI 育儿"付费弱，情绪陪伴反而卖得动** |
| 牛听听 | — | 2025 年 9 月停会员、内容全免费 | 订阅收不上来的真实案例 |
| Character.AI | 订阅 | 青少年自杀诉讼，40 州总检察长警告 | 内容安全是生存问题 |
| 筑梦岛 | — | 因擦边被约谈 | 同上 |

监管关注点（滇黔桂消联 2026.10 比较试验、新华社/经济日报报道）：**内容安全、防沉迷缺位、语音隐私**。这三条必须写进 §6 的默认档位，不是写进"后续优化"。

### C.4 数字分身 / A2A

- 付费验证：**Character.AI 订阅、星野/猫箱/筑梦岛 12–14 元/月**（角色养成 + 多模态卡片）；**豆包 2026.5 起试水 68/200 元/月三档**，免费打底、生产力功能付费。
- **Delphi / Personal AI / Tavus** 验证的是"创作者分身"（克隆本人做视频问答课），第三方分身产品客单价 $1000–2000/年 —— B 端/创作者，不是消费者。
- **A2A 协议**（Google 发起，已交 Linux 基金会，150+ 组织参与）**全部是企业侧互操作；消费侧 agent-to-agent 社交没有查到任何跑通的真实案例。** → 本文把它排在 P3，只做技术储备。

### C.5 "换底座"先例

| 成功侧 | 做法 |
| --- | --- |
| M5Stack | pogo-pin 堆叠 +  UIFlow 开放社区，面向开发者与 B 端行业方案 |
| Nothing CMF Phone | 螺丝 accessory point 轻量模块化，$200 低价 |

→ **核心机独立可用、接口对第三方开放、配件便宜热插拔。**

| 失败侧 | 死因 |
| --- | --- |
| Project Ara | 磁吸磨损、成本失控、没有模块生态 |
| LG G5 / Moto Mods / Zags | 私有协议、模块定价高、SKU 碎片化（联想 2022 终止） |

→ 对 §4 的直接要求：**pogo-pin / dock 协议尽量公开，底座低价，每个形态单独讲得通，不设计"必须买全家桶"。**

主要来源：
- [Garmin inReach 套餐](https://www.garmin.com/en-US/blog/outdoor/overview-inreach-subscription-plans/) · [暂停套餐仍免费 SOS](https://www.dcrainmaker.com/2026/06/garmin-adds-free-inreach-sos-to-suspended-plans.html) · [Garmin SOS 机制](https://support.garmin.com/en-US/?faq=dENvi0yoo51ib0Zh8L4El6)
- [Apple 卫星 SOS](https://support.apple.com/en-us/101573) · [Zoleo 套餐](https://www.zoleo.com/plans) · [Bullitt](https://bullitt.com/individuals) · [华为北斗消息限制](https://consumer.huawei.com/cn/support/content/zh-cn15986536/)
- [Carly 定价](https://www.mycarly.com/blog/carly/how-much-is-carly/) · [Carly 订阅争议](https://www.reddit.com/r/BMW/comments/syq7p9/carlys_annual_subscription_model_is_a_joke/) · [途虎](https://www.tuhu.cn/)
- [Miko Max 订阅](https://miko.ai/products/miko-max) · [BubblePal 销量与收费](https://www.moomoo.com/news/post/47377643) · [芙崽](https://zhuanlan.zhihu.com/p/1997831366407115290) · [牛听听停会员](https://www.benewtech.cn/faq)
- [AI 玩具风险（新华社）](https://www.news.cn/liangzi/20260323/996ce6f532ed4d7c9aedae4c3e8043cd/c.html) · [AI 玩具比较试验](https://www.cnr.cn/mspd/msyx/20261019/t20261019_527846361.shtml) · [筑梦岛约谈](https://www.sohu.com/a/907114014_115565) · [NY AG vs Character.AI](https://ag.ny.gov/press-release/2026/attorney-general-james-urges-court-protect-children-and-consumers-characterai)
- [星野/筑梦岛订阅制](https://www.sfccn.com/2025/6-21/zNMDE0NDlfMjA0MDkzNg.html) · [豆包订阅](https://wallstreetcn.com/articles/3771622) · [Delphi/Tavus](https://www.tavus.io/customers/delphi-spotlight) · [分身产品比价](https://personify.fyi/best-ai-twin-apps/)
- [A2A 协议](https://a2a-protocol.org/latest/) · [Linux 基金会 A2A 进展](https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year)
- [M5Stack 生态](https://m5stack.com/business) · [CMF Phone 模块化](https://www.dezeen.com/2024/07/12/nothing-cmf-phone-1-modular-design/) · [Project Ara 失败讨论](https://www.reddit.com/r/LinusTechTips/comments/139jg09/remember_project_ara_im_still_bitter_about_it/)
