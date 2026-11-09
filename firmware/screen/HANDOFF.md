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

**当前联调拓扑（2026-11-05 傍晚起）**：圆屏和这台电脑都在 `aa123`（2.4G，通道 11）上，
屏 `10.50.174.29` → 服务端 `ws://10.50.174.165:8443`；屏的 Wi-Fi 走的是 Muse 那个开发覆盖
`CONFIG_HOMEHUB_WIFI_SSID`（**不写 NVS，会盖掉手机 App 的 8 位码配网**），密码只在本机
gitignored 的 build sdkconfig 里。手机热点那条网已经不参与了，要回手机网络就按 §9.7 改回空 SSID 重刷。

**第 0 步：半开链路看门狗，排在所有重构前面。** 这条是你发现的，我认：`state` 之外的帧不算数、
把看门狗饿住，就是"按了没反应"的真因。它比页面树重要，因为它是现场最容易演砸的那件事。

> ✅ **屏侧做完（2026-11-04 晚，build #12 上板实测）**，判据与复现命令在
> [`muse/README.md` §9.3](muse/README.md)。一句要更正你那段结论的：**饿死只是三个洞里的一个**，
> 而且不是最要命的那个。另外两条 ——「连上了但一帧 `state` 都没收到」时 `gap_s()` 返回 0、
> 于是这条死法永远不会超时；以及对端正常下线会被记成一次"看门狗踢了"（我第一版交上去的
> `wd=1` 就是这么来的，那条证据我已经收回）。修法都在 `link_task` 里，**契约一行没动**，
> 你那边不需要跟着改。
> 还没复现的一条我如实留着：565 秒那次若服务端连 ack 都不发，旧代码本该踢，
> 现场多半叠了 TCP 半开。下次再演砸，回读 `>carpet` 的 `wd=` 就能一次分清三种死法。

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

> ✅ **屏侧代码做完了（2026-11-04 深夜，build #14 上板）。"报出去了"这一半在 2026-11-05 傍晚拿到证据了**
> —— 三次翻页（`face` / `kids` / `vehicle`）在屏侧 `>carpet` 和彩排台日志两侧同时对上，
> **第 1 步按实测算过了**；读数、复现命令和三轮各卡在哪一层的原因在
> [`muse/README.md` §9.7](muse/README.md)。三件要你点头/知道的：
>
> 1. **判据里那条 `fake-phone.mjs` 用不了**。看它的源码：`command` 帧没有任何分支，
>    一路落到 `不认识的帧类型： command`（`fake-phone.mjs:215`），**页名根本打不出来**。
>    要用彩排台 —— `DemoDriver` 会打 `cmd      page_report  args={"page":"kids"}`，
>    而它跑的是真 `ScreenLinkServer` + 真 CardPolicy，归页那半也在那边。
> 2. **屏里烧的就是 PC 的地址**（`ws://192.168.43.161:8443`，我从 build #14 的二进制里把那个
>    `ws://…` 字符串抓出来认的；`devices/sdkconfig.carpet` 里那个手机地址没进这版）。
>    彩排台在 `0.0.0.0:8443` 听着，这台机器却没有任何放行 8443 的入站规则 —— 所以就差**一次管理员**
>    （跑 `../tools/firewall-allow-8443.cmd`，不用改 URL、不用重编重刷）。
>    在放行之前的读数只有 `>carpet` 的 `nav=kids reported=0` —— **翻页通了，上报还没上线**，
>    别按"第 1 步已过判据"讲。
>
>    **2026-11-05 更正，上面这段只对了后半。** 前一天"就差防火墙"是我把串口里那 40 秒静默
>    当成了"活着在撞墙"：真凶是 `E websocket_client: Error create websocket task` —— 内部 RAM
>    最大连续块只剩 4K，撑不起我们给的 8192 栈，而 `start()` 的返回值没人看，任务没建起来就是
>    **永久躺平**，防火墙那条规则根本还没轮到。三处修复在 `components/screen_link/screen_link.c`
>    （栈 4096 / 攒帧 buffer 挪 PSRAM / 失败走退避阶梯），完整记在
>    [`muse/README.md` §9.5](muse/README.md)。**对端机器要一并取那份 `screen_link.c` 并重烧**，
>    只跑防火墙脚本是没用的。改完再称，剩下的确实才是超时（包被静默丢）⇒ 入站规则那一条仍然要跑，
>    而且那份 .cmd 前一天是 LF 结尾、cmd.exe 括号块跑偏，静默什么都没做；现在是 CRLF，
>    每次运行往 `D:\dev\tmp\fw-run.log` 记一行，跑没跑成有据可查。
>    判据（两条一起看）：彩排台 `cmd page_report args={"page":"kids"}` + 屏 `>carpet` 的 `reported=1`。
> 3. **信息架构按产品那侧的新方案改了**：车联格里是「场景选择 → 场景页 → 明细」三层，
>    开机默认还是 Muse 那张脸（`face` 这一页名就是这么来的，它报的是"露脸那一格"）。
>    明细页和设置格都不报页，符合你那三条不要；`set_demo_mode` 没被复用，契约里也没有 `page_set`。
>    **11-05 补一句**：第 1 层的形状从五行列表换成了宫格（一行三个，车载/亲子/数字分身/时钟/图片播放）。
>    换的是画法，不是页名 —— `picker` 这一格报上来的还是 `picker`，你那边不用改。
>
> 第 3 步屏侧那半（`dialog.action`）同时接上了：只认 `dir=down`，只切页，
> 未知页名只打一行 `unknown action page: <名>`、不兜底、不回 ack。
> 串口可以直接 `>page kids` 驱动翻页，等价于收到一条 action（用它做上面的实验，不用手指）。
> 第 4 步的字号按你说的走了一档：**28 / 22**（不是 24→32；32 在 300 px 宽那一列里放不下五个条目，
> 算式写在 `carpet_page.c` 开头），字库已按新文案重生成（123,804 → 154,528 字节）。
> 宫格那轮又加了一档 **30 px**，但只用于每格那颗字（取场景名首字，纯视觉锚点，不承担读），
> 正文还是 28/22。三档都要一份字库，`tools/muse_font_subset.py` 会自己扫源码，改文案就重跑。
> `PAGE_NAMES` 现在真的被 `SchemaDriftTest` 比着了 —— 以前那张表不存在，那条断言是静不比的。

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

> **2026-11-05 更新（手机侧 1.13.0 已经把 M0/M1 做完了）**：这一条现在**只差你那一行**，
> 手机端能给的输入形状已经定死了，不用再等我：
>  - 一帧里给屏的是 `Suggestion.headline` **那一句**，不带数字、不带判据。六条规则的全文是：
>    `补气到 2.5 bar` / `做常规保养` / `换空调滤芯` / `每周开十分钟` / `加卫星额度包` / `车上配一个应急包`，
>    最长 9 个字。另有更短的一张**索引码对照表** `MaintCodes.names`（`core/maint/WorkOrderCodec.kt`，
>    1..6：补气到标定胎压 / 做常规保养 / 换空调滤芯 / 长期停放，一周开一次 / 加卫星额度包 / 配车载应急包），
>    那是工单 21 字节里 6 bit 用的那份，**和 headline 同一条规则、两份措辞** —— 你要用短的就得引这张表，
>    别在屏上自己改写句子：屏上出现一条手机没写过的措辞，"每句话都有出处"这条主张当场就没了。
>  - `Severity` 就是三档 `Due/Soon/Note`（`到点/临期/知会`），对应你说的 `worried/worried/neutral`，不会有第四档。
>  - 一条建议有没有"桩"，手机侧是**读数自己说**的（`Reading.source`），所以屏上不需要标"示意"，
>    那条标只在手机上出现；屏上一律当"车主该做的事"画。
>
> **`UiCard.maint` 我没加**，故意的：`enum class UiCard`（`:core` `protocol/ScreenLink.kt`）
> 一旦多这一档，手机就可能发出去，而你那侧的"收到不认识的档退回 `status`"还没落地。
> 加枚举、报 `protocols` v3、容错那三条要落在**同一个改动**里才安全，所以整档留给你 ——
> 我这边 `CardPolicy` 会先按 `hello.protocols` 过滤，你没申报 v3 就一条都发不过去。

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

---

## 追加（2026-11-06）：屏侧对 M3 的回执 —— 三件已落地，两件要你定

> **合并提示，先看这一条**：上面那段「追加（2026-11-05）：车况养护推荐，屏侧那一半（M3）」只在
> `origin/main` 上（本地 main 还没有），这一段和它**追加在同一个位置**，`git merge` 在这儿必冲突。
> 保留顺序：先 11-05 你那条，再这一条，谁都不算"旧版"。
> **2026-11-07 这条已经执行**（rebase 到 `9320664`）：11-05 段在前、11-06 段在后，两段原文一个字没删；
> 下面那句"本地 main 还没有"从这次之后不再成立，`docs/MAINTENANCE.md` 那三份文档已经在树上。
> 同样只存在于 `origin/main` 的还有 `docs/MAINTENANCE.md`、`docs/VEHICLE-DATA.md`、`docs/AVATAR.md` ——
> 我这轮的 `protocol/screen-link.md` 引用了 `docs/MAINTENANCE.md` §8.2/#4，**那份文档没合进来之前，
> 我这边的注释是悬空引用**（本地 `ls docs/` 看不见它，我全程是 `git show origin/main:docs/MAINTENANCE.md` 读的）。

### 1. M3 那三件，屏侧现在真的有了

| M3 要求 | 落地成什么 | 出处（本机已称过） |
| --- | --- | --- |
| 屏申报 `hello.protocols` **v3** | 报 `[1, 3]`。以前"省略 protocols = 按 v1 待"那条默认值还在，但我们这版不再省略 | `components/screen_link/screen_link.c:168`、`include/screen_link.h:37`（`SCREEN_LINK_CONTRACT_MAX 3`） |
| `UiCard` 多一档 `maint` | 枚举有 `maint`；固件查表画「养车卡」，串口 `>carpet` 回读 `card=养车卡`；表里查不到的值落「未知」，不崩不黑屏 | `core/.../protocol/ScreenLink.kt:161`、`muse-carpet.patch` 里那张 `UI_CARD` 表 |
| 表情联动 | **没做，且做法要你定** —— 见下面第 3 小节的 (B)，Muse 底座的脸没有"担心"这一档 | — |
| 判据 #4「`protocols = null` 的连接永远收不到 `maint`」 | 服务端按连接版本把 `maint` 降回 `status` 再发 | `core/.../protocol/ScreenLink.kt:56`、`core/.../screen/ScreenLinkServer.kt:177`、钉住的测试 `core/.../screen/CardPolicyTest.kt:208` |

判据 #4 这条我要多说一句，因为它和你 §8.2 最后那条写的不是同一处：**容错我做在了服务端，不是固件里**。
两个理由，都不是省事：

1. `maint` 不是一种新帧，它骑在 v1 就有的 `state` 里。服务端那道逐连接的闸门（`deliverable`）按**帧类型**过滤，
   根本过滤不到一个枚举值 —— 这条最容易写成"我以为加了 v3 闸门就安全了"。
2. 你写的那句"屏侧收到不认识的档退回 `status`"，救不到**已经发出去的那些固件**。而"手机换了卡、屏上一根横杠"
   在现场永远被读成链路坏了。在服务端降，则是任何 v1/v2 的屏都收不到 `maint`，包括我这台没升级的备用板。

两道现在都留着：服务端降完之后，固件那张查表仍对未知值落「未知」。降级挂在 `sendOrDrop` 这**一个**出口上，
不是挂在 `broadcast` —— 出站有两条路（`broadcast` 每条连接、`sendTo` 单发），判据挂在调用点上早晚漏一条。

顺带一件你那边需要知道的：**契约 v3 现在装的是三件事，不止 `maint`** ——
还有 `hello.screen`（面板几何：`{w,h,round,touch,ppi}`，填 0 等于整项不申报）和答句的语音通道
`command{cmd:"audio_chunk"}`（PC 做 TTS，声音推到屏，屏用真能量驱动口型）。都写在
`protocol/screen-link.md` 的 v3 那一节，`SchemaDriftTest` 逐条钉住。看护今天的数：`:core` **281 条全绿**、
`:app` 单测与 `testDebugUnitTest` 过、`:simulator` 编得过。

**还有一条你已经改过的东西我这边动了，rebase 前先看**：`ProtocolTest.kt` 的
`unknown contract version is rejected` 和 `ScreenLinkInboundWireTest.kt` 的 `契约版本不认识时必须拒绝`
原来把探针**写死成 `v:3`** —— v3 一上线，这两条就变成"解一个我们支持的版本、然后期望它炸"，
绿的时候最危险。现在探针是 `ScreenCodec.CONTRACT_VERSION + 1`，并加了一条"窗口内 1..CONTRACT_VERSION 全收得下"。
这是尺子错了，不是被测的东西错了。

