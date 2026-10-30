# 圆屏固件侧：Screen-Link 展示端

这块目录是**展示端固件**的链路层。它不含驱动、不含 UI、不含小智本体 ——
那些由小智官方固件（板名 `waveshare-s3-touch-amoled-1.75`）给。
这里只管一件事：**这块屏怎么连上手机 App，怎么把契约那五种帧发对/收对。**

打通的判据只有三条，全在链路层，不需要等 UI：

1. 连上后 1Hz 有画面；
2. 断网 3 秒 → 链路图标变灰、**保留最后一帧** + "链路中断 mm:ss"（不许黑屏）；
3. 断网 10 秒 → 主动断开，按 1/2/5/10/30s 退避重连。

---

## 0. 你需要什么

- 一台能插 USB 的电脑（**云电脑不行**：没有 USB 直通，烧录和串口都过不去）。
- ESP-IDF **v5.5**（小智当前要求，5.3/5.4 编不过它的 board 目录）。
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
`idf_component.yml` 里那条 `esp_websocket_client` 依赖**大概率会报重复组件** ——
小智本来就依赖它。报了就把那个文件删掉，组件会直接复用顶层解析出来的那份。

## 4. 两处必须对着本地头文件核对（我在这台机器上编不了，这两处是唯一没法自证的地方）

```powershell
notepad C:\esp\xiaozhi\managed_components\espressif__esp_websocket_client\include\esp_websocket_client.h
```

1. **事件回调签名**。我按三参数写的：
   `void ws_handler(void *arg, esp_websocket_event_handle_t *evt, esp_websocket_event_data_t *data)`，
   数据从 `evt->data_ptr / data_len / payload_len` 取。
   如果你这份头文件里是两参数（`data` 合并在 `evt` 里），把签名改掉、字段名不变即可。
2. **`esp_websocket_client_config_t` 的字段名**。我用到的：
   `uri / buffer_size / task_stack / disable_auto_reconnect / auto_reconnect / reconnect_timeout_ms`。
   哪个字段不存在就删掉那一行，`disable_auto_reconnect` 一定要留（退避阶梯是我们自己走的）。

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

## 8. 契约速查（详见 `protocol/screen-link.md`）

- 手机是**服务端**，屏是**客户端**；端口固定 `8443`；**明文 ws，不是 wss**。
- 首帧必须 `hello`，带 `screen_id` / `fw` / `dock_type` / `protocols:[1,2]`。
  协商看 `protocols`，**不看帧上的 `v`**；省略 `protocols` 等于按 v1 待。
- `state` 1Hz，它本身就是心跳（没有独立心跳帧）。
- `event` 必须回 `ack`（带 `for_event_id`），App 3 秒收不到重发一次；屏按 `event_id` 去重，留最近 32 个。
- `dialog` 只对谈到 v2 的连接发。`turn_id` 由**起头那一方**生成。
  `dir:down` 只补记录、**不再触发答题**（少这条两边会无限对答）。
- 重启/换底座前发一条 `bye`，否则台账上那台屏要挂几十秒才变离线。

## 9. 词表闸（`screen_link_redline.c`）

**任何一句话上屏之前都要过 `screen_link_guard_copy()`**，包括小智官方云直接画到屏上的那一路 ——
手机的闸管不到那条路，而现场冒出一句"救援已在路上"是不可挽回的错。
词表和手机端 `SosCopy.BANNED` + `EphemerisCopy.FORBIDDEN` 同源，改任何一边都要跑文件头那条 grep 对一遍。

## 10. 还没做（别在这里等）

- **录音 → ASR 那一段**：小智官方云那一路已经能用，但要把它识别出的文字接到 `screen_app_ask()`。
- **UI**：状态盘、对话列表、SOS 那颗键的触摸上行，都还是空的。
  可以先拿 `simulator/screen.html` 当视觉稿 —— 它是同一份契约的浏览器冒充件，
  22 条断言里 11 条走的就是对话面。
- **配对**：现在 `screen_id` 编译期写死，演示现场改不了。要不要换一次性配对码，
  等 TLS 那条决策（D8）一起定。
