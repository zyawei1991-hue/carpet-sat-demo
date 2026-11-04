# 圆屏固件侧：Screen-Link 展示端

这块目录是**展示端固件**。它分成两层：`components/screen_link/` 是链路层（怎么连上手机 App、
怎么把契约那五种帧发对/收对），`app/` 是**真正烧进板子的那个工程**（板级驱动 + 画面）。

> **2026-10-31 改了架构，读旧文档的人注意**：这一层原来写的是
> 「不含驱动、不含 UI，那些由小智官方固件给」。现在不是了 —— 驱动和 UI 走微雪官方 BSP
> （`waveshare/esp32_s3_touch_amoled_1_75`）+ LVGL 9.4，自己起工程。
> 理由：小智自带一整套云协议和设备侧逻辑，跟这份契约 v2 会变成两套话；而这块板的厂商 BSP
> 已经把面板、触摸、图片解码都补齐了，借它比借小智省事。
> 小智留下的两样东西还在用：视觉/表情那套做法的参考，以及录音→ASR 那一路（还没接，见 §10）。

打通的判据只有三条，全在链路层，不需要等 UI：

1. 连上后 1Hz 有画面；
2. 断网 3 秒 → 链路图标变灰、**保留最后一帧** + "链路中断 mm:ss"（不许黑屏）；
3. 断网 10 秒 → 主动断开，按 1/2/5/10/30s 退避重连。

---

## 0. 这块板子的实物参数（全部对着厂商代码和真硬件核过，不是抄文档）

| 项 | 值 | 依据 |
|---|---|---|
| 主控 | ESP32-S3R8（QFN56 rev v0.2），16MB quad flash，8MB **八线** PSRAM | `esptool chip_id` 读到 + 厂商 `sdkconfig.defaults` 里 `SPIRAM_MODE_OCT` |
| 屏 | 1.75" 圆形 AMOLED **466×466**，QSPI 总线 | 微雪产品页 + BSP 依赖 `esp_lcd_co5300` |
| 显示驱动 IC | **CO5300** | `managed_components/waveshare__esp32_s3_touch_amoled_1_75` 的初始化命令表 |
| 触摸 | **CST9217**，且 `bsp_display_start()` 内部就把 LVGL indev 建好了 | BSP 依赖 `waveshare__esp_lcd_touch_cst9217` + 头文件 §299 那句 note |
| 电源 | 板上有 AXP2101（I2C `0x34`）。**点屏不需要初始化它**，但读电量需要：`app/main/axp2101.c` 只做只读遥测，挂在 BSP 那条 I2C 上（GPIO14/15），不另起总线 | 厂商 01_AXP2101 是独立例程；BSP 源码 grep 无 axp |
| 电池温度线 | 板上 BAT 座是 MX1.25 **2 芯，没有 NTC** → `ADC_CTRL` 的 TS 位**必须关掉**，否则接上电池后充电走异常分支，现象是"充不进电" | 厂商 `port_axp2101.cpp` 专门为这条留了 `disableTSPinMeasure()` |
| 扩展口 | 8Pin **排母**（黑色方孔母座，2.54mm），丝印 `IO18 IO17 IO16 RXD TXD 3V3 GND VBUS` —— 要往外接线得买**公头**杜邦线。厂商英文手册把它叫 "header"，容易读成排针，看图为准 | 官网产品图 `photo/development-board/ESP32-S3-Touch-AMOLED-1.75/-3.jpg` 背面俯视，母座方孔和丝印都拍清了 |
| 电池/喇叭座 | 板上两个白色 2P 母座，丝印 `BAT` / `SPK`。**SPK 厂商文档写明 MX1.25 2Pin**；BAT 的型号文档没写，照片上和 SPK 是同一种 → 买 MX1.25 2P 端子线，**到手先对着看再轻插，别硬怼 PH2.0**（间距不对会顶坏针）。极性必须核对，插反直接烧 | `README_ZH.md:54`、同一张背面图 |
| USB | USB-Serial-JTAG，本机 `COM8`；MAC `b8:1f:3f:d7:60:d0` | 刷机日志 |
| 白送的依赖 | `esp_new_jpeg` / `libpng` / `qoi` / `esp_mmap_assets` / `esp_lv_decoder` / `freetype` / `esp_codec_dev` / `button` | 一次 `idf.py build` 自动拉下来的清单 |

