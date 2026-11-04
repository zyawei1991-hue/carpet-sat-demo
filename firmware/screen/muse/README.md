# 圆屏换底座到 Muse Gadget SDK：车联页与 App 侧要接的东西

这份是给**另一台电脑**看的。你要研究的是"功能整合进我们自己的 App"，所以正文在第 5 节；
前面几节只到"能把这一版重出来、能刷上板"为止。

基准：本文件与 `muse-carpet.patch` 记的是 2026-11-04 的状态。

---

## 1. 为什么换底座（一句话，不展开成因）

目标是 **AI 对话在圆屏本机上跑**（会听、会说、动态表情）。我们自己那套 `firmware/screen/app/`
里这三件事全得从零写；Meta 的 Muse Gadget SDK 已经把
"麦克风 → 上行 → 云端答句 → TTS → 口型/表情动画" 这条链做完了，而且官方就带
Waveshare ESP32-S3-Touch-AMOLED-1.75 的板级支持（跟我们手上这块是同一块，屏 466×466 CO5300 QSPI、
CST9217 触摸、ES8311+NS4150B、16MB flash + 8MB octal PSRAM）。

所以路线是**用 Muse 当固件底座，把 Screen-Link 客户端搬进去当手机那一路的入口**，不是二选一。
老的 `app/` 那套继续留着当对照和回滚路径（它自己的构建与刷写在 `../HANDOFF.md` 里，
板子插回来就是另一份 build 目录、另一套 defaults）。

## 2. 这棵树在哪、能不能推

- 树：`D:\dev\projects\muse-gadget-sdk-main`，分支 `muse-carpet`，baseline `cdc169d`
  （那条提交就是官方 zip 的快照，**里面一个我们的字都没有**）。
- **没有 remote，也永远不要加。** 这棵树混着第三方 BSP 预编译 blob 和客户材料；
  要往任何地方同步只走 `muse-carpet.patch` 这一条口（见下一节）。
- 我们的全部改动 = 11 个文件、651 行新增 / 19 行删（`muse-carpet.patch`，893 行）：
  ```
  esp32/components/muse/CMakeLists.txt       |  10 +-
  esp32/components/muse/Kconfig              |  14 +
  esp32/components/muse/carpet/carpet_link.c | 254 +   (新)
  esp32/components/muse/carpet/carpet_link.h |  50 +   (新)
  esp32/components/muse/carpet/carpet_page.c | 167 +   (新)
  esp32/components/muse/carpet/carpet_page.h |  21 +   (新)
  esp32/components/muse/muse_input.c         |  17 +   (>carpet 回读 + bench 命令 ui=carpet)
  esp32/components/muse/muse_ui.c            | 103 +-
  esp32/components/muse/muse_ui.h            |   5 +
  esp32/devices/sdkconfig.carpet             |  20 +   (新)
  esp32/main/muse_glue.c                     |   9 +
  ```
  `esp32/components/screen_link/` **不在 patch 里** —— 它就是把本仓库
  `firmware/screen/components/screen_link/` 整目录复制过去，只换一个 `CMakeLists.txt`（原因见 §4），
  复制命令在 §3。

`CONFIG_MUSE_CARPET_CARDS=n` 时行为与上游逐条等价（圆点偏移公式、`settings` 判定都逐式对过），
所以这一版可以随时退回"就是官方固件"。

## 3. 从零把这版重出来

前提：ESP-IDF **v6.0.1**（Muse 要 6.0；我们原来那套用 5.5.2，两份在这台机器上是共存的，
`D:\dev\esp\6.0.1\esp-idf-v6.0.1`）。Muse 树自己 `.gitignore` 掉了 `build*/`、`managed_components/`、`sdkconfig`。

