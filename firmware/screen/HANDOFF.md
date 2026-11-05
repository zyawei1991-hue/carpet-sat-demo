# 圆屏固件打通操作手册（另一台电脑从零照做）

写给：**手上只有这台 Windows 电脑 + 一块微雪 ESP32-S3-Touch-AMLED-1.75（带壳，不带 GPS）**，要把它连起来的人。
这个包是自包含的 —— 不需要另外 clone 仓库，不需要 JDK，不需要真手机也能走完前 6 步。

预计：第一次点亮 40~90 分钟，大头在装 ESP-IDF 和装驱动，不在写代码。

> **本手册讲的是我们自己那套 `app/`。** 2026-11-04 起还有第二条路：把固件底座换成 Meta 的
> Muse Gadget SDK（它会听、会说、自带表情动画），`screen_link` 组件搬进那棵树当手机侧的入口。
> 那条路的现状、坑与**App 侧要接什么**在 [`muse/README.md`](muse/README.md)，
> 改动本身在 [`muse/muse-carpet.patch`](muse/muse-carpet.patch)。两份手册讲的是同一块板子、两种固件，
> 别混着刷。

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
examples/screen_link_app.c      怎么调用它（本仓库不编它，抄进你自己的工程；§4 ⑤ 那个外接工程编过一遍）
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

### ~~两处必须核对的点~~ —— 已经核对完了，2026-10-30 更新

这一节原来写的是"编译报错就改这两处"。**现在不用猜了**：把组件仓库
`espressif/esp_websocket_client` 的 1.5.0 / 1.6.1 / 1.7.0 / 1.8.0 四份
`include/esp_websocket_client.h` 都拉下来对过，结论是这四种版本里**一个都一样**，
而且原来列的两个候选签名**都不对**。`screen_link.c` 已按这个结论改好，不用再动
（改完在 ESP-IDF v5.5.2 / esp32s3 上编过，`-Wall -Werror=all` 零告警，见下节）：

**① 事件回调：四个参数，且 `esp_websocket_event_handler_t` 这个类型根本不存在。**

```c
// 组件不收自定义 typedef，收的是 IDF 通用 esp_event_handler_t：
static void ws_handler(void *arg, esp_event_base_t event_base, int32_t event_id, void *event_data)
{
    esp_websocket_event_data_t *evt = (esp_websocket_event_data_t *)event_data;
    switch (event_id) { ... }        // event_id 从第三个参数来，不在 evt 里
}
esp_websocket_register_events(s_ws, WEBSOCKET_EVENT_ANY, ws_handler, s_ws);
```

原来 ① 里给的两种写法（3 参数带 `esp_websocket_event_handle_t *`、2 参数不带 data）
都编不过 —— 前者那个类型名不存在，后者拿不到帧。`esp_websocket_event_data_t` 的字段
是 `data_ptr / data_len / payload_len / payload_offset / op_code / fin / client / user_context / error_handle`，
代码里用到的那几个都对得上。

**② config 结构体：用到的字段里有一个不存在。**

| 字段 | 有没有 |
|---|---|
| `uri` `buffer_size` `task_stack` `task_prio` `disable_auto_reconnect` `reconnect_timeout_ms` `network_timeout_ms` `user_context` | 有 |
| `auto_reconnect` | **没有**（另有 `enable_close_reconnect`，语义不同） |
| `event_handler` | **没有**，配置结构体里没有回调位，回调只走 `esp_websocket_register_events` |
| `task_priority` | 没有，叫 `task_prio` |
| `certificate` | 没有，叫 `cert_pem` |

`disable_auto_reconnect = true` 那条要求照旧成立，而且这是**唯一**能成立的做法：
库自带的重连只有一个固定间隔，契约要的 1/2/5/10/30 秒阶梯必须自己走，
否则"每次连上都要重发 hello"会丢，屏显示已连上但手机永远不认识它。
顺带一句：`reconnect_timeout_ms` 在 `disable_auto_reconnect=true` 时不起作用，留着只为以后好读。

**③ `REQUIRES ... esp_timer`** 在 5.5 上是公开组件，不用删；真报错再从这行删掉。

**④ 关于"重复组件"：这条不用担心，方向还反了。**
小智 `main` 分支的 `main/idf_component.yml` 里**没有** `esp_websocket_client`
（它连自己服务器走的是 `protocols/` 里那套，不是这个组件）。所以这个依赖是由
`idf_component.yml` 现拉一份，不会撞车；**反倒别把那一段删掉** —— 删了构建时报
找不到组件。构建时组件管理器要能访问 `components.espressif.com`。

顺手记下另一条会绊住 §3 的：小智 `main` 现在写的 IDF 下限是 `>=6.0.1`，不再是 5.5。
按标记（tag）起工程的人以那个 tag 的 `idf_component.yml` 为准。

**⑤ 上面这些已经不是纸面结论了。** 这台机器上有 ESP-IDF v5.5.2（在 `D:\dev\esp\5.5.2`），
`D:\dev\screen-link-probe` 是一个只挂着本组件的最小工程：顶层 `CMakeLists.txt` 用
`EXTRA_COMPONENT_DIRS` 指向本仓库 `firmware/screen/components`（不拷副本，验出来的错改在正主上），
`main/CMakeLists.txt` 还把 `examples/screen_link_app.c` 用绝对路径编了进来。结果 `Project build complete`，
零告警。那份示例原来有两个错（`cJSON_TRUE` 在 IDF 那份 cJSON 里不存在 —— 那是上游 1.7.18 才加的名字，
IDF 只有 `cJSON_True`；换帧摘下来的旧树没人释放，`-Wall` 只给个不致命的 unused-variable 就混过去了，
1Hz 实际是一秒漏一棵），已按它自己注释里讲的规矩改完。

**⑥ 2026-10-31：那块板子真的连上局域网跑了，上面那三条判据里的两条已经实测。** 屏连 `aa123` 拿到
`10.180.126.29`，服务端是这台 PC 上跑的 `:simulator`（`10.180.126.165:8443`，所以 §5 那条防火墙入站规则
最终没用上 —— 这个 AP 不做客户端隔离，屏直接摸得到 PC）。抓到的是 `hello` → 协商**契约 v2** →
`ack {"for_seq":1}` → 之后 58 秒窗口里 `state` 连续 53 帧没断（`gap=0s`），上行 dialog/event 六轮全 `ESP_OK`，
服务端回了一条 `dir=down source=local_fallback`。第三条「断网约 3 秒变灰」是 UI 侧行为，
这份仓库还没有界面代码，所以它仍然只是纸面的。

实测揪出三个只有跑起来才露出来的错，都已改在本组件里：

1. **退避阶梯以前根本没跑**：`link_task` 里「连不上」那条出口原来是一行 `continue`，绕过了 `sleep_backoff`，
   重连变成死板的每 10 秒撞一次（改之前实测到的间隔就是 10.0s 整）。现在两条失败出口共用 1/2/5/10/30 那个阶梯。
   也就是说 §5 表格里「屏不能崩，要按 1/2/5/10/30 退避」这一条，**改之前是假的**。
2. **Close 帧的 reason 会被当成正文**：`WEBSOCKET_EVENT_DATA` 原来不看 `evt->op_code`，服务端
   `close(CLOSE_POLICY, "bad contract")` 那条 Close 的状态码 + reason 被拼进装配缓冲并 dispatch，
   日志里出现「契约错：json 解不开」而 raw 根本不是 JSON。危害不是难看，是把**真的**契约错盖在一堆假错里。
   常量名注意：opcode 枚举在 IDF 的 `tcp_transport/include/esp_transport_ws.h`，叫
   `WS_TRANSPORT_OPCODES_TEXT` / `WS_TRANSPORT_OPCODES_CONT`；没有 `WEBSOCKET_OPCODE_*` 这种东西，
   照网络示例抄直接 `undeclared`。收帧的完成判据同时从「累计长度 == `payload_len`」换成了 `evt->fin`：
   `payload_len` 说的是**当前这一片**的总长，一条消息拆多帧时旧判据会在第一片收满就 dispatch 半条 JSON。
   这条是照字段语义改的预防，今天一帧一条消息，**没实测到分片**。