那串"白送的依赖"值得停一下，它直接决定了后面几件事的做法：

- **中文字体**：两层，别混。兜底那层是 LVGL 9.4 自带的思源黑体 SC 位图（`LV_FONT_SOURCE_HAN_SANS_SC_16_CJK`），
  开一个 Kconfig 就进固件；但它**只有 1456 个码点**，"卫/救/景/湖/锁/窗"这些业务字不在里面，
  缺的会画成方块。真正用的那层是 `app/spiffs_image/carpet-noto.ttf`（GB2312 一二级 + 仓库里出现过的
  每个汉字，7405 码点，2.18MB），开机由 `fonts_load()` 读进 PSRAM、`lv_tiny_ttf_create_data` 出 16/24 两档。
  重出这份文件：`app/assets/make-font.py`（依赖和命令写在文件顶上）。
  契约里 `fence.name` 是"西湖景区"这种中文，用默认 Montserrat 会全画成方块，而且**串口一点错都不报** ——
  只能靠看屏发现，所以 `fonts_load()` 每一条失败路径都有 `ESP_LOGE`。
- **图片展示**：JPEG/PNG/QOI 解码 + `esp_mmap_assets` 资源映射都在依赖树里，
  所以"屏上出图"缺的**只是搬运**（契约帧是 ≤4KB 的 JSON TEXT，二进制帧现在会被组件的 op_code 过滤丢掉），
  不缺解码能力。搬运方案单独定：要么屏本地存图 + 手机只发图号，要么加一条分片二进制通道。
- **表情动画**：LVGL 9.4 里带了 ThorVG（Lottie 矢量动画），加上 `esp_lv_decoder` 的动图解码，
  表情那一路有现成地基。
- **麦克风→ASR**：`esp_codec_dev` 在依赖树里（板上是双麦阵列），但这条还没接，见 §10。

## 0.1 你需要什么

- 一台能插 USB 的电脑（**云电脑不行**：没有 USB 直通，烧录和串口都过不去）。
- ESP-IDF **v5.5**（本目录的 `app/` 在 v5.5.2 上编过；厂商例程那份 defaults 是 5.4 生成的，
  它的 LVGL/依赖版本线跟 5.5 更顺，而且换过 IDF 大版本要 `rm -rf build sdkconfig` 重来）。
- 一根能传数据的 USB-C 线（微雪那块板供电和刷写走同一个口；BOOT 键在壳子上，见 §4）。
- 手机一台（第 5 步才用得上，前四步不需要）。

## 1. 装工具链

Windows 上用最省事的那条路：

```powershell
# 下载 eim（ESP-IDF Installation Manager）后：
eim express
# 或者手动：
git clone -b v5.5 --recursive https://github.com/espressif/esp-idf.git C:\esp\esp-idf
C:\esp\esp-idf\install.ps1 esp32s3
```

每个新终端都要先激活：

```powershell
. C:\esp\esp-idf\export.ps1
idf.py --version    # 期望 v5.5.x
```

## 2. 先把官方固件刷亮（不要带我们的东西）

```powershell
git clone --recursive https://github.com/78/xiaozhi-esp32.git C:\esp\xiaozhi
cd C:\esp\xiaozhi
idf.py set-target esp32s3
idf.py -D BOARD_TYPE=waveshare-s3-touch-amoled-1.75 menuconfig   # 或直接改顶层 CMakeLists 里的 BOARD_TYPE
idf.py build
idf.py -p COMx flash monitor
```

`COMx` 在设备管理器里看（刷的时候如果端口占用报错，按住 BOOT 再插一次线进下载模式）。

**这一步的验收**：屏亮、触摸有反应、连上 Wi-Fi、能跟小智说完一句话。
没到这一步就先别往下走 —— CO5300 QSPI、8MB Octal PSRAM、AXP2101 电源轨这几个坑
和我们的协议毫无关系，混在一起调会互相甩锅。

## 3. 放我们的组件

```powershell
# 在仓库根目录（carpet-sat-demo/）执行：
xcopy /E /I firmware\screen\components\screen_link C:\esp\xiaozhi\components\screen_link
```

