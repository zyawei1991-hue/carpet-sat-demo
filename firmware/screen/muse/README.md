# 圆屏换底座到 Muse Gadget SDK：车联页与 App 侧要接的东西

这份是给**另一台电脑**看的。你要研究的是"功能整合进我们自己的 App"，所以正文在第 5 节；
前面几节只到"能把这一版重出来、能刷上板"为止。

基准：本文件正文的功能状态记到 2026-11-06 契约 v3 那一轮；**补丁本身更靠前** ——
它已经带上 11-11 的两轮（播放任务的 PSRAM 栈 + 屏侧上行 `mic_chunk`，`HANDOFF.md` §15.17、§16）、
11-12 的 `ack` 无条件回读（§18.2），以及 **11-13 凌晨的语音唤醒词整块**（§18.4）。
手机那半边的 v4 不在本仓库里，**屏侧只能靠串口回读判它到没到位**，别从这里的代码推。
唤醒词这一版**改了分区表**，第一次装必须全片刷（§3 第 4 步）；
而且**真人语音唤醒还没称到**（台架没有能出声的物理输出设备），别把它当已验收的功能。

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
- 我们的全部改动 = 36 个文件、6686 行新增 / 95 行删，其中一份是 261,748 字节的字库
  （`muse-carpet.patch`，11,496 行 / 621,914 字节，`git diff --binary` 打的，字库在里面是 base85 段。）
  这两个数字每次重打 patch 都要跟着改，别信上一版留下的 —— 这一组是 2026-11-13（换装道具 + 场景环动效 + 一次划动一屏这一版）实读的。
  这一轮（11-13 下午）在唤醒词那版之上又加了三件 owner 当面拍的交互：**A** 导航圆点从全底部
  一排改成四方位（上=场景环 / 下=功能 / 右=设置 / 左=氛围，脸=中心 Avatar 不单独占点，`muse_ui.c`）；
  **B** 所有「返回」一律回 Avatar/脸页，不再层级回退（`carpet_page.c` 的 `nav_back()`）；
  **P0** 点 Avatar 进对话 —— B §1 那条"唯一兜底永远有效"，LVGL 任务立记号、采集任务取走，
  和唤醒词命中走同一条 `talk_to_phone(false, true)`，闸一道都不放宽（`carpet_mic.c/.h` 的
  `talk_request/talk_take` + `muse_voice.c` 的取记号块 + `carpet_page.c` 脸格单击分支）。
  **B §3 场景环 + 换装（11-13 这一版再加）**：下拉那页从五场景宫格改成**三场景横卡**
  （车·守护 / 家·亲子 / 对话·助手，`carpet_page.c` 的 `PICKER_CELLS` 缩到 3 条 + `build_picker` 重写），
  点一张卡 = 选场景 + 回脸页。**换装**照 B §7「调色不新画」：`muse_pixel_set_scene()`
  （`avatar/muse_pixel.c` 新增 25 行 + `muse_pixel.h` 声明 4 行）只给身体绒毛 `C_BD..C_BH` 混入场景色
  （车=蓝 / 家=薄荷 / 对话=紫），脸 `C_SKIN*` 不动，按帧 `k=1-expf(-dt*7)` 混合约 0.5 s 过渡。
  三张卡标签 车/亲子/对话 都在现有字库子集里，**不用重跑子集脚本**。台架口 `>scene=1|2|3`（`muse_input.c`）。
  **B §5 默认氛围（11-13 这一版再加）**：右滑第一下去时钟还是相册，改在左滑设置「声音」页新增的
  「默认氛围用相册」开关里选（NVS `album_def`，u8，缺键=时钟）；`carpet_page.c` 右滑进氛围那一格读
  `muse_settings_album_default()` 决定落 `PG_CLOCK` 还是 `PG_PHOTO`，进了氛围再右滑仍轮换。
  台架口 `>amb [clock|photo]`（`muse_input.c`，和设置页开关同一个 NVS key）。真机称到：
  `>amb photo` → 右滑 `nav=photo`、`>amb clock` → 右滑 `nav=clock`，两张 466×466 截图实看。
  设置页那格中文走全量 Noto fallback，**不用重跑字库子集**。相册内容本身仍是占位
  （推图通道见 `docs/APP-CHANGES-2026-11-11.md` §8.3，P2）。
  **换装道具 + 场景环动效 + 一次划动一屏（11-13 这一版再加，owner 当面拍三件）**：
  **A 道具**——上一版换装只调色，owner 嫌太简单，这版给每个场景画看得见的道具（`avatar/muse_pixel.c`）：
  车·守护=斜挎胸前的安全带 + 捧在腹前的方向盘（两者上下错开，盘不盖带）；家·亲子=左爪一只呼吸玩偶 +
  右旁一架滑滑梯；对话·分身=躯干穿正装（外套 + 白衬衫 V + 领带 + 口袋巾）。脸 `C_SKIN*` 一律不动
  （「还是它换了装扮」）。道具锚在 avatar 几何上，随 `s_costume_pop` 用 ordered-dither 浮现、再下滑入位
  （≈0.4 s）。**B 动效**——场景环那页之前没动效，这版补入场：三张卡错峰 80ms 升起、其余页整列轻轻
  settle（`carpet_page.c`，`translate_y` 不开销图层）。**C 一次划动一屏**——owner 报「划一下切了好几个
  页面」，真因是十字 tileview 对角格互跳开 `LV_ANIM_ON` 会滚穿中心脸；carpet 三处跨格跳转改 `LV_ANIM_OFF`
  瞬切不穿格（`muse_ui.c`），动效落在内容入场而非格间滚动。
  **称到了**：串口每划一次只一条「判为」+ 一个落点（下→场景环 / 上→功能 / 左→设置 / 右→氛围 / 点→不动），
  四方向无回归；`>scene=1|2|3` 三张换装截图 + picker 三卡截图实看，方向盘+安全带 / 玩偶+滑滑梯 / 正装都看得见。
  **没称到**：格间瞬切「不飞穿」与入场动效的中间帧——快照一张要 3~4 s，抓不到 300 ms 的过程，靠 `LV_ANIM_OFF`
  语义（瞬切无中间帧）+ 串口单落点证明，动效靠代码证；要肉眼确认得 owner 在真屏上划一下。
  字库从 154,528 涨到 261,748 字节是这轮之前补词表时重跑的，不是这轮加的。
  比上一版（30 个文件 / 5291+ / 8,427 行）多的 528 行是**语音唤醒词那一整块**（owner 拍的路线，
  `HANDOFF.md` §18.3、§18.4）：新增 `carpet/carpet_wake.c/.h`、`idf_component.yml` 里那条
  带 rule 的 `espressif/esp-sr`、`partitions_muse.csv` 尾部追加的 `model` 分区，
  外加 `app.c` 关 BLE 腾内部 RAM、设置页「声音」里那一格、`muse_voice.c` 的免按键一轮。
  **这一版起分区表变了 ⇒ 装机必须全片刷一次**（见 §3 第 4 步）。
  上一版那 17 行（`on_ack()` 无条件回读「整份契约 v + protocols」）仍在里面，没被这轮覆盖掉。
  ```
  （下面每一行的两个数是实读的「新增行数 + / 删除行数 -」，只写一个数的是整份新文件；
  上一版这张表用的是 diffstat 那一列合成数，和 5291/86 对不上，从这一轮起改成分开写）
  esp32/components/muse/CMakeLists.txt       |  29 + /   1 -   (11-12 唤醒词：carpet_wake.c 进 srcs + **按 Kconfig 分叉**的 `espressif__esp-sr` 进 PRIV_REQUIRES（它是带 rule 的托管组件，无条件写会让别的板子 configure 阶段就找不到组件）；原有：+carpet_audio.c 那行 + embed 那行 + 11-08 中文化：carpet_lang_zh.c 那行 + 11-09 断电验尸：carpet_diag.c 那行 + 11-11 对着屏说话：carpet_mic.c 那行)
  esp32/components/muse/Kconfig              |  30 + /   1 -   (+MUSE_UI_LANG_ZH 那一档，`depends on MUSE_CARPET_CARDS`)
  esp32/components/muse/idf_component.yml    |  10 +   (11-12 唤醒词新入列：`espressif/esp-sr` 钉死 `==2.4.7`，带 rule `if: "$CONFIG{MUSE_CARPET_CARDS} == True"`。**右边必须是 `True` 不是 `"y"`** —— MUSE_CARPET_CARDS 是真 bool，组件管理器的 `IfClause` 对 bool 只走 `eval_bool`（只认 True/False），写 `"y"` 当场 `Invalid boolean ""y"" in "if" clause` 构建失败；隔壁 mdns 那条能写 `"y"` 是因为 MUSE_VIDEO_MDNS 是 string。别的板子上这个符号不存在 ⇒ `MissingKconfigError` 被 manifest/models.py 吃掉 = 跳过这条依赖，不是报错，所以不影响任何非 carpet 构建)
  esp32/components/muse/carpet/carpet_font.ttf | Bin 0 -> 261748   (新，字库；页面上 28/22 两档的词表)
  esp32/components/muse/carpet/carpet_lang_zh.c | 315 +   (新，「英文串→中文」那张表 **163 条**（实数，上一版这里写的 166 是旧数）+ `>zh` 的命中计数；11-12 唤醒词 +1 条 `Wake word`→`语音唤醒词`；11-08 补进 w.detail / h.detail 那两句整句，折叠那轮再加 `Muse 云`/那句说明/`已折叠`/`已展开`/`电池诊断` 五条，11-09 加 `熄屏后保持联网` + 那句说明两条)
  esp32/components/muse/carpet/carpet_lang_zh.h |  58 +   (新)
  esp32/components/muse/carpet/carpet_audio.c | 493 +   (新，v3 音频环：base64→PSRAM 线性缓冲→实时放音，缺块丢整段；缓冲 2 MB。383 → 493 是这两轮涨的：11-11 下午播放任务从 `xTaskCreate` 换成 `xTaskCreateWithCaps` + MallocCaps 栈（内部 RAM 拿不到 16 KB 连续块，那条任务**从来没起来过**，这就是"圆屏不出声"的真因），prebuffer 从永久闸门改成只在开口前生效；11-11 晚上再加 `carpet_audio_live()`，给上行那句"对着屏说话先掐掉在播的答句"用)
  esp32/components/muse/carpet/carpet_audio.h |  61 +   (新)
  esp32/components/muse/carpet/carpet_diag.c | 226 +   (新，11-09 深夜「断电验尸」：NVS 命名空间 `cdiag` 一圈 24 条，每条带同一组 PMU 原始寄存器 + VBAT；`>diag` 翻、`>diag clear` 抹。开机那两条**先存 RAM**，等 NVS 写得动了再补 —— 第一版没绕这一道，刷完实测 `>diag seq=0`，两条开机记录当场静默丢失，根因就是 §2 下面那条"`carpet_link_start()` 早于 `muse_settings_init()`"。)
  esp32/components/muse/carpet/carpet_diag.h |  41 +   (新，那五个入口：latched / boot / event / tick / dump)
  esp32/components/muse/carpet/carpet_link.c | 1268 +   (新，11-12 唤醒词：`carpet_link_start()` 尾部 +`carpet_wake_init()` —— create() 要一块 12 KB **连续内部** RAM，开机这几十秒是内部 RAM 最整的时候，等 Wi-Fi/TLS/LVGL 铺开再要就是"总量够、连续不够"；要不到只打日志，按键那条路照常好使。原有：v3 链路 + 上行命令队列 + page_report + 首屏那条带念稿 + `>audio` 探针 + on_command/几何申报 + 口型翻页跟播放位置 + maint 担心的脸三道让路 + `>maint` 造帧口 + 11-08：接入点地址运行期可设那一层 `url_read/url_effective/link_open/carpet_link_set_url/carpet_link_url_dump` + 11-09：`carpet_link_dial_fails`/`carpet_link_last_error` 两个转发、`@url`/`@carpet` 多印 `fails=` `err=` + 11-09 深夜：`carpet_link_start()` 顶上叫一次 `carpet_diag_boot()` + 11-10 深夜：`gate_task` 那道门不再一次性 —— `GATE_RETRY_S[] = {5,15,30,60}` 重拨阶梯，外加两条任务用完各自的栈高水位回读，`url_restart()` 那格从 4096 字降到 2048 字，OOM 那行多印「内部 RAM 多少空闲/最大连续」 + 11-11 晚：契约 v4 那三处接线 —— `on_ack()` 读 `protocols` 里有 ≥4 就 `carpet_mic_phone_declared(true)`、`on_connected()` 把它清掉（每一连重新判一次，不给跨会话缓存）、`on_disconnected()` 叫 `carpet_mic_link_lost()` 掐掉在录的那一段，`carpet_link_start()` 尾部补 `carpet_mic_init()`，`>carpet` 那一行尾部多印 `mic=<模式> armed=<0|1>` + 11-11 夜：`on_ack(ack, v)` 多收一个 envelope 的 `v`，许可改成 **`v >= 4` 且 `protocols ∋ 4` 两个都点头**；只报 4 而 `v` 还是 3（= 今天装机那版：申报发早了、`CommandType` 里没 mic_chunk，收到就关线）当场留一行 W 说明为什么不开上行 + 11-12：那一行改成**无条件打** —— `ack：整份契约 v=<n>、protocols=[…] ⇒ 上行开/不开`，开/不开都留证据；旧版只在"两个数都点头"时才有动静，另两种失败情形一行不打，现场只看到屏上那句「对着屏说话还没开」)
  esp32/components/muse/carpet/carpet_link.h | 156 +   (新)
  esp32/components/muse/carpet/carpet_mic.c | 516 +   (新，11-11 晚「对着屏说话」：屏主动上行那一整块 —— 按下 BOOT 起一段、松开整段发、`command{cmd:"mic_chunk", args:{turn_id,seq,total,rate,b64}}`。三道闸：NVS `carpet/mic_mode`（出厂 `off`）∧ 这一次连接里 App 申报过 `protocols ∋ 4` ∧ 没跳过闸；外加一具断路器（发过上行后 5 秒内被手机以 reason<1000 或≥4000 断开就 `s_dead`，本次开机不再试）。16k/单声道/s16le，2560 B 一块 = 80 ms，8 秒封顶 = 256,000 B = 100 块，`turn_id` = `m-<6 位开机随机>-<段号>`。采集只在 `voice_task` 里取它已经拿到的那 20 ms 块（`muse_audio_read` 不加锁，第二个读者会等满 1 秒令牌），发送走自己那条 8 KB PSRAM 栈任务 + 两块 256 KB PSRAM 段缓冲。`>micup`/`>mic [off|auto|force]`/`>mic`/`>mictest [秒]` 四个开发口)
  esp32/components/muse/carpet/carpet_mic.h | 120 +   (新，那十个入口（含 11-13 下午 P0 的 `talk_request/talk_take`）+ `carpet_mic_mode_t` 三档)
  esp32/components/muse/carpet/carpet_page.c | 1206 +   (新，第 1 层宫格 + 五个场景页 + 明细页 + 12px 琥珀点；11-13 B §3：下拉改三场景横卡 `PICKER_CELLS` 缩到 3 条 + `build_picker` 重写 + `on_scene_clicked` 选场景换装回脸 + `nav_back()` 一律回脸 + 脸格单击进对话 + 11-13 B §5 右滑进氛围读 `muse_settings_album_default()`；**11-13 场景环动效这一版再加 59 行**：`col_slide_cb/animate_column_in/animate_picker_cards/entrance_anim` + `s_shown_col`，picker 三卡错峰 80ms 升起 260ms、其余页整列 settle 220ms，`translate_y` 带孩子移不开销图层、`lv_anim_delete` 防堆叠、露出的列变了才播，进脸/设置清记号下次重播；`sync_tile`/`detail_open` 各挂一次)
  esp32/components/muse/carpet/carpet_page.h |  58 +   (新；11-13 B §3 +`carpet_page_scene_bench()` 台架口)
  esp32/components/muse/carpet/carpet_wake.c | 229 +   (新，11-12 语音唤醒词：只用 esp-sr 的 **WakeNet**，不上 AFE/AEC/MultiNet。`esp_srmodel_init("model")` → filter(ESP_WN_PREFIX) → `create(name, DET_MODE_90)`；喂的是 `idle_capture()` 那**唯一**的 20 ms/320 样本块，内部用累加器重切成引擎要的 512 样本/32 ms（**绝不新开第二个麦克风读者**，§16 那条坑）。`init()` 校验 chunk∈(0,1024]、单声道、16000 Hz，不对就 destroy 退出 —— 块长错了引擎会**静默永不命中**。三道不检测：设置关 / `carpet_audio_live()` 在念 / 一轮还没完（`s_inhibit`），命中后 62 块≈2 s 不应期。`>wake` 回读三行 `@wake`：ready/词/blocks/hits/dropped + detect 平均与最坏耗时 + 内部与 PSRAM 余量。**一个只有实读模型才发现的坑**：`esp_srmodel_get_wake_words()` 返回的是 `_MODEL_INFO_` 第 3 个下划线段原文，四个候选里只有「你好小安」那份写的是拼音 `nihaoxiaoan` ⇒ 屏上会教人念拼音，已加一行 strcmp 译回中文)
  esp32/components/muse/carpet/carpet_wake.h |  67 +   (新，那七个入口：init / ready / feed / take / inhibit / word / dump)
  esp32/components/muse/muse_app.c            |   3 +   (11-09 新入列：设置监听里 `MUSE_SETTING_STAY_ONLINE` 那一格 → `muse_wifi_apply()`，让已经在 nap 的那次当场结束)
  esp32/components/muse/muse_input.c         | 410 + /   6 -   (11-13 B §5：+`>amb [clock|photo]` 读/改默认氛围（和设置页同一个 NVS key）；11-13 B §3：+`>scene=1|2|3` 造场景换装；11-12 唤醒词：+`>wake` 读 / `>wake on|off` 改（和设置页那一格同一个 NVS key），回读走 `carpet_wake_dump()`；原有：>carpet / >page 回读 + bench 命令 ui=carpet + `>say` 造答句 + `>audio` 放音 + `>audtest` 造音频 + `>maint` 造养护帧 + 11-08：`>url` 读/改/清、`>wifi clear`、`>zh` 回读 + 11-09：nap 闸门认 `muse_settings_stay_online()`、`>stay` 回读口、`naps=`/`drops=` 两个 RAM 计数 + 11-09 深夜：`CARPET_DIAG`/`CARPET_DIAG_TICK` 两个宏（carpet 外=空话）接在 `power_off()` 顶、`set_asleep()`、`update_power()` 三档、`update_wifi_nap()`、POWER 键 press/long/release、输入循环里的 USB 通断和 30 秒兜底，加 `>diag`/`>diag clear` + 11-10：`muse_input_wake(why)` —— POWER 键那条 `set_asleep(false, …)` 的公开口 + 11-11 晚：`>micup`/`>mic`/`>mictest` 三条 + `>wifi set <ssid十六进制> <pass十六进制|->`（中文 SSID 带空格没法裸着往串口塞，走和屏上键盘同一个 `muse_settings_set_wifi()`；口令只报字节数不回显）+ 前面那两条 `hexval()`/`hex_bytes()`)
  esp32/components/muse/muse_input.h         |   9 +   (11-10 新入列：`muse_input_wake()` 那一句原型 + 它那份注释，说清它是按键同一条重路 —— 点亮面板、当场回连 Wi-Fi，不是轻量唤醒)
  esp32/components/muse/muse_lang.c          |  57 +   (11-08 新入列：给设置页那几档字体挂 CJK fallback，照越南语那份 `copies` 的做法；标题那档 unscii 整体换成带 fallback 的一档)
  esp32/components/muse/muse_pixel.h         |   4 +   (11-13 B §3 新入列：`muse_pixel_set_scene(int)` 声明 + 那段「按场景调色不新画」注释)
  esp32/avatar/muse_pixel.c                  | 215 +   (11-13 B §3 新入列，**在 components/muse 之外**：`muse_pixel_set_scene()` 实现 + `update_palette()` 里给绒毛 `C_BD..C_BH` 混场景色、按帧 `k=1-expf(-dt*7)` 约 0.5 s 过渡，脸 `C_SKIN*` 不动。**11-13 换装道具这一版再加 190 行**：12 个道具色 `C_BELT..C_TIE` + `s_costume_pop` 浮现 ramp（每次换场景清零、`1-expf(-dt*8)` 约 0.4 s 涨到 1）+ `cpx/c_disc/c_ellipse` 三个 ordered-dither 画笔 + `draw_vehicle/draw_kids/draw_suit/draw_costume`。车=安全带斜挎胸前 + 方向盘捧腹前（两者上下错开，盘不盖带，owner 要两个元素都看得见）；亲子=左爪呼吸玩偶 + 右旁滑滑梯；对话=正装外套 + 白衬衫 V + 领带 + 口袋巾。道具随 pop 用 dither 浮现再下滑入位，脸一律不动（「还是它换了装扮」）。**重打 patch 的路径清单必须含 `esp32/avatar/muse_pixel.c`**，漏了它声明进补丁、实现却没进 ⇒ 对端机器 undefined reference)
  esp32/components/muse/muse_pmu.c           |  35 +   (11-09 深夜新入列：`muse_pmu_init()` 的 `key_irqs` 分支在**清 INTSTS 之前**把已锁存的按键/中断抄给诊断 —— 那两格原本是开机即清，断电回来谁也问不到；加 `muse_pmu_diag_regs()` 纯读那一列七个寄存器，不改任何行为)
  esp32/components/muse/muse_pmu.h           |   8 +   (同上：`MUSE_PMU_DIAG_REGS 7` + 那个原型，都在 `#if CONFIG_MUSE_CARPET_CARDS` 里)
  esp32/components/muse/muse_settings.c      |  82 + /   2 -   (11-13 B §5：NVS 再多一条 `album_def`（u8，缺键=时钟）+ `album_default` 那两个函数；11-12 唤醒词：NVS 再多一条 `wake_on`（u8，**缺键=默认开** —— owner 要的就是免按键，默认关等于没做）+ 那两个函数 + 开机日志尾部 `wake %s`；11-08：NVS 多一条 `link_url`；11-09：再多一条 `stay_online`（u8，缺键=默认关）+ 那两个函数 + 开机日志尾部 `nap %s`)
  esp32/components/muse/muse_settings.h      |  30 +   (11-13 B §5：`MUSE_SETTING_AMB_DEFAULT` + `muse_settings_album_default/set_album_default`；11-12 唤醒词：`MUSE_SETTING_WAKE` + `muse_settings_wake_on/set_wake_on`，同样刻意不加 CONFIG 闸（主机侧 harness 也 include 这份头）；11-08：`MUSE_LINK_URL_MAX` + 那两个声明，刻意不加 CONFIG 闸 —— 主机侧 harness 也 include 这份头；11-09：`MUSE_SETTING_STAY_ONLINE` + `muse_settings_stay_online/set_stay_online`)
  esp32/components/muse/muse_settings_ui.c   | 333 + /  45 -   (11-13 B §5：「声音」页加「默认氛围用相册」一格 + 说明（全量 Noto fallback，不重跑子集）；11-12 唤醒词：「声音」页加一格 `Wake word`（**不新开页面**，owner 的规矩），说明那一行是**动态**的 —— 要念哪个词由 `carpet_wake_word()` 报（真在听的词不是屏幕上写的那个，是最难查的一种"唤醒词不灵"），模型没起来就直说「唤醒词没起来：model 分区里没有模型」。这一格的中文走 `muse_lang_montserrat()` 挂的**全量** Noto 16px fallback（7765 个码位），所以**不用重跑字库子集脚本** —— 那份 683 字的 `carpet_font.ttf` 只服务 `carpet_page.c`；11-08：WI-FI 子页多一行「Access point」复用现成的 `open_text` 键盘；中文化把这一页的文案接进 `muse_lang_menu()`，`w.detail`/`h.detail` 那两句整句也过表；折叠那轮加 `fold()`/`fold_toggle()` 一组 —— MUSE 页那四格 + 测试连接 + 配对说明收进默认折上的「Muse 云」，电量页那七行读数收进「电池诊断」，都在 `#if CONFIG_MUSE_CARPET_CARDS` 里；bench 那条 `>ui.settings=<页>[+]` 的 `+` 在 `#if LV_USE_SNAPSHOT` 里，只为截图能拍到展开态。11-09：熄屏页加 `s_stay_sw` 那一格，**放在五个时长选项上面**——圆屏一屏放不下五格再加一格，滚动条是关的、台架也没有滚的口)
  esp32/components/muse/muse_ui.c            | 196 + /  19 -   (tile + 手势吞并修 + 圆点可点 + show_carpet；**11-13 一次划动一屏这一版再加 11 行**：carpet 构建下 `muse_ui_show_face`/`muse_ui_goto_tile`/`dot_clicked_cb` 三处跨格跳转一律 `LV_ANIM_OFF` —— 十字布局里对角格互跳（场景环↔功能、设置↔氛围）开动画会滚穿中心脸，一次划动飞过两三屏；瞬切不穿格，动效改落在露出那一屏的内容入场（见 carpet_page.c）。非 carpet 构建保持原 epaper 三元行为)
  esp32/components/muse/muse_ui.h            |  11 +
  esp32/components/muse/muse_voice.c         | 207 +           (11-12 唤醒词：`idle_capture()` 里 +`carpet_wake_feed(s_chunk, MUSE_AUDIO_CHUNK)`（同一个块，不新读麦克风）；idle 循环里 `carpet_wake_take()` 命中 ⇒ 先 `muse_input_wake("voice")` 点亮，再按 `carpet_mic_armed()` 分三路：armed 就 `muse_wifi_power(FULL)` + `inhibit(true)` + **走按键同一条 `talk_to_phone(false, true)`** + `inhibit(false)`，没 armed 说「对着屏说话还没开」，没链路说「先让手机连上圆屏」。**免按键那一轮的句尾判据**（按下那条路不变，仍可提前松手发送）：1 秒滑窗取**最低**电平当底噪，阈值 = 底噪 +8 dB 且封顶 −25 dBFS；连续 700 ms 低于阈值且真出过声 ≥160 ms 就收口发送；2 秒还没人出声判成误触发、撤回这一轮并说「没听清，再说一次」（判据是 `loud < HF_MIN_LOUD` 不是 `!loud` —— 一声关门响就能把 loud 顶到 1，那样这道保险就废了，会一路录到 8 秒上限把一车噪声发上去）；11-11 晚：`talk_to_phone(bool barge_in)` —— 按下即录、松开整段发给手机那一条路；`BTN_TALK` 的 press 分支在**进 Hatch 那道闸之前**先看 `carpet_mic_armed()`， armed 就自己吃掉这次按键（`pre_reset()` + `continue`，不再走 Muse 云端那套）。owner 报的"按下键屏上让我装 Muse"就是这里：那条键是 BOOT=TALK，没配对时 `can_record()` 撞 `MUSE_HATCH_NOT_SET`，屏上打的是英文串「SET UP MUSE FIRST」；现在链路已经接上就改口成「对着屏说话还没开」，且带子里没配对的这句只留作真的没配时才出)
  esp32/devices/sdkconfig.carpet             |  72 +   (新，11-12 唤醒词：+`CONFIG_MODEL_IN_FLASH=y` +`CONFIG_SR_WN_WN9_NIHAOXIAOAN_TTS2=y`（你好小安）+ 那一段**这块板上实读**的开销注释：app 镜像 3,346,432 B / 4M 槽余 847,872 B（20%）、srmodels.bin 291,068 B、create() 34.0 ms 吃内部 21,128 B（最大连续块 131072→98304）+ PSRAM 322,864 B、detect() 一块 32 ms 平均 2.74 ms 最坏 4.61 ms（≈8.6% 一个核）、带引擎稳态 `int=43K/29K min=32K`。**探针那份更大的数（21,140 B / 3.63 ms / 11%）量的是 wn9_nihaoxiaozhi_tts，它的 `_MODEL_INFO_` 是 wakenet9l = 重档，只能当上限看**；原有：含 tiny_ttf 三条；`CONFIG_SCREEN_LINK_URL` 就是这里那条出厂值；11-08 加 `CONFIG_MUSE_UI_LANG_ZH=y` 和 `CONFIG_LV_USE_SNAPSHOT=y` —— 后者是截图验收那条口的开关，关掉就不出 `SNAP BEGIN`)
  esp32/main/app.c                           |  49 + /  10 -   (11-13 补一处**误导性读数**：`enter_advertising_state()` 在 carpet 构建下报 `off` 并直接 return —— 关了 BLE 之后心跳原本还在报 `setup=advertising … ble=advertising`，那是给一个根本没起来的协议栈贴标签，而这两格是下一轮排查唯一能读到的东西。已刷已验：`setup stage: idle -> off` + `hb … setup=off … ble=off`；11-12 唤醒词：carpet 构建**跳过 BLE 配网服务的启动**（`start_ble_setup_server_if_needed()` 顶上 `#if CONFIG_MUSE_CARPET_CARDS` 直接 return）。实测这一步吃 41 KB 内部 RAM：`after noise_ctrl_init int=102K/62K` → `after ble_start int=61K/52K`，稳态落到 `int=16K/8K`，**低于唤醒词引擎要的 21 KB 总量 / 12 KB 连续** ⇒ 不关 BLE 这功能根本装不下。关掉后 int 16K/8K → 73K/56K，带上引擎稳态 43K/29K。车联屏从来不用 BLE 配网（它从自己的 Wi-Fi 页入热点再拨 App 的 WebSocket），`ble_server_*` 在 `!s_started` 时本来就全是空操作，等 BLE 同步的调用方都在 `CONFIG_HOMEHUB_BLE_COMMANDS` 下而这个构建关着。**代价：这一版起 BLE 配网没了**，要退回官方行为把那个 `#if` 去掉；11-08：开机选网那条优先级倒过来 —— NVS 里用户填的网络 > 编译期那一条)
  esp32/main/muse_glue.c                     |  74 + /  11 -   (11-08 加：`dev_override_now()`，让看守任务在编译期 SSID 为空时也不再"别碰 Wi-Fi"；11-09 加：`DIAL_STALL_FAILS 4` 那条升级 —— Wi-Fi 还连着但抓屏链路连续 4 次拨号没通过就 `wifi_mgr_disconnect()` 走全量重连，并把 `err=` 原文一起打进 W 那行)
  esp32/partitions_muse.csv                  |   8 +   (11-12 唤醒词新入列：**只往尾巴上加一行** `model, data, spiffs, 0x822000, 0x300000,`（3 MB）。上面每一行的偏移一个都没动 ⇒ NVS(0x11000) 里的 Wi-Fi、`link_url`、`mic_mode`、`wake_on` 全保住，换这张表不用重新配网（用 IDF 自己的 `gen_esp32part.py` 解出编译产物逐行对过，不是看着 csv 推的）。**分区名必须就叫 `model`**：esp-sr 的 CMakeLists 用 `partition_table_get_partition_info("--partition-name model")` 找它，找不到就只打印一句提示、不生成 `srmodels.bin`，运行期 `esp_srmodel_init("model")` 返回空 —— 现象是"编译刷机全成功、唤醒词永远不灵"。16 MB flash 到 0x1000000，这张表到 0xB22000，后面还空约 4.8 MB)
  ```
  这份 patch 在这台机器上**用 baseline 干净树验过能打上**（空仓只放 baseline 那 20 份被改文件的
  原样副本，再 `git apply`，36 个文件全落对，字库解出来字节数一致），不是"看着应该能打上"。
  验法脚本：`D:/dev/tmp/verify_carpet_patch.py <patch> <muse_root> cdc169d <空目录>`，
  它同时做两件事 —— ① 在 baseline 空仓上 `git apply --check` + 真 apply；
  ② 把 apply 出来的每一份和**工作树**（就是真正编译刷机那份）逐字节比。
  2026-11-08 这天打了四次：第一次收进 R1~R4 那几处；**上板第一次刷机就死循环**
  （`assert failed: xQueueSemaphoreTake queue.c:1709 (( pxQueue ))`，根因是 `carpet_link_start()`
  里提前 `url_read()`，那时设置模块的锁还没建）之后改成 `url_effective()`，重编、重刷、称到了数字，
  再打第二次是 5,595 行那组；第三次收进设置页中文化那一整块（`MUSE_UI_LANG_ZH` + `carpet_lang_zh.c/h`
  + `muse_lang.c` 的字体 fallback + `muse_settings_ui.c` 接表 + 6 条 detail 整句），6,367 行；
  第四次收进折叠分组那一块（`fold()`/`fold_toggle()` + MUSE/电量两页的收口 + 五条新表项 +
  bench 的 `+`），当时那一版 6,560 行（往上追到 11-10 深夜是 7,418 行；上面那组 8,390 行是
  2026-11-11 这一整天两轮重打后的实读数，别拿这几个历史数互相对）。
  四次都走同一条验法：`git apply --check --binary` 过 → 真 `git apply` 过 →
  解出来 `carpet_link.c` 1041 行 / 字库 154,528 字节 / `carpet_lang_zh.c` 里 `"Pair in the Muse app"` 在 → worktree 删掉。
  `:core` 的 `SchemaDriftTest` 13 条、`:core:test` 305 条同一轮实跑（`--rerun`，不吃缓存）全 PASSED —— 其中 `SchemaDriftTest` 有一条是**直接读这份 patch 里的 `UI_CARD` 表**
  去硬比 schema 和 Kotlin 那份枚举的，所以它绿了就说明新补丁真的被读了，不是在原地打转。
  每次改动都重打并在干净树上验一遍，一轮记一笔：
  - 2026-11-04 深夜 §11 步骤 1（场景页信息架构 + `page_report`）那轮 —— 验过 `PAGE_NAMES`
    解得出来（漂移测试靠这一个符号，补丁里没有它就是静默不比，绿灯是假的）。
  - 2026-11-05 宫格那轮 —— `carpet_page.c` 解出来 845 行、`carpet_font.ttf` 154,528 字节、
    `PAGE_NAMES` 在。
  - 2026-11-05 答句上首屏那轮 —— 干净树 `git apply --check` 过、解出来 `carpet_link.c` 631 行 /
    `carpet_page.c` 849 行 / 字库 154,528 字节 / `PAGE_NAMES` 在。板上实测：`>say` 那条 369 字节的
    句子按页翻到 `at=216/369`（一页三行、页与页重叠一行，所以步进是 108 字节）后自己收回、脸回 idle；
    彩排台三句键盘问话都走完「问句 THINKING → 答句 SPEAKING → 翻到头 → 6 秒后清空」。
    两条只有上板才看得见的坑记在 `carpet_link.c` 里：① 屏打盹时 UI 任务在 `update_sleep()` 就 return，
    这条念稿的 tick 跟着停（`poke` 不叫醒打盹的屏，彩排时先戳一下或发 `w`）；② 脚本每 6 秒一条主动线
    会把刚落地 0.4 秒的答句顶掉，所以带子上定了轮次优先：同 `turn_id` 的后一句和屏自己那句可以挤，
    新轮次的主动线让路（`>say` 之外没有第二条能验它，彩排台的键盘因此要补一条上行回显给屏）。
  - 2026-11-06 音频探针（`>audio [秒]`）这轮 —— 干净树 `git apply --check` 过、解出来
    `carpet_link.c` 708 行 / `carpet_page.c` 849 行 / 字库 154,528 字节 / `PAGE_NAMES` 在。
    板上称到的音频实况（这几条只有上板看得出来，别再从代码推）：
    ① 上电日志 `audio ready: 16000 Hz, 2 ch, vol 100` 且 `muse_settings` 那行不带
    `(speaker off)` ⇒ 软件静音、音量、codec 打开三条全排除；
    ② `>audio` 印 `PA46=1`：ES8311 只做 DAC，推喇叭的是板上 NS4150B，使能脚 GPIO46 在放音全程为高
    （读它要先 `gpio_input_enable()`，驱动把脚配成了纯输出）；
    ③ 250 次 `muse_audio_write()` 全 `ESP_OK`、每"一秒"实测 0.92~1.00 秒 ⇒ 样本真按 16k 节拍进了 DMA，
    不是软件空转。剩下只能靠耳朵/万用表：功放供电、MX1.25 喇叭座、喇叭本身。
    ④ **麦克风那条不能用来听自己**：开机 5.7 秒 Muse 的 `muse_audio_selftest` 读得到（-51 dBFS），
    之后 `muse_audio_read()` 一律立刻失败在 `i2s_channel_read(1410): The channel is not enabled`，
    而 `muse_audio_power(true)` 因 `s_open` 仍为 true 原地返回；强行 `power(false)+power(true)` 重开
    会改成不返回（日志停在 `I2S_IF: Pending out channel for in channel running`），串口开发口那条任务
    （3584 字节栈）从此读不到任何命令。所以探针只放音，不读麦克风。
    **【2026-11-10 改判：这一段保留作历史观测，但结论作废。】** 那两次失败都是**探针自己撞上了
    `voice_task` 的常驻读取**：`muse_audio_read` 不加锁，第二个读者会等满 esp_codec_dev 的 1000 ms 令牌
    然后打出同一行 `The channel is not enabled`（超时日志，不是状态位）；`power(false)` 卡住也是在等
    被在途读者拿着的令牌（`portMAX_DELAY`），不是硬件坏。**判据已称到**：设置页声音那一栏对着麦克风拍手，
    电平条/dBFS 有反应 ⇒ 录音通路本来是好的。详见 §8 第 7 条和 `protocol/screen-link.md` v4 那一节。
  - 2026-11-06 契约 v3（`carpet_audio.c` + `on_command` + `hello.screen` + `UI_CARD` 补 maint）这轮 ——
    干净树（`git worktree add --detach … cdc169d`）`git apply --check` 过、解出来
    `carpet_link.c` 804 行 / `carpet_audio.c` 368 行 / `carpet_page.c` 849 行 / 字库 154,528 字节 /
    `PAGE_NAMES` 在 / `UI_CARD` 里有 `maint` / `on_command` 里认 `audio_chunk`。`:core` 的
    `SchemaDriftTest` + `AudioChunksTest` 实跑全 PASSED（含那条硬比 schema↔Kotlin↔补丁里 `UI_CARD` 的）。
    **PC 那半已经在真链路上称过**：彩排台带 `--voice` 起，`node simulator/linkcheck.mjs … --voice`
    收到 92 块 / 233,740 字节 ≈ 7.3 秒，seq 连续、每块加起来正好等于帧里写的 total、最大帧 3570 字节
    （预算 3800，屏侧 buffer 4096）。**屏那一半还没上板**：环形缓冲、预缓冲 6400 字节起播、
    真口型（`muse_audio_level`）、翻页跟着播放位置这四条现在是代码 + 编译通过，判据要在板子上看
    `>audtest 3` 印出来的 `@audio at …/… level …` 那几行，以及带 `--voice` 的彩排台推一句真答句。
  - 2026-11-08 接入点地址运行期可设（R1~R4）这轮 —— 干净树 `git apply --check --binary` 过、真 `git apply` 过。
    **板上称到**：`>url ws://192.168.1.17:8443` 之后按 DTR/RTS 冷复位，重启回读
    `@carpet url=ws://192.168.1.17:8443 src=nvs`（不再是 `src=factory`），且彩排台在 PC 上起着时
    `@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1` —— 地址活的重启还在、而且真的拨通了。
    出厂那条 `ws://192.168.43.1:8443` 对应 `src=factory`，即"没填过就是 R4 那条兜底"，两者是同一条。
  - 2026-11-08 设置页中文化这轮 —— 干净树 `git apply --check --binary` 过、真 `git apply` 解出来
    `carpet_lang_zh.c` 里 `"Pair in the Muse app"` 在。`tools/check_zh_table.py` 五项全过
    （`entries: 159 … problems: 0`：表里每条中文的字数都在同一档 Noto 16 px 下按像素预算称过，
    标签档 124 px、值档 130 px、note 档 330 px 折两行）。**截图验收不是只看编译过**：
    `tools/zh_shot.py` 一次串口会话里逐页 `>ui.settings=<page>` + `p`，人眼看回
    home / wifi / muse / ble / sound / sleep / battery / power 八页 + 宫格页 + 车载页，
    没有豆腐块、没有被省略号截掉的条目；就是靠看第一版截图才发现「在 Muse 应用里配对」漏翻
    （`h.detail` 那句整句没走表），补进固件和表之后重刷重截确认。
    车载页那张（`shots8/zh_shot0.png`）同时是 `>maint` 的图证：「对话」行前那枚 12px 琥珀点亮着，
    回读 `@carpet card=养车卡 emotion=担心 maint_worried=1`。
    **一个坑记在这儿**：Windows 上开串口会脉冲 DTR/RTS 把芯片复位（`snap.py` 那句 termios HUPCL
    只对 POSIX 有效），所以逐页截图必须开一次口、等它启动完、每页给 3 秒再按 `p`；
    分开每次开一口的写法称到的是"刚开机那半秒页面还没填值"，看着像漏翻其实是抢跑。
    没截到的：WI-FI 页「接入点」那一行在折叠区以下，要手指往上滑才露出来，串口没有滚动旋钮，
    所以那一行和它的输入键盘页没出图 —— 它和已出图的同类行走同一档字体、同一张表。
  - 2026-11-08 折叠分组这轮 —— 干净树 `git apply --check --binary` 过、真 `git apply` 过，
    解出来 21 个文件 / `carpet_lang_zh.c` 311 行 / `carpet_link.c` 1041 行 / 字库 154,528 字节。
    `check_zh_table.py` `entries: 164 … problems: 0`、`tests/test_muse_lang.py` 7 条 OK、
    `:core:test --rerun` 305 条 0 失败（含 `SchemaDriftTest`）。
    **截图验收（`shots10/`，人眼看过四张）**：`>ui.settings=muse` 折上态 —— 「Muse 云 / 已折叠」
    那一行 + 下面那句「以后接 Muse 官方 API 才用，现在这套车联演示连不到它。」两行折好，
    服务器/虚拟机/令牌那四格不在屏上；`muse+` 展开态 —— 同一行变「已展开」，
    「服务器 hatch.metaaivm.com」两行折出、「虚拟机 未设置」露出半行；
    `battery` 折上态只剩「电量 / 没有电池 / 电池诊断 已折叠 / 重新计时」；
    `battery+` 展开态那套读数原样在（电量 无、已用掉 -、充满能用 -、熄屏 -、打盹时间 -）。
    四张都没有豆腐块、没有被省略号截掉的条目。
    **为什么要有那个 `+`**：这条串口注入不了点击（`muse_input.c` 里所有 bench 命令逐条看过，
    没有一条能合成一次 tap），折上的分组就永远拍不到展开态；`+` 只在 `#if LV_USE_SNAPSHOT`
    里、只把折上的那一组打开、从不反过来折，所以不带 `+` 的名字拍到的仍是用户默认看到的那一屏。
    折叠状态不落 NVS：子页返回时整个销毁重建（`show()` 里 `drop(prev)`），重启天然回到默认折上。
  - 2026-11-10 熄屏的叫醒这轮（`HANDOFF.md` §15.14）—— 干净树 `git apply --check --binary` 过、
    真 `git apply` 解出来 27 个文件 / `carpet_link.c` 1135 行 / `carpet_diag.c` 226 行 /
    `carpet_lang_zh.c` 314 行 / 字库 154,528 字节。
    **而且这一轮在干净 worktree 里真编过一遍**：照 §3 那四步走完 `Project build complete`、
    `esp-idf/screen_link/libscreen_link.a` 在、`muse-gadget.bin` 3,346,432 字节 = 刷进板子那次的写入量；
    `diff --strip-trailing-cr` 比 `carpet_link.c` / `muse_input.c` / `muse_input.h` 等工作树，全 SAME。
    `:core:test --rerun-tasks` 305 条 0 失败（含 `SchemaDriftTest` 13 条 ——
    它硬比 `CommandType` ↔ schema 的 `cmd` 集合相等，所以 schema 加 `wake` 就必须连着 Kotlin 一起加）。
    **板上称到**：三趟 `>nap` → 叫醒全过，`waking (ws say)` → `carpet.diag: eN t=… lit` →
    `carpet: woke by link, carpet_cmd 还剩 3732 字节` → `nap-` → `@stay … nap=0 wifi=1`，
    没有溢出、没有复位。新落下的是 `esp32/components/muse/muse_input.h` 里那条
    `muse_input_wake(const char *why)`（POWER 键那条 `set_asleep(false, …)` 的公开口）。
    `cmd_task` 的栈从 4096 字提到 **6144 字**，是称出来的：4096 那版峰值吃到 3675 字，
    6144 那版量到 5211 字、剩 933 字（约 3.7 KB）—— 注意 `usStackDepth` 是**字数**不是字节数。
    **这一轮还没称的**：`on_command` 里真认 `cmd:"wake"` 那一行走的是 ws，要一个拨得通的服务器，
    这台 PC 和屏在华为热点上互不通，留到现场由 App 那一头发。
  - 2026-11-10 深夜「App 显示无法连接圆屏」这轮（`HANDOFF.md` §15.16、`docs/APP-SCREEN-CONNECT.md` §11）
    —— 干净 worktree `D:/dev/tmp/muse-clean-v4`（`git worktree add --detach … cdc169d`）
    `git apply --check` 过、真 `git apply` 过、解出 patch 里那 27 个文件（`git status` 只看得到 17 条：
    15 个改过的 + 新目录 `carpet/`（内含 10 个文件）+ 新的 `sdkconfig.carpet`）/ 字库 154,528 字节
    （sha256 前缀 `a77558a4`，和工作树同一份），
    `diff --strip-trailing-cr` 比 `carpet_link.c` / `muse_input.c` / `muse_input.h` / `muse_ui.c` /
    `main/muse_glue.c` / `main/app.c` / `devices/sdkconfig.carpet` 七个全 SAME。
    **这一轮没在干净 worktree 里从头重编**（上一轮编过，见上面那条），源码是在带 patch 的工作树里
    编 + 刷 + 上板称的：`Project build complete`、esptool `Hash of data verified`。
    落下的东西全在内存那一条线上：`gate_task` 拨号失败改成 5/15/30/60 秒阶梯重试、
    `carpet_url` 那条一次性任务 4096 → 2048 字（真机称到过 `url restart task out of memory`）、
    `carpet_cmd`/`carpet_gate` 各留一条高水位回读。`components/screen_link/`（不进 patch 那份）
    同一轮改了：`link_task` 栈 8192 → **4096 字**（那个数一直是按字节写的，`usStackDepth` 收字数），
    高水位实测「还剩 2004 字」，`screen_link_start()` 失败路径统一走新加的 `start_fail()` 退回已拿到的东西，
    `esp_websocket_client_start()` 失败时换掉整个句柄再来。**板上称到链路本来是通的**：
    `dialing ws://192.168.43.1:8443 (built-in)` → `ws 接入` → `hello 已发：WS175B-A3F7` →
    `linked, contract v3` → `sent page_report`；同一次开机 1 秒之后
    `i2s_alloc_dma_desc: allocate DMA buffer failed` → `i2s_rx_channel_start` 空指针 panic
    （backtrace 已用 `addr2line` 对 `muse-gadget.elf` 解过，ELF SHA256 前缀 `b283b092f`）。
    **§6 那三条 `errno` 现在多了一种用法**：`dialed=0` 说明屏从没拨号（这一轮修的正是它），
    `dialed=1 fails>0` 才轮到那三条去分"网的锅还是 App 的锅"。
  - 2026-11-11 这一整天三轮（下午「圆屏不出声」= `HANDOFF.md` §15.17 + 晚上「对着屏说话」+
    夜里「屏拨不上号」和那道闸看错了数 = 都记在 §16、§17）
    —— patch 重打到 **30 个文件 / 8,410 行 / 438,957 字节**（新增 5,274 / 删 86，`carpet_link.c`
    解出来 1,228 行、`carpet_mic.c` 463 行）。
    干净 worktree `D:/dev/tmp/muse-clean-gate`（`git worktree add --detach … cdc169d`）
    `git apply --check --binary` 过、真 `git apply` 过、30 个文件全落对、字库 154,528 字节
    （sha256 前缀 `a77558a4`，和工作树同一份），
    再 `diff --strip-trailing-cr` 比 `carpet_link.c` / `carpet_mic.c` / `muse_voice.c` /
    `muse_input.c` / `CMakeLists.txt` 五个全 SAME，worktree 删掉。
    **没在干净 worktree 里从头重编**（上一轮编过，见上面那条）；源码是在带 patch 的工作树里
    编 + 刷 + 上板称的：`Project build complete`、esptool `Hash of data verified`（夜里那一版也重编重刷过两次）。
    下午那一轮落下的是内存那两条：播放任务 `xTaskCreate`（要 16 KB **连续内部 RAM**，板上最大块只有
    7,424 B ⇒ 那条任务从来没起来过，这就是"屏不出声"的真因）换成 `xTaskCreateWithCaps` + PSRAM 栈，
    prebuffer 从"永久闸门"改成只在开口前生效。晚上那一轮落下的是屏主动上行：新文件
    `carpet/carpet_mic.c/.h`（整段采完、松手一口气发，`mic_chunk`，三道闸 + 熔断，出厂 `off`），
    `muse_voice.c` 里 `talk_to_phone()` 和"按下 BOOT 先看 `carpet_mic_armed()`"那道插队，
    `screen_link` 的 hello 多报 `SCREEN_LINK_MIC_UP=4`（**`v` 仍是 3**），
    以及 owner 报的那句英文「SET UP MUSE FIRST」改口。
    夜里那两处在 `components/screen_link/` 和 `carpet_link.c`（**前者不进 patch**，走 §3 那条 `cp -r`，
    两份树这一轮已经比过 SAME）：`ws_cfg.buffer_size` 从 4096 提到 4608 跨过
    `CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL` 那条界、把 rx/tx 两份缓冲挪进 PSRAM，还回 8 KB 内部 RAM；
    栈阶梯删掉 2048 那一档（它给得出来但不够 ws 任务用 ⇒ 变成重启循环）；
    `on_ack()` 的许可从"protocols 里有 4"改成"`v >= 4` **且** protocols 里有 4"。
    **板上称到**（原文都在 §16.2 和 §17.2）：地基 `I (1051) carpet_mic: 上行地基就绪：2 只 256000 字节段缓冲（PSRAM）…`；
    链路 `I (7053) carpet: dialing …` → `I (7081) screen_link: ws 接入` → `linked, contract v3`
    —— **一拨就上，两次开机都是**（这一条以前从来没过：拨号那一刻内部只剩 5 KB 空闲 / 最大连续 2 KB）；
    上行整条走通 `上行 m-d4952e-2：43/43 帧、110080 字节（3.44 秒语音）0.71 秒发完` +
    假手机那侧 `上行段齐：… 乱序缺号=无` + 落出来的 WAV 是合法 16k/单声道/s16le；
    两道闸都按预期：装机那版的样子（`v=3` + `protocols [3,4]`）⇒ `armed=0` 且留一行 W 说明理由，
    `--v4-ready`（`v=4`）⇒ `armed=1`。
    **还差的**：这一整套是拿假手机服务端称的，真机 App 那一头现在**还没有** `mic_chunk` 的解码，
    所以"按下屏上的键对着手机说话"要等 App 补完；而这块板上还需要一只手——真按那个键 + 真人出声
    （台架是 `'d'/'u'` 模拟按键 + 房间底噪，采到的 peak 只有 9 / rms 1 ≈ -71 dBFS，通路有信号但没语音）。
  - 2026-11-12 「按了键屏说『对着屏说话还没开』，串口里却查不出为什么」这一轮 ——
    只改一处：`on_ack()` 那一行日志改成**无条件打**（`ack：整份契约 v=<n>、protocols=[…] ⇒ 上行开/不开`）。
    旧版把 `ESP_LOGI` 写在"两个数都点头"的分支里，另两种失败情形（没报 `protocols` / 报了 4 但整份还是 v3）
    **一行都不打** ⇒ 现场只剩屏上那句话，而 App 那半边的代码不在这棵树里，没这一行就无从判责。
    patch 只重打 `carpet_link.c` 那一节（`D:/dev/tmp/splice_carpet_audio_patch.py`，3484..4717 → 1251 行），
    到 **30 个文件 / 8,427 行 / 440,190 字节**（新增 5,291 / 删 86，`carpet_link.c` 解出来 1,245 行）。
    **验法这一轮换了个更省的**（`D:/dev/tmp/verify_carpet_patch.py`）：不再 `git worktree` 拉整棵上游树，
    而是空仓里只放 patch 会**修改**的那 16 个文件的 baseline 版（`git show cdc169d:<path>`），
    `git apply --check` 过 → 真 `git apply` 过（30 节）→ 30 个文件逐个和工作树**逐字节**比。
    结果 28/30 字节全等，另两个（`carpet_mic.c/.h`）差的正好是"每行一个字节"：
    **工作树那两份是 CRLF、patch 里是 LF**，把行尾归一后 `same=True` ⇒ 内容没漂，不是漏打。
    两个坑记在这儿：① 空仓 `git init` 会吃到 Git for Windows **system 级** `core.autocrlf=true`，
    不 `git config --local core.autocrlf false` 的话 30 个文件会全部报"差一行一个字节"，看着像补丁全坏了；
    ② 逐字节比之前先归一行尾，否则 CRLF 那两份永远比不平。
    源码这一版**已编已刷已开机验过**（`Project build complete` / `Hash of data verified.` /
    `I (1051) carpet_mic: 上行地基就绪…`），**但那一行 ack 还没在真机上读到过** ——
    要等 owner 把热点和 App 再开一次（记在 `HANDOFF.md` §17.5）。
  - 2026-11-13 凌晨 **语音唤醒词那一整块**（owner 拍的路线，`HANDOFF.md` §18.4）——
    `verify_carpet_patch.py` 实跑：`baseline copies restored: 18` / `git apply: OK (34 sections)` /
    `byte-compare: 32/34 identical`。差的两个又是 `carpet_mic.c/.h`，归一行尾后
    `normalized_same=True`（工作树 CRLF 463 / 98 行，patch 里 LF），和 11-12 那轮同一个已知坑，不是漏打。
    这一版 34 个文件 / 5819+ / 86- / 9,045 行 / 471,665 字节，字库仍 154,528 字节。
    **源码已编已刷已开机称过，这一轮刷了两次**：第一次带唤醒词整块（全片刷，5 个写入段全
    `Hash of data verified.`），第二次只 `app-flash` 带上面那处 BLE 标签修正
    （`Project build complete` / WEXIT=0 / `Hash of data verified.`）。
    第二次开机回读：`setup=off … ble=off`、引擎 `ready=1 word=你好小安`、
    `blocks=821 hits=0 dropped=0`（≈26 s 环境音，**0 误触发**）、`detect avg=2.75 ms worst=4.00 ms`、
    稳态 `int=43K/29K min=32K`。
    **但真人语音唤醒没称到** —— 卡在这台 PC 没有能出声的物理输出设备，详见 §18.4。
  - 2026-11-13 中午 **「对着屏说话」上设置页 + 出厂默认 OFF→AUTO**（`HANDOFF.md` §18.7）——
    补的是 owner 的硬约束「现场不用电脑」：这一格原来只有串口 `>micup` 能改、出厂还是关，
    换板或清 NVS 就永久开不回来。「声音」页加一行开关（开=AUTO、关=OFF，**FORCE 故意不上屏**：
    对着解不了 `mic_chunk` 的旧 App 硬发会被它关连接），读写 `carpet/mic_mode` **同一格**，不新开 NVS 键。
    新增台架口 `>micup default`：只抹这一个键回出厂档（整片擦 NVS 会抹掉 owner 存的 Wi-Fi，不能那么干）。
    **两态都用像素验过**（串口截图 466×466，`tools/zh_shot.py`，不走 HTTP）：存 auto 时那格开着、
    `>micup off` 后重开页关着，「扬声器」不受影响。**出厂默认实测**：抹键 + 冷开机后
    `@micup now=auto` / `@mic mode=auto`，同次回读 `ssid=Mate 60` 还在 ⇒ 没碰 Wi-Fi。
    没称到：手指点那一格（串口注不了触摸，写路径是代码证据 + 串口实跑同一个 set_mode）、
    这一版 linked=1 时的 `armed=1`（热点不在空中，`scan complete, 0 APs found`）。
    这一版 34 个文件 / **5900+ / 86- / 9,126 行 / 478,685 字节**，字库仍 154,528 字节；
    `verify_carpet_patch.py` 全新 scratch：`baseline copies restored: 18` / `git apply: OK (34 sections)` /
    `byte-compare: 32/34 identical`，差的两个 CRLF **485 / 114** 行 = 字节差（逐行数过）；
    `:core:test` **329 条 / 红 1 条** = photo 漂移，无新红。
  - 2026-11-13 上午 **上行那道闸修对了 + `>url` 栈溢出修掉**（`HANDOFF.md` §18.6，
    给 App agent 的交接在 `docs/APP-CHANGES-2026-11-11.md` §0）——
    真因是屏侧自己写的 `bool open = says4 && v >= 4`：ack 外层**没有 `v` 这个键**
    （App `encodeDefaults=false`），`num_or(root,"v",1)` 永远读到兜底 1 ⇒ 这条判据对
    **任何版本**的 App 都不可能成立，按键和唤醒词一起报「对着屏说话还没开」。
    改成只看 `ack.protocols ∋ 4`。**App 侧不需要改代码，App agent 是对的。**
    `verify_carpet_patch.py` 实跑（全新 scratch）：`baseline copies restored: 18` /
    `git apply: OK (34 sections)` / `byte-compare: 32/34 identical`；差的两个仍是
    `carpet_mic.c/.h`，这次逐行数过：工作树 CRLF **463 / 102** 行、patch 里 LF，
    字节差 463 / 102 **正好等于行数**，归一化后 `相同=True` ⇒ 纯行尾，不是漏打。
    这一版 34 个文件 / **5838+ / 86- / 9,064 行 / 473,685 字节**，字库仍 154,528 字节。
    （**已被下面 11-13 中午那一版取代：5900+ / 86- / 9,126 行 / 478,685 字节。**）
    **已编已刷已称**（`Project build complete` / WEXIT=0 / `Hash of data verified.`，
    只 `app-flash`，分区没动）：
    `ack：protocols=[4,4]（envelope v=1，契约规定它不上线，不作判据）⇒ 上行开`、
    `@mic mode=auto armed=1 phone_v4=1 rejected=0 dead=0 linked=1`、
    `@wake ready=1 word=你好小安 … on=1 inhibit=0 live=0 armed=1`。
    `>url clear` 在**链路通着**时不再崩：`carpet_url 这一趟用完还剩 3500 字节`
    （6144−3500=**用了 2644 字节**，比原来那档 2048 多 596 ⇒ 溢出是真因）、
    整趟 `reset? False`、`stack overflow`/`RTC_SW_CPU_RST` 各 0 次。
    **顺带挖出第三个 bug：栈的单位全仓库写错了 4 倍。** ESP-IDF 的 `StackType_t = uint8_t`
    （portmacro.h:78）、`idf_additions.c:50` 是 `× sizeof(StackType_t)` = ×1、
    `idf_additions.h:268` 明写 "number of bytes"、`task.h:1509` 高水位 "in bytes"
    ⇒ `usStackDepth` 和高水位都是**字节**。独立反证：板上最大连续内部 RAM 22,528 字节，
    若 6144 是"字"就要 24,576 字节连续 ⇒ 要不到，而任务起来了。
    已改 `carpet_link.c` 三处 + `screen_link.c` 两处日志/注释（`carpet_mic.c` 那句「8 KB」本来就对）。
    **两条用错单位算出来的假真因一并作废**：① `carpet_cmd` 4096→6144 的"依据" ——
    两次峰值都是 `4096−1684 = 6144−3732 = 2412` 字节，**一样深**，它在 4096 上从没溢出过
    （保留 6144 不冒回归风险，但它是**可压回 4096 的候选**）；② `link_task` 8192→4096 那条
    "32 KB 吃掉内部 RAM 导致 i2s DMA panic" —— 8192 从来就是 8 KB，**24 KB 差额不存在**，
    那次 panic 与这条栈的因果**从没称过**。详见 `HANDOFF.md` §18.6。
    **还没称到**：真人那一轮上行（要 owner 对着屏说话，屏侧造不出来）。
  - 2026-11-13 **B §3 场景环 + 换装**（owner「文档中提到的其他方面我看还是没做啊」）——
    下拉改三场景横卡（车 / 亲子 / 对话）+ 按场景给绒毛调色换装（脸不动，约 0.5 s 过渡）。
    新增 `avatar/muse_pixel.c`（25 行）+ `muse_pixel.h` 声明（4 行），`carpet_page.c` 1098→1145、
    `carpet_page.h` 46→58、`muse_input.c` 355→394（`>scene=`）。**路径清单这轮加了 `esp32/avatar/muse_pixel.c`**
    （它在 components/muse 之外，漏了换装实现就进不了补丁，对端机器会 undefined reference）。
    `verify_carpet_patch.py` 全新 scratch：`baseline copies restored: 20` / `git apply: OK (36 sections)` /
    **`byte-compare: 36/36 identical`**（这一轮工作树那两份 `carpet_mic.c/.h` 已是 LF，连历史那个 CRLF 差都没了）。
    这一版 36 个文件 / 6378+ / 95- / 11,161 行 / 607,186 字节，字库仍 261,748 字节。
    **已编已刷已称**（`Project build complete` / WEXIT=0 / `Hash of data verified.`，只 `app-flash`，分区没动）：
    串口 `选场景 亲子 → 换装 scene=2` / `选场景 对话 → 换装 scene=3`，四方向 + 单击回脸无回归；
    三张 466×466 截图实看：picker.png 三横卡（车蓝环高亮）、face-2.png 薄荷绒毛、face-3.png 紫绒毛，脸都保持奶白。
    **没称到**：手指真点那张卡（串口注不了触摸，写路径是代码证据 + `>scene=` 实跑同一个 `on_scene_clicked`）。
  - **11-13（B §5 默认氛围）**：`muse_settings.c/.h` 加 NVS `album_def` + `muse_settings_album_default()/set_…`，
    `muse_settings_ui.c`「声音」页加「默认氛围用相册」开关，`carpet_page.c` 右滑进氛围读它，`muse_input.c` 加 `>amb`。
    重打 patch：36 文件 / 6426+ / 95- / 11,209 行 / 609,552 字节。
    `verify_carpet_patch.py` 全新 scratch：`baseline copies restored: 20` / `git apply: OK (36 sections)` /
    **`byte-compare: 36/36 identical`**。真机称到：`>amb photo` → `>gest right` → `右滑 → 氛围（photo）`/`nav=photo`；
    `>amb clock` → `右滑 → 氛围（clock）`/`nav=clock`；amb-photo.png / amb-clock.png 两张 466×466 实看。
    **没称到**：设置页那格开关在「声音」页折叠线以下，串口注不了滚动，截图只拍到页上半
    （开关本体是代码证据 + `>amb` 与它同一个 NVS key 的实跑）。#33 同轮复称：carpet 构建下说话键不再出
    英文「SET UP MUSE FIRST」（talk33.png 实看为 NO WI-FI + 脸；英文串只在非 carpet 的 `#else` 分支、本构建编译不进）。
  - 重打命令（**`components/screen_link` 必须排除**，
  它就是 §3 那条 `cp -r`，进 patch 反而会让对端机器多一份）：
  ```bash
  cd D:/dev/projects/muse-gadget-sdk-main
  git add -N esp32/components/muse/carpet esp32/devices/sdkconfig.carpet
  git diff --binary cdc169d -- esp32/components/muse esp32/devices/sdkconfig.carpet esp32/main \
      esp32/partitions_muse.csv esp32/avatar/muse_pixel.c \
      > D:/dev/projects/carpet-sat-demo/firmware/screen/muse/muse-carpet.patch
  git reset -q esp32/components/muse/carpet esp32/devices/sdkconfig.carpet   # 别让状态多个暂存
  ```
  **`esp32/partitions_muse.csv` 是 11-12 这轮加进路径清单的**：它在 `esp32/` 根下，
  不在原来那三个路径里，漏了它补丁就少一行 `model` 分区 ⇒ 对端机器全片刷也没有模型，
  唤醒词永远 `ready=0`，而编译刷机全都"成功"。
  **`esp32/avatar/muse_pixel.c` 是 11-13 这轮（B §3 换装）加进路径清单的**：它在 `esp32/avatar/` 下，
  不在 `esp32/components/muse` 里。漏了它，`muse_pixel.h` 的声明（在 components/muse 里）会进补丁、
  `muse_pixel_set_scene()` 的实现却没进 ⇒ 对端机器编译直接 undefined reference。和 partitions 那条同一个坑。
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
#    patch 里带那份字库（carpet/carpet_font.ttf）。不想打整份补丁、只搬我们的目录时，
#    这一份必须单独拷过去 —— CMakeLists 里那行 embed 指的就是它，缺了直接 configure 失败：
cp firmware/screen/muse/font/carpet_font.ttf <muse>/esp32/components/muse/carpet/

