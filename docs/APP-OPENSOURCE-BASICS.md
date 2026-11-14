# App 侧开源借鉴方案（地基件优先）

> 2026-11-14，App 侧整理。背景：圆屏那份（`SCREEN-SCENE-OPENSOURCE.md`）的结论是
> "找现成项目改一改，少开发"；**App 侧对照代码查完，结论正好相反** ——
> UI 和业务架构是我们自己的、也够用，欠的全是**地基件**（网络 / 存储 / 图表）。
> 这份文档回答一件事：**哪些成熟库换进来比自己养便宜，换成什么，怎么换。**

---

## 0. 先对账：附录 B 的欠账到今天还剩多少

`APP产品架构.md` 附录 B 在 V1.7 分了三档，当时的话是「到 V1.6 为止，清单里的项目一个都没进代码」。
这次把代码翻了一遍，对账结果：

| 附录 B 的账 | 到今天的实况 |
|---|---|
| material-icons-extended（B.0 第一档） | ✅ 真在依赖里，图标全来自它 |
| digivita 的 Skill 定义 + Heartbeat（抄定义不抄码） | ✅ `core/ai/` 已落地 |
| fork Now in Android 当骨架（B.1） | ❌ 仍是纸面计划 |
| B.2 图表库（ComposeCharts / Vico / JetLime / SpeedView） | ❌ 一个没装，倒计时环 / 曲线 / 时间轴仍手画 |
| B.3 车载参照（AndrOBD IA / LubeLogger 数据模型） | ❌ 车辆通道未通，暂用不上 |
| B.4 KidSafe 家长管控 IA | ❌ 亲子场景 P0 未动 |

**这次对照代码新查出来的欠账**（附录 B 没覆盖的，因为它们不是 UI 也不是架构）：

- 手写 `HttpURLConnection` 一共 **5 个文件 21 处**（下面 §1）。
- 持久化只有 `MaintStore` 一个 `SharedPreferences`（§2）。
- 图表全是 Canvas 手画（附录 B.2 早列了库，就是没装）。

---

## 1. 第一优先：OkHttp 替掉手搓 HTTP（Apache-2.0）

现状盘点（`HttpURLConnection` / `openConnection` / `java.net.URL` 按文件计）：

| 文件 | 处数 | 在干什么 |
|---|---|---|
| `core/ai/Providers.kt` | 7 | OpenAI 兼容 `/chat/completions` 的 POST + 流式解析 |
| `core/ai/AzureSpeech.kt` | 4 | TTS 取 MP3 |
| `core/ai/SiliconFlowAsr.kt` | 4 | ASR 上传 WAV |
| `app/NetVoice.kt` | 4 | 无 key 兜底 TTS 拉音频 |
| `app/NetworkMonitor.kt` | 2 | 连通性探测 |

合计 **5 个文件 21 处**，每处都在自己管：超时、错误码、流关闭、重试。
已经在用第三方网络库的先例：`ScreenLinkServer` 用 **Java-WebSocket**（org.java_websocket），
`EdgeSpeech` 用它的 `WebSocketClient` —— 所以"地基件换成熟库"在这个仓库里不是新思路，是既有惯例。

换 OkHttp（4.x，Apache-2.0）的收益，按痛处排：

1. **超时与重试一处配齐**：现在 21 处各自写 `connectTimeout` / `readTimeout`，各处数值还不一样。
2. **连接池与 HTTP/2**：`Providers.kt` 的 LLM 轮询、`NetVoice` 的兜底拉音频，同一个 host 反复建连。
3. **拦截器**：日志、上游代理（桌面彩排台要走代理）、失败重试，都从 21 处收敛到 1 个 interceptor。
4. **`java_websocket` 与 OkHttp 不冲突**：前者只管 ws，后者管 http，共存没问题。

**怎么换**：一次一个文件，`Providers.kt` 先行（LLM 链路最值得先有重试与流式健壮性）；
`NetworkMonitor` 最后甚至可以不换（它就两个探测点）。每换一个文件跑一遍
`:core:test` + `:app:testDebugUnitTest`，现有测试就是安全网。

**红线**：`EdgeSpeech` / `EdgeToken` 那条**不动** —— 它是逆向协议实现（WSS 行为对齐），
不是 HTTP 请求，OkHttp 替不掉它，动了反而要重对令牌算法。

---

## 2. 第二优先：Room + DataStore 落地持久化（Apache-2.0）

现状：全 App 的持久化只有 `MaintStore` 一个 `SharedPreferences`（`AppRuntime` 注入）。
`EdgeSpeech` 没有存储；对话台账 `ConversationLog` 在内存里。

三个场景马上要落盘，现在没有地基：

1. **`ConversationLog`**（V1.8 起是"全产品唯一的对话台账"）—— 现在纯内存，杀进程就没了。
   这是要落盘的第一张表。