3. **上行枚举值不能凭名字像不像来编**：探针最初发 `source="probe"`、`kind="sos_test"`，两个都不在
   `core/.../protocol/ScreenLink.kt` 的枚举里，服务端解 JSON 失败就关连接。**仓库
   `examples/screen_link_app.c` 里是同一类错**：它发的是 `"sos_press"`，那是 `CommandType` 不是 `EventKind`，
   照抄的人一定踩；已改成 `"sos_triggered"`。合法值只有 `DialogSource{asr, local_rule, model, local_fallback,
   denied, xiaozhi_cloud}` 和 `EventKind{sos_triggered, sos_acked, sos_failed, sos_cancelled, fence_enter,
   fence_exit, sentinel_motion, link_switched, sat_visible, downlink_msg, track_reset}` 这两套。

**编译期两条、运行期三条，现在都有了对应证据**：前两条判据靠 ⑥ 那次真机抓串口结掉，第三条只能等 UI 代码存在。

**⑦ 2026-10-31（同一天，晚上）：契约加了可选的 `state.ui`，固件那侧第一次有了界面代码。**

契约这一侧一次改齐五处，没有只改一端：`protocol/screen-link.schema.json` 加 `ui{card,emotion,tz_min}`、
`ScreenLink.kt` 加 `UiCard/UiEmotion/UiState`、`SchemaDriftTest` 把 `ui` 放进逐字段比对并**新增一条枚举取值比对**
（固件那边是手写分支表，App 枚举手滑多一个值，屏不会报错只会默默落回兜底那张 —— 现场看起来就是"点了没反应"）、
`DemoEngine.snapshot()` 带上 `ui`、`DemoDriver` 按脚本步轮播卡片外加 `--card NAME` 钉死。
`node simulator/linkcheck.mjs` 那条新断言实测通过：`ui 6/6 帧：卡=[clock,status] 表情=[sleepy,neutral] tz_min=480`。

**`ui` 没升契约版本**，这是想清楚的：加性可选字段，固件的 cJSON 只读它认识的键，多一个键不花它一分钱；
升版本防的是"一种它不认识的 `t` 把分派 if-else 撞穿"，v2 加 dialog 就是这个原因。代价是两条硬规矩 ——
`ui` 缺失必须回到 `status` 那张（不许黑屏、不许停在最后一张卡），以及取值表两端一字不差。

屏这侧新建的 `app/` 工程（微雪 BSP + LVGL 9.4，不再是"只挂组件的示例"）现在画五张卡加一条顶栏：
顶栏那条「>3 秒没帧就变灰并写 链路中断 mm:ss、正文一个字不清」就是判据第 2/3 条的 UI 半边，
**代码写了、编译过了、还没上硬件**。钟面用的时间来自整帧的 `ts` + 组件新加的
`screen_link_state_ts_ms()` / `screen_link_state_age_ms()`：这块板子没有电池-backed RTC，SNTP 在车上说断就断，
所以屏不自己算时间，只把手机的时间按本地过了多少毫秒往前推。64 位在 S3 上读写不原子，这两个取值配了自旋锁。

三条已知没验的，别当已经完成（2026-11-01 更新，②已经半验，①③仍然只欠眼睛）：
① 表情是矢量现画的（`face_cfg_t` 那张角度表），**我看不到屏**，弧度和位置必然要现场调。
   这一轮脸又缩小过一次（240→132、96→72），所以角度表等于全新没看过；
② 中文走 storage 分区那份 `carpet-noto.ttf`（7405 码点，运行时 tiny_ttf 现渲染，见 `app/assets/make-font.py`）。
   **挂载、读盘、三档渲染已经在真机日志里成了**（`中文字库 2183484 字节 → 56/32/22px 三档`），
   码点覆盖也验了：`app_main.c` / `screen_link.c` / `screen_link_redline.c` 三个文件字符串字面量里的
   259 + 79 + 63 个非标点字符，逐一对过那份 ttf 的 cmap，**缺字 0 个**
   （脚本 `D:/dev/tmp/fontcheck.py`，用 IDF 那份 venv python 跑，`python3` 别名在这台机器上是坏的）。
   仍然没验的是观感：56px 的数字在圆屏中间到底压不压边、22px 明细在两米外读不读得出；
   读不到字库时退回内置思源 16（只有 1456 字，会露出方块）；
③ 96 字节缓冲的截断两处都改成按码点退了（组件 `copy_clip` + `app_main.c` 的 `clip_utf8`），
   编译过、逻辑推演过，**但同样没在屏上验过**：拿一句 >95 字节的长答句画一遍才知道退得对不对。

这一轮自己复查 `app_main.c` 又揪出五处，全部改在代码里，都不是编译器会报的那种：

1. **`lv_arc_create` 默认带 CLICKABLE**，而这块屏有电容触摸 —— 观众戳一下屏幕，嘴的弧度被自己拖改。
   现在 `face_arc()` 一个工厂函数统一 `clear_flag(CLICKABLE | SCROLLABLE)`， eyes 和 mouth 都走它。
2. **`bsp_display_lock(0)`**：LVGL 任务可能正画到一半，超时会直接返回失败，接着建控件树等于两个线程改同一棵树。
   改成 `ESP_ERROR_CHECK(bsp_display_lock(1000))`。`bsp_display_start()` 里已经起了 LVGL 任务和 100% 亮度，
   不用再手动调 `bsp_display_backlight_on()`。
3. **嘴的位移算了两遍半径**（`mouth_dy - d/2 + d/4`），画出来在脸外面。`mouth_dy` 本来就是相对脸心的量。
4. **卡片容器和脸的容器都没关 SCROLLABLE**：一拖就把内容拖出可视圆区。圆屏可视宽度随 y 变，
   滚出去的东西人根本不知道存在。`page_new()` / `face_new()` 各补一条 `clear_flag`，
   脸那个还额外加了 `OVERFLOW_VISIBLE` —— 嘴角正好压在容器边上，默认那份裁剪会把弧的两端剪平。
5. **时区标签只报小时**：`tz_min` 是 330（印度）或 545（尼泊尔）时，屏上写的 "UTC+5" 和钟面差半小时。
   现在非整小时写成 `UTC+5:30`。

**⑧ 2026-10-31 深夜：第一次真板跑起来，链路全对、屏一个像素都没亮。** 串口同时给出两边证据：

- 链路侧全绿：`hello` → 契约 v2 → 1Hz `state` 连续，`屏：卡=dialog/clock/status/sat/emotion` 跟着手机的
  `ui.card` 一张不落，堆低水位 6.9MB 几乎不动（1Hz 重画 + 表情重建控件树没漏）。
- 画屏侧全红，每帧都来：
  `E esp_lvgl:bridge_v9: Draw bitmap failed: ESP_ERR_NO_MEM`
  ← `E co5300_spi: panel_co5300_draw_bitmap(313): send color data failed`
  ← `E lcd_panel.io.spi: panel_io_spi_tx_color(395): spi transmit (queue) color failed`

根因不在应用代码里，也不在报错的那三层里：BSP 把 LVGL 画缓冲放在 PSRAM
（`bsp_display_lcd_init` 里 `.use_psram = true`、`.buffer_height = 50` 都是写死的），而 S3 的 SPI 驱动
**不能直接 DMA 外部内存**（`esp_driver_spi/src/gpspi/spi_common.c`：`external memory is not supported now`），
于是 `spi_device_queue_trans` 每次都要按整块 malloc 一块内部 DMA 内存当中转站。
`esp_lcd_panel_io_spi.c` 只在 `chunk_size > spi_trans_max_bytes` 时切块，而 `spi_bus_get_max_transaction_len()`
返回的是总线 `max_transfer_sz`（BSP 给的是整屏 434KB），所以一次 50 行的 flush 就是一整块 46,600 字节。

实测的三个数（同一块板子、同一份固件，只在 `log_ram()` 里打的）：

| 时刻 | 内部 free | 内部最大连续块 | 一次 flush 需要 |
|---|---|---|---|
| 刚点完屏（还没起 Wi-Fi） | 133,935 | **90,112** | 46,600 ✅ |
| Wi-Fi + websocket 起来后 | 46,255 | **23,552** | 46,600 ❌ |

也就是说 **Wi-Fi 一个人吃掉 88KB 内部 RAM 并把剩下的打碎**，屏就再也刷不动了。
最坑的是这时候 `esp_get_free_heap_size()` 还有 6.9MB —— 因为那全是 PSRAM，
所以"看剩余内存"这种常规判断在这儿彻底失效，只有 `heap_caps_get_largest_free_block(MALLOC_CAP_DMA)` 说真话。