```bash
# 1) 解 zip 得到上游树，做个 baseline 提交，然后打补丁
git apply muse-carpet.patch                       # 冲突就 git apply -3

# 2) 搬 Screen-Link 客户端组件
cp -r firmware/screen/components/screen_link  <muse>/esp32/components/screen_link
rm <muse>/esp32/components/screen_link/idf_component.yml
#    并用下面 §4 那份 CMakeLists.txt 覆盖它自带的（REQUIRES 写法不一样）

# 3) 配置 + 编译（四层 defaults：我们的那份排在最后）
idf.py -B build-muse-s3-175-carpet -DIDF_TARGET=esp32s3 \
    -DSDKCONFIG=build-muse-s3-175-carpet/sdkconfig \
    -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;devices/sdkconfig.muse;devices/sdkconfig.muse-waveshare-s3-175;devices/sdkconfig.carpet" \
    reconfigure
idf.py -B build-muse-s3-175-carpet ... build      # 同一串参数，子命令换 build

# 4) 只刷 app 分区（这一版没动分区表，别全片刷）
idf.py -B build-muse-s3-175-carpet ... -p COM8 app-flash
```

板子插这台机器时是 **COM8**（就是那颗 USB-Serial-JTAG，同时也是第二控制台）。

**两个必须照做的步骤，跳了会静默：**
- `reconfigure`：新增 `components/` 目录**不是 ninja 的监视输入**，不 reconfigure 的现象是
  "编译成功"但 `esp-idf/screen_link/` 根本不存在、日志里零句提到它。
- 判成败只看日志里的 `Project build complete`，**别看包装脚本的退出码**
  （`idf.py` 外面套一层 PowerShell/grep 时，`grep -c` 命中 0 就是 exit 1，会把成功的构建报成失败）。

## 4. v6.0.1 的三个坑（都是这轮实测踩出来的，不是文档里读来的）

1. **6.0.1 删掉了公共组件 `json`**，cJSON 改由 managed component `espressif/cjson`（1.7.19~2）提供。
   所以搬进来的那份 `CMakeLists.txt` 里 REQUIRES 必须写**带命名空间**的组件名：
   ```cmake
   idf_component_register(
       SRCS "screen_link.c" "screen_link_redline.c"
       INCLUDE_DIRS "include"
       REQUIRES espressif__esp_websocket_client espressif__cjson esp_timer
   )
   ```
   （原版在 IDF 5.5.2 那边写的是 `json` + `esp_websocket_client`，两份**不共用一个 CMakeLists**。）
2. **顶层把 `esp_websocket_client` 锁在 1.2.3**。组件自带的 `idf_component.yml` 会再声明 `^1.5.0`，
   触发一次依赖重新解析（这台机器连不上组件仓库时是死路），所以那个 yml 直接删了 ——
   `screen_link.c` 用到的 API 在 1.2.3 里逐个 grep 验过，全都有。
3. 每次 reconfigure 都会重跑 component manager、**重新下载 `managed_components/`**（含 LVGL）。
   所以改代码用 `build`，只有加/删组件目录才用 `reconfigure`。

## 5. ★ App 侧要接什么（这一节是给另一台电脑的正文）

### 5.1 板子上现在的样子

三块 tile：面（0,0）、设置（右滑到 1,0）、**车联页（上滑到 0,1）**。

为什么不是左滑：横向那一轴已经被占了 —— 设置页自己的子页**也用横滑**，
`muse_ui_set_swipe_enabled(false)` 就是在子页里把 tile 滑动关掉好让手势归子页；
再挂一块横向 tile 会和它抢同一个轴。竖向原来没有任何竞争者，`LV_DIR_TOP` 是空的。
车联页的 `dir` 只给 `TOP`：从那儿往左右都是"面"的后面，不该能滑过去。

车联页现在画 5 行：链路 / 卫星 / 对话 / 表情 / 呼救。**只有第一行接了真状态**，
其余四行是占位（`—`）。

### 5.2 一条硬的接线规则

`screen_link` 的回调**全在链路任务里跑**。`on_state` 里阻塞超过 10 秒，组件会认为链路死了、
自己断开重连。而 Muse 的 UI 是**拉取式**的：只有一个 LVGL 定时器（`frame_tick` → `update_chrome`，
0.25 秒一档）读状态并做差分上屏，别的任务一律不许碰 LVGL。