### 2. 一句实话：现在没有任何地方会发出 `maint`

你说「`UiCard.maint` 我没加，故意的」，那档枚举我这轮加了；但**生产它的那一支还没有**：
`CardPolicy.decide` 里没有 maint 分支，`git grep UiCard.maint` 在本地 main 和 `origin/main` 上都只有枚举定义、
schema、固件查表和测试这几处（`AppRuntime.kt:73`、`SceneCards.kt:137` 那两个 `maint` 是 `MaintStore` 的局部变量，不是卡）。
所以现在的状态是「屏收得到，但没人发」。**发不发、什么时候发、和 `dialog` 抢不抢同一格屏，是 `CardPolicy` 的活，
在你那边**（定内容的权归手机这条我不越）。我这轮只保证：你一发出来，v3 的屏画得对，v1/v2 的屏不会一根横杠。

### 3. 两件屏自己定不了的 —— 2026-11-06 已拍板，两件的落点都写在这

**(A) `Suggestion.headline` 那句到不了屏上 —— 拍板：骑现有 `dialog` 帧，不加新字段。**

结论（他的原话是「屏上就直接用对话中带的文字就行，手机和未来搞服务器的时候做好信息归类存储」）：
**`UiState` 不加 `line`，契约不动**，那句养护话发成一帧现成的 `dialog`：

```
dialog{dir:"down", text:"胎压该补到 2.5 bar 了…", skill:"maint", turn_id:"t-hb-maint-<新 id>"}
```

屏那边**零改动**就画得对：`carpet_link.c` 收到 down 就交给 `face_offer()`，
首屏那条带当场念、分页、翻页跟着播放位置走 —— 和答句完全同一条路，没有第二套排版。
所以**你那边只剩一件事**：MaintEngine 算出 due/soon 的那一句，从 `Suggestion` 送到 dialog 帧里
（`Conversation.record()` 已经把 `skill` 存进台账，归类靠这个字段，不靠句子措辞）。

我这边已经钉住的（`core` 单测，`DialogBridgeTest` 那条
「养护提醒那句骑的就是对话帧 归类靠 skill 不靠新字段」）：
帧是 `dir=down`、`skill="maint"`、文本原样；台账里 `Turn.skill="maint"` 且 `proactive=true`；
**它不会混进「娃在屏上说了啥」那几句**（那一筛只认屏起头的问句）。

这一条当初摆过三条路（加 `UiState.line` / 借 dialog 帧 / 屏自己查码表），拍的是第二条。
留一句副作用免得下次又当成新问题：借 dialog 帧确实会进对话台账，而这正是我们要的 ——
车主该看见屏主动说过什么；要防的只是把它误当成"有人问过一句"，那一条由上面那个单测守着。
第三条（屏按 `MaintCodes.names` 自己查句子）不管什么时候都别做：那是"屏不许自己算"那条红线。

**(B) 「脸切 `worried`」—— 拍板：答句赢，加一枚琥珀点，两边都要。**

`worried` 不是"照 §5.4 那张表加一行"就完事：它和答句抢的是同一个 `mode` 字段，
而**语音在播时把脸换走，嘴立刻不动**（口型是这一轮的主功能）。按拍板的优先序实现完了
（**还没上板，等一次刷机许可**），三条让路 + 一个记号写进 `muse/README.md` §5.4.1，
这里只复述判据：

1. `maint` + `worried` → `MUSE_MODE_THINKING`（不给 `ERROR`，那是报警脸，观众读成"屏坏了"）；
   `note` 那档手机发的是中性，天然不抢脸，不用额外分支。
2. 有句子挂在脸上（`s_face_live`）或当前 mode 不是 `IDLE`/`THINKING` 时**一律不写**；
   答句念完 hold 到期收回 idle，下一帧 `state`（一秒一帧）自然把"担心"补上。
3. 不担心了写回 `IDLE` —— 少了这一条脸会一直"想"下去，就是老任务 #34 那个坑在脸这侧重演。
4. 脸被答句占走时那句提醒还剩什么：场景页「对话」「情绪」两行行前一枚 12 px 琥珀点。
   串口多回一个 `maint_worried=0/1`，那是布尔记号本身，不是屏上的中文串。

**§8.2 / §9 那两行字不用改**：脸仍然算 M3 的判据之一，只是它让路给答句 —— 这不是"挪走"，
是把"什么时候演担心"说清楚了。上一条"挪走的话请同步改文档"的那个替代路作废。

### 4. 我这边可以接走的（不抢你的活，只是这台机器有）

M2 里那半句「**彩排台加一个工单解码视图**」住的就是这台机器（`simulator/`）。
`core/maint/WorkOrderCodec.kt` 合进来之后给我一句，我按 `docs/MAINTENANCE.md` §6.2 那张 21 字节的表接，
**不另写第二份接口文档**。判据我照你 §10 #3 来：用真 `MessageBudget.chunkBytes` 算，不写常数。

### 5. 还等你点头的一件事（不做就不算称过）

#68 上板验音频那半。屏这轮的固件已经编好、补丁重打验过（干净 `cdc169d` 上 apply 通、14 个文件全落对），
但**声音到屏这件事我只在 PC 侧称过**：Node 冒充一台 v3 屏连 8443，32 字的本地规则答句 → 233,740 B PCM
≈ 7.3 秒 → **92 块，seq 从 0 连续、各块解出来的字节数加起来正好等于 `total`、最大帧 3570 B（预算 3800）**。
真屏那一半要**一次刷机许可**（每个 session 单独点那条）。点头之后判据是串口这三行：
`>audtest 3` 之后 `@audio at …/…` 每秒前进、`level` 跟着音节变、**`drops` 的增量为 0**。
最后那条 11-07 改了写法，原因一句话说清：**`drops` 是开机以来的累计数（`carpet_audio.c:90-95`
在锁里 `++`，只有复位归零），一块开了几小时的板子上它必然不是 0** —— 判据写成"等于 0"
永远过不了，而过不了时人容易去怀疑链路，其实那只是历史。要么重启后称，要么称一段前后各读一次比差值。
`drops` 增量为 0 说的还是那件事：非 0 就说明这条 ws 的入站吞吐扛不住 22 KB/s，那个数我**没实测过**，
`protocol/screen-link.md` 里写的也是"第一次跑要盯这一行"，不是"已称过"。

---

## 追加（2026-11-07）：上板称到的那一半，和还差的那一半

板子还是 11-06 那版固件，**没重刷、没重启**（串口 uptime `t≈30,200` 秒）。先证它确实是这一版：
`link.heartbeat` 之外还打着本轮新加的那句 `screen_link: ws 断开/未接通，下一次按退避阶梯再来`，
且 `>carpet` 回读里已经有 `maint_worried=` 这个字段。

**称到的（`>audtest 3`，本机造音，不依赖网络）：**

```
@audio at 32000/96000 level 0.95
@audio at 64000/96000 level 0.95
@audio at 96000/96000 level 0.00
@audtest 发了 38 块 / 96000 字节，喂了 3.56 秒（音频 3.0 秒）
```

所以 §5 那三条判据里，**前两条成立**：位置一行行往前走、`level` 跟着音节（有音 0.95、静音 0.00）。
2 MB 那块也是真拿到了 —— `buf_get()` 是懒的，拿不到会在第一块就回 `@audtest 第 1 块被拒`，而 38 块一块没被拒。
现场读数：`@audio cap=2097152 psram_free=1526604 psram_max=1507328 装得下 65.5 秒`。

**`drops=0` 这条当场不成立，但原因不是链路：** 回读是 `frames=509 drops=5698 last=seq 不连续`。
连着三次 `>carpet`（跨约 15 秒）这两个数一个字没动，刚那 38 块也没被拒 —— 它是这块板**开机 8.4 小时里攒的历史计数**。
拿它当"现在还在丢"读是读错了。**已拍板（11-07）**：这条判据改成"重启后跑 `>audtest`，`drops` 增量为 0"，
§5 和 `muse/README.md` §5.7 那两处同步改过了。

**没称到的，以及卡在哪：** `maint_worried=1`（当前回读 `card=卫星卡 emotion=兴奋 maint_worried=0`，
因为一条 `state` 都没到）、那枚琥珀点、真链路推一句答句的语音。两个现场原因：屏 `wifi=down`
（发 'w' 让它重连，25 秒内没有 `connected with`），而它固件里烧的是 `ws://10.50.174.165:8443`，
这台电脑现在在 aa123 上是 `10.50.174.166` —— 热点是开的，IP 换了。改 URL 要动 build 里的 `sdkconfig`
＋ `idf.py build` ＋ 一次刷机许可。

**两处和交接说明不一致：** ①「串口口子 `>maint`」11-07 之前**确实没有** —— 当时 `maint` 只在
`UI_CARD` 表（`carpet_link.c:224`）和 `frame_maint_worried`（`:251/:258/:285`）里，布尔记号是
`>carpet` 打的 `maint_worried=0/1`（`carpet_link.c:822`）。**现在已经按拍板补上了**，见下面第 3 小节。
② 串口是**先认单字符、再认 `>` 开头的行**（`muse_input.c:1044-1070`：`a`/`s` 菜单、`d`/`u` PTT、
`m` mp3 测试、`z`/`w` 睡/醒）—— 不带 `>` 直接发 `audtest 3` 会被拆成一串按键。这条不是新坑，
`muse/README.md` §9 开头就写着（"控制台有两种输入，写错一种就什么也收不到"），是我没先翻到。

### 1. 为什么换服务端地址绕不开刷机（他问的那条，答案是"不绕不开，但有一条不用刷机的路"）

先说结论：**烧录不是"连不上"的原因，"往哪台机器拨"才是**；而这条路不一定要刷机。三条核过的证据：

1. 我们的 URL 是**编译期常量**：`carpet_link.c:732` 整句只有 `.url = CONFIG_SCREEN_LINK_URL`；
   值在 `esp32/build-muse-s3-175-carpet/sdkconfig:4278 = "ws://10.50.174.165:8443"`
   （出厂那份是 `devices/sdkconfig.carpet:26 = ws://192.168.43.1:8443`）。
2. **全树没有运行期改 URL 的口**：`nvs_set_str` 只命中 Muse 自己的 `muse_settings.c:92` /
   `main/config_store.c:83`；`muse_input.c` 那一组串口指令只有 page/say/audio/audtest/maint/ui.settings=/face=/chat，
   没有一个碰 url。
3. Muse 自带的 wifi/配网（`muse_app.c`、`muse_ble.c`、`muse_settings*.c`）只能做三件事：连 AP、
   配它自己界面那些设置、从账号 API 拿 **Muse 云自己的** ws 地址（`muse_account_api.c:144` /
   `vm_api.c:321` 那个 `vm_ws_url`）。它没有一个格子让人填自建服务端地址 —— 而现在 wifi 本身是通的
   （板子在 `10.50.174.34`，ping 得着），卡的从来不是凭据。

于是两条路摆在这儿：

- **不刷机那条**：给这台电脑的 WLAN 加一个二级地址 `10.50.174.165`（`netsh ... add address`，要管理员），
  彩排台在 8443 一listen，屏按它自己那条退避阶梯就接上了。**这条要动本机网络配置，他没点头，我没执行。**
- **刷机那条**：改 `build-muse-s3-175-carpet/sdkconfig` 里那行 → `idf.py build` → `app-flash`。

### 2. 推送其实走的是 github.com，不是 origin 那个代理地址

`origin` = `https://gh-proxy.com/https://github.com/...`，这个代理**转发不了写认证**：凭据存好之后
`push origin main` 仍回 `remote: No anonymous write access.`（而它读是好的，`ls-remote`/`fetch` 都正常）。
实际落远端用的是同仓同分支的直连地址，一次 fast-forward，**没改任何配置**：

```bash
git -c http.proxy= -c https.proxy= push https://github.com/zyawei1991-hue/carpet-sat-demo.git main:main
```

之后 `git fetch origin` 把 `refs/remotes/origin/main` 拉到同一个 commit，`origin/main..HEAD` 为空。
凭据按 host 存了两份（`gh-proxy.com` 和 `github.com`），token 没进任何文件。

### 3. `>maint` 补上了，称到哪一步

`muse_input.c:950-958` 收 `>maint` / `>maint off` → `carpet_link.c:802` 的 `carpet_link_bench_maint()`
（声明在 `carpet_link.h:126`）。它造的是 `ui={card:"maint",emotion:"worried"|"neutral"}` 一帧，
**交的是链路收帧时同一条 `on_state()`**，所以称的是"这一帧到没到"，不是我另写的一句 if；
回读走 `carpet_link_state()`（页面那两枚点读的就是这个视图），打印
`@maint sent=? view.maint_worried=? card=? emotion=? face=? mode=?`。

