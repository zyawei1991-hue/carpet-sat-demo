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