所以两边接法只有一种：**回调里只存快照 + 置脏，取值在 tick 里做**。
现成的样板就是 `carpet_link.c`（写）+ `carpet_page.c:carpet_page_tick()`（读），
`set_value()` 里那句 `strcmp` 就是差分——没变就不重画。

### 5.3 五行分别吃什么

字段名都取自 `protocol/screen-link.schema.json`（`state` 那一支），不是我编的：

| 屏上行 | 数据来自 | App/服务端现在写了吗 | 屏这边消费了吗 |
||-|-|-|-|
| 链路 | 有没有收到 `state`（`screen_link_state_gap_s()`）、协商档位 | 有 | **有**（含 3 秒变灰、>10 秒重连） |
| 卫星 | `state.sat.phase` + `next_pass_in_s` 倒计时 | 有（`core` 的 `DemoRuntime`/`SimulatedEphemerisSource`） | 有（已上板，实测见 §9） |
| 对话 | `state.ui.card`（`status/sat/dialog/emotion/clock`）+ 独立帧 `dialog{dir, source}` | 有（`ScreenLink.kt` 发 v2 dialog 帧） | 有（同上。`source` 契约里不是必填，缺了只写"手机在说"，不补一个念不通的尾巴） |
| 表情 | `state.ui.emotion`（`neutral/happy/excited/sad/worried/sleepy/thinking/null`） | 有 | 行上有值；**脸还跟着 Muse 自己的语音链路走**，见 §5.4 |
| 呼救 | `state.sos.level / since_ts / attempts / sat_ack`（下行）；上行是 `command{sos_press}` | 有 | 行上有值；上行还没接（§5.5） |

**接线规则已经照 §5.2 落进代码**：`carpet_link.c` 的 `on_state` 只做一次
`cJSON_Duplicate` + 换树（mutex 保护），`carpet_page_tick()` 在 LVGL 任务里取值、做差分。
**契约里的英文枚举一律不直接上屏**：五张中文对照表
（`sat.phase / ui.card / ui.emotion / sos.level / dialog.source`），
认不出的值落"未知"而不是把原样印出去 —— 服务端加一个枚举值，屏上不该冒英文（老固件那条红线 #31）。
`emotion` 是 `null`（= 这一帧不带情绪）时落回**中性**，不是保持上一帧。
整棵 state 树每帧整体换掉，所以"帧里没带的字段还挂着旧值"（老固件的 #34）在这一版是**结构性做不到**的。

事件那边 `ack` 是组件无条件回的（`screen_link.c:370`），不接 `on_event` 也不会让手机端重试风暴，
只是屏上不弹横幅 —— **屏上现在就没接 `on_event`**。

**这五行有一条不看眼睛的验法**：串口发 `>carpet` 会把链路数字和五行当前值原样打回来
（`carpet_link_dump()`，在 `#if LV_USE_SNAPSHOT` 外面，正式构建里也在）。另一台电脑没有抓屏环境时，
这条就是判据来源，输出长什么样见 §9。

### 5.4 表情这一行的真相：7 个情绪要塞进 6 个模式

Muse 的 `muse_mode_t` 是**状态机**，不是情绪表：`BOOT / IDLE / LISTENING / THINKING / SPEAKING / ERROR / OFF`。
脸的动画由 `mode` + `muse_state_set_level(float)`（音量/进度）+ `muse_state_set_caption(fmt, ...)` 三者共同决定，
`muse_state_make_happy()` 是另开的一个一次性动作，不是一个可停留的模式。

建议的映射（**还没上板验过，App 侧改之前先在屏上试**；`source` 的取值就是 schema 里那六个，
没有 `mic`/`ai` 这种写法）：