**上板称过没有：没有。** 这一版编好了（日志 `D:\dev\esp\build-carpet.log` 里 `Project build complete`），
补丁重打成 5,079 行并在干净 `cdc169d` 上 `git apply --check` 过，但 `app-flash` 报
`Could not open COM8, the port is busy or doesn't exist`，那之后 **COM8 从设备列表里消失了**
（`[System.IO.Ports.SerialPort]::GetPortNames()` 只剩 COM4/5/6/7，隔 10 秒、22 秒各看一次都一样）——
板子不在这台机器上了。所以 §5.4.1 那两条判据（空屏 `>maint` → `maint_worried=1` 且 mode 1→3；
`>say` 挂着句子时 `>maint` → mode 必须还是 4）以及琥珀点本身，**一条都没称**，板子回来插上就能称：

```bash
<IDF venv python> ../tools/sercmd_norst.py COM8 ">maint" 0.3 4 out.txt
```

### 4. 旧屏那棵树还在这儿（备忘，不是待办）

`firmware/screen/app/` 11-07 这轮**没进库、也没删**。它里面不只有构建树 —— `main/app_main.c`、
`axp2101.c/h`、`screen_audio.c/h`、`screen_shot.c/h`、两个 `CMakeLists.txt`、`idf_component.yml`、
`Kconfig.projbuild`、`partitions.csv`、`sdkconfig.defaults`、`assets/make-font.py`、
`spiffs_image/carpet-noto.ttf`(2.1 MB) 都在；`build/`、`managed_components/`、`dependencies.lock`、
`sdkconfig` 已被 `.gitignore` 挡住。它是换 Muse 底座**之前**那条路线（`muse/README.md` §1），
要不要收进仓库另说，先留这一行免得下次又当成没处理干净。

## 追加（2026-11-08）：接入点地址做成运行期可设，Wi-Fi 优先级那一处倒过来了

他的原话是需求不是建议：**用手机连圆屏这件事要在 Muse 自带的那个设置页里做，不要每次改编译期常量重刷；
手机 App 上的操作越少越好。** 上一节那条「绕不开刷机」因此作废 —— 现在不绕了，把刷机那一格从路上拿掉。

### 0. 现场最少要做的事

主路（手机开热点，**屏上零输入**）：

1. 手机开热点，要 **2.4GHz**（S3 不认 5GHz）。
2. 屏：Settings → WI-FI → 扫网 → 点那台热点 → 填密码。NVS 里有这一条，编译期那条 `aa123` 就退成兜底（§1.5）。
3. 屏出厂拨的就是 `ws://192.168.43.1:8443`，也就是手机在自己热点里的那个地址 —— **那一格不用动**。
4. 手机 App：「配对」那张卡里把**「允许圆屏接入」**打开就够了；`WS175B-A3F7` 已经在白名单
   （`core/src/main/kotlin/com/carpet/satdemo/core/screen/ScreenLinkServer.kt:22` 和 `:206` 的
   `DEFAULT_SCREEN_ID`），不用手填编号。App 这边一个按钮都没多给。

退路（热点开了客户端隔离、`.1` 拨不通时，才需要一次输入）：

5. 看 App 那张卡「接入点」那一行显示的地址（`ws://<服务端在那张网里的IP>:8443`）。
6. 屏：Settings → WI-FI → **Access point** → 屏上键盘填那条 → Ready。屏**立刻重拨**，不用重启。
   接着电脑的时候走串口更快：`>url ws://…`（不带参数 = 回读，`>url clear` = 抹掉回到出厂那条）。

称它成没成，不看眼睛：`>url` 印 `now= / src= / saved= / linked= / dialed= / wifi= / ssid=`，
`>carpet` 多一行 `@carpet url=… src=nvs|factory`。来源必须报出来，否则「改了没生效」和
「生效了但对面没起服务」在现场是同一幅样子。

### 1. 动了哪几处（固件侧，全走 `muse/muse-carpet.patch` 那一条口）

1. **NVS 多一条 `link_url`**（`muse` 命名空间）：`muse_settings.h` 加 `MUSE_LINK_URL_MAX 63` +
   一对读写口，`muse_settings.c` 在 init 里 `load_str("link_url", …)`。空串走 `nvs_erase_key`
   而不是存一个空字符串 —— 这样「抹掉了，所以用出厂那条」和「存了一个空地址」是两回事。
   启动日志尾部多印 `, link <地址>|built-in`，开机一眼看见拨的是哪条。
   这两条没有 `#if CONFIG_MUSE_CARPET_CARDS` 包住：`muse_settings.h` 是 Muse 的公共头，
   把 `sdkconfig.h` 引进它会碰主机侧的 harness（`esp32/tests/muse_eink_wifi_harness.cpp`），
   代价是非车联构建里多 64 字节 .bss 和两个小函数。
2. **设置页那一格**：`muse_settings_ui.c` 的 Wi-Fi 页在「Other network...」和 MAC 之间加一行
   **Access point**（点它 → `open_text()`，用的就是 Muse 现成那套键盘，没造轮子），
   下面一行 `note` 印「完整地址 + 来源」—— `row()` 的值那一栏被 `max_width 130` 卡住，
   `ws://192.168.43.1:8443` 会被点成 `ws://192.16…`，而现场要读的正好是后面那截。
   填错（不以 `ws://`/`wss://` 起头、带空格、超 63 字节）时那行 note 变琥珀色写
   "Not an address"，5 秒后自己恢复。空 = 抹掉那一格。
   页面上那格是**英文**标签，不是懒得翻译：设置页整页用 `lv_font_montserrat_*`，没有汉字字形。
   图标那枚用 `LV_SYMBOL_CALL` 而不是 `LV_SYMBOL_LINK`：这棵树的 LVGL 里根本没有 `LINK` 这个符号
   （第一次编译就被 `error: 'LV_SYMBOL_LINK' undeclared` 拦下了），而这条地址对面那台机器就是手机，
   `CALL` 反而是最贴的那一枚。
3. **链路改成先读 NVS**：`carpet_link.c` 新增 `url_read()` / `url_effective()` / `link_open()` /
   `url_restart_task()` / `carpet_link_set_url()` / `carpet_link_url()` / `carpet_link_url_dump()`，`gate_task` 原来那段
   建 cfg 的搬进 `link_open()`。重连是 `screen_link_stop()`（发 bye，手机台账立刻离线）
   \+ `screen_link_start()`，所以退避阶梯从头开始；`s_link_mtx` 保证 stop/start 不重叠
   （`screen_link_stop()` 自己最长等 2 秒）。地址存在静态 `s_url` 里 ——
   `screen_link_cfg_t.url` 是**指针**，栈上那条会在 start 之后变成悬空读。
   `url_effective()` 是这一节里唯一被硬件教的一课，见 §3 最后那段。
4. **串口两个口**（`muse_input.c`）：`>url`、`>wifi clear`。为什么要给串口留一条：
   1.75 寸圆屏上用屏上键盘敲一个 IP 是罚人，接电脑的时候走串口、没电脑的时候才用那一格，
   两条写的是同一个 NVS key，不是两套状态。
5. **优先级倒置（这条改了 Muse 的设计，所以为什么和怎么退回都得写死）**：
   `main/app.c` 那句 "Menuconfig override takes priority" 换成 `use_override = ovr_ssid[0] && !have_nvs`；
   `main/muse_glue.c` 新增 `dev_override_now()` 并把 keeper 里两处 `!WIFI_OVERRIDE` 换成它。
   **两处都要改**：只改 `app.c` 的话开机能连上用户填的网络，但之后在设置页换网络时 keeper 永远不重连
   （那两处判断正是「编译期覆盖在，Wi-Fi 归 Link，别碰」）。
   那条编译期覆盖原来的用途还在（调试时不重刷就不必重新配对），只是从「压制」降成「兜底」。
   不重刷退回它：`>wifi clear`（→ `muse_settings_set_wifi("","")` → `wifi_known_forget_all()`
   把 NVS 的 ssid/password/列表一起抹，**密码不印**）+ 重启一次。
   退回这条改动本身：`app.c` 删掉 `&& !have_nvs`、`muse_glue.c` 两处换回 `!WIFI_OVERRIDE`。
6. **编译期回落改回出厂值**：本机 `build-muse-s3-175-carpet/sdkconfig` 里那条从
   `ws://10.50.174.165:8443` 改回 `ws://192.168.43.1:8443`（跟踪的 `devices/sdkconfig.carpet` 本来就是它）。
   改的是本机那一份，所以留了备份 `sdkconfig.bak-20261108`。
   为什么必须改回去：出厂值等于「手机热点里那个 `.1`」，这才是「操作越少越好」的正解 ——
   主路一格都不用填，而不是让屏去拨某台笔记本。

### 2. App 侧：这一轮**不动代码**，改成交付文档

上面那份提交里我确实改过 `HomeShell.kt:641` 那句「配对怎么做」。这一轮按新规矩**回退了**——
`apps/android/` 一个字都不由我改，改法写成文档交给另一个 agent 执行：
**`docs/APP-SCREEN-CONNECT.md`**，精确到文件:行、判据、验收，不写背景故事。

要改的那一句现在是（`HomeShell.kt:641`）：「手机这边放开接入、把圆屏编号加进白名单，再到圆屏上填上面那个接入点地址。」
——「再到圆屏上填」是换底座之前那块屏的能力，Muse 这块屏默认路径**不需要填任何东西**。
文档里给了直接替换的那一句，以及三条约束：除那一句之外 App 不加新按钮、不加新开关；白名单已经带着 `WS175B-A3F7`；长按复制是建议不是必做。
「允许圆屏接入」（`HomeShell.kt:577` / `DemoRuntime.kt:331`）就是全部。

### 3. 上板称到哪一步（2026-11-08 上午，板子在 COM8）

称的是这一版固件：`app-flash` → `Hash of data verified.`（`D:\dev\esp\flash-carpet.log`），
开机自报 `ELF file SHA256: 4f6f8b4ad...`、`ESP-IDF: v6.0.1-dirty`。
下面每一条都是串口原文，抓在 `D:\dev\esp\m1108\*.txt`（读法：`<IDF venv python> tools/sercmd_norst.py COM8 ">…" 0.3 4 文件`，
不带 `>` 的字符会被当成按键，所以每条都得带 `>`）。

**称到的**

1. 出厂值（§0 主路的前提）：`@url now=ws://192.168.43.1:8443 src=factory saved=(none) linked=0 dialed=0`，
   `>carpet` 那行也在：`@carpet url=ws://192.168.43.1:8443 src=factory`。
2. 填错被拒（那一格不是"存了再看运气"）：`>url 192.168.1.17:8443` → `@url set: ESP_ERR_INVALID_ARG`，
   紧跟的回读还是 `src=factory saved=(none)` —— 没写进去，也没把自己绕进去。
3. 改地址：`>url ws://192.168.1.17:8443` → `@url set: ESP_OK` → `now=ws://192.168.1.17:8443 src=nvs saved=ws://192.168.1.17:8443`。
4. **真重启之后还在**（这才叫持久化，软复位不算）：拉一次 RTS 让 EN 断电重启，开机日志里
   `I (1085) muse_settings: … muse no token, link ws://192.168.1.17:8443`，重启后再 `>url` 仍是 `src=nvs`。
5. 抹掉回到出厂：`>url clear` → `I (29638) muse_settings: link url: (built-in)` → `src=factory saved=(none)`。
   **板子离开时停在这一档**，和出厂一致。
6. Wi-Fi 那条优先级在硬件上真的倒过来了：`I (1923) link.app: saved network beats the built-in creds in NVS, reconnecting (first: 吨吨吨的Mate 30 5G)`。
   NVS 里存着这部手机早先在设置页填的热点名 —— 那一版填了是不生效的，这正是这次要修的洞；现在它作数了，`aa123` 退成兜底。
   顺带证明"密码不印"那条：同一个回读只有 `ssid=`，没有 `pass=`。
7. 上一轮欠的 `>maint` 三条（判据读的都是 `carpet_link_state()` 那个视图 —— 和页面上那两枚点、脸上那一个是同一个来源，不是我另写的一句 if）：
   - 空屏 `>maint` → `@maint sent=1 view.maint_worried=1 card=养车卡 emotion=担心 face=0 mode=3`；
     发之前基线是 `mode=1 maint_worried=0`，所以 **1→3（idle→thinking）称到了**。
   - 挂句子时：`>say`（255 字节那句）之后 `@carpet face=1 mode=4 at=255/255`，此时 `>maint` →
     `mode=4` 不变 —— **第一道让路称到了**（脸没被搬走）。
   - `>maint off` → `view.maint_worried=0 emotion=中性 mode=1` —— **第三道搬回 IDLE 称到了**（#34 那个"一直想下去"的坑没复现）。