改法（全在 `app/sdkconfig.defaults`，带实测数字的注释也写在那儿）：把无线侧缓冲挪去 PSRAM
（`SPIRAM_TRY_ALLOCATE_WIFI_LWIP=y`）、静态/动态缓冲数量收小、**关掉 AMPDU**（这块屏收的是 1Hz 的几 KB
JSON，聚合帧换来的吞吐毫无用处，只会占内部内存）、`BSP_LCD_TRANS_QUEUE_DEPTH` 从默认 10 压到 1
（队列深度是 BSP 的内部 Kconfig，厂商自己的 defaults 里没写，所以换服务端地址之外还要点名它一次）。
这轮**只验到"数字对得上"**：改完是否真的能画出来，还得再烧一次看那条 `Draw bitmap failed` 有没有消失。

**⑨ 2026-10-31 凌晨：上面那轮 sdkconfig 不够，真正解决的是 SPI 传输上限。** 烧进去 80 秒串口，
失败一次没少（`Draw bitmap failed` 还是 105 条），而最大连续块只有 **23,552**、一次 flush 要 **46,600**：
差的不是碎片，是**根本没人能一次性拿出 46,600 字节的内部 DMA 内存**。
Wi-Fi 常态就要 60~80KB，靠收缓冲腾出那 23KB 是碰运气的做法，展会现场手一抖就黑屏。

所以改的是调用方，不是厂商代码（`managed_components/` 会被组件管理器按 `CHECKSUMS.json` 校验，
改了就是给下一次 `idf.py` 埋雷）。`app/main/app_main.c` 新增 `display_start()`，
用 BSP 和适配器的**公开 API** 把 `bsp_display_start()` 那五步原样走一遍
（`esp_lv_adapter_init` → `bsp_display_new` → `esp_lv_adapter_register_display` → `bsp_touch_new` +
`esp_lv_adapter_register_touch` → `bsp_display_brightness_init` → `esp_lv_adapter_start`），
只把一条参数换掉：`bsp_display_config_t.max_transfer_sz` 从厂商写死的整屏 434,312 换成 `FLUSH_MAX_BYTES` = 8192。
`esp_lcd_panel_io_spi.c` 拿到一次超过这个上限的颜色传输会自己切段（除最后一段都带 `SPI_TRANS_CS_KEEP_ACTIVE`），
中转站于是从 46,600 变成 8,192 —— 一次 50 行 flush 切 6 段，每段都在最大连续块以内。
`bsp_display_lcd_init` 里那条 `rounder_event_cb` 是 static 的，拿不到，所以按它的算法在本文件里重写了一份
（起始边向下凑偶数、结束边向上凑奇数；少了这段，奇数宽度的 flush 会在右边缺一列）。

烧完 80 秒的证据：`Draw bitmap failed` **0 条**、`ESP_ERR_NO_MEM` **0 条**，五张卡照旧全轮到；
内部 RAM 反而宽裕了（free 34,507→64,179，最大连续块 23,552→31,744），因为再没有 46KB 级的整块进出。
堆低水位 6,834,784 与 6,835,904 之间不动 —— 不是"暂时没漏"，是三轮采样都没往下走。
途中 `Wi-Fi 掉了` 2 次，两次都在 2s 重连后回到 `已连上手机，契约 v2 · 可对话`，这条是展会的 Wi-Fi 本身，不是固件。

**还差的只有一件事：肉眼。** 上面所有数字都来自串口，固件这边看不到屏。
观感问题（字号、表情那张脸的弧线、卡片留白）必须有人对着圆屏说一次才算完。

**⑩ 2026-11-01：一条 WS 帧被 TCP 段切开，屏侧把半条 JSON 当一帧 dispatch 了。**
抓 90 秒串口想验长按，结果长按一次没按下（没人碰屏），倒是撞出这个：

```
E (85075) screen_link: 契约错：json 解不开
E (85076) screen: 契约错：json 解不开 / {"t":"state","seq":278397,... "ui":{"card":"
E (85206) screen_link: 契约错：json 解不开
E (85206) screen: 契约错：json 解不开 / status","emotion":"happy","tz_min":480}}}
```

两条 raw 正好互补，拼起来是一条完整帧。**根因在 `esp_websocket_client` 的收包循环里**
（`esp_websocket_client.c` 的 `esp_websocket_client_recv`）：

```c
do {
    rlen = esp_transport_read(transport, rx_buffer, buffer_size, network_timeout_ms);
    client->payload_len = esp_transport_ws_get_read_payload_len(transport);
    client->last_fin    = esp_transport_ws_get_fin_flag(transport);
    esp_websocket_client_dispatch_event(client, WEBSOCKET_EVENT_DATA, rx_buffer, rlen);
    client->payload_offset += rlen;
} while (client->payload_offset < client->payload_len);
```

一条帧的正文跨了几个 TCP 段，它就发**好几个** `WEBSOCKET_EVENT_DATA`，而每个分片的 `fin`
都是从 transport 现取的同一个值 —— **每个分片都写着 `fin=true`**。
组件原来只按 `if (!evt->fin) break;` 判"这条消息收完了"，于是在第一个分片上就把半条 JSON 丢给解析。

> 这里有个反面教材值得留着：原来那条注释写的理由是"完成判据用 fin，不用累计长度 == payload_len，
> 因为 payload_len 说的是当前这一片的总长"。前一句对，后一句正好把真相说反了 ——
> `payload_len` 是**本帧**总长、`payload_offset` 是**本片之前**的偏移，所以「`offset + data_len >= payload_len`」
> 恰恰就是"这一帧收满了"的唯一可靠判据。而当时那句"今天没实测到分片，所以算预防"……
> **没实测到 ≠ 不会发生**：分片与否取决于 TCP 段边界，跟 Wi-Fi 拥塞、并发渲染都有关，
> 前一轮抓 5 分钟 0 条错、这一轮抓 3 分钟 6 条，同一份固件、同一个服务端。

修法（`ws_handler` 里两行，顺序不能反）：

```c
if (evt->payload_offset + evt->data_len < evt->payload_len) break;   /* 本帧没收满 */
if (!evt->fin) break;                                                 /* 消息还有后续帧 */
```

第一条治这次抓到的分片，第二条仍然管住"一条消息拆成多个 WS 帧"那种 CONT 链 —— 两条都要留着。

**为什么这条是展会级的**：解不开的 `state` 会被静默丢掉，屏只是"这一秒没更新"，
顶栏的 `>#3 秒没帧` 都触发不了（因为**下一帧正常来了）—— 也就是说屏幕会时不时冻住一下而日志里只有一堆契约错。
更要命的是它教会一件事：**屏上看到的"卡住一下"和串口看到的"契约错"是同一条分片**，
现场若只盯屏会以为是 Wi-Fi，只盯串口会以为是服务端。

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

### 事件横幅（2026-11-02 接上 `on_event`）

以前契约里的 `t=event` 只回 `ack` 就丢了，所以"屏像轮播"的一半原因是它压根没人话可说。
现在 `app_main.c` 里多了一条**叠在卡面上的横幅**，不是第六张卡，也不改 `s_shown_card`：

- kind → 固定中文文案在 `EVENT_BIG/EVENT_SMALL` 两张表里，顺序跟契约 `event.kind` 一致，
  末尾多一格 `EV_UNKNOWN`（不认识的那条也要说话，不能装没看见）。
- **每张表都过文案闸**：`banner_selfcheck()` 在 `ui_create()` 末尾逐格跑
  `screen_link_copy_is_banned()`，命中就 `ESP_LOGE`。改词的人只会看见板上那行字，
  看不见词表——这句"看不见"就是自检存在的理由。
- **过期是组件判的，不是 app 判的**：`on_event` 的第三个参数是**还剩**多少毫秒
  （`left_ms`，0 = 帧里没给 ttl）。组件先回 `ack` 再判过期，所以过期那条屏不弹、
  手机仍收到确认 —— 反过来写会让 App 一直重发同一条没人看的话。
- 现在时间没有 RTC，只能估：`state.ts + state_age`。估不出来时**放行**，
  因为把一条真事件丢掉，比多弹一条过期提示更糟。

