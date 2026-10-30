# 圆屏固件打通操作手册（另一台电脑从零照做）

写给：**手上只有这台 Windows 电脑 + 一块微雪 ESP32-S3-Touch-AMLED-1.75（带壳，不带 GPS）**，要把它连起来的人。
这个包是自包含的 —— 不需要另外 clone 仓库，不需要 JDK，不需要真手机也能走完前 6 步。

预计：第一次点亮 40~90 分钟，大头在装 ESP-IDF 和装驱动，不在写代码。

---

## 0. 先弄清三件事，能省掉一半的弯路

**第一，这台板子不带 GPS。** 位置由手机给：手机 App 每秒推下去的 `state.gnss` 里 `source` 会是 `phone`。
所以固件侧**不要**去做 GNSS 初始化、不要接 GPS 串口、不要在屏上写"GPS 未定位"这类话 —— 那条能力今天没有，写出来只会让人以为设备坏了。

**第二，手机是服务端，屏是客户端。** 反直觉，但这是定死的：App 是数据源，屏不需要被外部寻址。
所以**不需要**给屏分配固定 IP，也**不需要**在屏上监听任何端口。屏只负责"拨出去 + 断了重拨"。

**第三，链路是明文 `ws://`，不是 `wss://`。** 这是有意的偏离，补偿控制是"默认拒绝接入 + screen_id 白名单 + 只在演示时开"。
Kconfig 里把 URL 写成 `wss://` 会连不上，而且报错很难看（TLS 握手失败）。看到 URL 先确认前缀是 `ws://`。

---

## 1. 这个包里有什么

```
HANDOFF.md                      本文件
components/screen_link/         要放进 ESP-IDF 工程的组件（链路层 + 文案闸）
  include/screen_link.h         对外 API，先读这个
  screen_link.c                 WebSocket 客户端、退避、会话、去重
  screen_link_redline.c         禁用词表（与 App 侧同源，11 条）
  CMakeLists.txt / Kconfig / idf_component.yml
examples/screen_link_app.c      怎么调用它（不参与编译，抄进你自己的工程）
tools/fake-phone.mjs            假手机服务端，不装 JDK 也能验固件
README.md                       组件自己的说明（契约细节、为什么这么实现）
```

---

## 2. 装工具链（ESP-IDF v5.5）

小智固件跑的是 ESP-IDF **v5.5**，版本对不上会在 `idf.py build` 报一堆看不懂的错。

1. 打开 https://dl.espressif.com/dl/idf-exe/ （或搜 "ESP-IDF Windows Installer"），装 **v5.5** 的离线安装包，目标芯片选 **ESP32-S3**。
   装的过程中它会让你确认 Python、工具链的下载路径 —— 路径**不要带中文和空格**，`D:\esp` 这种最稳。
2. 装完从开始菜单打开 **"ESP-IDF 5.5 PowerShell"** 这个快捷方式（必须是这个，它才带环境变量；普通 PowerShell 里 `idf.py` 不存在）。
3. 验证：

```powershell
idf.py --version        # 期望 v5.5.x
python --version
```

**装不上或者不想现在装？** 那就先只做第 5 步（假手机那侧），它只要 Node.js，能先把三条判据跑通。

---

## 3. 先刷小智官方固件，确认板子是好的

**这一步不能跳。** 理由很实际：这块板子 8MB Octal PSRAM + CO5300 QSPI 屏 + CST9217 触摸 + AXP2101 电源，
任何一个不亮，你都会误以为是"我写的代码有问题"。官方固件已经把驱动都配好了，先用它把硬件排掉。

1. 板子 Type-C 插电脑。**设备管理器**里看 COM 口：Waveshare 这类板子可能是 CH343 / CP210x 外挂串口芯片，
   也可能直接是 "USB Serial JTAG" —— 看到哪个装哪个驱动（黄叹号就是没驱动）。记下 COM 号。
2. 拿小智官方的 ESP32-S3 发行固件（`waveshare-s3-touch-amoled-1.75` 这个 board 名对应的合并 bin），
   用 **Flash Download Tool** 或 `esptool.py write_flash` 烧进去。烧的时候如果一直 `Connecting....`：
   **按住 BOOT（GPIO0）不放，点一下 RST/EN，松 BOOT** —— 这就是进下载模式，板子上只有这一个坑。
3. 配网：屏上或热点里连上 Wi-Fi，能对话 = 屏幕、触摸、麦克风、扬声器、Wi-Fi 全好。
4. **记下它连的 SSID 和密码**，第 6 步要让屏连同一个热点。

到这一步板子就是"已知良好"的，后面出问题只可能是代码或配置。

---

## 4. 把组件放进工程

用你自己的 ESP-IDF 工程（或从小智源码起一个能编译的空工程）：