```
neutral  → MUSE_MODE_IDLE          happy/excited → muse_state_make_happy() + IDLE
thinking → MUSE_MODE_THINKING      worried        → MUSE_MODE_THINKING（先不给 ERROR，ERROR 有报警脸）
sad      → MUSE_MODE_IDLE + 低 level
sleepy   → muse_state_set_asleep(true)（这条会连带熄屏策略，别在展示中途发）
dialog 帧 dir=up   source=asr            → MUSE_MODE_LISTENING
dialog 帧 dir=down source=model|xiaozhi_cloud → MUSE_MODE_SPEAKING
```

契约里那个 `null` 是"这一帧不带情绪"，**必须落回中性而不是保持上一帧**——
这正是本仓库任务 #34（"帧里没带的字段屏上还留着旧值"）在老固件上踩过的同一个坑，
别让它在 Muse 上重演（车联页那一版是整棵树每帧换掉，结构上就挂不住旧值；**脸这边要自己保证**）。

**接脸之前先看清这一条，不然会把主功能砸了**：Muse 自己的语音链路也在写 `mode`
（听到→LISTENING、云端在想→THINKING、TTS 在播→SPEAKING），而 `carpet_page_tick()` 是 0.25 秒一次的轮询。
如果照上面的表无条件写 `muse_state_set_mode()`，**每 0.25 秒就把 Muse 正在播的口型脸拽回中性**，
现象是"会听会说的动画没了"，而且串口一点错都不报。所以接的时候必须同时满足两条：
只在**值真的变了**时写一次，并且**当前 mode 是 SPEAKING/LISTENING 时不写**（让 Muse 那一轮走完）。
什么情况下才该由链路夺走表情的控制权：**AI 答句是从我们自己的服务端进来的、而 Muse 那条云链路没在跑**
（token 没注入或端点指回我们）时。这两种引擎同时开着，谁都不能一直握着脸。

### 5.5 屏能往手机发什么（我们自己的 App 要做服务端就得收这些）

`screen_link_send_command(cmd, args)`，`cmd` 只收 schema 里那九个名字，别的当场退回
`ESP_ERR_INVALID_ARG`：`sos_press / sos_cancel / track_pull / set_demo_mode / set_fence /
reboot_ui / card_next / card_auto / poke`。

- `card_next` 的 `args.dir = ±1`、`poke` 是单击行 → 这两条要接在**行的点击手势**上，屏这边还没做。
- 三条都是**请求**：画哪张卡最终仍由服务端写回 `state.ui.card`，所以 SOS 进行中你可以顶掉切卡请求。
- `screen_link_send_dialog_up(text, source, ...)` 已经有了（`turn_id` 形如 `s-<开机随机>-<自增>`，
  跨重启唯一，服务端按它去重）。

**SOS 的物理入口不能是 BOOT 长按 5 秒** —— Muse 把那个组合当成恢复出厂用了。这是这一版必须换掉的老设计。

### 5.6 字号这件事先别按老固件想

圆屏可读的中文只有 Muse 那份 16 px CJK 两档可选：`muse_font_cjk_16.c`（Unifont 1bpp，约 1.2MB flash）
或 `muse_font_cjk_noto_16.c`（Noto Sans SC 4bpp，GB2312 那 6763 字，约 910KB，车联页 select 的就是这份）。
**老固件那套 tiny_ttf 32 px hero 大字在这条路上没有对应物** —— Muse 没有现成的大字号 CJK，
要大得先生成字体。排版因此照 Muse 的 16 px 走（行高 44、列宽 340、居中偏下 24px）。

## 6. 现场怎么连（拓扑与地址）

契约里拓扑是**反的**：手机是服务端，屏是客户端，屏连上去并先自报 `hello`
（`ws://<服务端>:8443`，名字带 443 但它是明文 ws）。服务端 hello 白名单没过就 `close(1000, "unknown screen")`。