踩到的坑，值得单独记：**`ts` 是 13 位 Unix 毫秒，塞不进 `uint32_t`**。
组件里原来用 `num_or()` 读它，`(uint32_t)valuedouble` 把它绕回 49.7 天以内，
于是钟面画的是 1970 年 1~2 月里某个时刻，而串口日志里那个数看着仍是个正常正数 ——
日志查不出来，只有眼睛看得见。大数一律走 `i64_or()`。

### 抓屏：把圆屏真实画出来的像素读回电脑（2026-11-02）

只写不读是这块屏最难查的地方：`ESP_ERR_NO_MEM` 那次，日志、堆、总 free 全正常，屏就是黑的。
现在有一条调试通道（完整用法在 `README.md` §6.1，这里只记要照做的三条配置和两个坑）：

1. 本机 `sdkconfig` 加两条 —— **都别写进 `sdkconfig.defaults`，仓库是公开的**：
   ```
   CONFIG_LV_USE_SNAPSHOT=y        # lv_snapshot_take 的编译开关，已经在 defaults 里
   CONFIG_SCREEN_SHOT_PORT=8444    # 0 = 不起服务；量产必须是 0
   ```
2. `main/screen_shot.c` 起来的是个只有一条 URI 的 httpd：**没有鉴权**，同网段谁都能读走整屏。
   它只在 `wifi_start()` 拿到 IP 之后才起（所以屏没网时这个口根本不存在，别以为是脚本坏了）。
3. PC 侧 `firmware/screen/tools/screen_shot.py` 把 BMP 转成 PNG，只用标准库
   （这台机器装不上 Pillow，`python -c "import PIL"` 现在是 ModuleNotFoundError，别指望它）。

坑一：`httpd_config_t` 里那条叫 `stack_size`，不叫 `task_stack_size`（后者是 esp_wifi 的命名），
而且 `HTTPD_503_*` 这个枚举**不存在** —— 最细只到 500/501/505/4xx，忙的时候只能回 500 加文本。
坑二：中文 Windows 的 `netstat` 是 GBK，脚本里用 `subprocess(..., text=True)` 会让读取线程
按 UTF-8 解码失败、主线程拿到 `None`，表现成"明明连上了却说找不到屏"。要 `capture_output=True`
再 `.decode("gbk", "ignore")`。

2026-11-02 傍晚已经在真板上跑通了（COM8 这份固件 + 手机热点，屏 192.168.43.243）：
一次 `/shot.bmp` 回 652,454 字节，转出来的 PNG 排版正常，11 步联动的 `lit_pct` 在 7.4~14.0%。
开 httpd 之后内部 RAM 没被挤垮 —— 串口「抓屏服务起好后」空闲 62,519、最大连续块 31,744，
切卡最低也还有 20,480，而一次 SPI 中转站要 8,192，够用。

**这条通道第一次跑就抓出一个串口永远抓不到的错**：卫星卡每一步都画
「卫星终端离线 / --:--」，而手机那边星是锁着的（`sat_ok=true`、GW-07、CNR 42.5）。
根因不在固件：契约里 `module_ok` 带了个 `= true` 默认值，出站 JSON 是
`encodeDefaults = false`，于是**终端健康的那一帧恰好把这个键整个省掉**；
固件 `bool_of` 找不到键就不写出参，出参缓存来自开机 `memset(0)` = false，
所以屏永远停在离线分支，串口一个错都不会报（帧里那个数看着完全正常）。
已改：`module_ok` / `queue_pending` 去掉默认值、schema 的 `sat.required` 补上这两项，
`SchemaDriftTest` 加两条钉住它（一条测 wire 上真的有没有 `"module_ok":true`，一条测
"schema 说必填 ⇒ Kotlin 不许带默认值"）。**改的是手机侧 Kotlin，固件一个字节没动，
所以验它不用重烧，只要重跑 `simulator/linktest.sh`。** 重抓第 2~7 步已确认真像素变了：
同一块屏从「--:-- / 卫星终端离线 / 心跳只能留在手机队列」变成「00:13 / 下次过境」。

同一批图里另外三处，都是**串口看不出来、只有眼睛能看见**的（下面这几条到"屏上看得见的六处"那节全部修完）：
- **帧里没带的字段，屏上还留着上一帧的值**：第 2 步那帧 `sat` 只有 `phase/idle`、`module_ok`、`next_pass_in_s`，
  **没有 `sat_name`**（App 侧 `if (modem.available) lock?.name else null`，null 被省掉），
  可屏上照样写着 `G W-07`。和 `module_ok` 是同一类：`str_of` 找不到键不动缓存，缓存只在开机 `memset` 一次 ——
  上行"这一项现在没有了"在屏上表达不出来。**"缺了等于不知道"和"缺了等于保留"必须在契约里写死是哪一个**，
  否则两侧各自猜。
- **窗口正开着却画「00:00 下次过境」**：第 5/7 步 `phase=locked`、`next_pass_in_s=0`、`sat_ok=true`，
  `render_sat` 只有"等过顶"这一支，没有"在窗口内"这一支（`app_main.c:1107`）。
  App 侧把 `next_pass_in_s` 发 null 只会换来 `--:--`，更差 —— 修在固件。
- **横幅本身是真画出来的**（更早那次连跑里抓到的实拍：`卫星已收到，正等平台回执` 叠在卡面上），
  但**这 6 步的定格帧里一条横幅都没有**。两种解释还分不开：
  ① `sos_acked` 的 `ttl_ms=15000` 在"等重连 + SETTLE"这段时间里就过了；② 每步重起驱动器会把整步历史
  **用同一批 `event_id` 重发一遍**（第 2 步和第 7 步都发 evt-24/25），而组件那 32 格去重环跨重连还在。
  **这条只有边跑边抓串口才能定，别拿图当结论**；顺带说，`linktest.sh` 这种"每步冷启动一次"的打法
  天生看不见 ttl 秒级的东西 —— 要验横幅得用连跑 + 掐点抓图，不是用这份脚本。

同一轮里另外三条当时还没修的（只有眼睛能看见，图在 `shots/real/`；下面那节一并修完）：
- 状态卡明细行把英文原值画上去了：`idle  G W-07`（`app_main.c:1074` 直接拼 `sat_phase`/`sat_name`），
  顶栏「在线 #187 v2」是帧号调试位 —— 这两处都没过词表闸，展会现场读起来就是"英文看着费劲"那句抱怨。
- 对话卡 32px 的 hero 一句 16 字，右边那个字已经贴到圆屏边上被切（`shots/real/step08.png`）。
- 22px 那行有字形叠压（横幅「已越界」那两行里的「台回」），字库 advance 自检 12 个字全是 1000/1000，
  不是子集化的锅，怀疑 tiny_ttf 在小字号上的取整，还没定位。

### 上面这六处已经修完（2026-11-02 晚，一次 app-flash）

改前那批图留在 `shots/real-before-moduleok-fix/`，改后在同名步数下的 `shots/real/` —— 同一块屏、同一步，逐张对得上。
先纠正一条：**22px 那处不是渲染 bug**。把原始 BMP 抠出来逐字量过（`D:/dev/tmp/measure.py`），
`idle  GW-07` 那行实测 117px、按本机 hmtx 算出来 114px，推进宽度是忠实的；
`l` 在 22px 只有 2px 宽，看着像"id le"是字号贴着可读下限，不是 tiny_ttf 取整错。所以这一条改的是字号，不是驱动。

- **#31 英文原值 + 帧号调试位**：固件新增 `PHASE_NAMES/PHASE_CN`、`PRIMARY_NAMES/PRIMARY_CN` 两张表，
  `on_state` 里用 `put_enum` 一次把"取键 → 查表 → 过闸"做完，屏上只出现中文；
  表里没有的取值落「相位未知 / 主链路未知」并 `ESP_LOGW` 吵一声（不出现键名和"看不见没跟上"是两件事）。
  `render_badge` 的「在线 #41 v2」改成「在线」，帧号只在 `log_ui` 那条串口日志里。
  手机侧 `Skills.kt` 那句「当前相位 idle」改成中文，并且 `IN_WINDOW` 从字符串集合改成 `SatPhase` 集合 ——
  新加一个相位忘了配文案，`when` 不穷尽当场编译不过。