**没称到的（卡在哪写清楚，别当过了）**

- 上面第 4 条当时只称到"地址还在"，**`linked=1` 那半是当天下午补称的**，见 §5。
- **WI-FI 页「接入点」那一行的长相没出图**：它在折叠区以下，要手指往上滑才露出来，
  而串口这边没有滚动旋钮（`ui.settings=` 只能选页，不能滚），所以那一行和它弹出的键盘页没截到。
  称到的是它背后那半条链：那一格写的就是 3~5 走过的那个 NVS key，读写同源；
  文案走的表和字体档和已出图的同类行完全同一条。

**一次刷机称出来的一件事，记在这儿别再踩**：第一版刷上去开机死循环
`assert failed: xQueueSemaphoreTake queue.c:1709 (( pxQueue ))`（`ELF dd4b43e42`），
根因是我在 `carpet_link_start()` 里提前 `url_read()` 了一次 —— 那时 `muse_settings_init()` 还没跑，
设置模块那把锁还是 NULL。改法不是"加个判空糊过去"，而是回读走 `url_effective()`：
一次都没拨出去过时，按同一条"先 NVS 再出厂"的规则现算。副产品是网还没起来的时候 `>url`/`>carpet` 也说真话
（上面第 3 条那次 `dialed=0` 却 `src=nvs`，靠的就是它）。

## 追加（2026-11-08 下午）：`linked=1` 补称到了，设置页整片中文化，靠截图抓出一处漏翻

### 5. `linked=1` —— 上午那条"没称到"补上了

补它的姿势不需要手机热点，也不需要重刷：**服务端就用彩排台**（`./gradlew :simulator:run --args="--hold 9"`，
`DemoDriver` 把 `ScreenLinkServer` 绑在 `InetSocketAddress(8443)`，通配地址）。
屏连的是手机热点 `Mate 60`（`192.168.43.13`），电脑在 `192.168.1.17`，两边隔着 NAT 但热点放行了这条入站——
所以上午那句"要屏和服务端在同一个网里"是**我当时判断错了条件**，实际拦路的是电脑自己没起服务端，不是网段。

`>url ws://192.168.1.17:8443` → 按一次 DTR/RTS 冷复位 → 重启后回读（串口原文）：

```
@carpet url=ws://192.168.1.17:8443 src=nvs
@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0
```

**"改地址 → 真重启 → 地址还在 **且真的拨通了**"这一整条现在称到了**，用的正是 §0 说的那条不用刷手机的路。
脚本 `firmware/screen/tools/url_persist.py`（一次会话里改地址、脉冲复位、等 15 秒、回读）。

### 6. 设置页中文化（`CONFIG_MUSE_UI_LANG_ZH`）

按"最干净的那条"落：不就地替换英文串，加一档 `MUSE_UI_LANG_ZH`（`depends on MUSE_CARPET_CARDS`），
把设置页文案接进 `muse_lang_menu()` —— 表查不到就原样返回英文，所以**选 EN 时天然等于上游**，
§2 那条 `CONFIG_MUSE_CARPET_CARDS=n ≡ 上游` 的等价性没被破坏；新增块全部圈在 `#if CONFIG_MUSE_CARPET_CARDS` 里。

三个坑都按预先称过的处理了：

1. **字体**：设置页原本不画任何中日韩——标题是 `lv_font_unscii_16`，正文 Montserrat 16/20/28。
   照 `muse_lang.c` 里越南语那份 `copies` 的做法给这几档挂 fallback，**标题那档 unscii 整体换成带 fallback 的一档**
   （它是点阵字，挂不上 CJK）。图里能用的汉字只有 16 px 那一档，所以定了一条：**中文一律走 16 px Noto，
   不追求和英文同字号**——20 px 那档没有汉字子集，为它重生成词表会把字库撑大，而 16 px 在这块 466×466 圆屏上够读。
2. **长串**：note 那一档 `SHOWN_MAX 96`，长尾注释重写成短句。
3. **宽度**：`LIST_W 330`、值那一档 `max_width 130` ⇒ 约 124 px ⇒ 20 px 英文约 12 个字母，而 Noto 16 px 的
   `adv_w=256` 正好 16.0 px/字 ⇒ **六个汉字**。所以每一条都数了字数，由 `tools/check_zh_table.py` 机器称：
   五项检查（表内中文按像素预算逐条量、符号串 `LV_SYMBOL_x` 先换成码位、注释里的引号不再被当字符串、
   经变量到达的键按最紧的 124 px 判、note 档折两行合计不超 660 px），现在 `entries: 159 … problems: 0`。

**验收不是只看编译过**：`tools/zh_shot.py` 一次串口会话里逐页 `>ui.settings=<page>` + `p` 出图，
home / wifi / muse / ble / sound / battery / sleep / power 八页 + 宫格页 + 车载页，人眼逐张看过
——没有豆腐块、没有被省略号截掉的条目。图在 `D:\dev\esp\shots3\`（八页）、`shots4\zh_avatar.png`（宫格）、
`shots8\zh_shot0.png`（车载页 + 琥珀点）。
设置主页那张要单独说一句：`shots3\zh_home.png` 抢跑了（截到的是脸页，不是设置主页），
补拍的在 **`shots9\zh_home.png`** —— 标题「设置」已经是中文（说明标题那档 unscii 真的整档换掉了），
六行 `Wi-Fi / Muse / 蓝牙 / 声音 / 熄屏 / 电量` 的值 `Mate 60 / 没设置 / 开 / 音量 100% / 2 分钟 / USB`
全中文、无豆腐块、无省略号。
**第一版截图就看出一处漏翻**：Muse 页第二行还是英文「Pair in the Muse app」——根因是 `h.detail` / `w.detail`
那两句整句没走表（它们是 `muse_chat.c` / `muse_glue.c` 写进状态里的句子，不是状态词）。
补进固件和表（6 条）之后重编、重刷、重截，那一行变成「在 Muse 应用里配对」。

**截图这一路的坑**：Windows 上开串口会脉冲 DTR/RTS 把芯片复位（`snap.py` 那句 termios HUPCL 只对 POSIX 有效），
所以每开一次口都称到的是"刚开机那半秒、页面还没填值"的图，看着像漏翻其实是抢跑。
`zh_shot.py` 的写法是：开一次口 → `rd(12, b"muse:")` 等它起来 → 每页发完命令等 3 秒再按 `p`。

**顺带纠正我之前对机主说错的一句**：我说过「抓屏通道要 IP（`CONFIG_SCREEN_SHOT_PORT=8444`）」，
这对 Muse 这棵树是**错的** —— 全树 grep 没有 `8444`、也没有 `SCREEN_SHOT_PORT`，那是
`firmware/screen/app/` 那块旧屏的 httpd 口（`main/screen_shot.c`）。Muse 侧的抓屏是**串口触发的
LVGL snapshot**：`muse_input.c` 的 `#if LV_USE_SNAPSHOT` 那一段，按键 `p` → `muse_ui_request_snapshot()`，
串口回 `SNAP BEGIN w h` / `SNAP END`，开关是 `devices/sdkconfig.carpet` 里 `CONFIG_LV_USE_SNAPSHOT=y`。
本轮所有图都是这条口出的，`muse/README.md` §7 那条 8444 红线也已改成"旧 app/ 的口，Muse 没有"。

`>maint` 那两条判据（欠的）也在这轮称完，串口原文：

```
@maint sent=1 view.maint_worried=1 card=养车卡 emotion=担心 face=0 mode=3   （空屏：1→3 让路成立）
@maint sent=1 view.maint_worried=1 card=养车卡 emotion=担心 face=1 mode=4   （句子在时：mode 不动，脸没被搬走）
```

第二句要先 `>say` 一句足够长的话（那次是 276 字节）——18 字节那句放完了 `face` 已经掉回 0，
一开始据此误判成 FAIL，重称才对。`>maint off` → `mode=1`。
`shots8\zh_shot0.png` 是它的图证：「对话」行前那枚 12px 琥珀点亮着，同时回读 `maint_worried=1`。
（拍这张要先停掉彩排台：它每推一帧都会用 `frame_maint_worried()` 重算那个布尔记号，把 `>maint` 造的那帧冲掉。）

### 7. 死项清单（给拍板用，本轮一条都没删，全部翻成中文）

设置页里这些条目按"还有没有活的读点"分四档，位置都是 `muse_settings_ui.c` 的行号：

| 条目 | 档 | 还活着的读点 / 为什么是死的 |
| --- | --- | --- |
| `Phone camera`（1700-1704） | **没编进来** | `# CONFIG_MUSE_VIDEO is not set`（`sdkconfig:4269`），那一行整块在 `#if` 外，屏上根本不会出现 |
| `Voice notes`（1411-1415） | **没编进来** | `# CONFIG_MUSE_BOARD_M5STACK_STOPWATCH is not set`（4231）、`# CONFIG_MUSE_REALTIME_VOICE is not set`（4265） |
| `Phone setup`（1310） | **半死** | 开机时已注册就被强清（`main/muse_glue.c:519`），真正读它的只剩 `muse_ble.c:334/354` 那两道闸 |
| `Server` / `VM ID` / `Device token` / `SDK token` | **活着，但和车联无关** | `CONFIG_MUSE_HATCH=y`（4263）；读点在 `muse_chat_session.cpp:807/823/924`、`muse_ble.c:84-85`、`muse_link.c:71-83` —— 走的是 Muse 云配对那条，不是手机连屏这条。**11-08 已按拍板折进「Muse 云」那一组**（见 §9） |
| 电池诊断那七行读数 | **活着，和车联无关** | `muse_battery_read()` / `muse_power_t`，只有 USB 拔插期间才有意义。**11-08 已按拍板折进「电池诊断」那一组**（见 §9） |
| `MAC address`（1079）、`Mic level`（1427） | **只读** | 没有写口，纯显示 |
| Wi-Fi / 接入点 / 蓝牙 / 声音 / 睡眠 / 电量 那几组 | **真有用** | 就是这一轮 R1/R2 和现场要用的那些 |

`>status` 现在自报 `"chat":true`、`hatch.state:"Not set up"` —— 也就是说 hatch 那一组虽然编进来了，
这台机器上没配过账号，屏上那四格填了也不会影响手机连屏。撤不撤由你定，本轮只做翻译和记账。

### 4. 同步到哪一步（11-08 这次推不出去，原因和昨天正好反过来）

本地提交 `7e9c73e`（4 个文件：`HomeShell.kt` / 这份 HANDOFF / `muse/README.md` / `muse/muse-carpet.patch`），
`:core:test` 305 条 + `SchemaDriftTest` 13 条按最终那份补丁实跑（`--rerun`）全 PASSED，`firmware/screen/app/` 照旧没 add 没删。

**push 没成功，板子和代码都不受影响**：昨天是"代理坏、直连好"，今天这台网络（`ChinaNet-FBe9-5G`）是
"**直连 github.com:443 连不上**（`Failed to connect ... Could not connect to server` / `Recv failure: Connection was reset`），
只有 `gh-proxy.com` 那面能读"。走代理推那两次挂在鉴权弹窗上（`GIT_TERMINAL_PROMPT=0` 一试就露出来：
`could not read Username for 'https://gh-proxy.com'`），而 §上一轮已经称过这个镜像**只转发读、不转发写鉴权**，
所以它不是"再试一次"的事。**没有改任何全局 git 配置，没有 force push。**

网络回到昨天那幅样子之后补这一条就完事（和昨天用的同一条，一次 fast-forward）：

```bash
cd D:/dev/projects/carpet-sat-demo
git -c http.proxy= -c https.proxy= push https://github.com/zyawei1991-hue/carpet-sat-demo.git main:main
git -c http.proxy= -c https.proxy= fetch origin main && git log --oneline origin/main..HEAD   # 必须为空
```

**后来的实情（11-08 傍晚补记，别照抄上面那段当结论）**：原因确实是**这张网只给读不给写**，不是命令写错。
当晚 `7e9c73e` / `93a6692` 连同后面四个提交都推上去了，走的就是上面那条 HTTPS 直连
（`93a6692..f8a0862`、`f8a0862..5daf775`、`5daf775..1f24ced`，全 fast-forward，没 force、没改全局配置、没改 remote）。
另外这面仓库现在多了一条**备用口**：本机新生成的一对 ed25519（私钥 `~/.ssh/id_ed25519`，无口令）已被加成
**仓库级 deploy key（带写权）**，`ssh -T git@github.com` 回的是 `Hi zyawei1991-hue/carpet-sat-demo!`
——回文是仓库名，证明认的是 deploy key 不是账号 key。HTTPS 再被卡住时走这条，同样不 force、不改 remote：