```powershell
cd D:\work\my-screen-app
xcopy /E /I "C:\path\to\screen-firmware-handoff\components\screen_link" "components\screen_link"
```

然后 `sdkconfig` 里开 PSRAM（屏帧缓冲要吃），依赖会由 `idf_component.yml` 自动拉：

```
CONFIG_SPIRAM=y
CONFIG_SPIRAM_MODE_OCT=y
CONFIG_ESP_DEFAULT_CPU_FREQ_MHZ_240=y
```

### 两处**必须**对本地头文件核对的点

这两个地方我是按 esp_websocket_client 的公开写法实现的，组件版本一换就可能对不上。**编译报错就改这两处，不要改别的**：

**① 事件回调签名。** 有的版本是 3 个参数、有的是 2 个：

```c
// 3 参数（新版本）
static void ws_event(void *handler, esp_websocket_event_id_t event, esp_websocket_client_event_data_t *data);
// 2 参数（老版本）
static void ws_event(void *handler, esp_event_base_t event, int32_t event_id);
```
对一下你本地的 `managed_components/espressif__esp_websocket_client/include/esp_websocket_client.h` 里
`esp_websocket_event_handler_t` 的 typedef，把 `screen_link.c` 里那个函数改成一样的。

**② config 结构体字段名。** `screen_link.c` 里用到这几项，逐个对本地头文件：

```
uri / certificate（我们不用）/ task_priority / buffer_size /
network_timeout_ms / auto_reconnect / disable_auto_reconnect / event_handler / user_context
```
特别是 **`disable_auto_reconnect` 必须为 true**：退避阶梯（1/2/5/10/30 秒）由我们自己走，
让库自己重连的话，"每次连上都要重发 hello"这条会丢，屏就连上了但手机永远不认识它。

**③ 另一个常见报错**：`unknown requested component: 'esp_timer'` → 打开
`components/screen_link/CMakeLists.txt`，把 `REQUIRES esp_websocket_client json esp_timer` 里的 `esp_timer` 删掉（5.3 之前它不是公开组件，那个符号随 esp_common 给）。

**④ "重复组件 esp_websocket_client"** → 把 `idf_component.yml` 里 `dependencies:` 那一段整段删掉，顶层已经解析出来了，直接复用。

### menuconfig

```
Screen-Link 展示端          （它在 menuconfig 根菜单上，不在 Example App Usage 底下）
  SCREEN_LINK_URL        ws://<手机或电脑的局域网IP>:8443
  SCREEN_LINK_ID         WS175B-A3F7        （必须和服务端白名单一字不差）
  SCREEN_LINK_FW         0.1.0-dev
  SCREEN_LINK_DOCK_TYPE  desk_stand
```

```powershell
idf.py set-target esp32s3
idf.py menuconfig
idf.py -p COM5 flash monitor
```

---

## 5. 先别连手机：用假手机把固件验掉

**为什么要有这一步**：真屏一上来就连真 App，出问题分不清是固件的还是 App 的。
假手机只用 Node 自带模块，不装 npm 包，不装 JDK。

```powershell
node --version                     # 没有就装 LTS
cd C:\path\to\screen-firmware-handoff\tools
chcp 65001                         # Windows 控制台默认 GBK，不设这个中文日志会糊成方块（不是工具坏了）
node fake-phone.mjs
```

看到 `假手机在 :8443 等屏` 就对了。**这时先别急，Windows 上 8443 大概率起不来**，两个坑：

```powershell
# 坑一：EACCES（这台开发机就撞上了）。Hyper-V / IIS 会把一段 TCP 端口划走，8443 常在里头。
netsh interface ipv4 show excludedportrange protocol=tcp     # 看保留段
node fake-phone.mjs --port 9100                              # 换端口，Kconfig 里的 URL 端口要一起改

# 坑二：防火墙挡入站。屏是另一台设备，不是本机 127.0.0.1，必须放行。
netsh advfirewall firewall add rule name="screen-link" dir=in action=allow protocol=TCP localport=8443
```

拿到这台电脑的局域网 IP：`ipconfig` 里看 IPv4 地址（**不是** 127.0.0.1，屏要从外面连进来）。
把 Kconfig 的 `SCREEN_LINK_URL` 填成 `ws://<那个IP>:8443`，重新烧屏。

### 三条验收判据（这就是这一节的完成定义）

| 判据 | 怎么制造 | 屏上/日志应该看到 |
|---|---|---|
| **连上了、状态在动** | 正常启动假手机，屏开机 | 假手机打印 `hello 通过：WS175B-A3F7 · 申报契约 1/2`，之后每秒一条 state 推下去；屏上数字在走、`距下次过顶` 每秒 -1 |
| **白名单真的在拦** | `node fake-phone.mjs --id OTHER-0001` | 屏连上但被关掉，close 原因文本 `unknown screen`；屏**不能**崩，要按 1/2/5/10/30 秒退避重试 |
| **断线检测真的在做** | `node fake-phone.mjs --drop 20` | 前 20 秒正常；到点后屏的链路图标 **约 3 秒变灰**，**约 10 秒**重连（假手机日志会再次出现"握手完成"） |

