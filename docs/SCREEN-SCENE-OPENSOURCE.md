# 圆屏场景层开源参考方案（给屏侧 agent）

> 2026-11-14，App 侧整理。背景：四方向手势架构（`ROUND-SCREEN-GESTURES.md`）已定，
> 但时钟 / 相册 / 数字分身 / 亲子 / 车载这些场景页现在都是空的。
> 这份文档回答一件事：**别从零写，哪些开源项目直接借、借哪块、怎么合进我们的协议。**

---

## 0. 硬件与栈先对齐

我们的屏是 **微雪 ESP32-S3-Touch-AMOLED-1.75C**（466×466 QSPI AMOLED + CST9217 触摸 +
ES7210 麦克 + ES8311 功放，LVGL 固件）。下面排名前三的项目**用的就是同一块板**，
例程和引脚配置可以原样对照，不用做板级移植。

---

## 1. 候选项目总表

| 项目 | License | 与我们的关系 | 借什么 |
|---|---|---|---|
| [scott987-cmd/ESP32-Round-Clock](https://github.com/scott987-cmd/ESP32-Round-Clock) | Apache-2.0 | **同板**（微雪 1.75C，LVGL 9.5，ESP-IDF 5.5） | 手势启动器骨架 + 时钟/天气/头像/故事屋应用壳 |
| [waveshareteam/ESP32-S3-Touch-AMOLED-1.75C](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C) | Apache-2.0 | **官方例程库，就是这块板** | 显示/触摸/音频初始化、LVGL9 demo、Brookesia UI |
| [stack-chan/m5stack-avatar](https://github.com/stack-chan/m5stack-avatar) | MIT（242★） | 纯 C++ 画脸引擎，无板级依赖 | 数字分身：眼睛/眉毛/表情/呼吸全部参数化，零美术资源 |
| [Gitshaoxiang/xiaozhi-esp32-avatar](https://github.com/Gitshaoxiang/xiaozhi-esp32-avatar) | 看仓库 | m5stack-avatar 接进小智固件的完整先例 | 集成路径参考 |
| [78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32) | MIT（33k★） | **我们屏刷的本来就是小智固件** | 表情系统、语音链路、后端生态 |
| [Pan-wh/esp32-car-dashboard](https://github.com/Pan-wh/esp32-car-dashboard) | **无 license** | 同为 466×466 圆屏 LVGL 的车表 | **只看架构不抄代码**：表盘组织、照片轮播+烧屏防护、BLE 推图协议 |

License 红线（仓库规矩见 `THIRD-PARTY.md`：没记录的不进）：
- Apache-2.0 / MIT 三家可以抄代码，抄了在 `THIRD-PARTY.md` 记一条出处。
- esp32-car-dashboard **没挂 license → 默认保留所有权利，一行都不能搬**，只允许看它的
  README / 协议设计 / 截图来对照思路。它自己还声明了禁止装车（高德 API 条款），
  我们的车载场景只做轨迹展示，不碰导航。

---

## 2. 按场景的借鉴方案

### 2.1 手势框架 + 氛围层（时钟/相册）→ 抄 Round-Clock

Round-Clock 是微雪 1.75C 上的中文圆屏微型系统：左右滑切应用、上滑看全部、
点击进入、实体键返回——**手势语义跟 `ROUND-SCREEN-GESTURES.md` 是同一套思路**，
等于有人已经把我们的骨架实现过一遍并跑通了。

- 抄它的：圆形启动器布局、手势判定、应用注册机制、中文渲染/字体生成流程
  （`docs/DEVELOPMENT.md` 有「如何增加第 19 个应用」一节，直接照做）。
- 时钟应用本身（大数字 + 日期 + 下一场过境一行小字）就是我们要的氛围层默认页。
- 它的服务端是 Python + nginx mTLS 那一套，**我们不借**——我们的数据通道是
  App WebSocket 下行，别被它带偏。

### 2.2 数字分身 → m5stack-avatar 的脸 + 小智的表情系统

`ROUND-SCREEN-GESTURES.md` 的默认态是「Avatar 常驻 + 情绪变脸」。
m5stack-avatar 把脸拆成 Eye / Eyeblow / Mouth / Balloon 的程序化绘制，
呼吸、眨眼、情绪切换、气泡都是参数，**不需要任何美术资源**。
xiaozhi-esp32-avatar 则证明它能塞进小智固件的显示层。

- 集成顺序建议：先在小智固件里把 m5stack-avatar 的 draw 循环跑起来
  （xiaozhi-esp32-avatar 就是现成答案），再把情绪接口对到我们的
  `dialog`/守护事件下行——屏收到什么事件，脸就变什么情绪。
- 与 App 的对接点：现有 `state` 帧里的情绪/守护字段直接映射 avatar 表情，
  不需要新协议。

### 2.3 亲子场景 → Round-Clock 的现成应用壳

18 个应用里的看图英语、互动故事屋、电子宠物、点点小星星就是亲子态的内容位。
不必逐个自研：挑 2-3 个挂进启动器，内容后换。

### 2.4 车载场景 → car-dashboard 只看架构

它的价值在三样：6 表盘的页面组织、**照片轮播 + 烧屏防护**
（AMOLED 长显必须做像素位移/亮度封顶，它实现了）、iOS 端 BLE 推图推导航的协议形状——
最后这条跟我们要做的 §8.3 `photo_chunk`（App → 屏推家人照片）需求形状一致，
App 侧设计协议时直接对着它的消息表参考。代码一行不抄（无 license）。

---

## 3. 分工与顺序

1. **屏侧**：waveshare 官方例程打底（显示/触摸/音频初始化）→ Round-Clock 手势启动器
   → m5stack-avatar 接情绪 → 逐个填场景（时钟先做，亲子故事屋随后）。
2. **App 侧（我）**：§8.3 photo_chunk 推图协议设计（对齐 car-dashboard 的消息形状），
   跟屏侧对齐后随「相册轮播」场景一起启动——本来就排在 P2。
3. 两边汇合点只有两个：`state` 帧的情绪字段（→ avatar 表情）和 photo_chunk
   （→ 相册页）。其余场景屏侧自闭环，不占协议。

---

## 附：检索记录（2026-11-14）

- 同板官方例程：[waveshareteam/ESP32-S3-Touch-AMOLED-1.75C](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.75C)（Apache-2.0，ESP-IDF 5.5/6.0 + Arduino 3.3，CO5300 + CST9217 + AXP2101 + QMI8658）
- 小智生态：[78/xiaozhi-esp32](https://github.com/78/xiaozhi-esp32)（MIT，33k★）、[xinnan-tech/xiaozhi-esp32-server](https://github.com/xinnan-tech/xiaozhi-esp32-server)（MIT 后端）
- 备选看过没采用的：[fasaiph/esp32-gallery](https://github.com/fasaiph/esp32-gallery)（无 license、功能太薄）、[diylxy/lvgl-watch](https://github.com/diylxy/lvgl-watch)（LVGL8 老栈）