```bash
git push git@github.com:zyawei1991-hue/carpet-sat-demo.git main:main
```

**验收那条要换**：`origin` 这个 ref 仍然指那面只转发读的 `gh-proxy.com` 镜像，它的本地值可能是陈的，
`git log --oneline origin/main..HEAD` 会把**已经推出去的算成未推**。改称这一条，两边 sha 相等才算同步：

```bash
git ls-remote --heads git@github.com:zyawei1991-hue/carpet-sat-demo.git   # 拿 refs/heads/main 的 sha
git rev-parse HEAD                                                        # 和它比，相等才是推上去了
```

11-08 傍晚实称：两条都是 `1f24cedf32140b5dbab3ce745940c8cc64d5405e`（HTTPS 直连 `ls-remote` 也回同一个 sha），
且 `git merge-base --is-ancestor 93a6692 HEAD` 成立 —— 远端没被任何一侧回退过。

### 8. 这一轮的同步

本轮改动分两个提交：一个固件+文档（`muse/muse-carpet.patch` 重打到 6,367 行、`muse/README.md` §2 的数、
这份 HANDOFF、`docs/APP-SCREEN-CONNECT.md`、`tools/` 那四个脚本），一个把 `HomeShell.kt` 回退回
658673d 那一句（按"App 侧不动代码"的新规矩，`7e9c73e` 里那处 App 改动撤销；不改历史、不 force push，
所以历史上留了"改了又回退"两笔，树是干净的）。

提交前跑的：`tools/check_zh_table.py` → `entries: 159 … problems: 0`；
`esp32/tests/test_muse_lang.py` → 7 条 OK；`./gradlew :core:test --rerun` → 305 条 0 失败 0 错误，
其中 `SchemaDriftTest` 13 条（它有一条直接读这份新 patch 里的 `UI_CARD`）；
干净树 `git apply --check --binary` + 真 `git apply` + 解出来 `"Pair in the Muse app"` 在 → worktree 删掉。

推送走 §4 末尾那两条口（HTTPS 直连为主，SSH deploy key 为备），验收按 §4 末尾换过的那条：
`git ls-remote --heads git@github.com:…` 的 sha 等于 `git rev-parse HEAD` 才算同步，
**不要再拿 `git log --oneline origin/main..HEAD` 当判据**（`origin` 指那面只转发读的镜像，本地 ref 可能是陈的）。

**11-08 傍晚实推**：`git -c http.proxy= -c https.proxy= push https://github.com/zyawei1991-hue/carpet-sat-demo.git main:main`
连推三次 `93a6692..f8a0862` → `f8a0862..5daf775` → `5daf775..1f24ced`，
这次把上午推不出去的 `7e9c73e` / `93a6692` 一起带上了（远端 `main` 从 `658673d` 走到 `1f24ced`）。
最终验收按上面换过的那条走：`git ls-remote --heads git@github.com:…` 与 `git rev-parse HEAD`
**两边都是 `1f24cedf32140b5dbab3ce745940c8cc64d5405e`**（HTTPS 那面 `ls-remote` 回同一个 sha），
且 `git merge-base --is-ancestor 93a6692 HEAD` 成立 —— 远端没被任何一侧回退过。
**没有改任何全局 git 配置，没有 force push，没有改 remote**，`firmware/screen/app/` 照旧没 add 没删。

## 追加（2026-11-08 傍晚）：把用不上的那两堆收进默认折叠的分组

四件拍板都按原话落地，一条没扩大范围。

### 9. 折叠分组这一轮

**【1】MUSE 页那四格**（`Server` / `VM ID` / `Device token` / `SDK token`）收进一组，
用的就是这一页现成的交互：一行 `row()`（左侧 `LV_SYMBOL_DOWN` 箭头 + 右侧一个状态词）
+ 一个带 `LV_OBJ_FLAG_HIDDEN` 的 `column()`，点那一行翻 flag —— **没有新控件语义**，
`fold()` 只是把这三样按顺序摆好（行 → 说明 → 折上的列），`fold_toggle()` 只改 flag 和那个状态词。
默认折上。同一格里带一行中文说明
「以后接 Muse 官方 API 才用，现在这套车联演示连不到它。」—— 这行刻意放在折上的列**外面**，
不然折着的时候说明也看不见，就白写了。四格那几个标签拍板说英文不翻也行，但上一轮已经翻好并出过图
（服务器 / 虚拟机 / 设备令牌 / SDK 令牌），就按现状留着，不回退成英文。
三条闸按原话留着：功能一个没删（`Test connection`、配对说明那两条一起进组，还在）、
NVS key 一个没删（`muse_settings.c` 那几条照旧）、Kconfig 里 `CONFIG_MUSE_HATCH` 那条依赖没动。
折叠状态**不落 NVS**：子页返回时被整个销毁（`show()` 里 `drop(prev)`），重启天然回到默认折上，
所以这一轮没往 NVS 加任何东西。
新增块全部圈在 `#if CONFIG_MUSE_CARPET_CARDS` 里，`CONFIG_MUSE_CARPET_CARDS=n` 时
`more = list`，那四格回到原位，与上游逐字等价。

**【2】电池诊断**同样处理：那一页没删，点进「电池诊断 / 已折叠」那一行，
下面还是原来那七行读数（`电量`/`已用掉`/`充满能用`/`熄屏`/`打盹时间`/`唤醒次数`/`CPU 占用`），
一个数没少；`重新计时` 那颗按钮留在组外，因为它不是诊断读数。

**【3】`Phone camera` 这一轮没动。** 出厂配置里 `# CONFIG_MUSE_VIDEO is not set`
（`build-muse-s3-175-carpet/sdkconfig:4269`），那一行本来就在 `#if CONFIG_MUSE_VIDEO` 里，
屏上根本没有入口 —— 没开 `CONFIG_MUSE_VIDEO`/`MUSE_VIDEO_WIFI`，PSRAM 预算一行没碰。

**【4】voice notes 这一轮没动，只确认它没漏进来。** 那一行在
`#if CONFIG_MUSE_BOARD_M5STACK_STOPWATCH && CONFIG_MUSE_REALTIME_VOICE` 里，两个开关出厂都没开
（同一份 sdkconfig 的 4231、4265 行都是 `is not set`），所以 SOUND 页没多出它；
表里那条 `"Voice notes" → "语音留言"` 是惰性的（那一行不编译，表项读不到，也就多不出一个死入口）。

**一处我之前说错的话已更正**：抓屏通道不是 `CONFIG_SCREEN_SHOT_PORT=8444`。
Muse 这棵树全树 grep 没有 8444、没有 `SCREEN_SHOT_PORT`，那是 `firmware/screen/app/` 那棵旧屏的东西；
Muse 侧出图走串口触发的 LVGL snapshot —— `>ui.settings=<页>` 选页 + 按 `p`
（`muse_input.c` 里 `#if LV_USE_SNAPSHOT` → `muse_ui_request_snapshot()`），
回读 `SNAP BEGIN w h … SNAP END`，脚本是 `tools/zh_shot.py`。
`muse/README.md` §7 和本文件 §6 里那句 8444 已经改成这条口。

**为什么多了一个 bench 专用的 `+`**：这条串口注入不了点击（`muse_input.c` 里所有 bench 命令逐条看过，
没有一条能合成一次 tap），折上的分组就永远拍不到展开态，验收就成了"只看编译过"。
`>ui.settings=muse+` / `battery+` 只在 `#if LV_USE_SNAPSHOT` 里、只在打开页面之后把那一组**打开**
（从不反向折上），所以不带 `+` 的名字拍到的仍是用户默认看到的那一屏。它不是 UI 控件，
不给手指用，也不改任何用户可见的行为。

**称到了什么（`D:/dev/esp/shots10/`，四张都人眼看过）**：

- `zh_muse.png` 折上态 —— 「Muse 云 / 已折叠」+ 那句中文说明折成两行，四格不在屏上。
- `zh_muse+.png` 展开态 —— 同一行变「已展开」，「服务器 hatch.metaaivm.com」两行折出、「虚拟机 未设置」露出半行。
- `zh_battery.png` 折上态 —— 只剩「电量 / 没有电池 / 电池诊断 已折叠 / 重新计时」。
- `zh_battery+.png` 展开态 —— 那套读数原样在（电量 无、已用掉 -、充满能用 -、熄屏 -、打盹时间 -）。

四张没有豆腐块、没有被省略号截掉的条目。`check_zh_table.py` → `entries: 164 … problems: 0`
（新加的那句说明走 note 档 330 px，已在脚本 `INDIRECT` 里写明理由，不是放宽默认）；
`test_muse_lang.py` 7 条 OK；`:core:test --rerun` 305 条 0 失败 0 错误（含 `SchemaDriftTest`）。

**没称到的，如实记着**：展开态那一屏视口只装得下前头两格，`Device token` / `SDK token`
两行在视口以下 —— 串口既不能注入点击也不能滚动，所以那两行的**中文渲染**没有图证，
只有代码和表（它们和已出图的同类行走同一档字体、同一张表）。
上一轮欠的「接入点」那一行和它的输入键盘页同样还在折叠区以下，仍未出图。

**这一轮的同步**：干活那笔是 `9bcd8de`（`muse-carpet.patch` 重打 + `muse/README.md` §2 的数 +
这份 HANDOFF §9 + `tools/check_zh_table.py` 那条 `INDIRECT`），下面这两笔（记推送结果、更正本段的措辞）
只是记账 —— 推的时候 `ls-remote` 称到的 sha 就是它自己那一笔之前的 HEAD，别当成漏推。走 SSH 那条口
`git push git@github.com:zyawei1991-hue/carpet-sat-demo.git main:main` → `4bef23f..9bcd8de`。
验收按 §4 末尾换过的那条：`git ls-remote --heads git@github.com:…` 与 `git rev-parse HEAD`
**两边都是 `9bcd8de465354a7796e28b60a4e347dcb70f60d2`**。
没有 force push、没有改全局 git 配置、没有改 remote，`firmware/screen/app/` 照旧没 add 没删。

### 10. 一处对不上的判断，已称清楚（11-08 晚，另一会话提出）

那边说「本机型构建里抓屏和串口开页都编译不出」，给的行号是
`esp32/build-muse-s3-175-carpet/sdkconfig:5080 = # CONFIG_LV_USE_SNAPSHOT is not set`。
**这条在车机这一构里不成立**，三份原文回读：

- `grep LV_USE_SNAPSHOT build-muse-s3-175-carpet/sdkconfig` → `5078:CONFIG_LV_USE_SNAPSHOT=y`
- `grep LV_USE_SNAPSHOT devices/sdkconfig.carpet` → `28:CONFIG_LV_USE_SNAPSHOT=y`
- `grep LV_USE_SNAPSHOT build-muse-s3-175/sdkconfig` → `5078:# CONFIG_LV_USE_SNAPSHOT is not set`

也就是说那个"not set"是真的，但它是**不带 `-carpet` 那一份构**；两份的行号都是 5078、
目录名只差五个字符，读错文件就会得出相反的结论。
构建脚本那条 defaults 链里 `devices/sdkconfig.muse-bench` 根本没出现
（`sdkconfig.defaults; …muse; …muse-waveshare-s3-175; …carpet`），所以不是"靠 bench 打开"的。
图证：这一轮那四张 466×466 的 PNG 就是从 `build-muse-s3-175-carpet` 刷进去的固件上拍的，
`zh_shot.py` 每页都解出了 `SNAP BEGIN 466 466`（它拿 `SNAP OFF` 时只会印 `no screenshot`）。
**结论按它给的第二条办**：不用另开 bench 构建、不用 `-d CONFIG_LV_USE_SNAPSHOT=y`，
理由 + 判定命令 + 那个读错文件的坑都写进 `muse/README.md` §7 了。
它给的线上格式（`SNAP BEGIN w h 144` / 每行 base64 / 144 字节原始宽 / `SNAP END`）和
`muse_ui.c` 里 `enum { RAW = 144 }` 那段逐字对过，一致。

COM8 这一段时间归那边跑四个现场验证项，我这边没有正在跑的 flash 或串口命令，
接下来只改代码、编译、写文档、提交，不开端口。

## 追加（2026-11-08 夜）：出厂地址在真手机热点上零输入拨通了；卡点挪到 App 那一侧

这一段是那边（跑现场验证的会话）称到的结果 + 我这边复核出来的两处更正。
**这一轮我没开 COM8、没刷机**（串口那段时间归那边），下面所有原文都是它抓的或仓里读出来的。

### 11. 现场称到的 / 还欠的

**称到（串口原文，那边抓的）**：

```
@url wifi=1 ssid=Mate 60 linked=1 dialed=1 src=factory saved=(none)
@carpet wifi=1 linked=1 contract=v3
```