# 2) 搬 Screen-Link 客户端组件
cp -r firmware/screen/components/screen_link  <muse>/esp32/components/screen_link
rm <muse>/esp32/components/screen_link/idf_component.yml
#    并用下面 §4 那份 CMakeLists.txt 覆盖它自带的（REQUIRES 写法不一样）
#    【2026-11-11 夜：这一句重新安全了。】前一晚这台机器的 <muse> 树里那份比仓库这份多东西
#    （未同步回仓库的 ws 栈阶梯 + `ws_probe()` 探针），整目录覆盖会把它们抹掉；那一摊现在
#    已经**反向同步回仓库这份**（阶梯留着、2048 那一档删了 —— 见 §16.3 和 §8 那条内存的），
#    两份 `screen_link.c` / `include/screen_link.h` 比过 SAME，`cp -r` 不再是破坏性操作。
#    以后再从工作树改这个组件，记得同步回来，否则这句又会变成"抹掉新东西"。

# 3) 配置 + 编译（四层 defaults：我们的那份排在最后）
idf.py -B build-muse-s3-175-carpet -DIDF_TARGET=esp32s3 \
    -DSDKCONFIG=build-muse-s3-175-carpet/sdkconfig \
    -DSDKCONFIG_DEFAULTS="sdkconfig.defaults;devices/sdkconfig.muse;devices/sdkconfig.muse-waveshare-s3-175;devices/sdkconfig.carpet" \
    reconfigure