2. **保养记录 / 工单**（§3.1）—— 数据模型直接照 LubeLogger（MIT）建，落 Room。
3. **成长记录 / 亲子问答史**（§3.3）—— 同一张台账模式的换皮。

**怎么分**：

- **Room**（2.x）存台账：`ConversationLog`、保养工单、成长记录 —— 三张表，
  共用一个 `core/data/`，别为每个场景单开一套。
- **DataStore**（Preferences）存配置：替代 `MaintStore` 的 SharedPreferences 读写，
  迁移路径是"读旧 prefs → 写 DataStore → 下个版本删旧读"。
- **先只做 ConversationLog 一张表**：它是"一条不丢"叙事的最后一环
  （消息队列 `pending` 已经落了，对话还悬着），也是最没争议的一张表。

**不做什么**：不引 WorkManager（后台任务现在没有真需求，DemoGuardService 够用）；
不上云（L1 云通道未开，业务数据默认本地）。

---

## 3. 第三优先：把附录 B.2 的图表库装上（都是纯 UI 组件）

| 需求 | 库 | License | 换掉哪块手画 |
|---|---|---|---|
| SOS 倒计时环 / 额度压力条 | `ehsannarmani/ComposeCharts` | Apache-2.0 | `SatelliteCards` 手画的进度环 |
| 能耗 / 里程曲线 | `patrykandpatrick/vico` | Apache-2.0 | 车辆卡里手画的折线 |
| 成长时间轴 / 工单历史 | `pushpalat/JetLime` | MIT | 亲子 / 保养的时间轴 |

装一个组件跑一个场景，**不动架构**：这些是纯 Composable，装了就是省 UI 代码，
不碰 §5.2 的"红只能等于出事"红线（颜色仍走我们自己的 token，不交给任何库的 palette）。
SpeedView（View 体系）先不装 —— 要 `AndroidView` 包装，等车辆仪表盘真做的时候再评。

---

## 4. 仍在纸面的两件（不动，但要说清）

1. **fork Now in Android 当骨架**（B.1）：四 Tab 和它的 `feature/` 切分几乎一一对应，
   但现在全 App 只有十几个文件，**这个体量上引入 NiA 的模块切分是杀鸡用牛刀**。
   攒到 feature 数超过一只手、或多人协作开始时再动刀 —— 到那天 NiA 的价值才兑现。
2. **KidSafe 家长管控 IA**（B.4，MIT）：对应 §3.3 的 P0"家长端管控"，
   是功能欠账不是代码欠账，跟着 §8.2 家长账号体系一起做，不单独立项。

---

## 5. 落地顺序与红线

**顺序**（按"风险最小收益最大"排）：

1. OkHttp 替 `Providers.kt`（一个文件，测试兜底最厚）→ 其余 4 个文件逐个来。
2. Room 只做 `ConversationLog` 一张表 + DataStore 迁 `MaintStore`。
3. 图表库按场景需要装（倒计时环最先，跟下一个卫星功能版本走）。

**红线（不变，全部沿用既有规矩）**：

- AndrOBD（GPL-3.0）只抄信息架构不抄代码；Khoj（AGPL）只读思路。
- 任何第三方代码进仓库前，**先在 `THIRD-PARTY.md` 记一条**（没记录的，不进）。
  OkHttp / Room / DataStore / ComposeCharts / Vico / JetLime 引入当天各记一条。
- 下载的第三方源码不进 `core/`，只进 `libs/` 或依赖坐标，保持"我们只维护三类自研"的口径。

**和屏侧文档的关系**：那份是"屏侧 agent 的施工图"；这份是 App 侧自己的采购单。
两边互不阻塞，唯一的交汇点仍是 §8.3 `photo_chunk`（协议对齐 car-dashboard 的消息形状）。

---

## 附：检索记录（2026-11-14）

- 现状核数：`HttpURLConnection` 5 文件 21 处（Providers 7 / AzureSpeech 4 / SiliconFlowAsr 4 /
  NetVoice 4 / NetworkMonitor 2）；持久化仅 `MaintStore`（SharedPreferences）；
  已有第三方网络库先例：org.java_websocket（`ScreenLinkServer` / `EdgeSpeech`）。
- [square/okhttp](https://github.com/square/okhttp)（Apache-2.0）·
  [androidx.room](https://developer.android.com/jetpack/androidx/releases/room) 与
  [androidx.datastore](https://developer.android.com/jetpack/androidx/releases/datastore)（Apache-2.0，AndroidX 官方）
- [ehsannarmani/ComposeCharts](https://github.com/ehsannarmani/ComposeCharts)（Apache-2.0）·
  [patrykandpatrick/vico](https://github.com/patrykandpatrick/vico)（Apache-2.0）·
  [pushpalat/JetLime](https://github.com/pushpalat/JetLime)（MIT）
- 评估过未采用：WorkManager（无真需求）、SpeedView（要 AndroidView 包装，待仪表盘真做再评）、
  NiA 骨架（体量不到，见 §4）