出厂那条 `ws://192.168.43.1:8443` 在**真手机热点**上、`saved=(none)`（NVS 里根本没填过地址）
零输入拨通了 —— §0 主路这一条从「没称到」改「称到」。
`dialed=1` 是"真的往那个地址拨过"，`src=factory` 是"用的是出厂那条，不是 NVS 覆盖"，
两者同时成立正是 R4 那条兜底要的样子。

**也称到（用户目视）**：设置页「Access point」那一格在，下面那行 note 把
`ws://192.168.43.1:8443` 整条写全、没被点掉 —— 上一轮记的那条"在折叠区以下没出图"现在划掉。

**还欠两条**：

- (a) `has_state=1` —— 卡在 App 那个缺陷上（`ScreenLinkServer` 同一实例二次 `start()` 必抛
  `can only be started once.`），判据、文件:行、改法都写进 `docs/APP-SCREEN-CONNECT.md` §6 了。
- (b) 那枚 12px 琥珀点 —— 那边已用 `>maint` 把它点着（`card=养车卡 emotion=担心 maint_worried=1`），
  等用户退出设置页往上滑到「数字分身」看「答句」「情绪」两行行首。**这条现在只有回读，没有新图**
  （上一轮 `shots8/zh_shot0.png` 那张是同一条口的旧图，不能拿来当这一次的图证）。

### 12. 两处更正，都是判据层面的

**① `contract=v3` 不是谈下来的，它证明不了服务端回了 hello。** 复核 `screen_link.c`：

- `:63` —— `static volatile int s_negotiated = SCREEN_LINK_CONTRACT_MAX;`（`screen_link.h:37` = `3`）。
  全文件 `s_negotiated` 只有这一处写，`:553` / `:674` / `:705` 三处读，**没有任何收到的帧给它赋值**。
- `:552-553` —— `send_hello();` 之后**立刻** `on_connected(s_negotiated)`，不等回执。

所以 `I carpet: linked, contract v3` 只是屏把自己声明的上限印了一遍。
这一串里算数的是 `ws 接入`（`:437-441`，打在 `WEBSOCKET_EVENT_CONNECTED`，
esp_websocket_client 只在收到 HTTP 101 之后才派它）—— 它证明 TCP + WS 握手通了。
**我自己 §5 那条没踩错**：当时称的是 `gap_s=0 has_state=1`，靠的就是 state 帧真的到了，
不是 `contract=v3`；但以后谁只贴 `linked=1 contract=v3`，那一行不算称到。
屏侧要不要把 `>carpet` 里那个 `contract=` 改成"我声明的上限"这种不误导的写法，是下一轮的小事，
不改也不影响判据 —— 只是别让它当证据。

**② 那条红字的机制，字段名要说准。** 那边写的是「`stop()` 不重置 `startcalled`」。
方向对，但 1.5.6 里没有 `startcalled` 这个字段。我对那份 jar 里的
`org/java_websocket/server/WebSocketServer.class` 跑了 `javap -p -c`：
`start()` 判的是 `selectorthread` 是否非空（非空就 `throw IllegalStateException(name + " can only be started once.")`），
而 `stop(int,String)` 做的是 `isclosed` CAS → 逐个 `close(1001)` → `wsf.close()` → `selector.wakeup()` →
`selectorthread.join(timeout)`，**整份 class 里 `selectorthread` 只被写过一次**（`run()` 里赋成当前线程）。
结论一样、更强：这个对象一旦起过就永远起不第二次。对上的 App 侧三行是
`DemoRuntime.kt:70`（`val server` 是构造默认值）/ `:216`（`runCatching { server.start() }`）/
`:238`（`runCatching { server.stop() }`），加上 `AppRuntime.kt:86` 一个进程一个 runtime、
`:576-585` 那个开关就是 `start()`/`stop()` —— 「停止演示 → 再开始演示」正好是同一实例 stop→start。

### 13. 屏侧缺陷一条（记在我这一节，别让它算到 App 头上）

开发口 `>page avatar` / `>page vehicle` 在**用户还停在设置页时不落地**：
`@page want=avatar now=face`，重试两次都一样（那边现场抓的，原文在 `D:/dev/esp/m1108v/tap3.txt`）。
机制查到一半：`nav_to()` → `muse_ui_show_carpet()` → `lv_tileview_set_tile(s_tv, s_carpet, …)`，
而设置页那一层是压在 tileview 之上的另一个对象，`s_tv` 当时不是活动屏；
tile 回调（`carpet_page.c:544`）又把 `s_tile` 写回实际位置，所以回读看着像"没动"。
**下一轮要做的**：`muse_ui_show_carpet()` 在设置页还开着时先把它关掉（或拒绝并回读一句
`page refused, settings open`），而不是静默不落地。
**任何人现在拿这一条验分页，先把屏退出设置页。**
（补一条 13:0x 的实测：`>ui=face` 退回脸那一格之后再 `>page avatar`，两秒后回读
`@page want=- now=avatar` —— 落地是好的，**只有设置页开着时不落地**，这一条判据现在才算闭合。）

## 14. 13:00 那一轮：现场四项称到三项，第四项卡在一个新形状的失败上

### 14.1 称到的（附原始回读）

1. **出厂那条地址在真手机热点上零输入拨通**：`@url now=ws://192.168.43.1:8443 src=factory
   saved=(none) linked=1 dialed=1`、`@url wifi=1 ssid=Mate 60`、`@carpet wifi=1 linked=1 contract=v3`。
   §0 那条主路到此称完 —— 手机上开热点（2.4GHz）+ 屏在设置页配一次网，屏上那一格 `Access point`
   一个字都不用填。
2. **设置页那一格的长相**：用户目视确认「Access point」那一格在、下面那行 note 把
   `ws://192.168.43.1:8443` 整条写全（没被 `max_width 130` 点成 `ws://192.16…`）。
3. **那枚 12px 琥珀点称到了，而且是量出来的**：车机构建这轮把 `CONFIG_LV_USE_SNAPSHOT=y` 打开后，
   串口抓屏这条路就能用了。`>maint` 点着（`card=养车卡 emotion=担心 maint_worried=1`）→
   `>ui=face` → `>page avatar` → `tools/muse/snap.py` 抓回 `D:/dev/esp/m1108v/shot_avatar.png`，
   逐像素量：两枚点各 **36×36 @3x = 12.0×12.0 逻辑 px**，和 `carpet_page.c:127` 的 `DOT_D 12` 对上。
   同一批截图还证明中文化和 §13 那两个折叠都落地了：`shot_muse.png` 里 `Muse 云 已折叠` +
   那行注「以后接 Muse 官方 API 才用，现在这套车联演示连不到它。」，`shot_battery.png` 里
   `电池诊断 已折叠`，`shot_wifi.png` 是设置首页（蓝牙 / 声音 / 熄屏 / 电量都是中文）。
   **页标题 `WI-FI` / `MUSE` 还是那枚像素字体英文** —— `muse_settings_ui.c:303-304` 用的是
   `lv_font_unscii_16`，没有汉字字形，这一条还欠着。

### 14.2 没称到的那一条，形状和以前不一样

13:0x 现场三条并起来读：App「接入点」那一格显示的是**对的** `ws://192.168.43.1:8443`（红字没复现，
说明这一次是干净启动）；手机热点的**已连接设备列表里没有这块屏**；而屏自己说它连着：
`>status` 里 `"wifi":{"on":true,"state":"connected","ssid":"Mate 60","ip":"192.168.43.13","rssi":-31}`，
每 30 秒拨一次，每次都停在 TCP 层：

```
E websocket_client: esp_transport_connect() failed with -1,
  transport_error=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST, tls_error_code=0, esp_ws_handshake_status_code=0, errno=119
W screen_link: ws 断开/未接通，下一次按退避阶梯再来
```

`errno=119` 是 ENETUNREACH —— 同网段 ARP 不到那台主机；**不是** 113 ECONNREFUSED（对面在、只是不听这个口）。
所以这一类失败的成因在网那一层：AP 已经把这台站踢了，S3 那边还留着 `connected` 和租约，
只靠 ws 那条退避阶梯永远不会把自己重新关联回去。**屏侧要做的两件事**：

1. keeper 认这个形状：连续 N 次（建议 3 次）`ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST` / ENETUNREACH
   就做一次**全量重连**（`esp_wifi_disconnect()` + 重扫 + 重连存过的那张网），而不是接着按阶梯拨同号。
   现场判据：把手机热点的已连接设备里那块屏踢掉（或让热点自己超时），屏要在 ~90 秒内自己重新出现在列表里。
2. `>url` / `>carpet` 把**最后一次 transport 错误的原文和 errno 一起印出来**。113 和 119 在现场是两件
   不同的事（前者=App 没在监听，后者=屏根本不在那张网里），现在两者都只写成「ws 断开/未接通」，
   人只能靠猜。

### 14.3 一条工具坑，用它的人必须先知道

`esp32/tools/muse/snap.py` 一开 COM8 就把板子**复位**一次（它是 `serial.Serial(port, …)` 默认开线，
COM8 的 RTS 接 EN；不像 `firmware/screen/tools/sercmd_norst.py` 先把 dtr/rts 压成 False 再开）。
后果：拿它截图 = 板子重启，`dialed` / `sent` / `gap_s` 这些 RAM 计数全清，读数会看着像"链路自己断了"。
我这轮就有三次是这么来的。**要么把 snap.py 改成开线前压 dtr/rts（和 sercmd_norst.py 同一招），
要么在任何文档里写"截图会重启"** —— 别让人拿被重启污染的计数去判链路。

### 14.4 我（这一轮）自己说错的一条，留个案

我在给用户解释时说过「`contract v3` 是从服务端 hello 回执谈下来的，不是屏自己填的」—— **这句是错的**，
正解在 §13 / `docs/APP-SCREEN-CONNECT.md` §6.1：`s_negotiated` 初值就是上限 3，`send_hello()` 之后
立刻 `on_connected(s_negotiated)`，不等回执。真正算数的是 `ws 接入` 那行（打在
`WEBSOCKET_EVENT_CONNECTED`，只在收到 HTTP 101 之后才派）。**对外不许再用 `contract=v3` 当"对面活着"的证据。**

---

## 15. 11-09：「每次要人手点一下屏」的机制是 nap，做成了设置页一个开关；顺带把拨号卡死自愈装上，`has_state=1` 这一轮称到了

### 15.1 结论先给（App 那边那句"必须刷机"不成立）

屏连不到手机，**不是 App 没做，也不是地址写死要重刷**。是 Muse 官方自带的省电行为：
**电池供电 + 屏幕熄灭满 2 分钟**，屏自己把 Wi-Fi 整个断开（源码里叫 nap），断到下次有东西把它唤醒为止 ——
断的时候手机侧、PC 侧谁都摸不到它，所以"要点一下屏才连得上"。USB 供电永远不 nap，
§14.1 那条"零输入拨通"就是插着 USB 称的，两边不矛盾。

现在这件事做成**设置页里的一个开关**（`熄屏后保持联网`）：不用刷机、不用重启、当场生效、重启还留着。
默认关 = 官方省电行为；开 = 熄屏后仍保持关联（`WIFI_PS_MAX_MODEM` 那档深睡），代价是电池。

### 15.2 屏侧改了哪几处（行号是这一轮读源码读出来的）

- **nap 的闸门**：`esp32/components/muse/muse_input.c:591`
  ```c
  bool auto_nap = !muse_settings_stay_online()
                  && s_cpu_low && now - low_since >= pdMS_TO_TICKS(WIFI_NAP_MS);
  ```
  `WIFI_NAP_MS` 还是 120000，`paused` 那条前提（要 `muse_state_on_battery()`）一个字没动 ——
  所以 USB 那侧行为完全不变，改的只有"电池 + 熄屏"这一条。台架上的 `>nap` 仍然照 nap：
  那是显式测量请求，不是这个定时器。
- **开关存哪**：`muse_settings.c:44`（struct 里 `bool stay_online`）、`:141`（NVS `muse/stay_online`，
  u8，缺键=默认 0）、`:291/:296` 读写函数、`muse_settings.h:96-97` 声明、`:53` 那条 `MUSE_SETTING_STAY_ONLINE`。
  开机那行日志多带一尾：`..., link %s, nap %s`。
- **当场生效**：`muse_app.c:57` 收到这个 setting 就 `muse_wifi_apply()`；已经在 nap 的那一次在下一轮重算里结束，
  不等下一次唤醒。
- **设置页那一格**：`muse_settings_ui.c:1577`（`switch_row`）+ `:1563` 回调。中文串走现成的表：
  `carpet/carpet_lang_zh.c` 里 `Stay online when asleep` → `熄屏后保持联网`、那句 note →
  `开着时熄屏也连着 Wi-Fi，手机随时能连上；代价是更费电。`