idf.py -B build-muse-s3-175-carpet ... build      # 同一串参数，子命令换 build

# 4) 刷机 —— 【2026-11-13 起改了：第一次装这一版必须全片刷】
#    上一版这里写的是"只刷 app 分区，这一版没动分区表，别全片刷"。**那句话现在不成立**：
#    唤醒词往 partitions_muse.csv 尾部加了 model 分区，分区表本身变了，而且 srmodels.bin
#    要写进那个新分区。只 app-flash 的话分区表还是旧的 ⇒ esp_srmodel_init("model") 找不到
#    分区、返回空，唤醒词永远 ready=0，而日志里一句报错都没有。
idf.py -B build-muse-s3-175-carpet ... -p COM8 flash          # 全片：分区表 + app + srmodels.bin
#    全片刷过一次之后，只改代码（没动 CONFIG_SR_WN_* 也没动分区表）就可以回到快的那条：
idf.py -B build-muse-s3-175-carpet ... -p COM8 app-flash
#    判刷对了看两处：esptool 的命令行里要有 `0x822000 .../srmodels/srmodels.bin`，
#    以及每个写入段后面的 `Hash of data verified.`。
#    注意：全片刷会重写分区表，但**不会**擦掉 NVS(0x11000) —— 偏移没动，配网和设置都还在。
```

板子插这台机器时是 **COM8**（就是那颗 USB-Serial-JTAG，同时也是第二控制台）。

**四个必须照做的步骤，跳了会静默：**
- `reconfigure`：新增 `components/` 目录**不是 ninja 的监视输入**，不 reconfigure 的现象是
  "编译成功"但 `esp-idf/screen_link/` 根本不存在、日志里零句提到它。
- 判成败只看日志里的 `Project build complete`，**别看包装脚本的退出码**
  （`idf.py` 外面套一层 PowerShell/grep 时，`grep -c` 命中 0 就是 exit 1，会把成功的构建报成失败）。
- **唤醒词这一版 `reconfigure` 要联网**：`espressif/esp-sr ==2.4.7` 是托管组件，
  第一次配置时下载到 `managed_components/`。这台机器上还要设 `IDF_GITHUB_ASSETS=dl.espressif.com/github_assets`
  才拉得动它的模型包。下不下来的现象是 configure 阶段就报找不到组件，不是静默。
- **PowerShell 包装脚本必须 `$ErrorActionPreference = 'Continue'`**（不是 `'Stop'`）：
  esp-sr 的 Kconfig 会往 **stderr** 写 NOTE 行，`'Stop'` 把它当成 NativeCommandError，
  整轮在 esptool 启动之前就被杀掉 —— 看着像刷机失败，其实一个字都还没写进板子。

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

三块 tile：面（0,0）、设置（右滑到 1,0）、**车联页（上滑到 0,1）**。开机默认落在**面**那一格
（就是 Muse 那张会听会说的脸），上滑才进场景。

为什么不是左滑：横向那一轴已经被占了 —— 设置页自己的子页**也用横滑**，
`muse_ui_set_swipe_enabled(false)` 就是在子页里把 tile 滑动关掉好让手势归子页；
再挂一块横向 tile 会和它抢同一个轴。竖向原来没有任何竞争者，`LV_DIR_TOP` 是空的。
车联页的 `dir` 只给 `TOP`：从那儿往左右都是"面"的后面，不该能滑过去。

车联格里面是**三层**，都在这块玻璃上自己走完，不需要手机参与（2026-11-04 深夜那一轮改的）：

```
第 1 层 场景选择   五个场景摆成宫格（一行三张、116 的方卡、卡里一颗字 + 场景名）
                  车载 / 亲子 / 数字分身 / 时钟 / 图片播放   （点一张 = 进第 2 层）