小智顶层已经会收 `components/**`，不用改 `CMakeLists.txt`。
`idf_component.yml` 里那条 `esp_websocket_client` 依赖**不会报重复组件** ——
小智自己没依赖它（它的连接走 `protocols/` 里那套）。构建时组件管理器会现拉一份，
所以要能访问 `components.espressif.com`；**别把那一段删掉**，删了反而找不到组件。

## 4. ~~两处必须核对~~ —— 已经对过头文件了，代码改好，不用再猜

对着 `espressif/esp_websocket_client` 的 1.5.0 / 1.6.1 / 1.7.0 / 1.8.0 四份
`include/esp_websocket_client.h` 逐个核过，四份一致，且**原来说的两种签名都不对**：

1. **事件回调是四个参数**，收的是 IDF 通用 `esp_event_handler_t`；
   `esp_websocket_event_handler_t` / `esp_websocket_event_handle_t` 这两个类型**不存在**：
   ```c
   static void ws_handler(void *arg, esp_event_base_t base, int32_t event_id, void *event_data)
   {
       esp_websocket_event_data_t *evt = (esp_websocket_event_data_t *)event_data;
       switch (event_id) { /* evt->data_ptr / data_len / payload_len */ }
   }
   ```
2. **config 里 `auto_reconnect` 这个字段不存在**，写了编不过；`event_handler` 也不在配置结构体里。
   存在的：`uri / buffer_size / task_stack / task_prio / disable_auto_reconnect /
   reconnect_timeout_ms / network_timeout_ms / user_context`。
   `disable_auto_reconnect = true` 一定留（1/2/5/10/30s 退避是我们自己走的）。

两处都已经在 `screen_link.c` 里改好了，细节和依据见 `HANDOFF.md` §4。
`REQUIRES ... esp_timer` 在 5.5 上是公开组件，不用动。

另一条会绊住第 2 步的：小智 `main` 现在写的 IDF 下限是 **`>=6.0.1`**，不再是 5.5；
按 tag 起工程的人以那个 tag 的 `idf_component.yml` 为准。

改完 `idf.py build`，剩下的是编译器的事。

## 5. 先用"假手机"验固件（这一步不要连真手机）

两个新变量一起调，出问题分不清是谁的。仓库里有一个按契约 v2 推 state 的假手机服务端：

```powershell
# 在 carpet-sat-demo/ 下（这台机器要装 JDK 17）
.\gradlew.bat :simulator:run --args="--hold 9"
```

> `--hold` 是**步号**不是秒数。传别的值会让进程活着但 1Hz 停掉 —— 这个坑我踩过。

然后屏连的是**跑 simulator 那台电脑的局域网地址**，改 Kconfig：

```powershell
idf.py menuconfig   # Screen-Link 展示端 → 手机 App 的接入点 → ws://192.168.1.23:8443
```

**Windows 防火墙会拦 8443 入站**，这是这一步最常见的"固件没问题但连不上"：

```powershell
netsh advfirewall firewall add rule name="screen-link-8443" dir=in action=allow protocol=TCP localport=8443
```

三条判据在假手机上都能过，再往下走。

## 6. 真机联调

1. **手机自己开热点给屏连**（别用商场地场、别用公共 Wi-Fi —— AP 隔离会让屏根本摸不到那个端口）。
   手机热点下手机自己通常是 `192.168.43.1`，所以 Kconfig 里那个默认值就是给这个场景的。
2. App 首页那张「圆屏」卡：打开**允许圆屏接入** → 把屏的 `screen_id` 加进白名单 → 屏上填接入点。
3. 顺序很重要：**先在 App 上放开开关再让屏连**。没放开时任何连接进来立刻被 close。

## 6.1 怎么在电脑上看见屏上真实画的东西

显示屏是**只写**的：像素 flush 进 QSPI 就再也拿不回来。所以串口能告诉你"没报错"，
却答不了"56px 的数字压没压住圆屏中线""这一帧是不是整片黑"。上一版的坑就是证据：
每一帧 `esp_lcd_panel_draw_bitmap` 都返回 `ESP_ERR_NO_MEM`，而日志和堆看着全都正常。