- **#32 圆屏把字裁掉**：根因是行宽按方屏给。新增 `UI_SAFE_W = 372`（0.8 直径；居中时半宽 186，
  而半弦 √(233²−dy²) 在 |dy| ≤ 140 内都不小于 186），卡片/横幅所有整屏宽的行改走 `line_new_w(…, UI_SAFE_W)`；
  `line_new` 保留 `LV_PCT(100)` 给那颗 120 的呼救钮用（父容器比圆小的时候撑满是对的）。
  实拍：那句 22 字的答句自己折成三行，头尾一个字不缺。
- **#33 字号**：`FONT_PX_BODY` 22 → 24，`SOS_SIZE` 112 → 120（钮内圈弦长 104，四个字 96 才放得下），
  呼救钮那三句状态字压到四个字（「手机没连上」→「没连手机」等），因为五个 24px 字 = 120 会折行糊在钮里。
  纵向重算：hero 56 + 两行 24×2 + 明细 24×6 + 行距 ≈ 250，250+120+24+24+18 = 436 ≤ 466。
- **#34 旧值残留**：新增 `view_clear_state()`，`on_state` 每帧开头先把 link/sat/fence/ui 能改写的东西清回"这一帧没说"，
  再按帧填。`talk` **不在清理之列** —— 那句走 dialog 帧，一帧 state 把它抹掉是另一种错（答句闪回那条已经修过）。
  布尔的语义随之变了：`bool_of` 找不到键 = 落回 false（离线），不再是"保留上一帧"；
  这两个键现在是 schema 必填 + Kotlin 无默认值，缺键只会是生产者坏了。
  实拍：第 11 步那帧没有 `sat_name`，改前屏上还挂着 `G W-07`，改后画「卫星 -」。
- **#35 窗口开着画 00:00**：`view_t` 多带一个 `phase_code`（`put_enum` 顺手写出来，不反过来猜中文），
  `render_sat` 加 `PHASE_IN_WINDOW` 那一支：hero「窗口开着」+「现在这几分钟能发呼救」，
  倒计时只在窗口外画。判据和 `core/ai/Skills.kt` 的 `IN_WINDOW` 同一份。`mmss` 挪进明细第 2 行，窗口内也还看得见数。
- **#36 掉线配笑脸**：固件删掉对话卡那行 `EMOTION_CN[v->emotion]`（表情已经用脸画了，再配一个字就是多余，
  而且它会说错）；手机侧 `CardPolicy` 那句答的 `happy` 改成 `if (snap.sat.moduleOk) happy else sad`，
  卡面仍然占住（别让上下半句忽然换题）。实拍第 8 步：`dialog/sad`，第 9/10 步终端在线才是 `dialog/happy`。

**闸那边**：`banner_selfcheck` 现在逐条扫 `PHASE_CN / PRIMARY_CN / EMOTION_CN`，不只扫横幅。
这条不是形式主义 —— `PHASE_CN` 里只要写一句「发送成功」（它正好在 `SosCopy.BANNED` 里），
卫星卡就会每帧踩一次红线而屏上看着一切正常。

**守卫**：`SchemaDriftTest` 新增 `固件手写的枚举手必须和 schema 的取值一字不差`，
把 `app_main.c` 读回来抠那 5 张 `*_NAMES` 表和 schema 逐条比，另外比中文列的**条数**（少一格 = 有取值没配文案，
表现为永远画兜底那句）。`CardPolicyTest` 新增离线那句答不配笑脸。`:core` 241 条全绿。

**仍然没验到的**：横幅在这轮的 11 张定格帧里还是一条都没有，而更早那次连跑实拍里出现过 ——
上面那两种解释（ttl 熬过去 / 同一批 `event_id` 被 32 格去重环吃掉）还是分不开。
要定它得换打法：驱动器连跑 + 掐点抓图，同时开串口，不是用 `linktest.sh`。

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

看到 `假手机在 :8443 等屏` 就对了。**但先认清一件事：`fake-phone.mjs` 不处理 `command` 帧**
（它只认 hello/ack/event/dialog，屏滑一下它一句都不回，卡永远不换）。所以要验"手势 → 换卡"这一条，
服务端得是跑**真 CardPolicy** 的那个 —— `./gradlew :simulator:run --args="--hold 9"`（彩排台，`DemoDriver` 起的是
`:core` 里那个 `ScreenLinkServer`，8443 就在它手上）。`--hold` 不影响卡片决策：`pushState()` 和 `tickOnce()`
走同一条决策，这条是写在 `DemoRuntime` 注释里的，别绕过去自己发帧。

Windows 上让屏连进这台电脑，两个坑 —— **2026-11-03 在这台机器上实测，两个坑的结论不一样**：

```powershell
# 坑一（这台机器没有）：EACCES / Hyper-V 保留端口段。
netsh interface ipv4 show excludedportrange protocol=tcp    # 这台打出来是空的 ⇒ 8443 随便用
# 那台拿板子的电脑撞过这个，所以脚本里留了换端口的退路：node fake-phone.mjs --port 9100

# 坑二（这台机器能解，那台的解不了）：防火墙挡入站。
netsh advfirewall firewall add rule name="carpet-sat-screen-link-8443" dir=in action=allow protocol=TCP localport=8443,8444
```

**坑二这条是两台电脑的真正分岔口，值得记清楚**（我 11-03 在这台上量过）：那台的三个 profile 被组策略钉成
`BlockInbound` 且本地规则库显示 N/A —— 那种机器**加不了**本地放行，只能换服务端主机；这台三个 profile 都是
`NotConfigured`（没人用策略设过 ⇒ 走 Windows 本地默认=挡入站），但规则库是本机的（已经躺着 231 条放行）。
差别就是：**这台跑一次管理员就永久通了，那台永远不通**。现成的脚本：
`tools/firewall-allow-8443.cmd` 右键"以管理员身份运行"，卸载那行也打印在它输出里。
不打算动防火墙的话，服务端就得是手机 App —— 而那条卡在 #37（鸿蒙那个安装免责窗）。

拿到这台电脑的局域网 IP：`Get-NetIPAddress -AddressFamily IPv4`（**不是** 127.0.0.1，屏要从外面连进来）。
`SCREEN_LINK_URL` / `SCREEN_WIFI_SSID` / `SCREEN_WIFI_PSK` / `SCREEN_SHOT_PORT` 这四条别再开 menuconfig 翻，
用 `tools/set_link.py --url ws://<IP>:8443 --ssid <网名> --psk <密码>`；它只动这四行、打印新旧对照、
密码只打印长度。**这四条全在 gitignored 的 `sdkconfig` 里，换电脑必须重设一遍**（defaults 里只有 `replace-me`）。

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
| 钟面显示 1970 年 1~2 月某一天 | `ts` 被当 `uint32_t` 读了（13 位毫秒绕回 49.7 天以内） | 组件里走 `i64_or()`，见 §4「事件横幅」末尾那段 |
| 事件帧到了、ack 也回了，屏上没横幅 | 要么过期（`left_ms` 已是 0 之前就过期丢弃），要么 kind 不在表里 | 串口有「横幅：…」；不认识的 kind 会打 `ESP_LOGW` 并画 `EV_UNKNOWN` 那格 |
| 对话发出去没回音 | 屏申报的 `protocols` 里没有 2 | v1 连接收不到 dialog 帧，服务端是逐连接过滤的 |
| 重连后头几句问了没反应 | `turn_id` 用了纯自增 `s-0` | 冒充件必须 `s-<开机随机段>-<自增>`，否则撞上旧 id 被当重复吃掉 |

---

## 8. 还没做（不要在这里踩坑）

- **屏侧 UI**：五张卡（状态/卫星/对话/表情/钟）已经在真屏上画出来过，而且改成了「一个 hero + 两行 + 可展开明细」：
  展会上隔着两三米读的是 hero（数字 56px、中文词 32px），凑到跟前点卡面才看明细。
  SOS 的触摸上行已经通了（长按 1.5s → `command{sos_press}`）。接的那一侧从 #41 起只有**一处**：
  `core/runtime/DemoRuntime.kt` 的 `handleScreenCommand`，真机 App 和桌面彩排台共用它。
  以前两边各写一个 `when`，新加的三条上行正是这样在真机侧漏掉的 —— 别再往回改。
  **还没验的是观感**：字号、脸的弧线、留白这些只有眼睛能判，串口只能证明"没报错"。
  图片那张卡（契约里现在故意没有 `image`，通道还没定）还没做。