第 2 层 场景页     这个场景里现在说什么 + 页底那排按钮 + 每条可点进第 3 层
第 3 层 明细       一条一个字段的大字读数，只回一层
返回有两条：每页左上角那颗「返回」，和**从左边缘向右滑**（≥60 px，阈值还没实测，见 §9.4）
```

第 1 层 2026-11-05 从"五行列表"改成宫格（他点名要"像 app 那样"）。两条值得记的：
卡片里那颗字**用的是场景名的第一个字**（车/亲/分/钟/图），不是图标 —— LVGL 自带那 32 个
FontAwesome 符号里没有汽车、没有人、也没有钟，硬凑只会印出一个对不上号的图；而字取自己名字里
的第一个，子集里一定会有（名字本身就要画）。宫格一行只摆得下三个，所以第二行是
**时钟 + 图片播放**，右边那一格空着 —— 这是手机 app 的流式排法，不是漏摆。
每格一颗色，全部取自 Muse 自己用过的色号（见 §5.6）；图片播放那一格整格压暗，
它这一版没接上，不该长得和其他四格一样能用。
**这一层一个值都不显示**：条目右边跟着帧变会让人以为手机在报这个数，那条红线从列表那版就有。

第 3 层不设"层数上限"这种说法：它是明细，不是新的一屏功能。**图片播放这一版是明着的空页**
（屏上不放假数据，进去只有一句"还没接上 · 不放假数据"），别当已通去讲。

### 5.1b 屏在哪一页，手机怎么知道

第 1、2 层每换一次页，屏就往手机发一条 `command{cmd:"page_report", args:{page:"…"}}`；
第 3 层（明细）**不发**，设置格**不发**。发的是**通报**，不是申请 —— 手机不许因为它改任何东西，
也**不会**把它写回 `state.ui.page` 来命令屏（那个字段是**回声**，屏这边只读不切页）。
断线重连（`hello` 发出去）之后会补报一次当前页，不然手机上那一格要空到下次真翻页。
下行 `dialog{dir:down, action:"kids"}` 会让屏翻到那一页 —— 只翻页，不做别的事。

### 5.2 一条硬的接线规则

`screen_link` 的回调**全在链路任务里跑**。`on_state` 里阻塞超过 10 秒，组件会认为链路死了、
自己断开重连。而 Muse 的 UI 是**拉取式**的：只有一个 LVGL 定时器（`frame_tick` → `update_chrome`，
0.25 秒一档）读状态并做差分上屏，别的任务一律不许碰 LVGL。

所以两边接法只有一种：**回调里只存快照 + 置脏，取值在 tick 里做**。
现成的样板就是 `carpet_link.c`（写）+ `carpet_page.c:carpet_page_tick()`（读），
`set_line()` 里那句 `strcmp` 就是差分——没变就不重画。

### 5.3 每一项分别吃什么、画在哪

字段名都取自 `protocol/screen-link.schema.json`（`state` 那一支），不是我编的：

| 数据 | 来自 | App/服务端现在写了吗 | 屏这边 |
||-|-|-|-|
| 链路 | 有没有收到 `state`（`screen_link_state_gap_s()`）、协商档位 | 有 | **画在第 1 层顶上那句**（没网=等网络、没连=未接入、等首帧、>10 秒=掉线重连、>3 秒=卡 N 秒、否则=已接入。契约号 11-05 起不上屏，只在串口 `linked, contract v2` 那行） |
| 手机画哪张 | `state.ui.card`（`status/sat/dialog/emotion/clock`） | 有 | 画在**带按钮那两页（车载/亲子）的副行**"在画 X"；页底三个按钮请求换的就是它。**时钟页那一条行值也是它**（这一页的功能就是"看手机在画哪张"） |
| 卫星 | `state.sat.phase` + `next_pass_in_s` 倒计时 | 有（`core` 的 `DemoRuntime`/`SimulatedEphemerisSource`） | 有：车载页「卫星」、亲子页「过境」；点条目 = 进第 3 层看明细 |
| 对话 | 独立帧 `dialog{dir, source}` | 有（`ScreenLink.kt` 发 v2 dialog 帧） | 有：车载/亲子各一行「对话」，数字分身页叫「答句」（`source` 契约里不是必填，缺了只写"手机在说"，不补一个念不通的尾巴；5 秒后按约定清空） |
| 呼救 | `state.sos.level / since_ts / attempts / sat_ack`（下行）；上行是 `command{sos_press}` | 有 | 有：车载页「呼救」、亲子页「安全」；**上行还没接**（§5.5，物理入口不能是 BOOT 长按 5 秒） |
| 表情 | `state.ui.emotion`（`neutral/happy/excited/sad/worried/sleepy/thinking/null`） | 有 | 数字分身页「情绪」那一行；**脸本身也在演它**（§5.4，`worried` 那一档的让路规则和琥珀点在 §5.4.1）。`>carpet` 回读里照样有这一行 |

**接线规则已经照 §5.2 落进代码**：`carpet_link.c` 的 `on_state` 只做一次
`cJSON_Duplicate` + 换树（mutex 保护），`carpet_page_tick()` 在 LVGL 任务里取值、做差分。
**契约里的英文枚举一律不直接上屏**：五张中文对照表
（`sat.phase / ui.card / ui.emotion / sos.level / dialog.source`），
认不出的值落"未知"而不是把原样印出去 —— 服务端加一个枚举值，屏上不该冒英文（老固件那条红线 #31）。
`emotion` 是 `null`（= 这一帧不带情绪）时落回**中性**，不是保持上一帧。
整棵 state 树每帧整体换掉，所以"帧里没带的字段还挂着旧值"（老固件的 #34）在这一版是**结构性做不到**的。

事件那边 `ack` 是组件无条件回的（`screen_link.c:370`），不接 `on_event` 也不会让手机端重试风暴，
只是屏上不弹横幅 —— **屏上现在就没接 `on_event`**。

**这些值有一条不看眼睛的验法**：串口发 `>carpet` 会把链路数字、各项当前值、
还有 `sent=N last=<命令名>`（页上按出来的）、`nav=<页名> reported=N last=<页名>`（翻出去的和报出去的）
原样打回来（`carpet_link_dump()`，在 `#if LV_USE_SNAPSHOT` 外面，正式构建里也在）。另一台电脑没有抓屏环境时，
这条就是判据来源，输出长什么样见 §9。