- `CONFIG_SCREEN_LINK_URL` 出厂默认 `ws://192.168.43.1:8443`（手机开热点时它自己是 `.1`）。
  **这一条只有编译期能改** —— 屏上没有口、串口 console 也没有命令，换服务端 = 改 `sdkconfig` 重编重刷。
  （所以真正上台前值得给屏加一个能改 URL 的入口，别在现场拿笔记本重编固件。）
  这台 PC 在同一个热点里是 **192.168.43.161**，所以"用电脑当服务端验屏"时要把
  `build-muse-s3-175-carpet/sdkconfig`（未跟踪那一份）里的 URL 改成 `ws://192.168.43.161:8443` 重编；
  **跟踪的 `devices/sdkconfig.carpet` 保持手机那个默认值**，别把测试地址提交进去。
- PC 端服务端有两个现成的：`simulator/`（Java 彩排台，`DemoDriver --card dialog`）和
  `firmware/screen/tools/fake-phone.mjs`。验"真的在动"要用彩排台，`fake-phone` 不做换卡。
- 8443 会被**上一个没死干净的 JVM 占住**：`TaskStop` 杀的是外层 wrapper，得杀 `java.exe`；
  重启前先存旧命令行。这台机器还有一层防火墙入站要放行（`tools/firewall-allow-8443.cmd`，管理员跑一次）。
- **一个服务端"发对了"长什么样**（2026-11-04 直接从 8443 抓的，`ui`/`sos` 都在，屏上要画的东西一帧不缺）：
  ```json
  {"t":"state","seq":131813,"ts":1795260429000,"state":{
    "link":{"primary":"cell","cell_ok":true,"sat_ok":false},
    "sat":{"phase":"idle","next_pass_in_s":663,"module_ok":true,"queue_pending":0,
           "queue_capacity":50,"tpi_age_s":5,"tpi_stale":false,"eph_state":"SUCCESS",
           "next_pass_dur_s":24,"next_pass_max_el":50.0,"aim_az":94.0},
    "gnss":{"lat":30.2841,"lon":120.1551,"hdop":1.2,"source":"phone","fix_age_s":2.0},
    "fence":{"active":true,"inside":false,"name":"西湖景区","dist_m":-511.9},
    "sos":{"level":"normal","sat_ack":true},"sentinel":{},"phone_battery":78,
    "demo_mode":true,"ui":{"card":"dialog","emotion":"worried","tz_min":480}}}
  ```
  抓它的命令（不需要装任何 npm 包，`curl` 就够 —— 服务端→客户端的文本帧是不掩码的，
  JSON 能直接从字节流里读出来）：
  ```bash
  curl -s --max-time 3 -H "Connection: Upgrade" -H "Upgrade: websocket" \
       -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
       http://<服务端IP>:8443/ > ws-probe.bin
  ```
  **这条命令自己就是一个 WS 客户端**，握完手就会收到推流。服务端允不允许"两块屏同时连"
  没实测过，所以验屏的时候别把它跟屏的会话叠着开：`--max-time 3` 到点自己断，别改成常驻。

## 7. 凭据与红线（这几条不因为换底座而放松）

- **不把这套服务部署到公网，任何情况下都不。**
- **SDK token 不进任何仓库文件。** Muse 的设计是串口发一次、存进 NVS：
  `>sdk_token=mgst_…` → 回 `@sdk_token ok`。这台机器上它存在仓库外的
  `D:\dev\tmp\secret\muse-sdk-token.txt`，两边仓库 grep 都是 0 命中。
  屏上 Settings 里也能手填（Server / VM ID / Device token / SDK token 四行 + "Test connection"）。
- `sdkconfig` 含演示 Wi-Fi 的**明文密码**，已 gitignore，永远不要提交，也不要 `git add -A`；
  只跟踪 `sdkconfig.defaults` 和 `devices/sdkconfig.*`。
- **`CONFIG_SCREEN_SHOT_PORT=8444` 是个无鉴权的 `/shot.bmp`。上展台前必须改回 0。**
  （换到 Muse 这一版还没有这个口，但老 `app/` 有，回滚时会带回来。）
- Muse 的 App（gadgets.muse.ai）不是硬门槛：屏上 Settings → Wi-Fi 有开关、扫描列表和
  "Other network…" 手输，官方那句写得很明白 —— *"Pair with the Muse app to use your account;
  a device token here overrides it."* 但这台 PC 到 `gadgets.muse.ai`/`api.muse.ai` 要走代理
  （`127.0.0.1:7890`），板子用不了代理；`raw.githubusercontent.com` 直连超时，上游归档走 gh-proxy。