- **屏上的实体按键**：这块板没有用户按键（`BSP_CAPS_BUTTONS 0`），所以"实体按键没反应"不是故障，是没有。
  交互全在玻璃上，五条（映射表在 `protocol/screen-link.md`「这块玻璃上的手势」）：
  长按圆钮 = 呼救；左右滑 = `card_next`；单击 = `poke` + 展开/收起明细；上下滑 = 定开/定关明细；
  长按卡面 ≥0.8s = `card_auto`（把定卡权交回手机，滑过卡之后只有这一条能出来）。
  这四条上行的代码在 `app_main.c` 的 `swipe_cb`，**写完编过了但还没上板验** ——
  阈值 60px 是算出来的，不是手摸出来的，第一次上板要现场确认"滑多算滑"。
- **屏上画哪张卡**：决定在手机的 `core/screen/CardPolicy.kt`（呼救没走完 > 已越界 > 网没了且窗口在即 > 刚答过一句 > 守望动静 > 状态盘），
  每拍跟着 state 一起下发，固件不许自己挑。`DemoRuntime.setUi(UiState(卡,…))` 是那个"手动钉住"的入口，
  传 `null` 就交回策略；彩排脚本已经不再按步数换卡了（只有 `--card` 还钉）。
  **唯一的例外**：断链够 10s，屏本地落回钟面（`offline_clock_due()`），理由和边界写在契约里，
  别的地方新开这种口子就是两侧各讲一件事的开始。
  **还缺的是设置页上那个选择器本身** —— 入口有了，界面上没地方点。
- **电池读数（`app/main/axp2101.c`）**：代码写完、编过了，**一次都没在板子上跑过**。
  三件事只有接上电池才能定：
  ① `1.1mV/LSB` 这个折算系数 —— 厂商例程把同一个原始值直接当 mV 打印，两份说法冲突，
  拿万用表量 BAT 座两端和串口那条 `raw= / ≈mV` 对一次，对不上只改 `axp2101.c` 那一行；
  ② TS 引脚检测确实关掉了（BAT 座是 2 芯，没有 NTC，不关的话现象是"充不进电"，
  会一路查到硬件去）；③ `hello` 里那个 `battery_mv` 真进了帧、手机台账那行不再是「外接供电」。
  它还只是**建链时读一次**，断链重连才带新值 —— 中途换电池报不出去，要持续报得往 `state` 加字段，那是另一轮改契约。
- **提示音（`app/main/screen_audio.c`）**：同一批新代码，同样**编过了但没上板**。
  两个只有耳朵能判的点：① 音量 60（量程 0-100）和 12000 的幅值是拍的，展会现场太吵或太轻都改这两行；
  ② 采样率必须和 BSP 一致 —— `bsp_audio_codec_speaker_init()` 内部走 `bsp_audio_init(NULL)`，
  那份默认是 `BSP_I2S_DUPLEX_MONO_CFG(22050)`，**I2S 外设时钟建好之后改不了**，
  所以 `screen_audio.c` 里的 `AUDIO_RATE` 只能跟着 22050，写成 16000 的现象是"有声音但调子全跑偏"。
  功放是 BSP 在管的（`pa_pin = BSP_POWER_AMP_IO`，GPIO46），**不要自己拉那根脚**。
  这轮做的是合成正弦，不是任务里写的"播 SPIFFS 里的 wav"：先用零素材把 I2S→ES8311→功放验通，
  WAV 只是同一个 `esp_codec_dev_write()` 前面的取数环节，下一轮接。
- **I2S 的采样率是谁先初始化谁说了算 —— 加麦克风之前必须记住的顺序问题。**
  `bsp_audio_init()` 见到通道已建就直接返回，所以 `screen_audio.c` 现在把 I2S 钉在了 22050/16bit/单声道上。
  亲子陪伴那一路要的是 24kHz 上行录音，**如果**它先调 `bsp_audio_init(24k 那份配置)` 才能拿到 24k；
  顺序反过来（提示音任务先起来）就是录音跟着变成 22050，ASR 侧模型按 24k 解，出来的字是糊的，
  而且现象会先被当成网络问题去查。接 ES7210 那轮的第一件事是**把两个方向合到同一次 `bsp_audio_init(cfg)`**，
  而不是各起各的。
- **`send_cmd()` 在链路没通时静默丢弃**（另一台电脑 11-03 定位到的，代码在这份树里同样成立）：
  屏上滑了半天没反应，第一反应是手势没识别，实际是没连上而屏上不给出任何提示。
  这属于 #43 那一条（未接入时要给照着做的动作），改的时候别只加日志 —— 日志在屏上，观众看不见。
- **录音 → ASR**：小智那侧有现成的，接进来之前屏发的对话只能是文本冒充件。
- **现场配对**：`screen_id` 现在是编译期写死的，演示现场改不了就得重烧。要不要换一次性配对码，还没定。
- **第二块屏**：两块屏同时接入时，B 屏看不见 A 屏那句问话（只看"谁起的头"，不看连接）。今天一块屏，别顺手改。

### 第二批的图形路线：内部 RAM 不是判据（2026-11-03 从代码里量出来的，不用上板）

另一台电脑那份交接里写「GIF/矢量不能随手开：它们要的缓冲正是内部 RAM 那几十 KB」。**这条前提我在这台机器上核对过，结论是反的**，
而且不需要板子 —— 判据全在两份源码和 `sdkconfig` 里：

```
CONFIG_LV_USE_CLIB_MALLOC=y            # lv_malloc → lv_malloc_core → 裸 malloc（lv_mem_core_clib.c:64）
CONFIG_SPIRAM_USE_MALLOC=y
CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=256   # IDF 原文：小于这个数的 malloc 优先内部 RAM，大于的走外部 RAM
```

所以**任何超过 256 字节的分配都先落 PSRAM**，落不上才回退内部。`sdkconfig.defaults` 里那两条（第 34 行 `CLIB_MALLOC`、
第 96 行 `ALWAYSINTERNAL=256`）都写在跟踪文件外的同一份 defaults 里，第 62 行的注释其实已经点到过这件事 ——
但它当时讲的是字形缓存，没人在图形资产上称一遍。
而 `tiny_ttf` 那个教训之所以疼，是因为缓存节点是**小分配**（每个字形/每个 kerning 节点远小于 256B），
被这条规则死死摁在内部 RAM —— 也就是"调大 `CACHE_KERNING_CNT` 不行"的真正原因，跟总量无关，跟单块大小有关。
**接手时要盯的是：这两条在对方机器上必须也是这个值**，`sdkconfig` 不进仓库，手工设的东西两边会漂。

把 GIF 那条路逐块称了一遍（`managed_components/lvgl__lvgl/src/libs/gif/`）：

| 分配 | 代码位置 | 大小 | 落哪 |
|-|-|-|-|
| GIF 解码器状态（含 4KB FileBuf + 两份 768B 调色板 + 8KB LZW 表 + 8KB 像素表） | `lv_gif.c:29` 的 `lv_gif_t` 内嵌 `GIFIMAGE`，随 LVGL 对象一次性分配 | ≈ **24KB / 每个 gif 控件** | PSRAM（>256B） |
| 帧缓冲 | `lv_gif.c:260` `lv_malloc(w*h*(pixel_size_bytes+1))`，+1 是 8bit alpha 面 | 96×96 RGB888 = **36KB**；128×128 = 64KB | PSRAM |
| 解码器内部 | `AnimatedGIF/src/gif.c` 全文**没有一处 malloc**，`GIF_begin` 只是 memset 调用方给的结构体 | 0 | 不占 |

**所以 GIF 对内部 RAM 的增量是 0**，而内部 RAM 那 38.9KB 早就被显示流水定死了：一次 flush 46600B 分 6 次 + 每个翻转站 8192B 的 DMA 反弹缓冲 ——
**这些开不开图形都一样存在**。结论：路线别按"省内部 RAM"选（两边都不省），按下面三条选：

1. **flash 预算**：GIF 存的是编码字节，一段表情几 KB~几十 KB；预渲染 ARGB8888 是 `帧数 × w × h × 4`，96×96×10 帧就是 368KB。app 分区现在还剩 80%，两条都装得下，但预渲染的乘算会让"多加一张表情"变成 MB 级。
2. **CPU**：GIF 每帧解一次 LZW（写进 PSRAM 帧缓冲）；预渲染是纯 blit。Seeed 那份同级别面板的实测（SVG 7-9fps 优化到 30fps）说明这块屏的余量在 blit 那侧最舒服。
3. **迭代速度** —— 这一条才是真正的分水岭。乐鑫 `esp_emote_gfx` 那 23 个表情是 GIF 交付的，走 GIF 就是拿来用；走预渲染每次改表情都要重跑转换脚本、重烧。