发串口命令**要先发一个裸字符 `w` 把屏叫醒**：Muse 的自动熄屏（默认 120 秒无操作）让
`frame_tick` 在 `update_sleep()` 那里直接 return，而 `carpet_page_tick()` 挂在它后面的
`update_chrome()` 里 —— 屏睡着的时候 `>page kids` 会被收下、`nav` 却不动，看着像翻页坏了。
`>` 那一行**不算按键**，所以它自己不会唤醒屏幕（`w`=醒、`z`=睡，`muse_input.c` 的 `serial_task`）。

### 5.4 表情这一路的真相：7 个情绪要塞进 6 个模式

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

#### 5.4.1 `worried` 这一条 2026-11-06 接上了（#73），三道让路一道不能少

上面那句警告不是抽象的：这条链路的答句**也在写 `mode`**（`face_offer()` 最后一行
`face_set_mode(answer ? SPEAKING : THINKING)`），所以 `worried` 和答句抢的是同一个字段。
拍板的优先序是**答句赢**，实现落在 `carpet_link.c` 的 `on_state()`：

```
帧里 ui.card=maint 且 ui.emotion=worried  →  MUSE_MODE_THINKING（不给 ERROR：那是报警脸）
否则                                       →  MUSE_MODE_IDLE（少了这一条脸会一直"想"下去，#34 那个老坑）
```

三道让路，都在 `s_lock` 里判（`s_face_live` 是 UI 任务写的，出了锁再读就可能抢掉它刚接下的那句）：

1. `s_face_live` 为真不写 —— 语音正在播就把脸换走，**嘴立刻不动**，而口型是这一轮的主功能；
2. 只在当前 mode 是 `IDLE`/`THINKING` 时搬（`face_mode_takeable()`）—— `SPEAKING`/`LISTENING`/`ERROR` 各有各的主人；
3. 判在**解码这一层**：`carpet_state_view_t.maint_worried` 是个布尔，页面只认它。
   让 `carpet_page.c` 去比"养车卡""担心"这种中文串，等于把改措辞的成本摊到排版那边。

这条是每秒一帧判一次，**不是 0.25 秒轮询** —— §5.4 警告的正是轮询那个频率。答句念完之后
hold 到期把脸收回 idle，下一帧 `state`（≤1 秒）再把"担心"补上，所以不需要额外的记账。

**脸被答句占走时那句提醒还剩什么**：场景页「对话」「情绪」两行行首点一枚琥珀色小点
（`carpet_page.c` 的 `make_dot()`，12 px，`COLOR_HUE_AMBER`，不亮时用 `HIDDEN` 所以不占位、版式和没有它时一样）。
串口 `>carpet` 现在多回一个 `maint_worried=0/1`，那是那个布尔本身，不是屏上的中文串 ——
"这一帧到底到没到"要能不看眼睛就称出来。

**`>maint` 是这一条的开发口**（2026-11-07 加，`muse_input.c:950-959` → `carpet_link_bench_maint()`）：
本机造一帧 `ui={card:"maint",emotion:"worried"}`，交的是链路收帧时**同一条** `on_state()`，
所以它称的是那一帧真走得通，不是我另写的一句 if。`>maint off` 造中性帧，专称"搬回 IDLE"那一条。
两条用法：① 空屏发 `>maint` → `>carpet` 应回 `maint_worried=1` 且 `mode` 从 1(idle) 变 3(thinking)；
② 先 `>say 一句长话` 让句子挂在脸上再 `>maint` → `mode` 必须**还是 4(speaking)**（第一道让路），
这时这一条在屏上只剩那枚琥珀点。都不联网、不接模型。
**上板称过了：2026-11-08，COM8，`ELF 4f6f8b4ad`，三条都是串口原文**（11-07 那次没称成是因为板子从设备上掉了，
不是判据有问题）：

```
基线      @carpet face=0 mode=1 …  card=- emotion=- maint_worried=0
空屏      @maint sent=1 view.maint_worried=1 card=养车卡 emotion=担心 face=0 mode=3
挂句子    @carpet face=1 mode=4 at=255/255   ←  >say 那句 255 字节还挂着
句子在时  @maint sent=1 view.maint_worried=1 … face=1 mode=4      ← 第一道让路：没被搬走
>maint off @maint sent=0 view.maint_worried=0 emotion=中性 face=0 mode=1  ← 第三道：搬回 IDLE
```

判据读的是 `carpet_link_state()` 那个视图，和页面上那两枚点、脸上那一个是同一个来源。
**唯一还没称到的是那枚 12px 琥珀点本身**：它只能靠眼睛或抓屏，而抓屏通道要 IP，
这块板那天扫出来 `0 APs found`（`D:\dev\projects\carpet-sat-demo\firmware\screen\HANDOFF.md`
那份 11-08 追加的 §3 记了全部数字和卡在哪）。

### 5.5 屏能往手机发什么（我们自己的 App 要做服务端就得收这些）

`screen_link_send_command(cmd, args)`，`cmd` 只收 schema 里那十个名字，别的当场退回
`ESP_ERR_INVALID_ARG`：`sos_press / sos_cancel / track_pull / set_demo_mode / set_fence /
reboot_ui / card_next / card_auto / poke / page_report`。

- `card_next` 的 `args.dir = ±1`、`poke` 是单击 —— **屏这边已经接上了**（2026-11-04 这一轮）：
  带按钮那两页（车载/亲子）页底三个按钮是 上一张=`card_next dir=-1` / 下一张=`card_next dir=+1` / 交回=`card_auto`；
  `poke` 现在挂在**第 3 层明细页**那颗「戳一下」上（第 2 层的行点了是翻页，不是戳屏 —— 一层只干一件事）。
  屏只发**请求**，服务端不许收到"屏自己挑了张卡"这种东西 —— 屏上不出现 `card_set` 这条命令，
  也永远不要加（`ScreenLink.kt` 里那句"没有 card_set 是故意的"是同一件事）。
- `page_report{page:"…"}` —— **屏在第 1/2 层每换一次页发一条**（第 3 层和设置格不发）。
  这条是**通报**，不是申请：手机端只拿它更新首页那一格和"这句话归哪一页"，
  **不许**反过来靠它决定屏该显示什么。取值必须是 schema 里那七个页名之一，
  和 Kotlin `ScreenPage`、屏侧 `PAGE_NAMES` 由 `SchemaDriftTest` 逐条比着。
- **屏上的点击不直接碰 socket**：点击只往一个四条深的队列里塞一条，发送在
  `carpet_cmd` 任务里做。原因不是洁癖 —— `screen_link_send_command()` 走 websocket，
  链路卡住时它能阻塞接近两秒，而点击回调跑在 LVGL 任务里，一阻塞整块屏就冻在那儿。
- 串口 `>carpet` 现在多回读一行 `@carpet sent=N last=<命令名>`：按了按钮但 `sent` 不动，
  就是点击没吃到（版式/遮挡问题）；`sent` 动了屏上"在画"那一行却不变，是服务端没接或没写回
  `state.ui.card`。这两种坏的修法完全不同，别靠猜。
- 三条都是**请求**：画哪张卡最终仍由服务端写回 `state.ui.card`，所以 SOS 进行中你可以顶掉切卡请求。
- `screen_link_send_dialog_up(text, source, ...)` 已经有了（`turn_id` 形如 `s-<开机随机>-<自增>`，
  跨重启唯一，服务端按它去重）。

**SOS 的物理入口不能是 BOOT 长按 5 秒** —— Muse 把那个组合当成恢复出厂用了。这是这一版必须换掉的老设计。

### 5.6 字号：这一页现在自带一份小字库，别再按 16 px 想

Muse 给的中文只有 **16 px 位图**两档（`muse_font_cjk_16.c` 是 Unifont 1bpp 约 1.2MB flash，
`muse_font_cjk_noto_16.c` 是 Noto Sans SC 4bpp、GB2312 那 6763 字、约 910KB，车联页 select 的是这份）。
位图字体的字号是刻死的，**这一页嫌字小就没有"调大一号"这条路**。

**先分清哪块屏面用哪份字，别为改设置页去重跑子集脚本**（11-13 唤醒词那一格就踩过这个疑问）：
- **只有 `carpet_page.c`（车联页）用下面那份 683 码位的 `carpet_font.ttf` 子集 + tiny_ttf。**
- **设置页和菜单走另一条**：`muse_lang.c` 的 `muse_lang_montserrat()` 把 Montserrat
  14/16/20/28 四档各复制一份、`.fallback` 挂到 `muse_font_cjk_noto_16`（**全量**位图，
  从它的 cmaps 实解出 **7765 个码位** = GB2312 那 6763 字 + ASCII 与标点）。
  所以设置页加中文格子**不需要动字库**，代价是中文只有 16 px 一档。

做法是把老固件那套搬过来了：页面上用 LVGL 的 **tiny_ttf** 现算字号，字库是一份
**只收本页会画的字**的 TTF 子集 —— `carpet/carpet_font.ttf`，154,528 字节（683 个码位），
由 `../tools/muse_font_subset.py` 造：

```bash
# 三个参数：源字体（整片 Noto，这台在 D:/dev/tmp/carpet-noto.ttf）、扫哪个目录、写到哪
D:/dev/python311/python.exe firmware/screen/tools/muse_font_subset.py \
    D:/dev/tmp/carpet-noto.ttf \
    <muse>/esp32/components/muse/carpet \
    D:/dev/tmp/carpet-muse.ttf
# 然后两处都要换：fork 里那份是编译用的，我们仓库这份是别人重编用的
cp D:/dev/tmp/carpet-muse.ttf <muse>/esp32/components/muse/carpet/carpet_font.ttf
cp D:/dev/tmp/carpet-muse.ttf firmware/screen/muse/font/carpet_font.ttf
```

三条规矩：

1. **改了车联页的文案就要重跑这一条**。漏收的字不是豆腐块 —— `fallback` 挂在 16 px 那份全量上，
   表现是**一行里字大小不一**，很好认，但也很容易被当成渲染 bug 去查半天。
2. 那工具是**扫源码里的非 ASCII**，连注释一起扫（所以词表比页面真正画的字多，
   这一版扫到 685 个码位、子集里真的收下 683 个，缺的 `⇒` 和 `・` 只出现在注释和 EXTRA 里、不会上屏）。
   想再压尺寸就得不扫注释，但那会漏掉查表翻出来的中文，别省这一步。
3. 字号阶梯三档：**30 px** 只给宫格那颗字（视觉锚点，不承担读）、**28 px** 页面标题与条目名、
   22 px 值/副行/按钮/场景名。再大就不行了 —— 不是字库的问题，是 466 的圆里塞不下：
   300 宽的一列最高只能 356 px（半宽 150、半径 233 算出来的），实测最满的场景页 338 px。
   宫格那一层不走这条：它是定宽定高的盒子 + 绝对坐标，116 的方卡一行三张，
   每卡的四个角都按半径 233 逐角称过（最紧的一张外角 223 < 233）。
   两处算式都写在 `carpet_page.c` 开头那几行注释里，改尺寸前先复算那里。

`devices/sdkconfig.carpet` 里因此有三条，**第三条不是调优，是压 bug**：

```
CONFIG_LV_USE_TINY_TTF=y
CONFIG_LV_TINY_TTF_CACHE_GLYPH_CNT=64
CONFIG_LV_TINY_TTF_CACHE_KERNING_CNT=0     # 不为 0 会画坏字（LVGL 的跨缓存释放，另一台实拍过）
```

字模缓存落在 PSRAM：Muse 把 LVGL 的分配器接到了 PSRAM（`muse_lv_mem.c`），
所以这三档缓存不会来抢内部 RAM。开机判据就一行：

```
I (2692) carpet_page: tiny_ttf 154528 字节 → 28/22/30px
```

**"内部 RAM"这四个字在这台板子上是硬约束**，2026-11-05 就是它把链路饿死的：Muse 这一版跑起来之后
整块内部 RAM 只剩 12~13K、最大连续块 4K，而 `esp_websocket_client` 那个任务的栈要从内部 RAM
要一整块连续的 —— 我们原来给它 8192，真机上直接 `Error create websocket task`。
处理见 §9.5，别把它当成"调调参数就好了"的事：**新加一个用内部 RAM 的东西之前，先算这一本账**。

镜像尺寸这笔账要盯着：app 分区 4M（0x400000），v3 这一版 `muse-gadget.bin` 是 **3,346,432 字节
（0x331000），余 847,872（20%）** —— 这个数是构建日志里 `Smallest app partition` 那一行给的，
别拿 4,194,304 减出来的数当分区大小（分区表留了尾部对齐，减错就得一个假余量）。
字库那份 154,528 是直接从整片 Noto 里裁出来的；**整片子集（2.18MB）这条路是走不通的**，
装不进这个分区，所以才要工具。

### 5.7 答句的声音（契约 v3）：谁放、口型跟谁、翻页跟谁

服务端合成好的 PCM 分块推下来，屏放喇叭。这一节说清三件容易做错的事。

**来向只有一条**：`command{cmd:"audio_chunk", args:{turn_id, seq, total, rate, b64}}`，
`rate` 只认 16000。新开一种 `t` 是错的：组件收到不认识的 `t` 关连接，收到不认识的 `cmd` 只丢那一帧
（`screen_link.c handle_frame()` 那两条分支）—— 音频骑在 `command` 上，没升 v3 的屏才会继续正常看状态。