## 8. 还没做的（不要在这里以为已经做了）

1. ~~`on_state` → 五行数值~~ **代码已经写完**（§5.3），**但还没在屏上验过** —— 刷进去看五行跟着帧变，
   是这一版剩下的唯一动作。
2. `state.ui.emotion` → `muse_state_set_mode()`（§5.4，**接法有条件，照那段的两条约束做，否则砸掉口型动画**）。
3. 行可点 → `card_next`/`poke`/`sos_press`（§5.5）。
   注意 `screen_link_send_command()` 现在只能从 LVGL 任务里调，而它里面那句
   `esp_websocket_client_send_text(..., pdMS_TO_TICKS(2000))`（`screen_link.c:122`）
   **最多阻塞 2 秒**：发送卡住 = 界面卡住、触摸没反应。要接就先改成"置位、由链路任务发"，
   别直接在点击回调里发。
4. `on_event` 没接 → 屏上不弹横幅（`sat_visible`/`fence_exit`/`downlink_msg` 那三类）。
   `ack` 组件已经无条件回了，所以服务端不会重试风暴，只是观众看不见。
5. SOS 的物理入口挪走（BOOT 长按被上游占作恢复出厂）。
6. 大字号 CJK 字体（§5.6），要不要做取决于展台距离。
7. 语音：屏侧"听/说"是 Muse 自己那条云链路，**和我们自己的 App 无关**；
   要让它走我们 App 的服务端，得先看 Muse 的 endpoint 能不能被屏上那四个字段改掉。
8. 屏幕上的第三块 tile **还没有目视确认过**（判据见 §9）。

## 9. 已经实测到的 / 还差的（2026-11-04 这一轮）

刷这一版：`idf.py -B build-muse-s3-175-carpet ... -p COM8 app-flash`（只动 app 分区，
3,149,824 字节的镜像写在 `0x20000`，`Hash of data verified` 过）。板子插这台机器就是 COM8。

串口一行命令 + 回收输出，用 `../tools/sercmd.py`（**注意它一开串口就脉冲一次 RTS，等于复位板子**，
脚本里因此先睡 6 秒等 console 起来再写；四个参数都要给）。

**控制台有两种输入，写错一种就什么也收不到**（这轮踩过）：普通字符是**按键**，
`>` 之后的才算**一行命令**。所以 `sercmd.py COM8 "carpet"` 发出去的是 c-a-r-**p**-e-t 六个键，
其中 `p` 命中"请求抓屏"，屏只回一句 `SNAP OFF`（bench 没编进来的意思）；要的是 `">carpet"`。

```bash
<IDF venv python> ../tools/sercmd.py COM8 ">status" 8 D:/dev/tmp/ser-out.txt
<IDF venv python> ../tools/sercmd.py COM8 ">carpet" 8 D:/dev/tmp/ser-out.txt
# 例：发 token（期望回 @sdk_token ok）
... sercmd.py COM8 ">sdk_token=mgst_…" 8 out.txt
```

> 本机 `python` 是 3.12、没有 pyserial；用 IDF 那份 venv：
> `D:\dev\esp\idf-tools\python_env\idf6.0_py3.11_env\Scripts\python.exe`。

**这轮已经看到的：**
- 起机正常，`link.heartbeat` 在跑，`muse_keep` 栈高水位 4,040 字节，没有 `Guru meditation`。
- **板子自己就进了手机热点**（`sta ip: 192.168.43.243`，网关 `.1`）—— 不需要现场手输 Wi-Fi，
  凭证在 NVS 里带着。这一条把"必须先人手点屏"的假设否掉了。
- URL 改成本机 `.161` 重编重刷之后，链路真的起来了：
  ```
  I (7170) screen_link: hello 已发：WS175B-A3F7  电量未知
  I (7171) carpet: linked, contract v2
  ```
  日志里的 `ws=down` 是 **Muse 自己的**那条 Home-Link，不是 Screen-Link，别看错。