**我的建议：两条其实可以同时走，因为它们共用同一个入口。**
`lv_gif.c:246` 那条分支是 `LV_IMAGE_SRC_VARIABLE → GIF_openRAM(img_dsc->data, data_size)` ——
把 GIF 文件当 **C 数组**喂进去，就**既不用开文件系统**（`CONFIG_LV_USE_FS_*` 现在全关，一条都没开，走文件那条还得先补 FS + SPIFFS 挂载），
**又拿到 GIF 的资产生态**。也就是"预渲染 C 数组"和"开 GIF 解码"不是二选一：
`CONFIG_LV_USE_GIF=y`（`Kconfig:1431`，**无任何依赖**）+ 表情以 `const uint8_t[]` 进 flash + `lv_gif_create()` 传 `lv_image_dsc_t`。

**这条路线唯一要付的代价**：`CONFIG_LV_GIF_CACHE_DECODE_DATA`（Kconfig:1434，"多花 16KB RAM 加速"）别开，
以及每个 gif 控件那 24KB 状态是**建控件时一次性拿的** —— 所以别给五张卡各放一个常驻 gif，
用一张卡一个、切走就 `lv_obj_delete`，让 PSRAM 回收。

**上板要验的就一件事**（我这台做不了）：`lv_gif_create` 之后打一条 `log_ram()`，
确认掉的是 PSRAM 而不是内部 RAM 那 38.9KB。如果内部 RAM 掉了 20KB，说明 `ALWAYSINTERNAL` 在真机上不是 256
（`sdkconfig` 不进仓库，那台机器是自己手工设的），那才是真的回到原前提。


---

## 9. 一句红线，两侧各守一次

屏上画出来之前要再过一次文案闸（`screen_link_redline.c`）。
**这不是重复劳动** —— 小智官方云那条路是它自己往屏上画的，手机的闸管不到，
而现场冒出一句"救援已在路上"是不可挽回的那种错。改词表要连着 App 侧的 Kotlin 表一起改，两边不能漂。

---

## 10. 两台电脑的树是分开的（2026-11-03 记账，接手前先读这一段）

现在有两台开发机在改同一个项目，而 `firmware/screen/app/` **整个目录从来没进过 git**（两边都是未跟踪状态）。
后果不是"没提交"这么轻：**`app_main.c` 没有 merge 机制**。它现在是两份不同的文件，谁存盘谁覆盖，没有第三份能把它们合起来。

两边各拿着对方没有的东西：

| 哪台 | 独有的改动 | 能不能验 |
|-|-|-|
| **拿板子那台**（COM9 + 圆屏 + 手机热点在这边） | UI 第一批五项（页码轨 `rail_new`/`rail_show`、`page_slide_in()` 切卡位移、`sos_new()` 靶环、顶栏 `s_badge`、去回声文案）；**`sdkconfig.defaults` 里那条 `CONFIG_LV_TINY_TTF_CACHE_KERNING_CNT=0`** | 已实拍，四项静态确认 |
| **另一台**（我） | `app/main/axp2101.c/.h`、`app/main/screen_audio.c/.h`、`screen_link` 的 `hello.battery_mv`、契约 v2 的 schema/md/漂移测试、`core` 的 `CardPolicy` | 只编过、只跑过 JVM 测试，**没上过板** |

**最锋利的一个坑：如果从"另一台"提交 `app/`，会把坏字修复丢掉。**
`CONFIG_LV_TINY_TTF_CACHE_KERNING_CNT=0` 压的是 LVGL 9.4 tiny_ttf 那个跨缓存 release bug
（`ttf_get_glyph_pair_kerning_width()` 从 `kerning_cache` 取、却拿 `glyph_cache` 放）。
丢了它的现象是"链路中断"的"路"被压扁、后面的字叠上来 —— 而那看起来**完全像字体文件坏了**，
于是会把 fontTools 查 advance 那条弯路重新走一遍（已经查过、已经否掉）。
所以规矩定死：**`app/` 由拿着板子的那台先提交**，因为只有它能证明屏幕上画对了；另一台只交增量。

另一台这轮增量怎么搬（`app_main.c` 故意**不给 diff** —— 两边基准不同，硬 diff 会在对方的版本上错位）：

1. **可 `git apply` 的部分**：`screen_link.{h,c}`、`screen_link_redline.c`、`protocol/screen-link.md`、
   `protocol/screen-link.schema.json`、`core` 的 `ScreenLink.kt` + `SchemaDriftTest.kt`、本文件、`README.md`。
   以 4952275 为基准打补丁，冲突就 `git apply -3`，别手搓。
2. **直接复制的新文件**（这两台机器都还没有，不存在冲突）：`app/main/axp2101.c`、`axp2101.h`、`screen_audio.c`、`screen_audio.h`。
3. **`app_main.c` 手工六处**，锚点在这份树里唯一，三分钟能扫完：
   - `#include "screen_shot.h"` 后面加 `#include "screen_audio.h"` 和 `#include "axp2101.h"`。
   - `on_event()` 末尾（那句 `event %s kind=%s 剩 %ums` 的 `ESP_LOGI` 之后）加 `screen_audio_tone(880, 120);`。
     **不要**在 `on_event` 里等播完 —— 写 I2S 是按实时消耗的，链路任务等不起。
   - `ui_tick()` 里那条 10s 一次的 `log_ui(&v)` 分支里加电量打印（`axp2101_battery_present()` 那个 if）。
     挂在 10s 而不是 250ms 是为了量底座放电时能画出曲线，同时不把串口刷满。
   - `app_main()` 之前加 `static int battery_mv_for_hello(void)`，然后：`display_start()` 之后、`wifi_start()` 之前
     依次调 `axp2101_start()` 和 `screen_audio_start()`，两者失败都只 `ESP_LOGW` 不带崩（没装电池的板子照样要能亮）。
     **顺序有硬约束**：AXP2101 走 BSP 那条 I2C（GPIO14/15），而总线是 `display_start()` 里才建起来的。
   - `screen_link_config_t` 里加一行 `.read_battery_mv = battery_mv_for_hello`。
   - **`swipe_cb()` 里两处日志**（这一处不是功能，是给 #42 装的眼睛，见上一节末）：
     `LV_EVENT_PRESSED` 分支里 `s_press_live = true` 之后打 `按下卡面 (%d,%d) 当前卡=%s`；
     算完 `dx`/`dy` 之后、进那串 if 之前，无条件打 `抬手 dx=%d dy=%d held=%ums → 判为%s（阈值 %dpx）`。
     没到阈值的触摸原来一条都不留，所以"滑了但没切"和"根本没滑"在日志里长得一模一样。
4. **`app/main/CMakeLists.txt`**：`SRCS` 加 `"axp2101.c" "screen_audio.c"`，
   `PRIV_REQUIRES` 加 `esp_http_server esp_driver_i2c`。这里一旦写了 `PRIV_REQUIRES`，IDF 就不再自动给公共依赖，
   漏一个是以 `fatal error: xxx.h: No such file or directory` 露头，不是以链接错误。
5. **三个新脚本**（`firmware/screen/tools/`，都是新文件、不可能冲突）：`gesture_log.py`（抓串口 + 把 dx/dy 变成阈值建议）、
   `set_link.py`（改 `sdkconfig` 那四条现场值）、`firewall-allow-8443.cmd`（管理员跑一次的入站放行）。

**顺手纠正交接文档里一条说法**：`send_cmd()` 不是"静默丢弃" —— 链路没通时它打的是
`链路没连上，command{card_next} 不发`（`app_main.c:889`，WARN 级）。**静默的是屏幕，不是串口**：
观众看不见，所以 #43（未接入时屏上要给照着做的动作）该做，但"240 秒串口零输出"不能用这条解释。
那一轮真正说得通的原因只剩两个：**要么确实没滑，要么触摸压根没进回调** —— 上面那两处日志就是用来把这两个
可能性分开的（按下有条、抬手没条 = 触摸流断在抬起之前 = 驱动问题；两条都没有 = 事件没挂到那一层）。