这一条通道把缺口补上，代价只有 flash 里几 KB：

1. 本机 `sdkconfig` 里 `CONFIG_SCREEN_SHOT_PORT=8444`（**默认 0，也就是不开**；
   真值只写本机，和 Wi-Fi 密码同一个道理 —— 仓库是公开的）。
   非 0 时固件在拿到 IP 之后起一个 httpd，`GET http://<屏的IP>:8444/shot.bmp` 返回整屏 24 位 BMP。
   实现是 `main/screen_shot.c`：在 LVGL 的锁里 `lv_snapshot_take` 出一块 434KB 的 PSRAM 快照，
   **出了锁再慢慢发** —— 攥着锁发 65 万字节等于把屏冻住几百毫秒。
2. PC 侧转成 PNG（这台机器的 python 装不上 Pillow，所以只用标准库手写 BMP 解码 + PNG 编码）：

   ```bash
   /d/dev/python311/python.exe firmware/screen/tools/screen_shot.py grab --out shots/real/now.png
   ```

   不给 `--ip` 时它去 `netstat` 里找连到本机 8443 的那个对端 —— 屏是 ws 客户端，
   它的地址就在那条已建立连接里，省得每次手抄、也免得屏换了租约。
   输出里那个 `lit_pct` 是抽样出来的亮像素比例，整片黑会掉到 0。
3. 一次跑完所有场面（电脑当手机 + 真屏 + 每步抓一张 + 时间线）：

   ```bash
   simulator/linktest.sh          # 11 步各一张；也可以 simulator/linktest.sh 5 6 11
   ```

   它每步**单独起一次**驱动器（`--hold N` 由驱动器自己走到那一步定格），而不是连跑之后
   这边 sleep 猜时间 —— 猜出来的抓帧只会给你"差不多那一步"。产物在 `shots/real/`：
   `stepNN.png` 是屏，`stepNN.log` 里那行 `屏=card/emotion｜依据` 是手机这一拍决定画什么，
   `stepNN.jsonl` 是那一步真实发出去的帧。画错了先分清是手机没发对还是屏画错。

   **跑之前先挂一个驱动器别停**：脚本第一步就探"有没有设备连到本机 8443"，45 秒探不到整轮直接退出，
   而屏掉线之后走的是退避阶梯、重连要几十秒。所以先
   `cmd /c start /B simulator/build/install/simulator/bin/simulator.bat --hold 1`，
   等 `screen_shot.py wait` 能返回 IP 了再跑 `linktest.sh`（脚本每步会自己杀掉并重起驱动器，不冲突）。

两条约束：**这条路没有鉴权**，同网段任何设备都能读走整屏 —— 只在调试机上开，
演示出厂前把端口改回 0 重新 app-flash；抓屏那一下会让 LVGL 重画一整帧，
屏会明显停一瞬，属于预期，不是死机。

## 7. 排错表

App 侧拒绝时是用 **close reason 文本**表达的，固件 monitor 里能看到：

| 看到 | 意思 | 怎么办 |
|---|---|---|
| `demo lock` | App 上「允许圆屏接入」没开 | 开它，然后屏会自动退避重连 |
| `unknown screen` | `screen_id` 不在白名单 | 核对 Kconfig 里那条和 App 白名单**逐字符**一致 |
| `bad contract` | 帧解不开 | 看 monitor 里 `契约错：` 那行打印的原文，字段名对着 `protocol/screen-link.schema.json` 核 |
| 连上但一帧 state 都没有 | App 那边 `accepting` 中途被关，或屏没发 hello | 确认 `send_hello` 有日志；App「我的 → 我的设备」里那台屏应该显示在线 |
| 屏说"已连上"但问一句没答 | 连到的是只会 v1 的老 App | 契约的协商不回读版本，屏这边只能乐观认为谈成了；换最新 APK |
| 重连后头几句问了没反应 | `turn_id` 撞了旧 id | 必须是 `s-<开机随机段>-<自增>`，纯自增重启后会从头编号，被手机端按重复吃掉 |
| `grab` 报"找不到屏" | 屏还没连上这台电脑的 8443（netstat 里没有对端） | 先看串口有没有"拿到地址 / 连上接入点"；确认屏连的 URL 是这台机器的地址（不是手机 192.168.43.1）；实在这就 `--ip` 手填 |
| 对端有，但 `GET /shot.bmp` 连不上 | 固件这版 `CONFIG_SCREEN_SHOT_PORT` 还是 0（量产默认） | 本机 sdkconfig 填端口，重新 app-flash；改 `sdkconfig.defaults` 没用，那条**故意不进仓库** |

