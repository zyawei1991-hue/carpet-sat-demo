# 车宠卫星通信融合 Demo

手机 + GT1005 卫星终端 + 微雪圆屏 三件套。对应《04_车宠卫星通信Demo_新架构方案_V2.0.docx》。

## 目录

| 路径 | 内容 |
|---|---|
| `protocol/` | **单一事实来源**。三方接口契约，改这里必须同步 Android 和固件两端 |
| `protocol/screen-link.schema.json` | 展示端 ↔ App 消息格式（JSON Schema 2020-12） |
| `protocol/screen-link.md` | 上面那个的传输规范：拓扑、建链、保活、可靠性、安全边界 |
| `protocol/sos-payload.md` | SOS 卫星报文编码，含 §1.3「≤1900B」与 §10「≤90B」矛盾的处理 |
| `protocol/sos_frame.py` | SOS 帧的参考实现 + 自检，两端照它对齐 |
| `protocol/link-policy.md` | 4G/卫星切换状态机。文档里只有「> 阈值」，阈值在这里定死 |
| `docs/DECISIONS.md` | 决策记录。为什么这么选、什么条件下要推翻 |
| `tools/validate_protocol.py` | 契约校验，CI 入口 |
| `apps/android/` | Gradle `:app`。手机 App 薄壳（定位、蜂窝探测、展示端服务、按钮） |
| `core/` | Gradle `:core`。纯 Kotlin JVM，协议 / 链路状态机 / SatelliteModem / DemoEngine |
| `firmware/screen/` | 展示端固件（fork 小智 AI）· 待建 |
| `simulator/` | 展示端桌面模拟器，466×466 圆屏，不等硬件就能调 UI · 待建 |

## 现在能跑的东西

```bash
./gradlew :core:test              # 32 条单测，含与 sos_frame.py 的逐字节对拍
./gradlew :app:assembleDebug      # 出 APK（debug 签名，可直接装演示机）
python protocol/sos_frame.py      # SOS 帧编解码自检
python tools/validate_protocol.py # 契约示例校验
```

`:core` 里已经跑通的是整条业务链路：有网 → 断网 → 切卫星 → 按 SOS → 出结果，
用 `SimulatedModem` 顶替真硬件，所以 GT1005 到货前就能彩排，CI 上也能跑。
装 APK 需要 JDK 17 + Android SDK 34，本机环境见 `docs/DECISIONS.md` D6 末尾。

## 阻塞项（外部依赖，不是工作量）

按对进度的杀伤力排序：

1. **GT1005 BLE 协议文档**（时空道宇）—— 已有兜底：`ModuleOperation` 传输层可替换，先写 UART 实现跑通全部业务逻辑，BLE 文档到了换注入。见 DECISIONS D3
2. **测试卡 + 测试平台账号**（时空道宇）—— 没有就只能验到 App 侧，上不了星
3. **ICI 90B 是二进制还是 hex 字符**（时空道宇）—— 决定 SOS 报文和分片大小，见 `sos-payload.md`
4. **MCQ 的 ACK 超时时长**（时空道宇）—— 决定重发策略，见 `link-policy.md`

## 约定

- 契约不向后兼容时升 `v` 字段，并在 `DECISIONS.md` 记一条
- 演示用的所有阈值（切换时间、心跳间隔、重试次数）集中在一处可配置，不许散落在业务代码里 —— 现场彩排一定要调
- 展示端断链时**保留最后一帧 + 灰色水印**，任何情况下不黑屏