再加一条服务端侧的：

```powershell
node fake-phone.mjs --reject        # 直接 close("demo lock")
```
屏要退避重试而不是疯狂重连 —— 这条验的是"演示中关掉开关，屏不会把手机的网络打满"。

**三条全过，固件的链路层就是好的。** 后面再有问题，都是 App 或屏上 UI 的。

---

## 6. 换成真手机 App 联调

假手机验通之后，把 URL 从电脑 IP 换成手机的：

1. 手机开 **热点**（演示现场推荐这个，不要连商场地场的公共 Wi-Fi —— 明文链路）。手机自己就是 `192.168.43.1`。
2. App 里打开 **"允许展示端接入"**（默认是关的，开着也不放行任何连接）。
3. Kconfig `SCREEN_LINK_URL` = `ws://192.168.43.1:8443`，`SCREEN_LINK_ID` 和 App 白名单里那条对齐，重烧。
4. 屏和手机连同一个热点（屏连手机热点）。
5. App 首页那台屏的设备卡应该从"没连过"变成在线，台账上有每秒刷新的状态。

**App 报的地址和屏能连的地址可能不是一回事。** App 页面上会写"接入点 ws://x.x.x.x:8443（取自 wlan0）"，
那行是它自己从网卡挑出来的 —— 如果它挑到了蜂窝那一路（`rmnet_data0`），那个地址填进屏永远连不上。
现场先看那行括号里的网卡名：`wlan0` / `ap0` / `swlan0` 才是对的。

---

## 7. 排错表

| 现象 | 最可能的原因 | 怎么办 |
|---|---|---|
| `idf.py` 不是内部或外部命令 | 用了普通 PowerShell | 换成"ESP-IDF 5.5 PowerShell"快捷方式 |
| build 报 `unknown requested component: 'esp_timer'` | IDF < 5.3 | 见 §4 ③ |
| build 报重复组件 | 顶层已有 esp_websocket_client | 见 §4 ④ |
| `esp_websocket_client.h` 里找不到字段 | 组件版本不同 | 见 §4 ②，对本地头文件改 |
| monitor 一直 `Connecting....` | 没进下载模式 | 按住 BOOT 点 RST 再松 BOOT |
| 屏连上立刻被关，原因 `unknown screen` | `SCREEN_LINK_ID` 和白名单不一致 | 用假手机时它会直接把要加的 id 打出来，照抄 |
| 屏连上立刻被关，原因 `bad contract` | 帧的 JSON 不合法（字段名/层级写错） | 假手机会把**原文和原因**打出来，照它改，不要猜字段 |
| 屏连上立刻被关，原因 `demo lock` | 服务端没放开接入 | App 打开那个开关 / 假手机去掉 `--reject` |
| 手机 App 上的地址屏连不上 | 挑到蜂窝网卡；或不在同一 Wi-Fi；或防火墙 | 看 §6 最后一段；`ipconfig` 确认两台设备同网段 |
| 一切正常但屏上数字不动 | state 到了但 UI 没刷 | 检查 `on_state` 里是不是只存了个指针就返回 —— 那块内存在回调返回后就失效，要 `cJSON_Duplicate` |
| 对话发出去没回音 | 屏申报的 `protocols` 里没有 2 | v1 连接收不到 dialog 帧，服务端是逐连接过滤的 |
| 重连后头几句问了没反应 | `turn_id` 用了纯自增 `s-0` | 冒充件必须 `s-<开机随机段>-<自增>`，否则撞上旧 id 被当重复吃掉 |

---

## 8. 还没做（不要在这里踩坑）

- **屏侧 UI**：状态盘、对话列表、SOS 触摸上行都还没画。现在这个包只保证"数据到屏了"。
- **录音 → ASR**：小智那侧有现成的，接进来之前屏发的对话只能是文本冒充件。
- **现场配对**：`screen_id` 现在是编译期写死的，演示现场改不了就得重烧。要不要换一次性配对码，还没定。
- **第二块屏**：两块屏同时接入时，B 屏看不见 A 屏那句问话（只看"谁起的头"，不看连接）。今天一块屏，别顺手改。

---

## 9. 一句红线，两侧各守一次

屏上画出来之前要再过一次文案闸（`screen_link_redline.c`）。
**这不是重复劳动** —— 小智官方云那条路是它自己往屏上画的，手机的闸管不到，
而现场冒出一句"救援已在路上"是不可挽回的那种错。改词表要连着 App 侧的 Kotlin 表一起改，两边不能漂。