## 8. 契约速查（详见 `protocol/screen-link.md`）

- 手机是**服务端**，屏是**客户端**；端口固定 `8443`；**明文 ws，不是 wss**。
- 首帧必须 `hello`，带 `screen_id` / `fw` / `dock_type` / `protocols:[1,2]`；读到电量时再带 `battery_mv`。
  `battery_mv` **一次连接只报一次**（`send_hello` 里现读），中途换电池不会补发；没读到就整项不发，
  千万别发 0 —— 手机那边 `null` 显示「外接供电」，`0` 会被当成「电池空了」。
  协商看 `protocols`，**不看帧上的 `v`**；省略 `protocols` 等于按 v1 待。
- `state` 1Hz，它本身就是心跳（没有独立心跳帧）。
- `event` 必须回 `ack`（带 `for_event_id`），App 3 秒收不到重发一次；屏按 `event_id` 去重，留最近 32 个。
  过期判定也在组件里：`ttl_ms` 到点的**不回调**、但 `ack` 照回，回调给的是**还剩**多少毫秒
  （`on_event(event, id, left_ms)`，0 = 帧里没给 ttl）。app 侧把 `left_ms` 当横幅的停留时长。
- `dialog` 只对谈到 v2 的连接发。`turn_id` 由**起头那一方**生成。
  `dir:down` 只补记录、**不再触发答题**（少这条两边会无限对答）。
- 重启/换底座前发一条 `bye`，否则台账上那台屏要挂几十秒才变离线。

## 9. 词表闸（`screen_link_redline.c`）

**任何一句话上屏之前都要过 `screen_link_guard_copy()`**，包括小智官方云直接画到屏上的那一路 ——
手机的闸管不到那条路，而现场冒出一句"救援已在路上"是不可挽回的错。
词表和手机端 `SosCopy.BANNED` + `EphemerisCopy.FORBIDDEN` 同源，改任何一边都要跑文件头那条 grep 对一遍。

## 10. 还没做（别在这里等）

- **录音 → ASR 那一段**：小智官方云那一路已经能用，但要把它识别出的文字接到 `screen_app_ask()`。
- **UI**：五张卡已经在真屏上跑，排版是「一个 hero（数字 56px / 中文 32px）+ 两行 22px + 点卡面展开的明细层」，
  SOS 那颗键的触摸上行也通了（长按 1.5s → `screen_link_send_command("sos_press", NULL)`）。
  两条硬约束别破：**画哪张卡只由手机的 `state.ui.card` 定**，点屏永远只切明细层；
  卡上面还可以叠一条**事件横幅**（`on_event` → 两张固定中文表，`EV_UNKNOWN` 兜底），
  它是叠层不是第六张卡，也不改"画哪张卡"的归属；开机时 `banner_selfcheck()` 会逐格跑词表闸。
  上行只能走 `t=command` —— 拿 `send_event` 发 `sos_press` 会让手机解不出帧、静默关连接（§4⑥）。
  这块板没有用户实体按键（`BSP_CAPS_BUTTONS 0`），所以"按键没反应"是没有那个键，不是故障。
  可以先拿 `simulator/screen.html` 当视觉稿 —— 它是同一份契约的浏览器冒充件，
  22 条断言里 11 条走的就是对话面。
- **屏上录音**：板子有麦（`BSP_CAPS_AUDIO_MIC 1`）但这份固件没接采集，`screen_link_send_dialog_up()` 没有调用者。
  所以对话卡上那句写的是"屏还没接麦：话在手机上说"，别改回"屏上按住说话" —— 那是个观众照着戳、什么都不发生的承诺。
- **配对**：现在 `screen_id` 编译期写死，演示现场改不了。要不要换一次性配对码，
  等 TLS 那条决策（D8）一起定。