- **五行读到的是帧里的真值，而且在动。** `>carpet` 是这一版加的回读命令（`carpet_link_dump()`，
  故意放在 `#if LV_USE_SNAPSHOT` 外面 —— 不带 bench 的正式构建也能用串口验，不用眼睛）。
  同一条命令隔 25 秒抓两次：
  ```
  @carpet wifi=1 linked=1 contract=v2 gap_s=0 has_state=1     @carpet wifi=1 linked=1 contract=v2 gap_s=0 has_state=1
  @carpet sat=空闲 下次11:12                                   @carpet sat=空闲 下次11:24
  @carpet dialog=手机在说                                      @carpet dialog=-
  @carpet card=对话卡 emotion=开心                              @carpet card=对话卡 emotion=中性
  @carpet sos=正常                                             @carpet sos=正常
  ```
  倒计时在走、表情换了、`dialog` 挂 5 秒后按约定清空 —— 这四件事一起才说明数据是真的从帧来的，
  不是写死的。第一行的 `gap_s=0` 对应页面上那句"已接入 v2"（换算规则见 §5.3）。
- 起机就有 `no device credentials in NVS, waiting for BLE provision` —— SDK token 还没注入。

**还差的一步，得有人做：** 注入 token `>sdk_token=…`（不进任何文件，见 §7）。
没有它 Muse 那套"会听会说"上不了云，但**车联页这五行不依赖它**，现在就是通的。

**不用眼睛也能验：把屏拍回来。** Muse 自带抓屏（上游那句注释写着
"Streams the screen over the USB cable as base64 RGB565"），要的东西只有两样：

```bash
# 1) 把 bench 那一层叠到 defaults 链的最后（它只有 CONFIG_LV_USE_SNAPSHOT=y 这一条有效项）
-DSDKCONFIG_DEFAULTS="sdkconfig.defaults;devices/sdkconfig.muse;devices/sdkconfig.muse-waveshare-s3-175;devices/sdkconfig.carpet;devices/sdkconfig.muse-bench"
# 2) 抓一张 PNG（第三个参数是输出文件）
<D IDF venv 的 python> <muse>/esp32/tools/muse/snap.py COM8 ">ui=carpet" D:/dev/tmp/carpet.png
```

`snap.py` 的参数规则要看代码不能看说明：**字符串里 `>` 之前的部分一个一个字符当按键发，
`>` 之后到结尾整行当控制台命令**；而那句 `s.write(b'p')` 是**固定最后发的**（就是"请求抓屏"），
所以键串里不用自己加 `p`。第四个参数 `reset_wait_s` 才会真的复位板子。

`ui=carpet` 是这一版**新加的那 6 行**（`muse_input.c`），因为上游只有 `ui=face`/`ui=settings`，
第三块 tile 在竖直方向上，串口里没有键能到它 —— 不加这条，抓屏工具永远拍不到车联页。
两个都在 `#if LV_USE_SNAPSHOT` 里，bench 关着时不占一个字节。
`snap.py` 会把图放大 3 倍再写 PNG，中文才看得清。

四条判据，缺一不算过：

1. 面上**上滑**能到车联页，圆点是 3 个，页面不闪；从车联页**只能上滑回面**（左右划不动）。
2. 没连 Wi-Fi 时第一行是"等网络"（橙）；连上 Wi-Fi 但服务端没起是"未接入"；
   服务端起来后 **≤5 秒**变"已接入 v2"（蓝）。
3. `CONFIG_MUSE_CARPET_CARDS=n` 重编一次，圆点回到 2 个、左右滑行为与上游一致 —— 这条是回滚保险。
4. 串口 60 秒内没有 `Guru meditation` / `abort()`，且能看到 `hello` 发出去
   （`screen_link` 的日志 tag 就是 `screen_link`；连接失败只会以 `websocket_client` 的错露头）。