**两边都没解的**：`SWIPE_MIN_PX 60` 是算出来的、没实测过（约 5.7mm @266dpi）。
换它只需要一次真滑动 + 一条串口抓取，谁的板子在谁那儿跑：
`<IDF venv python> tools/gesture_log.py capture --port COM9 --seconds 45 --out D:/dev/tmp/serial-raw.bin`
（**只写原始字节**，Windows 控制台会把 UTF-8 中文重编码成 GBK，管道给 grep 就静默零输出——上一轮就是这么"什么都没抓到"的），
然后在屏上左右滑、上滑下滑、按、长按各来几下；抓完 `... gesture_log.py report --raw 同一个文件` 出表。

## 11. 手机端给屏侧的活（2026-11-04，`838615e` 之后照做即可，不用再讨论分权）

分权已经定死并进了 `DECISIONS D10` + `protocol/screen-link.md`「页面归屏、内容归手机」：
**哪一页归屏（触摸直接切，不等手机往返），这一页里说什么归手机（还是 1Hz 那帧的 `ui.card`/`ui.emotion`）**。
下面四步按顺序做，每步都给了"过了才算过"的判据。

**第 0 步：半开链路看门狗，排在所有重构前面。** 这条是你发现的，我认：`state` 之外的帧不算数、
把看门狗饿住，就是"按了没反应"的真因。它比页面树重要，因为它是现场最容易演砸的那件事。

### 1. `page_report`：翻页处加一行，建链后补一次

组件那道闸（`screen_link.c` 的 `ALLOWED`）**我这边已经放行 `page_report`**，固件不用改闸，只要发。

```c
/* 页名表：和 core 的 ScreenPage 一字不差。SchemaDriftTest 会读这张表比对，
   名字定死 PAGE_NAMES —— 改了名那条守卫会静默失效 */
static const char *const PAGE_NAMES[] = { "face", "picker", "vehicle", "kids", "avatar", "clock", "gallery" };

/* 翻页处（page_index() 变了那一刻）：通报，不是申请。手机不许驳回，也不该被你去等回执 */
cJSON *a = cJSON_CreateObject();
cJSON_AddStringToObject(a, "page", PAGE_NAMES[idx]);
screen_link_send_command("page_report", a);   /* args 所有权被组件吃掉，不要重复 delete */
```

**`hello` 发出去之后必须补报一次当前页。** 少了这一条，重连之后手机上那一格会一直空着
（写的是「屏没报它在哪一页」），直到下一次真的翻页才复明 —— 那不是 bug，但看起来像 bug。

三条不要：

- **不要报 L3**。手机上只有场景这一层，报得越深，两侧各自说话的机会越多。
- **不要复用 `set_demo_mode` 报场景**。那个名字在手机上已经是 Tab4 那个「演示模式」开关，
  而且它在 `handleScreenCommand` 里没有分支。我这边加了断言，契约里出现 `page_set` 也会被拦。
- **不要按 `state.ui.page` 切页**。那个字段是**回声** —— 手机把你报的原样写回来，
  用途只有两个：让 App 那块镜像说真话，和让你能看出两侧不一致。按它切页就把刚拿到的翻页权又交回去了。

**判据**：手机首页「圆屏」那张卡下面那行，从「屏没报它在哪一页」变成「屏上现在 · 亲子」。
不接真手机也能验：`node fake-phone.mjs` 那侧能看到 `command{cmd:"page_report",args:{page:...}}`。

### 2. `event` 那层的全屏抢占（这是唯一让服务端动屏的一档）

`SOS 确认 / 平台回执 / 越界` 三类要能盖住屏当前所在的任何一页。手机端那几条 `EventKind`
（`sos_acked` / `sos_failed` / `fence_exit` …）**早就在发了**，缺的是屏侧 `on_event` 没有接线 ——
所以现在这一层**不存在**，文档和对外话术里都别按"已实现"讲。

做的时候守两条：

- 抢占只给这三类。别的（过境窗口、守望动静、答句）都走 `ui.card`，别在屏上加第四种自己盖自己的东西。
- 抢占层**不吞 ack**。契约规定 `event` 必须回 `ack`（带 `for_event_id`），否则服务端 3s 重发、再 3s 放弃并记一条「展示端没收到」。

### 3. `dialog.action`：只认下行、只切页、别的什么都不做

```
家长在屏上说"给孩子放首歌" → dialog{dir:up, source:muse_cloud}
  → 手机答题 → dialog{dir:down, text:"…", action:"kids"} → 你切页，再回声一条 page_report
```

- **只挂在 `dir=down` 上**。挂在上行会被手机剥掉（屏不能给自己下命令）。
- **只做换页这一件事**：不是技能口、不是 SOS 口。未知页名忽略 + 打一行，别兜底切到某一页。
- 对话面不重发，所以丢了这一条 = 屏停在原页、手机记录仍在。**不要为它加 ack**。
- **手机端的生产者已经有一半了**（2026-11-04 之后）：技能表里新加了一条 `kids.recap`（「娃在屏上说了啥」），
  它读的就是手机那份对话记录里屏说上来的原话。这条**不跳页**，但它证明了 `page_report` 有用：
  你在亲子页说的那句话，会出现在手机「家人」页顶上那一串里；在车载页说的会进「车辆」页。
  跳页那一半还是要靠技能表里出现一条真的落在某一页的技能，别为了演示手搓一个"永远跳 kids"的假映射 ——
  那种东西会被当成真的通了。

### 4. 字号 24→28~32 与子集重生成

认。改完**必须重跑** `tools/muse_font_subset.py`，否则新字在 `carpet_font.ttf` 里没有，
现象是那几个字在屏上是空白 —— 而 tiny_ttf 不报错。`ui=<name>` 那个控制台钩子正好能用来逐页拍一遍。

### 我这边同步会做的事（不用你等）

App 侧继续推：~~场景页各自独立、对话时间线一份~~（**这两条 2026-11-04 已经落地**，
见 `docs/DECISIONS.md` D11；车辆页和家人页各自一根就地发问条，记录仍只有一份）、
圆屏连接收成常驻一条 + 「我的」里配对、
卫星那八张卡收成一条链路状态 + 明细。契约再改我会走同一个口子（`protocol/` 两份文件 + `ScreenLink.kt` +
`SchemaDriftTest`），你 `git pull` 就能拿到，不需要对齐会议。

**你做完第 1 步之后现场这样验归页**（不用手机，彩排件就够）：`simulator/screen.html` 顶上那个页选择器
点到「亲子」，随便说一句；手机「家人」页那串里要有它，「车辆」页里不能有，首页两条都要有。
如果手机家人页看不见 —— 先查你有没有在 `hello` 之后补报，再查页名拼的是不是契约里那七个之一。

---

## 追加（2026-11-05）：车况养护推荐，屏侧那一半（M3）

规格在 **`docs/MAINTENANCE.md`**，先读它的 §8.2 和 §9，别的都可以跳过。
这一条**现在不用动手** —— 我在 M0/M1，契约那一档等你那边的 `hello.protocols` 申报 v3 才生效。

要做的就一件事：`UiCard` 多一档 `maint`，屏上画**一行字**，脸跟着切。

- **只画一行，不画判据。** 1.75" 装不下"读数 + 判据"两行，而"为什么"是手机的事。
  行如：`左后胎压偏低`。不要加数字，数字在这块屏上会被读成仪表。
- **脸**：`due` → `worried`，`soon` → `worried`，`note` → `neutral`。就这两档，别自己发明。
- **屏不许自己算推荐，也不许自己挑哪一条。** 内容仍由手机 `CardPolicy` 定，和现有那一套一致。
  屏上出现一条手机没推过的建议，这一版的产品主张当场就没了。
- **老固件收不到 `maint`，这是设计不是 bug。** 手机端会按 `hello.protocols` 过滤：
  没申报的按 v1 处理，永远只发它画得出来的档。所以你**必须先申报 v3** 才收得到，
  报不上去就是"我这边没反应"，先查 `protocols` 再查卡策略。
- 未知枚举值的容错照旧：收到不认识的一律退回 `status`，**不要崩、不要黑屏**。

M2 那一半（工单编码 `tag=0x03` 上行 + 彩排台解码视图）不在你这台机器上，
但如果第三台机器接手，`docs/MAINTENANCE.md` §6.2 那张 21 字节的表就是它的接口文档，别再另写一份。
