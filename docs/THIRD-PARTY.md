# 第三方材料与许可

仓库是 **public**。任何第三方代码、算法或素材进这个仓库之前，先在这里记一条出处。
没记录的，不进。

---

## Edge 朗读接口的时间令牌（`core/src/main/kotlin/com/carpet/satdemo/core/ai/EdgeToken.kt`）

| 项 | 值 |
|---|---|
| 上游 | [`rany2/edge-tts`](https://github.com/rany2/edge-tts) 7.3.1，文件 `edge_tts/drm.py` |
| 上游许可 | LGPL-3.0 |
| 我们的用法 | 逐行移植 `generate_sec_ms_gec_v2`、`_sha256_digest`、`_cs64_digest` 及常量表到 Kotlin |
| 性质 | 对 Microsoft Edge 在线朗读服务的**逆向实现**；算法本身不是微软公开的规范，也不在本仓库任何许可的授权范围内 |

这段代码的作用只是取得一个会过期的准入令牌（`Sec-MS-GEC`），用来连
`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`。
它不包含微软的任何二进制、模型或语音数据。

**为什么还是放进了 public 仓库**：桌面那条语音链（`tools/tts-edge.py`）本来就依赖 pip 装的
`edge_tts`，手机的语音要跟桌面同一个声音（晓伊），而本机没有任何 Azure 订阅 key。
许可风险和一条更强的兜底判据记在 `docs/DECISIONS.md` D13。

**这一步先坏时会怎样**：服务端改算法 → 令牌 403 → `NetVoice` 自动退百度那条无 key 的接口 →
界面「答句声音」那格从「晓伊」变成「网络兜底」，不会静默无声。

---

## 时空道宇 SDK（`apps/android/libs/*.aar`）

第三方 2026-10 提供的星历与基础通信 SDK，不是我们的成果，也按不到开源许可上。
详见 README 顶部说明。

---

## 小轨形象（`apps/android/.../ui/`、`firmware/screen/muse/avatar/`）

自己重画，不复制上游默认角色的像素。几何与色表约束、禁止事项见 `docs/AVATAR.md`。

---

## App 侧候选开源依赖（2026-11-14，仅评估，尚未引入）

App 侧地基件调研的完整方案见 `docs/APP-OPENSOURCE-BASICS.md`。此条先记账：

- [square/okhttp](https://github.com/square/okhttp) — Apache-2.0，替换 5 文件 21 处手写 HttpURLConnection，若引入从此处取
- [androidx room / datastore](https://developer.android.com/jetpack/androidx) — Apache-2.0，对话台账落盘 + 替换 SharedPreferences
- [ehsannarmani/ComposeCharts](https://github.com/ehsannarmani/ComposeCharts) — Apache-2.0，SOS 倒计时环
- [patrykandpatrick/vico](https://github.com/patrykandpatrick/vico) — Apache-2.0，能耗/里程曲线
- [pushpalat/JetLime](https://github.com/pushpalat/JetLime) — MIT，成长时间轴

---

## 圆屏场景层候选开源项目（2026-11-14，仅评估，尚未引入）

屏侧场景层调研的完整方案见 `docs/SCREEN-SCENE-OPENSOURCE.md`。此条先记账：

- [scott987-cmd/ESP32-Round-Clock](https://github.com/scott987-cmd/ESP32-Round-Clock) — Apache-2.0，同板（微雪 1.75C）手势启动器，若借代码从此处取
- [waveshareteam/ESP32-S3-Touch-AMOLED-1.75C](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C) — Apache-2.0，同板官方例程
- [stack-chan/m5stack-avatar](https://github.com/stack-chan/m5stack-avatar) — MIT，程序化画脸引擎
- [Pan-wh/esp32-car-dashboard](https://github.com/Pan-wh/esp32-car-dashboard) — **无 license，只参考架构，不搬代码**