**缓冲是一条 2MB 的 PSRAM 线性缓冲，不是环形**（11-06 从 512KB 提上来的，`carpet_audio.c` 的
`AUDIO_CAP` 和 core 那份 `AudioChunks.MAX_TOTAL_BYTES` 必须是同一个数，改一处就要改两处）。
故意的：写的人只有链路任务、读的人只有放音任务，
两个头各自朝前走，环形缓冲要多维护一套头尾同步，而这段音频从来不需要回头读。
判据全是"丢整段"，没有"放半段"这一档：`seq` 不等于期望、`total` 超容量、单块超 2560 字节、
写爆缓冲 → 整段丢掉并记 `drops`；`turn_id` 变了 → 清缓冲重来；链路断了 → `carpet_audio_abort`。
**文字答句走 `dialog` 那一帧，跟这里没有任何依赖** —— 语音失败最多是没声音，扣不下答句。

**口型跟的是真能量**：v3 之前那条 `0.22/0.72` 来回toggle 是"看着像在说话"的唯一来源，
它现在只在**这一轮没有音频**时才用（别把没声音那一路也弄成没嘴）。有音频在放时
每 20ms 写一次 `muse_state_set_level(muse_audio_level())`，那是真 RMS。
§5.4 那两条约束（值真变了才写、Muse 自己的 SPEAKING 不抢）一条没放松。

**翻页跟的是播放位置**，不是墙钟：带子上那一页显示到第几个字 = `len × played / total`。
`FACE_HOLD`（6 秒）在有音频时只随播放延长、播完就收 —— 上一轮踩过的坑是"hold 被一直往后推，
带子永远不收回"，这一版靠"延长有上限且只在 live 时延长"挡住。锁的顺序是 `s_lock` → `carpet_audio` 那一把，
反过来的地方没有。

**两条回读**（`>carpet` 都会打）：
```
@carpet screen=466x466 round=1 touch=1 ppi=372      ← hello.screen 报的那一项，服务端排版用
@audio turn=… wrote=… total=… played=… live=… level=… frames=… drops=… last=…
```
`>audtest 3` 不联网造一段 440Hz「音节+静音」，专证三件事：节拍（`@audio at …/…` 每秒往前挪）、
口型（`level` 不是一常数）、分页（字跟着声音走）。

**这一节还差的**：上板。判据三条 —— `@audio at` 那行每秒动、`level` 随音节起伏、
**`drops` 的增量为 0**。为什么是增量不是绝对值：`drops` 是 `drop_locked()`
（`carpet_audio.c:90-95`）在锁里 `++` 的**开机以来累计计数**，只有复位才归零 ——
一块开了几小时的板子上它必然非 0，把判据写成"等于 0"永远过不了，而过不了的时候人第一反应
是去怀疑链路，其实那只是历史。要么重启之后称，要么称一段前后各读一次 `>carpet` 比差值。
PC 那半已经在真链路上称过（彩排台 `--voice` + `node simulator/linkcheck.mjs … --voice`：
92 块 / 233,740 字节 ≈ 7.3 秒、seq 连续、最大帧 3570 字节 < 预算 3800）。

### 5.8 叫醒熄屏的屏（契约 v3 第三条下行，11-10 屏侧已落地）

`command{cmd:"wake"}`，不带参数。屏收到走的是 **POWER 键那条路**：熄屏清掉、面板起来、
Wi-Fi 立刻重连（不等拨号退避）。**不回 ack、不重试、不排队** —— 醒了下一步它自己会推
`state` / `page_report`，那才是证据；发出去 3 秒这条连接没动静就按「没叫醒」跟用户说话。

什么时候发：**手机侧的唤醒词命中**那一下，或者用户点「叫醒圆屏」。**答句不要额外发** ——
`dialog{dir:"down"}` 在屏侧本来就会点亮（11-10 起 `face_offer` 自己会递这一次叫醒）。

为什么唤醒词不在屏上：熄屏进 rest 时麦克风/codec/功放是**断了电**的，而这套 SDK 里没有
esp-sr / WakeNet。要"屏一直听着"就得推倒现在的功耗模型 —— 见 `HANDOFF.md` §15.14。

**硬前提：那条 WebSocket 还活着。** 电池上熄屏满 2 分钟屏会 `nap`（关 Wi-Fi），那时这一帧送不到，
而且不报错。所以这一条只在设置页「熄屏后保持联网」开着时成立；
App 侧配套要改的四条（发帧、partial WakeLock、退出别停服务、`acceptScreens` 跟守护联动）
写在 `docs/APP-SCREEN-CONNECT.md` 第十节。

## 6. 现场怎么连（拓扑与地址）

契约里拓扑是**反的**：手机是服务端，屏是客户端，屏连上去并先自报 `hello`
（`ws://<服务端>:8443`，名字带 443 但它是明文 ws）。服务端 hello 白名单没过就 `close(1000, "unknown screen")`。

- **接入点地址现在是运行期可设的**（2026-11-08 加的，为的是"用手机连屏"不该要求重刷）。
  读的顺序是 **NVS(`muse` 命名空间 / `link_url`) → 出厂常量 `CONFIG_SCREEN_LINK_URL`**，
  三个入口写读的都是这一份，不是三套状态：
  - 屏上 **Settings → WI-FI → Access point** 那一格（用的就是 Muse 现成那套键盘，
    `muse_settings_ui.c` 里只多了一行 `row()` + 一段 `note()`）；
  - 串口 **`>url`**：不带参数 = 回读"现在拨的是哪条 + 来源 NVS 还是 factory"，
    `>url ws://a.b.c.d:8443` = 存进 NVS 并**立刻重拨**（先 `screen_link_stop()` 发 bye 再 start，
    所以退避阶梯从头开始，不用等它转完），`>url clear` = 抹掉那一格、回到出厂那条；
  - 出厂常量本身：**回落到 `ws://192.168.43.1:8443`**（跟踪的 `devices/sdkconfig.carpet` 一直是这个值，
    11-08 这轮改的是本机那份 `build-muse-s3-175-carpet/sdkconfig`，它被现场测试地址顶掉过）。
    所以**手机开热点那一档，屏上一个字都不用填** —— 手机在它自己的热点里就是 `.1`。
  - 为什么还留串口那一条：1.75 寸圆屏上用屏上键盘敲一个 IP 是罚人；接电脑的时候走 `>url`，
    没电脑的时候才用那一格。
  - 什么时候真需要填：热点开了「客户端隔离」，屏拨 `.1` 拨不通，就把服务端在那张网里的地址
    （电脑就是 `ipconfig` 那个 IPv4）填进去。
  - 判据（不用眼睛看屏）：`>carpet` 多一行 `@carpet url=… src=nvs|factory`，`>url` 另印
    `now= / saved= / linked= / dialed= / wifi= / ssid=`。来源这一项必须报出来，
    否则"改了没生效"和"生效了但对面没起服务"在现场分不开。
  - **上板称过（2026-11-08，`ELF 4f6f8b4ad`）**：`>url 192.168.1.17:8443` → `ESP_ERR_INVALID_ARG`
    且回读没动；`>url ws://192.168.1.17:8443` → `src=nvs`；**拉 RTS 真复位一次**之后开机日志里就是
    `muse_settings: … link ws://192.168.1.17:8443`、`>url` 仍 `src=nvs`；`>url clear` → `(built-in)` + `src=factory`。
    **`linked=1` 那半没称到**：那天屏自己扫网是 `link.wifi: scan complete, 0 APs found`（电脑在 5GHz 那张网，
    S3 没有 5GHz 射频，手机热点没开），所以"和服务端真的连上"要有网。全部数字与卡点见 `../HANDOFF.md` §3。
- **屏上连 Wi-Fi 的优先级倒过来了**（#R2；这条改的是 Muse 自己的设计，所以要把为什么和怎么退回写死）。
  原来 `main/app.c` 那句注释写着 "Menuconfig override takes priority"：编译期 `CONFIG_HOMEHUB_WIFI_SSID`
  非空（本机是 `aa123`）就**让 NVS 里的 ssid/密码完全不起作用** —— 那等于用户在屏上 Wi-Fi 页
  填了手机热点也不会生效，那一页对连手机这件事是假的。现在：**NVS 里有用户填的网络就用它，
  没有才用编译期那条**。两处一起改才算，少一处就是"看着改了其实没重连"：
  - `main/app.c` 开机连网那段：`bool use_override = ovr_ssid[0] && !have_nvs;`
  - `main/muse_glue.c`：新增 `dev_override_now()`（= 编译期覆盖**且** NVS 那份是空的），
    keeper 里原来两处 `!WIFI_OVERRIDE` 换成它。只改 `app.c` 的话，开机之后在设置页换网络时
    keeper 永远不重连（那两处判断把它整条关掉了）。
  - 当初为什么要有那条编译期覆盖：调试隧道吞吐时不想每次刷机都重新配对。这条意图没变，
    变的只是它现在是**兜底**而不是**压制**。
  - 退回：`app.c` 删掉那句 `&& !have_nvs` 并把 `use_override` 换回 `ovr_ssid[0]`，
    `muse_glue.c` 两处换回 `!WIFI_OVERRIDE`。
  - 不刷机就退回编译期那条 SSID 的口：串口 **`>wifi clear`**（走 `muse_settings_set_wifi("","")`
    → `wifi_known_forget_all()`，把 NVS 的 ssid/password/那一串列表一起抹；**密码不印出来**），
    然后重启一次才看得见 —— keeper 在 NVS 空了以后不会再自己拨编译期那条，那是开机那一次的事。
  - **这条倒置在硬件上称过（2026-11-08）**：那块板 NVS 里正存着用户早先在设置页填的热点名，
    开机日志 `link.app: saved network beats the built-in creds in NVS, reconnecting (first: 吨吨吨的Mate 30 5G)` ——
    换底座那几年这条一直不会生效，现在它作数了，`aa123` 退成兜底。同一行也证明"密码不印"：只有 `ssid=`。
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
  这条口是**老 `app/` 那棵树的**（`main/screen_shot.c` 起的 httpd）。**Muse 这棵树没有它** ——
  全树 grep 没有 `8444`，也没有 `SCREEN_SHOT_PORT`；谁在 Muse 侧写下"抓屏要 IP / 要开 8444"，
  那是把两棵树记混了（11-08 就在这句话上错过一次，见 HANDOFF 的 11-08 那节）。
  Muse 侧的抓屏是**串口触发的 LVGL snapshot**：`muse_input.c` 里 `#if LV_USE_SNAPSHOT` 那一段，
  按键 `p` → `muse_ui_request_snapshot()`，串口回 `SNAP BEGIN w h` … `SNAP END`；
  开关是 `devices/sdkconfig.carpet` 里那条 `CONFIG_LV_USE_SNAPSHOT=y`。
  现成的用法别自己写：`tools/zh_shot.py`（一次会话、等启动、逐页 3 秒再按 `p`）。
  回滚到老 `app/` 时那条无鉴权口会跟着回来，所以红线照旧：上展台前改回 0。

- **图形验收走哪一条构（11-08 定，别再让下一个人自己试）**：走**车机这一构本身**，
  不另开 bench 构建，也不用 `-d CONFIG_LV_USE_SNAPSHOT=y`。
  开关就在 `devices/sdkconfig.carpet:28` 那一条 `CONFIG_LV_USE_SNAPSHOT=y`，
  11-08 中文化那轮加进去的，**不是从 `devices/sdkconfig.muse-bench` 继承来的** ——
  构建脚本那条 defaults 链是
  `sdkconfig.defaults; devices/sdkconfig.muse; devices/sdkconfig.muse-waveshare-s3-175; devices/sdkconfig.carpet`
  （前三份都没提这一条，carpet 排最后所以它说了算），`sdkconfig.muse-bench` 根本不在链里。
  理由：要称的恰恰是中文字形、96 字节折叠、`max_width 130` 那点截断，
  这三样只在 `CONFIG_MUSE_CARPET_CARDS=y` + `CONFIG_MUSE_UI_LANG_ZH=y` 这套组合下才是真的；
  换一份配置去拍，拍到的是另一套字体档，绿灯是假的。
  这一条也不是"产品开关" —— 抓屏只有按 `p` 才走，而 `p` 只能从 USB 串口来，不碰网络。
  **一个会误判的坑**：判定别看 `esp32/build-muse-s3-175/sdkconfig`（那份 5078 行确实是
  `# CONFIG_LV_USE_SNAPSHOT is not set`，11-08 就有人读了它然后断言"车机构建里抓屏编译不出"），
  要看 `esp32/build-muse-s3-175-carpet/sdkconfig:5078` —— 两个目录名只差 `-carpet`，行号都一样。
  别猜，直接称：按 `p` 回 `SNAP BEGIN w h 144` 就是开着，回 `SNAP OFF` 就是没开
  （`muse_ui.c:1919 send_snapshot()` 的 `#else` 分支）；`tools/zh_shot.py` 拿到 `SNAP OFF`
  会印 `no screenshot`，不会塞给你一张空白图。
  线上格式（`muse_ui.c` 里 `enum { RAW = 144 }`）：`SNAP BEGIN <w> <h> 144` + 每行 base64 的
  RGB565（一行 144 字节原始宽，为的是塞进驱动那 256 字节 TX ring）+ `SNAP END`，全走 console 口；
  解码用 `esp32/tools/muse/snap.py` 的 `decode_snapshot`，别自己写。
  串口开页那几条 bench（`>ui=settings`、`>ui.settings=home wifi muse ble sound sleep battery power`、
  `>ui.demo=`、`>ui=carpet`）也圈在同一个 `#if LV_USE_SNAPSHOT` 里（`muse_input.c:1038` 起那一段），
  所以这一构开着它们就一起在；`>carpet` / `>page` / `>url` / `>zh` 那几条回读**不在**这个闸里
  （`muse_input.c:904` 那句注释就是写这个），关抓屏的构里照样能称链路。
- Muse 的 App（gadgets.muse.ai）不是硬门槛：屏上 Settings → Wi-Fi 有开关、扫描列表和
  "Other network…" 手输，官方那句写得很明白 —— *"Pair with the Muse app to use your account;
  a device token here overrides it."* 但这台 PC 到 `gadgets.muse.ai`/`api.muse.ai` 要走代理
  （`127.0.0.1:7890`），板子用不了代理；`raw.githubusercontent.com` 直连超时，上游归档走 gh-proxy。

## 8. 还没做的（不要在这里以为已经做了）

1. ~~`on_state` → 五行数值~~ **已经上板验过**（§9）：倒计时在走、`dialog` 按时清空、表情换得动。
2. `state.ui.emotion` → `muse_state_set_mode()`（§5.4，**接法有条件，照那段的两条约束做，否则砸掉口型动画**）。
3. ~~行可点 → `card_next`/`poke`~~ **已经接完**（§5.5：三张卡 = `poke`，页底三个按钮 = `card_next ±1` / `card_auto`；
   发送按那条说的挪进了 `carpet_cmd` 任务，点击回调只入队，所以界面不会被 2 秒阻塞拖住）。
   **还差的是 `sos_press`** —— 它需要一个屏外的物理入口（见第 5 条），不是这一页上的一个按钮就能算数。
4. `on_event` 没接 → 屏上不弹横幅（`sat_visible`/`fence_exit`/`downlink_msg` 那三类）。
   `ack` 组件已经无条件回了，所以服务端不会重试风暴，只是观众看不见。
5. SOS 的物理入口挪走（BOOT 长按被上游占作恢复出厂）。
6. ~~大字号 CJK 字体~~ **做完并上板**（§5.6：123,804 字节的本页词表子集 + tiny_ttf 24/20 px）。
   展台距离真要不要更大，是下一件事 —— 现在这套是 24/20，还能往上加，
   但加一档就要重排这一页（圆里的余量已经用完了，见 §9.1 最后那条）。