- **台架口**：`muse_input.c:995` 的 `>stay`；`>stay on` / `>stay off` 改，不带参数回读
  `@stay on=<开关> nap=<此刻在 nap 没> wifi=<连着没>`。
- **拨号卡死自愈**：`main/muse_glue.c:84` `DIAL_STALL_FAILS 4` + `:601-613` —— Wi-Fi 还连着、
  但抓屏链路连续 4 次拨号没一次通过，就打一条 W 然后 `wifi_mgr_disconnect()` 走全量重连
  （重扫 + 重连存过的那张网），不再按退避阶梯死撞同一个 ARP 不到的地址。§14.2 要的第一件事到此做完。
- **119 和 113 分得开了**：`components/screen_link/screen_link.c:449` `note_dial_error_text()` 从
  `WEBSOCKET_EVENT_ERROR` 的正文里解析 `transport_error= / errno= / esp_ws_handshake_status_code=`，
  `:75/:604/:614/:674` 维护 `s_dial_fails`（拨通即清零），`:765/:770` 两个读口；
  `>url` 第二行、`>carpet` 第一行都带上了 `fails=` / `err=`。§14.2 要的第二件事做完。

### 15.3 称到的（原始回读，全部 COM8，`sercmd_norst.py`/`cap.py` 抓的，不碰线不复位）

1. **开关 + 持久化**：`@stay on=0 nap=0 wifi=1` → `@stay on=1 nap=0 wifi=1` → 复位后仍 `on=1`；
   这一轮重刷（只动 UI 排布）后开机再读，还是 `@stay on=1 nap=0 wifi=1` —— 刷应用区没动 NVS。
2. **那一格真上屏、而且不用滚**：`>ui.settings=sleep` 截图 `D:/dev/tmp/shot_sleep2.png`
   （文件 1398×1398 = 466×466 @3x）。原来这格放在五个时长选项后面，圆屏一屏放不下（滚动条是关的、
   台架也没有滚动的口），现在提到时长列表**上面第一格**。逐行量墨迹带：
   `熄屏后保持联网` y=399..443 **h=45**，下面 `从不` y=606..647 **h=42**、`30 秒 / 1 分钟 / 2 分钟` 全在 45 上下 ——
   同一档字号，没掉进 16 px fallback，也没有一行里大小不一。开关画在右边，紫色轨道 + 白色圆钮在右 = 开。
   （第一次数到 h=96 那条是把**开关钮**并进了同一个带，不是字大；裁到 1:1 才看清。）
3. **自愈真的触发过**（上一轮同一份逻辑，行号变了、行为没变）：
   ```
   W (15937) link.muse: screen link dialed 4 times with none through
     (ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST errno=119 http=0); rejoining wifi
   ```
   后面紧跟一次真重新入网（`run -> init`、`reason=8`、扫描、`joining saved network 吨吨吨的Mate 30 5G`、
   `auth -> assoc -> run`，全程 ~1.4 s）。
4. **错误读数分得开**：`@url wifi=1 ssid=吨吨吨的Mate 30 5G fails=9 err=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST errno=119 http=0`
   —— 119=ENETUNREACH（同网段 ARP 不到手机，问题在网）；113=ECONNREFUSED（对面在、没监听这个口，问题在 App）。
5. **`has_state=1` 这一轮称到了**（§14 欠的那一条）：屏切到 Mate 60 热点、App 在 8443 上真在听之后，
   ```
   @carpet wifi=1 linked=1 contract=v3 gap_s=5 has_state=1 wd=0 fails=0
   @carpet wifi=1 linked=1 contract=v3 gap_s=3 has_state=1 wd=0 fails=0
   @carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0 fails=0
   @carpet sat=已锁定 下次3:21
   ```
   三次间隔 ~15 s，`gap_s` 一直是 0-5（`state` 1 Hz 在跑），`fails=0` 是"拨通过就清零"那条生效。
   按 §14.4 的规矩，**算数的是 `has_state=1`，不是那行 `contract=v3`**。
6. **复位后零输入自己回到"已接入"，而且拍到了屏**：上面那张截图是 `snap.py` 抓的（它会复位板子，§14.3），
   复位后不碰屏再读一次：`@carpet wifi=1 linked=1 contract=v3 gap_s=1 has_state=1 wd=0 fails=0`、
   `@stay on=1 nap=0 wifi=1`。截图 `D:/dev/tmp/shot_carpet_live.png` 里宫格页顶部那行是
   **`已接入`**（琥珀紫），而这一行只在 `carpet_page.c:853` 那个 linked 分支才画 ——
   也就是说"重启 → 自己连热点 → 自己拨 8443 → 屏上写出已接入"这一整条，全程没人点屏。

### 15.4 还没称到的，说清卡在哪

1. **"电池 + 熄屏 3 分钟，手机侧仍然连得到"这条没在台上称成**：nap 的前提是
   `muse_state_on_battery()`，板子插着 USB 就永远不成立，`>nap` 又是显式强制、不走这个开关 ——
   所以这条只能在现场称。判据（给现场用）：电池供电、开关打开、屏幕熄灭 3 分钟后，
   **手机热点的已连接设备列表里那块屏还在**，`>stay` 回读 `nap=0`。
   这一条的仪器在 11-09 晚换过一次，原因和现在的期望读数见 §15.7 —— 别再拿 `nap=0` 单独当证据，
   它当场只说明"此刻不在 nap"，要看的是 `naps=` / `drops=` 这两个累计数。
   **11-09 更晚：这一趟跑了两次，两次都在窗口里被重启毁掉（§15.9、§15.11）——
   电池本身是好的、确实在供电（§15.10 撤回了我当时那句"没有电池"），
   但"电池 + 熄屏"这条路上整机会断电，所以它撑不到能称 nap 的那 3 分钟。这一条仍然是没称到的，
   而且现在排在"电池 + 熄屏会断电"后面。**
2. 那句 note（`开着时熄屏也连着 Wi-Fi…`）在截图里落在折叠区外、**没拍到像素**。
   但它过了那道脚本守卫：`firmware/screen/tools/check_zh_table.py` 这轮跑出
   `entries: 166   noto glyphs: 7240   problems: 0` —— 每个汉字都有字形、百分号/占位符对得上、
   键还在画图那份文件里、宽度预算也没超，剩下的只有"排版挤不挤"这一条肉眼账。
3. `snap.py` 一开 COM8 就复位板子那条（§14.3）仍然没修 —— 这一轮所有计数读数都改用
   `sercmd_norst.py` / `cap.py`（开线前把 dtr/rts 压成 False）拿的，截图那一张是复位后的新会话，
   所以 `shot_sleep2.png` 里的 UI 状态可信、计数不可信。
4. **一次没抓到的转换，记下来免得下轮当谜**：`@url ... fails=9`（连的是 `吨吨吨的Mate 30 5G`）之后
   我抓了 100 秒串口，那 100 秒里**一条 websocket / screen_link 日志都没有**，只有 5 秒一条的 `hb`；
   再下一次读 `>url` 已经是 `ssid=Mate 60 fails=0 linked=1`。也就是说"换到 Mate 60 + 拨通"这一步
   发生在两次抓取的缝里，没留下过程证据。`fails` 到 9 就停住不再涨、且 100 秒零次拨号尝试，
   和退避阶梯（醒着最长 60 秒一档）能对上，但也可能升级那条已经把它接走了 —— **下一轮要称的话，
   连着抓 3 分钟以上，别拿两段读数中间的空档讲因果**。

### 15.6 一条仪器坑：电脑 ping 不通屏，即使在屏明确在线的时候（11-09 晚，我自己踩了并据此下过错结论）

电池 + 熄屏 3 分钟那一次，我从这台电脑判"屏不在这张热点上"，用的是
`ping -n 20 -w 3000 192.168.43.13`（真回复 0 条，20 条全是本机自己报"无法访问目标主机"）
加整段 `192.168.43.1-254` 扫描（邻居表里只剩 `192.168.43.1` 手机自己）。
**这套判据是废的**，因为把 USB 插回来、屏自己报告明确在线的同一时刻，它照样 ping 不通：

```
@carpet wifi=1 linked=1 contract=v3 gap_s=6 has_state=1 wd=0 fails=0   ← 链路此刻是活的
@stay   on=1 nap=0 wifi=1
ping -n 6 -w 2000 192.168.43.13 → 已发送 6，已接收 1（那条是本机的不可达回显），丢失 5
```

也就是说这台华为热点上 **电脑→屏 这条路本来就不通**（客户端隔离，或者 S3 在省电下根本不应 ICMP/ARP），
和 nap 无关。所以：

- **不许再用"电脑 ping 得到屏"当现场判据**，它连"屏现在在线"都证不出来。
- 电池那条的正确仪器在**手机侧**：热点的「已连接设备」列表（站一掉就消失），或者 App 里屏的在线状态。
  现场流程：拔 USB → 让它熄屏 3 分钟 → 在手机上那块屏还在不在列表里。全程不碰屏。
- 这一条到此仍是**没称到**，作废的原因是仪器不对，不是屏不对。
- 顺带一条实测到的好事：USB 插回来之后屏自己恢复到 linked 并继续收 `state`，
  冷启动那两遍也是 8 秒内起来（§15.3 第 5、6 条），没有任何人工输入。

### 15.7 电池那一趟换的仪器：nap 计数记在 RAM 里，拔电期间也能事后读（11-09 晚）

§15.6 把 ping 那条作废之后，"屏在电池上打没打盹"只剩两种问法：问手机（热点的已连接设备列表，
我没实测过它掉站之后多久刷新），或者**插回 USB 之后再问屏自己**。后一条成立的前提是拔 USB 期间屏不断电
—— 它确实不断：拔掉 USB 只是没了串口，MCU 由电池继续跑，**RAM 里的东西还在**。所以计数放 RAM 里就够了：

- `muse_input.c:585-586` 两个 `volatile uint32_t`：`s_nap_events` = nap 真的开始过几次，
  `s_wifi_drops` = Wi-Fi 从"连着"变成"没连着"几次（`update_wifi_nap()` 里 `was_up && !up` 那一条，`:593-598`）。
  开机清零，不需要 NVS —— 要证的本来就是"这一趟"，不是"这台机器一辈子"。
- `>stay` 回读从 `on=%d nap=%d wifi=%d` 变成 `on=%d nap=%d naps=%lu drops=%lu wifi=%d`（`:1028-1030`）。
- 一个真踩过的坑，记下来免得再犯：`was_up` 的初值一开始写成 `true`，而刷完那一刻 Wi-Fi 本来就没起来，
  于是刚开机就白记一次掉线，实测读到 `@stay on=1 nap=0 naps=0 drops=1 wifi=0`。
  改成 C 的零初值（`static bool was_up;`）之后重刷，基线才是 `naps=0 drops=0`。
- 判据：跑完「拔 USB → 让它自己熄屏 3 分钟 → 插回来」这一趟之后读一次，
  **`on=1` 期望 `naps=0 drops=0`**（开关生效 = 根本没打盹，也就没掉网）。
  对照组（关掉开关应该看到 `naps≥1`）**只能在同一趟电池里做**，原因见下面"台架上永远走不到自动 nap"那一条。
- **仪器自己先证过一次**（就在这一轮，电脑前，不需要热点）：`>nap` 走的是 `s_nap_now` 那条显式口，
  当场让它 nap，`naps` 立刻从 0 变 1；`w` 醒过来之后 `nap` 归 0、累计数留着。
  所以 `naps=0` 不是"这个数永远是 0"：

```
@stay on=1 nap=0 naps=0 drops=0 wifi=0      ← 刷完的基线，没人碰过
>nap  →  I (732876) muse_input: display paused (USB host: CPU at full speed)
          I (732881) muse_input: Wi-Fi napping
@stay on=1 nap=1 naps=1 drops=0 wifi=0      ← 计数器是活的
w     →  @stay on=1 nap=0 naps=1 drops=0 wifi=0   ← nap 结束，累计数留在 RAM 里
```

  `drops` 那一次仍是 0，不是仪器坏：nap 那一刻 Wi-Fi **本来就没连着**（`was_up` 为假），
  而 `drops` 只数"连着 → 断了"这一个转换。这一半要有网之后再断才数得到 ——
  也正是那一趟电池要顺带称的东西。
- **台架上永远走不到"自动 nap"这一条**，这是这一轮读代码读出来的硬事实：
  `muse_input.c:543` 是 `want_low = pause && !muse_console_host()`，而 `auto_nap` 要求 `s_cpu_low`
  （`:607`）。**插着 USB 跟电脑说话时 `s_cpu_low` 恒假 ⇒ 自动 nap 恒不发生**，
  跟开关开没开没有关系。所以"开关挡住了自动 nap"这半条在电脑前一次也测不出来，
  要么拔电（就是那一趟），要么用 `>nap` —— 而 `>nap` 故意绕开开关，拿它验开关是验不出来的。
  §15.4 第 1 条那句"只能在现场称"到这里才算说死。