7. 语音分两半，别当一件事讲：
   - **说（服务端合成 → 屏放）这一半代码已经写完**（§5.7 + 契约 v3），PC 那半在真链路上称过
     92 块 / 7.3 秒一条不少，**屏这一半还没上板**，所以这里仍然不算"做完"。
   - **听（屏上的麦克风 → ASR → 我们 App 的答题）这一半完全没有**：屏侧"听/说"现在是 Muse 自己
     那条云链路，**和我们自己的 App 无关**。
     **2026-11-10 更新：做法不再依赖"改 Muse 的 endpoint"。** 契约 v4（`protocol/screen-link.md`
     「上行音频通道」）定的是**在 `voice_task` 里取它已经拿到的那 20 ms 块**，经 `mic_chunk` 推给我们手机，
     手机转写 + 答题，答句照旧走 v3 下行。Muse 那条云链路不动、也不改它的四个字段。
     **原来记在这里的那条硬坑已改判**：`muse_audio_read()`"开机 5.7 秒之后一律失败"不是通路坏了 ——
     `voice_task` 在不 rest 时每 20 ms 常驻读麦克风，而 `muse_audio_read` 自己不加锁，
     串口探针是**第二个读者**，等满 esp_codec_dev 那 1000 ms 令牌超时后打出
     `The channel is not enabled`（那是超时日志，不是状态位）。判据已称到：进设置页声音那一栏对着麦克风拍手，
     **电平条和 dBFS 有反应**（owner 实测）→ 通路本来是好的。所以**上行不许新开读者**。
   - **屏不出声的真因已称到并修掉（2026-11-11）：播放任务从来没起来过，不是链路、不是手机、不是模型。**
     两行回读就是全部证据。改之前，`>carpet` 报
     `played=0 frames=3 drops=116 last=seq 不连续`，而**本机自喂**的 `>audtest 4` 在第 0 帧就报
     ```
     W (77188061) carpet_audio: 这段语音丢掉（播放任务起不来），文字不受影响
     @audtest 第 0 块被拒，停在这
     ```
     `>audtest` 不碰网络、不碰手机、不接模型，走的却是**同一套** `feed()` 和同一个播放任务，
     所以这一条把手机端、WS 链路、TTS 全排除在外了 —— 屏**从来没有放过一声**。
   - **为什么起不来**：`carpet_audio.c` 里那句是 `xTaskCreate(player_task, "carpet_audio", 4096, ...)`，
     **4096 是字，ESP32-S3 上 32 位 = 16,384 字节连续内部 RAM**。同一块板 `>power` 实测稳态
     `"heap":{"free":20587,"largest":7424,"min":8227}`，`link.heartbeat` 也一直打 `int=17K/7K`
     —— 最大连续块只有 7.4 KB，**这个申请永远不可能成功**。缩栈救不了：降到 2048 字 = 8 KB
     还是大于 7,424。同一个坑上一次是在 `carpet_link.c` 的 `url_restart` 上撞到的
     （那里 16 KB→8 KB 侥幸绕过去了），音频这一条没人动过。
   - **改法**：栈挪进 PSRAM（那里 `psram_free=1529916`），用的是这块板自己已经在走的写法 ——
     `muse_chat_session.cpp` 的 `muse_chat` 就是 48 KB PSRAM 栈、同样在那条栈上调
     `esp_codec_dev_write`；`sdkconfig` 里 `CONFIG_FREERTOS_TASK_CREATE_ALLOW_EXT_MEM` 和
     `CONFIG_SPIRAM_ALLOW_STACK_EXTERNAL_MEMORY` 两份构建都是开的。DMA 相关的缓冲一个都不在栈上
     （`s_scratch` 是静态 .bss、`muse_audio.c` 的 `s_out_stereo` 同理），所以挪栈不动 DMA。
     **注意 WithCaps 这一族的栈深度单位是字节，不是字**，和 `xTaskCreate` 不一样。
     失败那支现在还打四个内存数（PSRAM / 内部各报空闲和最大连续），因为现场只有
     "内存"和"别的"两种死法分得开，没数字就只能回去怀疑 Wi-Fi。
   - **改完复称（同一块板、`app-flash`、`Hash of data verified`）**：
     ```
     @audio at 32000/128000 level 0.95
     @audio at 64000/128000 level 0.95
     @audio at 96000/128000 level 0.00
     @audio at 128000/128000 level 0.95
     @audtest 发了 50 块 / 128000 字节，喂了 4.75 秒（音频 4.0 秒）
     @audio 放完 128000 字节 / 4.0 秒
     ```
     `>carpet` 跟着变成 `frames=50 drops=0 last=-`（改前 `frames=3 drops=116 last=seq 不连续`）。
     中间那行 `level 0.00` 是本机造的静音段，口型跟着真电平动这一条也顺带称到了。
   - **同日第二半：先承认 `>audtest` 自己在骗人，然后它当场称出两处真 bug。**
     以前这条 bench 直接调 `feed()`，**把 base64 解码和 args 校验整个绕开了** —— 于是"本机自喂通过"
     并不能证明真从手机进来那一句也会通过，而现场只会撞后一种。现在每块现编 `b64`、现拼
     `cJSON args`（`turn_id/seq/total/rate/b64` 一个字段都不省），走 `carpet_audio_on_chunk()`
     进同一个 `feed()`、同一个播放任务，和线上那一帧同一条路：
     ```
     @audtest 发了 188 块 / 480000 字节，喂了 15.04 秒（音频 15.0 秒，1.00 倍实时），最大一块 b64=3416
     @audio 放完 480000 字节 / 15.0 秒
     @audio ... frames=238 drops=0 last=- gaps=0 gap_ms=0
     ```
     `b64=3416` 是帧预算的现场读数：一块 2,560 字节 PCM 编出来 3,416 字符，加 JSON 外壳整帧约 3.5 KB，
     屏侧组帧上限 `SCREEN_LINK_MAX_FRAME=4096`、App 侧 `AudioChunks.JSON_LIMIT=3800`，两头都在线上。
   - **bench 暴露的第一处：节拍会自己漂慢。** 以前是"发完睡 80 毫秒"，而本机编码 + 拼 JSON 每块多花
     20 来毫秒，15 秒的音频被喂成 19.4 秒（0.77 倍实时），称到的停顿就分不清是链路还是 bench 自己。
     改成按**绝对到期时间**（第 n 块该在 `t0 + n*80 ms` 发）；超了立刻补发下一块，平均速率锁在 1.00 倍实时，
     上面那行 `1.00 倍实时` 就是这个改完的读数。
   - **第二处更要紧：prebuffer 是永久闸门，屏会抖着放。** 节拍锁回 1.00 倍实时之后第一次读数
     `gaps=232 gap_ms=10692` —— 19.5 秒音频里停了 10.7 秒。根因不在链路，在 `player_task()`：
     `avail < PREBUFFER_BYTES && !complete` 这一支只管"开场先攒 200 毫秒"，却写成了永远生效，
     而生产者按实时速率喂时缓冲存量本来就永远不到 6,400 字节，于是**放 200 毫秒、停 200 毫秒重新攒**。
     改法：加 `spoke` 标志，闸门**只在开口之前生效**，开口之后只看"有没有数据"（`avail==0` 那一支负责，
     1.5 秒判死照旧），开场那 200 毫秒余量留着吸收到达抖动。改完同样节拍 `gaps=0 gap_ms=0`。
   - **新增的两个读数就是给现场用的**：`@audio … gaps=<次数> gap_ms=<累计毫秒>`，
     从此"边到边放顺不顺"是数出来的，不用人耳判断，也不靠日志翻。
   - **`last=seq 不连续` 那 116 次不是真的丢包**，是这个 OOM 的次生现象：`feed()` 先 `s_frames++`
     再建任务，任务建不起来就 `drop_locked()` → `reset_locked()` → `s_expect_seq=0`，
     于是同一段的第 2 帧开始每帧都"跳号"，一路刷到段尾。所以**丢弃原因写 seq 的时候先去看有没有一句
     "播放任务起不来"**，别看成 Wi-Fi。
   - **仍然没称到的**：这是**本机自喂**那一条。真从 App 进来的 `audio_chunk`（手机热点开着、
     屏 `ws=up`）刚才称不了 —— 这一轮抓的时候板子 `wifi=down ws=down`、`scan complete, 0 APs found`，
     热点没开。**现场那一趟要看的是**：问一句之后 `>carpet` 的 `frames` 跟着往上涨、
     串口出现 `@audio 放完 …`、`gaps=0`（放顺了，没有中途重新攒缓冲），
     而手机设置页「答句声音」下面**没有那行红字**，并且按 §12.11 那条规矩**只听得到一个声音**。
   - **这一处改动已经单独重打进 `firmware/screen/muse/muse-carpet.patch`（2026-11-11），只换了
     `carpet/carpet_audio.c` 那一节。** 那棵树现在还压着另一路会话未提交的本地化改动
     （`muse_lang.c` / `muse_settings*.c` / `muse_ui.c` 等），整份 `git diff` 出去会把别人的活一起卷走，
     所以不重打整份补丁、只把这一节的内容换成现在的工作区版本（carpet 目录在补丁里是
     `new file mode 100644`，一节就是整个文件）。
     **验证是硬的**：`git archive cdc169d` 解一份干净基线树 → `git apply --check -p1` 通过 →
     真 apply 之后 `carpet_audio.c` 和板子上刚刷进去那份逐字节一致（只差 apply 时 autocrlf 给的 CRLF，
     整份补丁 27 节都这样，与本节无关）。重装固件的人现在拿得到这个修复了。
   - **喇叭只有一只这条规矩照旧立着**：我们的 `audio_chunk` 和 Muse 自己的云 TTS 抢同一个
     ES8311+NS4150B 输出，手机侧对 `foreignCloud` 那几轮只记日志、不会来闭 Muse 的嘴
     （`core/.../ai/DialogBridge.kt:121-125`）。**v4 落地时屏侧要定"我们的音频进来时 Muse 那条链路要不要静音"**。
     上一轮这里写的"这棵树的手机 App 里没有任何放音代码、装机那份 APK 另有来源"**整段作废**，
     依据扫的是没 fetch 的本地树；手机侧语音在远端 `ff78279`（1.20.0）里，
     定性见 `docs/APP-SCREEN-CONNECT.md` §12.3。
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

五条判据，缺一不算过：

1. 面上**上滑**能到车联页，圆点是 3 个，页面不闪；从车联页**只能上滑回面**（左右划不动）。
   三个圆点本身也能点（第 3 个点直接跳车联页）—— 到这一页不该只靠手势划得对。
2. 没连 Wi-Fi 时第一行是"等网络"（橙）；连上 Wi-Fi 但服务端没起是"未接入"；
   服务端起来后 **≤5 秒**变"已接入"（紫，`COLOR_ACCENT`）。
3. `CONFIG_MUSE_CARPET_CARDS=n` 重编一次，圆点回到 2 个、左右滑行为与上游一致 —— 这条是回滚保险。
4. 串口 60 秒内没有 `Guru meditation` / `abort()`，且能看到 `hello` 发出去
   （`screen_link` 的日志 tag 就是 `screen_link`；11-05 起它自己也会打一行 `ws 断开/未接通…`，
   不再只有 `websocket_client` 那个错露头 —— 以前那份静默害我把"任务没建起来"当成"在撞防火墙"）。
5. **页上的东西按得动**：手按"下一张"两下，串口 `>carpet` 的 `sent` 要跟着涨、
   `last=card_next`，并且第二行"在画 X"在两秒内换掉（这条要服务端接得住，见 §5.5）。
   按三张状态卡任意一张 = 发 `poke`，`sent` 同样要涨。

### 9.1 同一天的傍晚：他挑出来三件事，都改了

现场反馈是"四个手势都能成，但你那页**条目点不了、字太小、UI 和 Muse 不统一**"。三条都是真的，
各自的根因记在这儿，免得下一个人重新查：

- **点不了**：那五行是 `lv_obj_create` 建的 —— 普通对象默认不带 `CLICKABLE`，
  所以按上去什么也不发生，看着像"页面对触摸没反应"。现在条目是 `lv_button_create`
  （Muse 的 `card()` 就是这么分的：能点就建按钮）。
- **上滑吞手势**（同一批里更早的那次反馈）：tile 本身是普通 `lv_obj`，**默认就带
  `SCROLLABLE`**，而 LVGL 找滚动对象时是从按下的那个控件往上走、**遇到第一个在该方向可滚的就停手**。
  脸那张 tile 的孩子在中心线上下都有，于是它看起来"竖向可滚"，上滑被它自己吃掉，
  tileview 根本没机会动。修的是 `muse_ui.c`：给 face 和 carpet 两块 tile
  `lv_obj_set_scroll_dir(..., LV_DIR_NONE)` —— 设成 NONE **不会**动 `SCROLLABLE` 标志位，
  于是拖动会链回 tileview。车联页里每张卡也照样摘掉 `SCROLLABLE`，同一个理由。
  圆点同时改成可点（点第 3 个直接跳车联页），到这一页就不只靠手势划得对。
- **字太小**：Muse 的中文只有 16 px 位图那一档，不是"没调大"，是**调不动**，
  要走 §5.6 那份子集 + tiny_ttf 才有 24/20 px。**别的第一件事**：`CONFIG_LV_TINY_TTF_CACHE_KERNING_CNT=0`
  必须带上，不为 0 会画坏字（老固件实拍过的 LVGL 跨缓存释放），这条丢了就是"字大了但是残缺"。
- **不统一**：配色和卡片全部改成抄 `muse_settings_ui.c`（`COLOR_CARD 0x1a1530` /
  按下 `0x2e2552` / `COLOR_ACCENT 0xa77dff` / 圆角 18 / 左右内衬 16 / 列宽 330 = 它的 `LIST_W`）。
  卡片高用 56 而不是它的 58，是因为这一页比设置页多一排按钮，58 会把最外那张顶出圆（余量算在源码注释里）。

**这一轮实测到的**（build #10，只 `app-flash`，`Hash of data verified`）：

```
muse-gadget.bin binary size 0x321000 bytes. Smallest app partition is 0x400000. 0xdf000 bytes (22%) free.
I (2679) carpet_page: tiny_ttf 123804 字节 → 24/20px        ← 字库真的起来了，不是退回 16px
I (6836) wifi:state: auth -> assoc ...
@carpet wifi=1 linked=1 contract=v2 gap_s=0 has_state=1
@carpet sat=空闲 下次11:32
@carpet dialog=-
@carpet card=对话卡 emotion=难过
@carpet sos=已确认
@carpet sent=0 last=-
```

`sent=0` 是**当时还没人按**，不是通路坏了 —— 按完再发一遍 `>carpet` 看它涨不涨（判据 5）。
另外 `>carpet` 要在复位后**至少等 16 秒**再发：`sercmd.py` 是开串口睡 6 秒就写，
那块屏联上服务端要 7 秒，太早发就打出 `wifi=0 linked=0`，看着像链路死了其实是被你自己复位了。

### 9.2 按完没反应：坑在服务端那台旧 JVM，不在屏

他按了三个条目加一排按钮，屏上"看着什么都没发生"。两侧各自的账本一对，就知道屏是对的：

- 屏侧（**不要用 `sercmd.py` 读这个数**，它开串口就复位板子，而 `s_sent` 只在 RAM 里，
  一复位就清零，读出来永远是 0，会把人带沟里）：`tools/sercmd_norst.py` 在 `open()` **之前**
  就把 dtr/rts 置 False，所以 COM8 那根 RTS→EN 不会跳。读出来：
  ```
  @carpet wifi=1 linked=1 contract=v2 gap_s=0 has_state=1
  @carpet card=状态卡 emotion=中性
  @carpet sent=4 last=poke
  ```
- 服务端侧：`cmd card_next args={"dir":-1}` / `{"dir":1}` / `cmd poke`×2，
  外加处理后的 `> 屏请求切卡 · 往左 → clock`、`> 屏被戳了一下`。**4 = 4**，条数对得上。

之前那台一直在 8443 上的 JVM 是 **0:04 启动**的，而 `core-0.1.0.jar` 是 **13:01** 重编的 ——
它跑的那份 core 里根本没有 `CommandPayload`，所以旧日志里全是
`展示端帧解不开：com/carpet/satdemo/core/protocol/CommandPayload$$serializer`：
帧收到了、序列号也在（`seq 692/701`），只是解不开，屏那侧照样把 `s_sent` 加了。

**判据（下次照着做）**：改过 `core/` 就重启彩排台，别看它还在监听就当它是新的。

```bash
# 1) 先看进程起于何时、jar 何时编出 —— 进程比 jar 旧就是旧的
powershell -NoProfile -Command "(Get-Process -Id <pid>).StartTime"
ls -la --time-style=+%m-%d_%H:%M core/build/libs/core-0.1.0.jar
# 2) 重编两处（只编 jar 不够，DemoDriver.class 也会过期）
cmd //c "gradlew.bat --console=plain -q :core:jar :simulator:classes"
# 3) 杀掉 java.exe（杀 wrapper 不杀 java，8443 会被它占着），再按原命令行重启
```

重启前**先把旧命令行存下来**（`Get-CimInstance Win32_Process -Filter 'ProcessId=…'` 的
`CommandLine`），照原样起回来。坑：PowerShell 的重定向会按控制台宽度折行，
把那条长命令行**从中间插字**（`carpet-sat-demo` 这种碎片就是这么混进去的），
所以别拿折行后的文件当可执行副本 —— 照字段手工重排一份脚本，重启前逐个 jar 路径 `[ -f ]` 验在不在。


### 9.3 半夜那轮：半开链路上其实埋着三个洞，我只堵住了一个才算数

§9.2 把"按了没反应"归到服务端那台旧 JVM，那是真的，但**屏侧当时并不干净**。
契约里那句「state 超过 10 秒没来本组件自己断线重连」写在 `screen_link.h` 头上，
实测它有三条不成立的路径（`screen_link.c` 的 `link_task`）：

1. **被非 state 帧饿死**（旧代码的真 bug）。超时判断躲在"这一轮队列空"后面，
   而 `ack`/`event`/`dialog` 也算"有货"。服务端只要还活着回 ack，循环就一路往下走，
   判断永远轮不到 —— 你每按一次按钮恰好换来一条 ack，于是**越按越发不出去**。
   修法：把超时判断挪出 `if (!got)`，每轮都跑。
2. **连上了但一帧 state 都没来**（我第 1 条改完才撞见的，更要紧）。
   `screen_link_state_gap_s()` 对 `s_last_state_us == 0` **返回 0** —— 那行是刻意写的
   "刚连上别一上来就报灰"。可看门狗直接拿它当判据，就等于"服务端活着但不推首帧"这种
   死法**永远不会超时**。半开探针实测（build #11，服务端每 500 ms 发一条 ack、永不发 state）：
   45 秒里 `linked=1 gap_s=0 has_state=1 wd=0` 纹丝不动，屏上还挂着**上一轮的旧值**
   —— 这就是"按了没反应"最完整的形态：界面看着正常、链路报"已接入"、数据全是陈的。
   修法：`link_task` 在连上那一刻记一条 `up_us` 当首帧截止线，没收到首帧时用它算 gap；
   **不改 `gap_s()` 的对外语义**（页面"等首帧"的显示口径还依赖它返回 0）。
3. **计数器说谎**。对端下线时 `WEBSOCKET_EVENT_DISCONNECTED` 先把 `s_up` 置假，
   循环最后那一轮再读 gap 就得到 `UINT32_MAX`，于是**每一次服务端重启都会被记成一次
   "看门狗踢了"**，还会打一条 `gap=4294967295 秒` 的假 WARN。我第一版交上去的
   `wd=1` 正是这么来的 —— 所以那个数当时**不能**当作自愈生效的证据，这条我收回。
   修法：判超时前先 `if (!s_up) break;`（正常断线走外层退避阶梯，不算 fire）。

复现与判据（都在这台机器上跑过，命令照抄可用）：