- 顺手一条 bench 坑：`>sleep` 只认 `SLEEP_CHOICES = {0,30,60,120,300,600}` 里**当前档位之后**的值，
  我发 `>sleep 30`（当时是 120）拿到的是 `sleep 30: error: unknown command or bad value`，
  而且 `set_asleep(false, "serial")` 藏在报错那条路径里 —— **报错顺带把屏叫醒了**。
  别拿 `>sleep` 当"让它熄屏"的工具，要用 `z` 或者真等自动熄屏。这一轮之后 `>status` 里 `"sleep":120` 原样还在。
- **那一趟仍然没跑成**：卡在这台 Mate 60 的热点当前不在空中 ——
  `link.wifi: scan complete, 0 APs found`，`no saved wifi network nearby`（同一次扫描在别的时间点能找到 3 个邻居，
  说明扫描本身好的，只是 `Mate 60` 不在）。`wifi=0` 的时候"熄屏 3 分钟"测的是"没网时会不会打盹"，
  不是要测的那件事。热点开起来、`wifi=1 linked=1` 之后再做这一趟才有意义。
  **（后面这一句成立，但那趟跑完之后结论变了 —— 这块屏撑不到"测的那件事"，见 §15.9。）**

### 15.9 那趟 5 分钟的电池测试跑完了：板子在那一趟里确实重启过（11-09 晚；本节"这块板没有电池"那句已被 §15.10 撤回）

**这一节的硬件判读已经在 §15.10 撤回**：电池是好的、确实在供电。留下来仍然有效的是那两条现象
—— 这一趟里板子**真的重启过**（`naps` 1→0、复位原因 `power on`），以及重启之后不碰屏也能自己连上。
"为什么那一次重启、后面那次没重启"的差别在 §15.10 里重新对过。

热点开了之后我先把基线拉到 `wifi=1 linked=1`（`>stay off` + `>stay on` 踢一下 keeper，
`muse_wifi_apply()` 立刻重新入网），然后跑完「拔 USB 5 分钟 → 插回来」，读到的原始回读：

```
基线（拔之前）：
@stay   on=1 nap=0 naps=1 drops=0 wifi=1
@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0 fails=0    ← 连读三次一样
插回来之后：
@stay   on=1 nap=0 naps=0 drops=0 wifi=1
@power  {"boot":"power on","uptime":76,"battery_boots":0,"started":false,"running":false,
         "battery_pct":[-1,-1],"battery_mv":[0,0], ...}
@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0 fails=0
I (288153) link.heartbeat: hb t=287s ... wifi=up
```

**判读：`naps` 从 1 变回 0 = RAM 被清零 = 这块板重启过。**（这一句仍然成立，是这一节留下的事实。）
当时我接着把它解释成"这块屏没有可用的电池"——**那个解释错了，错在哪见下面第二条的更正**，
正确的对照实验和结论在 §15.10。

- 复位原因是 `ESP_RST_POWERON`（`muse_battery.c:565` 把它印成 `power on`）—— 板子确实断过电。
  不是串口造成的：这一轮所有读数都走 `sercmd_norst.py` / `cap.py`（开线前把 dtr/rts 压成 False），
  而且 `uptime=76` 落在"他插回来"那一刻，不是我读的时候。
- 当时那条"没有电池"的推理（**保留下来当反面教材：读数都对，结论是错的**）：
  `battery_pct:[-1,-1]`、`battery_boots:0` 就断定电量这一路没有数，再引
  `boards/board_waveshare_s3_175c.c:78` 那句 `"PMU unavailable (%s): battery status disabled"`
  说 PMU 读不到。**错在两处**：那句 W 这次开机日志里根本没打出来（打的是 `AXP2101 ready`），
  而 `battery_pct` 只在"电池窗口"里才被采 —— 插着 USB 去读永远是 `[-1,-1]`，它不是"没电池"的证据。
- 真正剩下的问题当时被我读反了：`muse_state.c:239-243` 是
  `on_battery = s_as_if_battery || (!p.usb && p.battery_pct >= 0)`，窗口一开 `battery_pct` 就有数，
  **所以 nap 在这台机器上是会真的发生的**，那一趟之所以没留下读数，是因为板子重启把 RAM 清了，
  不是因为这条路径不存在。

所以那一趟的账要这么记：

- **那一趟什么都没称到** —— 判据要的 `naps` / `drops` 被重启清了 RAM，读到的 0 是"开机以来的 0"。
  当时我据此写的"这块屏没有电池那一档"是错的，对照实验和更正见 §15.10。
- 称到的那条仍然算数：**断电重启之后，没人碰屏，它自己连上了**
  （上面 `@carpet` 那行 `has_state=1`；时间尺度见 §15.3 第 5、6 条，8 秒内 linked）。
- `熄屏后保持联网` 这个开关的现场意义没有被推翻：电池是有的、`on_battery` 会真的成立、
  nap 会真的发生，所以那一趟**还得重跑一次**才算称到。

### 15.10 §15.9 那句"这块板没有电池"撤回；同时称到"拔插电源之后屏自己回得来，但要 ~3 分钟"（11-09 深夜）

改成 10 秒的短拔、**并且让屏一直亮着**，再对着 flash 里那条电池窗口读数看：

```
肉眼：拔掉 USB 屏一直亮，插回来还是亮着
@stay  on=1 nap=0 naps=0 drops=2 wifi=1
@power {"boot":"usb","uptime":206,...,"secs":12,"battery_pct":[100,100],
        "battery_mv":[4121,4090],"screen_off":0.0}
开机日志：I (1066) muse_pmu: AXP2101 ready (hardware PWR hold-off 10 s, COMMON_CFG 34, PWROFF_EN 06, last off 20)
```

**电池是好的，在供电。** 三条互相独立的读数凑在一起才算数：PMU 上线（不是 §15.9 引的那句
`PMU unavailable`，那句这次根本没打出来）、12 秒窗口里 `4121 → 4090 mV` 的**带载压降**
（`muse_pmu.c:212-217` 是寄存器实读，编不出这种数）、以及 `uptime` 从 58 涨到 206 没归零
—— 那一趟 10 秒里板子**没有重启**。所以 §15.9 的判读作废，`on_battery` 在这台机器上是能真的成立的
（`muse_state.c:239-243` 要的那句 `battery_pct >= 0`，窗口一开就有数）。

那 §15.9 那趟 5 分钟是真的重启过（`naps` 1→0、复位原因 `power on`）。两次只剩一个差别：
**那一次屏熄了 3 分钟，这一次屏一直亮。** 于是新的怀疑是「电池 + 熄屏 = 走进 light sleep 那条路会重启」，
`@power` 的 locks 里那条 `{"name":"usb_serial_jtag","type":"NO_LIGHT_SLEEP","held":100.0}`
给了它一个可能的形状：**插着 USB 时这条锁 100% 压着不让睡，拔掉之后就没人压了。**
这只是假设，**没称**，别当结论用。

顺带这一趟称到一条现场用得上的正面证据——**拔插电源之后屏自己回得来，全程没碰屏**：

```
W (345908) link.muse: screen link dialed 12 times with none through (ESP_OK errno=119 http=0); rejoining wifi
W (345933) link.wifi: station disconnected reason=8 rssi=-37
I (346930) link.wifi: starting wifi scan (ch=12, one network)...
I (347354) link.wifi: got IP 192.168.43.13
I (377951) screen_link: ws 接入 ws://192.168.43.1:8443
I (377954) carpet: linked, contract v3
@stay   on=1 nap=0 naps=0 drops=3 wifi=1
@carpet wifi=1 linked=1 contract=v3 gap_s=1 has_state=1 wd=0 fails=0     ← 连读两次一样
```

判读三条：

- 断的方向是 `errno=119`（路由没了，不是 App 端口没人听），升级重连那一条按设计接住了它，
  重新入网 3 秒内拿到 IP，然后 `linked, contract v3`、`has_state=1`。
- **代价是时间**：从升级触发（345.9 s）到拨通（377.95 s）32 秒，而升级本身要等
  `dialed 12 times` 才动手 —— 现场如果中途拔插了电源，App 侧要有"大概三分钟自己就好"的预期。
  嫌慢的话有现成的旋钮：让 `errno=119` 这种"路由都没有"的失败立刻升级，别攒够 12 次。
- `drops` 从 2 涨到 3 就是这一次升级重连的断开 —— §15.7 那台仪器在真实事件上动过，仍然有效；
  `naps` 全程 0（屏亮着，本来也轮不到 nap）。

### 15.11 电池 + 熄屏 = 整机会断电，两次都这样（11-09 深夜，重跑那一趟的结果）

按 §15.10 的判据把那一趟原样重跑（拔 USB，让它自己熄屏，5 分钟以上，再插回来）：

```
@stay   on=1 nap=0 naps=0 drops=0 wifi=1
@power  {"boot":"power on","uptime":56,"battery_boots":0,"started":false,"running":false,
         "secs":0,"battery_pct":[-1,-1],"battery_mv":[0,0],"screen_off":0.0}
@power.saved {"boot":"usb","uptime":150,...,"secs":12,"battery_pct":[100,100],
              "battery_mv":[4121,4090],"screen_off":0.0}      ← 还是上一轮那条 12 秒的旧记录
@carpet wifi=1 linked=1 contract=v3 gap_s=2 has_state=1 wd=0 fails=0   ← 插回来自己恢复了
下一次开机（我复位抓日志）：
rst:0x15 (USB_UART_CHIP_RESET),boot:0x2b (SPI_FAST_FLASH_BOOT)
I (1066) muse_pmu: AXP2101 ready (hardware PWR hold-off 10 s, COMMON_CFG 34, PWROFF_EN 06, last off 00)
```

判读，一条一条对着 §15.10 那台仪器看：

- **这一趟又是重启**：新会话复位原因 `power on`、`uptime` 只有 56 秒、`naps` / `drops` 全被清零。
- **而且不是"插回来那一下抖的"**：`@power.saved` 里那条电池窗口记录**还是上一轮 12 秒的旧数**
  （`secs:12`、`4121 → 4090`）。窗口是"合上时才写"的（`USB back after N s on battery` 那一条），
  它没被更新 = **这一趟的电池窗口根本没合上，板子在窗口里就死了**。
  再对上 §15.10 那次"插回来 uptime 从 58 涨到 206 没归零"——插拔本身不重启板子，是板子先没了。
- **和 §15.10 那次唯一的差别就是熄屏**：那次按我说的让屏一直亮（`screen_off:0.0`），12 秒好过；
  这次让屏自己熄，5 分钟撑不住。**两次重复，方向一致。**
- 所以 §15.4 第 1 条那件"nap 称没称到"的事，现在排在后面：这台机器在电池上**到不了**
  "熄屏 3 分钟还活着"那一步。开关本身的效果另说，`>nap` 那半条（§15.7）是称过的。
- 有一条我看到了但不敢用：PMU 那句 `last off` 从上一轮的 `20` 变成这一轮的 `00`
  （`REG_PWROFF_STATUS 0x21`，`muse_pmu.c:28` 注释只写"what powered it off last"）。
  **我没有这张寄存器的位表，0x20 / 0x00 各是什么不认**，所以它只是线索，不是结论；
  真要认，要么拿到 AXP2101 那份定义，要么在断电之前把状态自己写进 NVS。

现场可用的结论（已经称到的那半边）：**充电宝 / USB 一直在，就没有这一档风险**——
§15.1 那一轮之后所有插着 USB 的熄屏、nap、拨号、断网恢复都跑过了，没出现过重启。

### 15.8 同步状态

`muse-carpet.patch` 这一轮（11-09 晚，§15.7 那两个 RAM 计数）重打过：
**22 个文件、3848 行新增 / 86 行删，patch 6,825 行**
（相对上一版只动了 `muse_input.c`：168 → 190）。
在 baseline `cdc169d` 干净 worktree 上 `git apply --check` 过、`git apply` 过，
字库解出来 154,528 字节一致；`diff --strip-trailing-cr` 这轮对 `muse_input.c` /
`muse_glue.c` / `muse_settings_ui.c` / `carpet_lang_zh.c` / `carpet_link.c` /
`sdkconfig.carpet` 六个文件比过，**内容和工作树一致**
（`diff -rq` 报的那些 boards/*.c 差异全是 autocrlf 的行尾，不是内容 —— 那些文件根本没进 patch）。
`components/screen_link/` 不进 patch，走 §3 那条 `cp -r`；这一轮把
`screen_link.c` 和 `include/screen_link.h` 从工作树拷回了本仓库。