```bash
# 探针：只回 ack、永不发 state 的服务端，占 8443（起它之前要先停彩排台，杀 java.exe 不是杀 wrapper）
# 源件在 tools/HalfOpen.java，classpath 里那两个 jar 是 gradle 缓存里的现成货
JW=$(find /d/dev/gradle-home/caches/modules-2/files-2.1/org.java-websocket -name 'Java-WebSocket-1.5.6.jar')
SL=$(find /d/dev/gradle-home/caches/modules-2/files-2.1/org.slf4j -name 'slf4j-api-2.0.6.jar')
D:/dev/jdk/jdk-17.0.20.1+1/bin/javac.exe -cp "$JW" -d /d/dev/tmp/halfopen tools/HalfOpen.java
D:/dev/jdk/jdk-17.0.20.1+1/bin/java.exe -cp "$JW;/d/dev/tmp/halfopen;$SL" HalfOpen

# 被动抓串口 45 秒：DTR/RTS 必须在 open() 之前置 False，否则 RTS→EN 一脉冲就复位，
# 而 wd 和 sent 都只在 RAM 里，复位即清零（这个坑我已经踩过一次，别再踩）
/d/dev/esp/idf-tools/python_env/idf6.0_py3.11_env/Scripts/python.exe \
  firmware/screen/tools/serwatch.py COM8 45 /d/dev/tmp/wd12-capture.txt
```

修完（build #12）的实测：

- 半开探针下每 ~15 秒一轮，串口日志 `state 超过 10 秒没来（gap=11 秒），主动重连 第2/3/4 次`，
  探针侧对应有 11 条 `OPEN`/`CLOSE` —— 两边数目吻合，是**真断开真重连**，不是只打日志。
  `gap` 报 11 不是 10：判断每 250 ms 跑一次，越过阈值那一轮才打印。
- 彩排台换回真驱动后：`linked=1 contract=v2 gap_s=0 has_state=1 wd=6`，之后不再涨。
- 把驱动杀掉（对端正常下线）18 秒：串口**一条 WARN 都没有**，`wd` 冻在 6。
  同一条动作在修复前会打出 `gap=4294967295 … 第 2 次` —— 这条对照就是第 3 个洞修好的凭据。

还剩一条我没复现、别再自称解释了：**565 秒那次事故**。如果那时服务端真的连 ack 都不发，
队列本该空、旧代码的第 1 个分支也该踢 —— 说明现场还叠着第三种情况，最像的是 **TCP 半开**
（屏侧收不到 FIN，`esp_websocket_client_close()` 自己也发不出去）。
这条我造不出环境，所以不写"已修"。它现在能被抓出来：`wd` 只在真超时时 +1，
下次现场再演砸，回读 `>carpet` 那一行就能一次分清「没踢」「踢了但连不上」「连上了但服务端不推首帧」三种死法。

### 9.4 深夜那一轮：信息架构改成三层，翻页通了，但"报出去"还没通

这轮做的事：把车联格里那一页五行的旧设计换成**场景选择 + 场景页 + 明细**三层，
加上契约那半条 `page_report`（屏报页）和 `dialog.action`（手机拉起页）。
方案本身是和产品那侧拉齐的：**定页权归屏，一页里说什么归手机** —— 所以屏翻页不问手机，
手机也不能靠 `state.ui.page` 把屏拽走（那个字段是回声，固件读了只用于显示，不切页）。

**这台板子上实测到的（build #14，COM8）**：

```
> w                    # 先叫醒，见下面那条坑
> page kids   →  @page want=kids now=kids
> carpet      →  @carpet nav=kids reported=0 last=-
> page vehicle / clock / face 同法，nav 一条条跟着走，没有 unknown action page
```

也就是：**页名合法就翻、翻完 `nav=` 跟着变、脸那一格算 `face`、明细页不报**这一半是通的。
`reported=0 last=-` 不是 bug —— 那条链路当时压根没连上。

**这轮撞到的坑：`>` 那一行命令不唤醒屏幕。** `>page kids` 发下去 `nav` 不动，
`>carpet` 里 `linked` 又一直是 0，看着像"pending 被吞了"或者翻页写坏了。
真实原因是 Muse 的自动熄屏（`@status` 里 `"sleep":120`）：屏一睡，`frame_tick` 就在
`update_sleep()` 那里 return，而 `carpet_page_tick()` 挂在后面的 `update_chrome()` 里 ——
**整个 tick 不跑，pending 的名字就一直攒着**。串口发一个裸字符 `w`（醒）之后，
前面那几条命令立刻一条条生效，一条没丢。所以判"翻页坏了"之前先 `w`，再 `>carpet`。
这条对功能本身有个连带含义：手机在屏睡着时发来 `dialog.action`，**不是丢了，是延到醒过来那一刻**，
这也对——睡着的屏不该自己亮。

**还没验的，别当已验**：

1. **`page_report` 真的上线**。屏里烧的**就是 PC 的地址** —— `ws://192.168.43.161:8443`
   （`build-muse-s3-175-carpet/sdkconfig` 2026-11-04 19:44 改的，22:08 编进 build #14；
   这条我是从 `muse-gadget-unsigned.bin` 里把 `ws://…` 那个字符串抓出来认的，
   不是从 `devices/sdkconfig.carpet` 推的 —— 那份跟踪文件里的手机地址**根本没进这版二进制**，
   我按它推过一次，错了）。彩排台确实在 `0.0.0.0:8443` 听着（PID 12968），
   这台机器上却**没有任何放行 8443 的入站规则**（`netsh … show rule` 查过，空），所以 `linked` 一直是 0。
   要走通就一件事：跑一次 `../tools/firewall-allow-8443.cmd`（管理员，装完永久，
   还原命令写在那份 .cmd 里）——**不用改 URL、不用重编重刷**。
   判据是**彩排台日志**出现 `cmd      page_report  args={"page":"kids"}`（`DemoDriver.kt:191`），
   而屏上 `>carpet` 变 `reported=1`。
2. **手指走一遍三层**：入口点得进、场景页点得进明细、返回两条（左上角那颗 + 左边缘右滑）都回得去。
   串口只能替我翻页，替不了"点得到"。
3. **28 / 22 的字有没有掉到 16 px 那份 fallback**。这轮把字库重跑过一次
   （123,804 → 154,528 字节，683 码位；缺的 `⇒`、`・` 只在注释里，不上屏），
   掉 fallback 的表现是**一行里字大小不一**。正式构建 `CONFIG_LV_USE_SNAPSHOT` 是关的，
   我这边抓不到像素，只能靠眼睛。
4. 左滑返回那个 60 px 阈值（≈5.7 mm）还是纸面算的，没实测（任务 #42）。

**代码侧的守卫**：`PAGE_NAMES` 那七条已经进了 `muse-carpet.patch`，
`SchemaDriftTest` 里那条"它一旦出现就必须和 `ScreenPage` 一模一样"从此**真的在比**
（以前是 `table == null` 静不比，等于没有守卫）；补丁在 baseline `cdc169d` 干净树上
`git apply` 打过一次，12 个文件、字库解出来 154,528 字节一致。

### 9.5 第二天（11-05）：宫格上了板，而"连不上"的真身不是防火墙

他点名"场景选择做得有点 low，最好像 app 选择那样"→ 第 1 层从五行列表改成宫格（形状见 §5.1，
尺寸算式见 §5.6）。同一天把 `linked=0` 查到底，结论**推翻了我前一天写在 §9.4 第 1 条的判断**：

```
E (7039) websocket_client: Error create websocket task        ← 真凶，前一天没人看这一行
I (…) link.heartbeat: … ws=down … int=13K/4K min=6K           ← 内部 RAM：剩 13K，最大连续块 4K
```

两件事叠在一起才表现为"永远连不上"：

1. **ws 任务的栈开不出来**。`esp_websocket_client` 那个任务的栈要从**内部 RAM** 要一整块连续的，
   我们给了 8192；Muse 这一版跑起来后内部 RAM 最大连续块只有 4K ⇒ `xTaskCreate` 失败。
   这一版那个组件里**没有** `task_stack_in_psram` 这个配置项（贴到源码看过），躲不开，只能少要。
2. **失败之后它再也不试**。`link_task` 里 `esp_websocket_client_start()` 的返回值没人看，
   任务没建起来 ⇒ `BIT_UP`/`BIT_DOWN` 再没人置 ⇒ 下面那句 `portMAX_DELAY` 死等。
   退避阶梯一次都没跑过。所以屏的"未接入"不是"还在等"，是**已经躺平了**。

改了三处（都在 `components/screen_link/screen_link.c`，仓库那份是源头，Muse 树那份是 `cp -r` 的产物）：
栈 8192→4096（就是组件自己的默认档 `WEBSOCKET_TASK_STACK`，明文 ws:// 没有 TLS 吃栈）、
攒帧那 4KB 从 `.bss` 挪去 PSRAM（`.bss` 每字节都是从内部 RAM 挖的，挪走还给堆一个整块）、
`start()` 失败走**和断线同一条**退避阶梯。重复 `start()` 是安全的：`create_transport` 进来先销毁上一轮的
`transport_list`（组件源码里读到的，不是猜的）。

顺手补了两条日志（CONNECTED 一条、DISCONNECTED/ERROR 各一条）。为什么补：连不上时组件**一个字都不打**
（`abort_connection` 只派事件不记日志），串口里 40 秒静默，分不清"任务活着但在撞防火墙"和
"任务压根没建起来"——前一天就是把这份静默当成了"只差防火墙"。改完实测：

```
W (17063) screen_link: ws 断开/未接通，下一次按退避阶梯再来
W (55207) screen_link: ws 断开/未接通，下一次按退避阶梯再来      ← 间隔跟着阶梯走，重试是真的在跑
```

**于是 §9.4 第 1 条剩下的那半才是真的**：现在是**超时**（`ESP_ERR_ESP_TLS_CONNECTION_TIMEOUT`）
而不是拒绝 —— 拒绝说明到了但没人听，超时说明包被静默丢掉，这台机器上确实没有放行 8443 的入站规则。
`../tools/firewall-allow-8443.cmd` 那份要管理员跑一次。**它前一天没生效过一次**：那份 .cmd 是 LF 结尾，
cmd.exe 解析括号块会跑偏（`'----' 不是内部或外部命令`），整段静默什么都不做；现在文件是 CRLF，
而且 `.gitattributes` 里加了 `*.cmd text eol=crlf` —— 仓库那条 `* eol=lf` 会在对端机器把它签回 LF，
这个坑就会原样复发。每次运行都往 `D:\dev\tmp\fw-run.log` 记一行（`RUN …` / `NOT_ADMIN` / `ADD_EXIT=…`），
跑没跑成有据可查，别再靠"我点了"判断。这轮的 13:00 那条是我自己以非管理员跑通的语法验证（`RUN` + `NOT_ADMIN`
两行都在，脚本终于走到判断那一步了），规则仍未落地。**（这一句到当天 14:36 就不成立了，落地后的读数在 §9.6）**
规则落地后的判据不变：彩排台日志出现
`cmd      page_report  args={"page":"kids"}`，同时屏上 `>carpet` 从 `reported=0` 变 `reported=1`。

§2 那份 `muse-carpet.patch` 这轮重打过（**4,006 行**），`SchemaDriftTest` 实跑 5 条全 PASSED。
宫格这版**还没有肉眼确认**：正式构建 `CONFIG_LV_USE_SNAPSHOT` 是关的，抓不到像素，
所以"三个一行 + 第二行两张、右边空着"这个排法、以及每格那颗字，都得他在屏前看一眼才算数。

### 9.6 同一天下午（11-05）：规则真落地了，接着轮到热点没开

他在屏前看过宫格，说**大体 OK**，只点名两处：**底部那颗按钮别写"回到那张脸"，直接"返回"**；
以及**圆屏文案尽可能精简**。改的是画法之外的一句句文案，全在 `carpet_page.c`：

| 原来 | 现在 |
|-|-|
| `回到那张脸`（L1 底部） | `返回`（和 L2/L3 同一个字，三层一致） |
| `手机在画 X`（车载/亲子副行） | `在画 X` |
| `已接入 v1` / `已接入 v2`（L1 顶行） | `已接入`（契约号退回串口那行 `linked, contract v2`） |
| `这一行是最新一帧里的值` / `链路 3 秒没帧：留着上一帧`（L3 提示） | `最新一帧的值` / `3 秒没帧 · 是旧值` |
| `这一版还没接上：屏上不放假数据`（图片播放） | `还没接上 · 不放假数据` |

**没重跑字库**，因为不是想当然：写了个脚本把改后的每一句逐字对着 `carpet_font.ttf` 的 cmap 称过
（69 个非 ASCII 码位，`ALL COVERED`）—— 少的是字、不是新字，`·` 和 `—` 本来就在 `EXTRA` 里。
这条以后照用：改文案先称覆盖，缺字才重跑 `tools/muse_font_subset.py`，否则白跑一轮还容易漏。

同一轮 `fw-run.log` 里 14:36 那条是 `ADD_EXIT=0`，`netsh … show rule` 回读到了实体：
**已启用 是 / 方向 入 / 本地端口 8443,8444 / 操作 允许** —— §9.5 剩的那半条从此有了凭据。
屏那边接着报的错也换了：`connect() error: Host is unreachable`（errno=118）而不是超时。
这一条**不是防火墙**：`>carpet` 现在是 `wifi=0`，串口里屏自己在说
`no saved wifi network nearby`（扫到 48 个 AP、8 个 SSID，没有它存的那个），电脑上
`netsh wlan show networks` 也只看得到 `aa123` —— **手机热点没开**，电脑也换到了 `aa123`
（10.50.174.165）。所以 §9.4/§9.5 那条"就差一次管理员"到此为止：管理员那下已经给了，
现在缺的是**热点开起来 + 电脑连回去**，屏里烧的仍是 `ws://192.168.43.161:8443`。

⚠ 电脑回到热点后**先看它分到几号**：不是 `.161` 就得改 URL 重编重刷一次（`build-muse-s3-175-carpet/sdkconfig`
里那行 `CONFIG_SCREEN_LINK_URL`），别默认它还是原来那个号。

这轮的板子实况（`sercmd.py COM8 ">carpet" 12`）：`nav=face reported=0 sent=0 wifi=0`，
烧写 `Hash of data verified`、编译 0 error —— 宫格 + 精简文案已经在这块板上跑着。

### 9.7 11-05 傍晚：不靠热点了，板子直接连 `aa123` —— 上报第一次跑通

他换了热点、让我们把圆屏也挪到电脑现在那张网（`aa123`，通道 11 = 2.4G，S3 连得上）。
**板子换网的正路不是 BLE 配网，是 Muse 自己留的那个开发覆盖**（`main/Kconfig.projbuild` 里的
`HOMEHUB_WIFI_SSID` / `HOMEHUB_WIFI_PASSWORD`，`main/app.c:2819` 那段就是它）：SSID 非空时
Link 直接join这个网、keeper 也不再去动 Wi-Fi，而且它**明确不写 NVS**。所以三条值都改在本机的
`build-muse-s3-175-carpet/sdkconfig`（gitignored）里，`idf.py build` 会继承它们、四层 defaults 不会盖掉：

```
CONFIG_HOMEHUB_WIFI_SSID="aa123"
CONFIG_HOMEHUB_WIFI_PASSWORD="……"        ← 密码只在这台机器上，任何情况下不进仓库、不进文档
CONFIG_SCREEN_LINK_URL="ws://10.50.174.165:8443"
```

⚠ 这条覆盖会**盖掉手机 App 的 8 位码配网**：这台机器上的板子从此开机就直连 `aa123`。
要回到配网那条路，把 SSID 改回 `""` 重编重刷一次。

改完 app-flash（`Hash of data verified`），串口一次就通：

```
I (5374) wifi:connected with aa123, aid = 2, channel 11, BW20
I (5677) link.wifi: got IP 10.50.174.29
I (7216) screen_link: ws 接入 ws://10.50.174.165:8443      ← §9.5 补的那条日志，第一次派上用场
I (7221) carpet: linked, contract v2
I (7330) carpet: sent page_report                          ← hello 之后的补报，不是等翻页才报
```

然后拿 `serseq.py`（**不复位**那份，能连着发好几条）在串口上驱了两次翻页，屏侧和服务端两侧读数一起对上：

| 动作 | 屏 `>carpet` | 彩排台日志 |
|-|-|-|
| 开机 | `nav=face reported=1 last=face` | `cmd page_report args={"page":"face"}` |
| `>page kids` | `nav=kids reported=2 last=kids` | `… {"page":"kids"}` |
| `>page vehicle` | `nav=vehicle reported=3 last=vehicle` | `… {"page":"vehicle"}` |

`reported` 是一个单调涨的计数器（每发一条 +1），所以这三行本身就证明**不是补报那一次**、
是真的每次翻页都在报。链路那行也第一次变成 `wifi=1 linked=1 contract=v2 gap_s=0 has_state=1 wd=0`，
彩排台那头同步显示 `展示端 1 台`。

**§11 步骤 1 到这里才是过了判据**（HANDOFF 那边同步改了）。之前三轮都卡在同一件事上，
但每轮卡的原因都不一样：11-04 是 ws 任务的栈开不出来（§9.5），11-05 中午是入站规则（§9.6 前半），
傍晚是网络根本不在一处（§9.6 后半）。这条链条值得记住的地方是：**上一轮的"只差 X"从来不是判据，
称过的读数才是。**

顺带两件：
- `dialog=手机在说`、`card=对话卡`、`emotion=担心` 这些值是帧里真来的，屏上那行"在画 X"跟着服务端
  的策略在换 —— 宫格 + 场景页 + 实时值这一套，现在是**接上服务端在跑**的状态，不是空页演示。
- 彩排台（`DemoDriver`）我重启过一次，并且**先把旧 JVM 的命令行存进 `D:\dev\tmp\driver-cmdline-1105.txt`**；
  现在它的日志落在 `D:\dev\tmp\driver-1105.log`，以后要认服务端那半边的证据就读这份文件。
  启动前记得 `gradle :simulator:installDist`：这台机器上 `install/lib` 里的 core jar 曾经是**旧的**
  （两个 sha1 不一样），直接用 `.bat` 会跑到旧契约上。


