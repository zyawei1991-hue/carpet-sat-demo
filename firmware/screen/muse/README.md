# 圆屏换底座到 Muse Gadget SDK：车联页与 App 侧要接的东西

这份是给**另一台电脑**看的。你要研究的是"功能整合进我们自己的 App"，所以正文在第 5 节；
前面几节只到"能把这一版重出来、能刷上板"为止。

基准：本文件与 `muse-carpet.patch` 记的是 2026-11-06 的状态（契约 v3 那一轮）。

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
- 我们的全部改动 = 22 个文件、3826 行新增 / 86 行删，其中一份是 154,528 字节的字库
  （`muse-carpet.patch`，6,803 行，`git diff --binary` 打的，字库在里面是 base85 段。
  这两个数字每次重打 patch 都要跟着改，别信上一版留下的 —— 下面这组是 2026-11-09「熄屏后保持联网」那轮重打后实读的）：
  ```
  esp32/components/muse/CMakeLists.txt       |  22 +-   (+carpet_audio.c 那行 + embed 那行 + 11-08 中文化：carpet_lang_zh.c 那行)
  esp32/components/muse/Kconfig              |  31 +-   (+MUSE_UI_LANG_ZH 那一档，`depends on MUSE_CARPET_CARDS`)
  esp32/components/muse/carpet/carpet_font.ttf | Bin 0 -> 154528   (新，字库；页面上 28/22 两档的词表)
  esp32/components/muse/carpet/carpet_lang_zh.c | 314 +   (新，「英文串→中文」那张表 166 条 + `>zh` 的命中计数；11-08 补进 w.detail / h.detail 那两句整句，折叠那轮再加 `Muse 云`/那句说明/`已折叠`/`已展开`/`电池诊断` 五条，11-09 加 `熄屏后保持联网` + 那句说明两条)
  esp32/components/muse/carpet/carpet_lang_zh.h |  58 +   (新)
  esp32/components/muse/carpet/carpet_audio.c | 383 +   (新，v3 音频环：base64→PSRAM 线性缓冲→实时放音，缺块丢整段；缓冲 2 MB)
  esp32/components/muse/carpet/carpet_audio.h |  54 +   (新)
  esp32/components/muse/carpet/carpet_link.c | 1062 +   (新，链路 + 上行命令队列 + page_report + 首屏那条带念稿 + `>audio` 探针 + on_command/几何申报 + 口型翻页跟播放位置 + maint 担心的脸三道让路 + `>maint` 造帧口 + 11-08：接入点地址运行期可设那一层 `url_read/url_effective/link_open/carpet_link_set_url/carpet_link_url_dump` + 11-09：`carpet_link_dial_fails`/`carpet_link_last_error` 两个转发、`@url`/`@carpet` 多印 `fails=` `err=`)
  esp32/components/muse/carpet/carpet_link.h | 156 +   (新)
  esp32/components/muse/carpet/carpet_page.c | 880 +   (新，第 1 层宫格 + 五个场景页 + 明细页 + 12px 琥珀点)
  esp32/components/muse/carpet/carpet_page.h |  46 +   (新)
  esp32/components/muse/muse_app.c            |   3 +   (11-09 新入列：设置监听里 `MUSE_SETTING_STAY_ONLINE` 那一格 → `muse_wifi_apply()`，让已经在 nap 的那次当场结束)
  esp32/components/muse/muse_input.c         | 168 +   (>carpet / >page 回读 + bench 命令 ui=carpet + `>say` 造答句 + `>audio` 放音 + `>audtest` 造音频 + `>maint` 造养护帧 + 11-08：`>url` 读/改/清、`>wifi clear`、`>zh` 回读 + 11-09：nap 闸门认 `muse_settings_stay_online()`、`>stay` 回读口)
  esp32/components/muse/muse_lang.c          |  57 +   (11-08 新入列：给设置页那几档字体挂 CJK fallback，照越南语那份 `copies` 的做法；标题那档 unscii 整体换成带 fallback 的一档)
  esp32/components/muse/muse_settings.c      |  48 +-  (11-08：NVS 多一条 `link_url`；11-09：再多一条 `stay_online`（u8，缺键=默认关）+ 那两个函数 + 开机日志尾部 `nap %s`)
  esp32/components/muse/muse_settings.h      |  18 +   (11-08：`MUSE_LINK_URL_MAX` + 那两个声明，刻意不加 CONFIG 闸 —— 主机侧 harness 也 include 这份头；11-09：`MUSE_SETTING_STAY_ONLINE` + `muse_settings_stay_online/set_stay_online`)
  esp32/components/muse/muse_settings_ui.c   | 324 +-  (11-08：WI-FI 子页多一行「Access point」复用现成的 `open_text` 键盘；中文化把这一页的文案接进 `muse_lang_menu()`，`w.detail`/`h.detail` 那两句整句也过表；折叠那轮加 `fold()`/`fold_toggle()` 一组 —— MUSE 页那四格 + 测试连接 + 配对说明收进默认折上的「Muse 云」，电量页那七行读数收进「电池诊断」，都在 `#if CONFIG_MUSE_CARPET_CARDS` 里；bench 那条 `>ui.settings=<页>[+]` 的 `+` 在 `#if LV_USE_SNAPSHOT` 里，只为截图能拍到展开态。11-09：熄屏页加 `s_stay_sw` 那一格，**放在五个时长选项上面**——圆屏一屏放不下五格再加一格，滚动条是关的、台架也没有滚的口)
  esp32/components/muse/muse_ui.c            | 146 +-   (tile + 手势吞并修 + 圆点可点 + show_carpet)
  esp32/components/muse/muse_ui.h            |  10 +
  esp32/devices/sdkconfig.carpet             |  46 +   (新，含 tiny_ttf 三条；`CONFIG_SCREEN_LINK_URL` 就是这里那条出厂值；11-08 加 `CONFIG_MUSE_UI_LANG_ZH=y` 和 `CONFIG_LV_USE_SNAPSHOT=y` —— 后者是截图验收那条口的开关，关掉就不出 `SNAP BEGIN`)
  esp32/main/app.c                           |  35 +-  (11-08 新入列：开机选网那条优先级倒过来 —— NVS 里用户填的网络 > 编译期那一条)
  esp32/main/muse_glue.c                     |  51 +-   (11-08 加：`dev_override_now()`，让看守任务在编译期 SSID 为空时也不再"别碰 Wi-Fi"；11-09 加：`DIAL_STALL_FAILS 4` 那条升级 —— Wi-Fi 还连着但抓屏链路连续 4 次拨号没通过就 `wifi_mgr_disconnect()` 走全量重连，并把 `err=` 原文一起打进 W 那行)
  ```
  这份 patch 在这台机器上**用 baseline 干净树验过能打上**（`git worktree add --detach ... cdc169d`
  再 `git apply`，22 个文件全落对，字库解出来字节数一致），不是"看着应该能打上"。
  2026-11-08 这天打了四次：第一次收进 R1~R4 那几处；**上板第一次刷机就死循环**
  （`assert failed: xQueueSemaphoreTake queue.c:1709 (( pxQueue ))`，根因是 `carpet_link_start()`
  里提前 `url_read()`，那时设置模块的锁还没建）之后改成 `url_effective()`，重编、重刷、称到了数字，
  再打第二次是 5,595 行那组；第三次收进设置页中文化那一整块（`MUSE_UI_LANG_ZH` + `carpet_lang_zh.c/h`
  + `muse_lang.c` 的字体 fallback + `muse_settings_ui.c` 接表 + 6 条 detail 整句），6,367 行；
  第四次收进折叠分组那一块（`fold()`/`fold_toggle()` + MUSE/电量两页的收口 + 五条新表项 +
  bench 的 `+`），就是上面这组 6,560 行。
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
  重打命令（**`components/screen_link` 必须排除**，
  它就是 §3 那条 `cp -r`，进 patch 反而会让对端机器多一份）：
  ```bash
  cd D:/dev/projects/muse-gadget-sdk-main
  git add -N esp32/components/muse/carpet esp32/devices/sdkconfig.carpet
  git diff --binary cdc169d -- esp32/components/muse esp32/devices/sdkconfig.carpet esp32/main \
      > D:/dev/projects/carpet-sat-demo/firmware/screen/muse/muse-carpet.patch
  git reset -q esp32/components/muse/carpet esp32/devices/sdkconfig.carpet   # 别让状态多个暂存
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
#    patch 里带那份字库（carpet/carpet_font.ttf）。不想打整份补丁、只搬我们的目录时，
#    这一份必须单独拷过去 —— CMakeLists 里那行 embed 指的就是它，缺了直接 configure 失败：
cp firmware/screen/muse/font/carpet_font.ttf <muse>/esp32/components/muse/carpet/

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
     那条云链路，**和我们自己的 App 无关**；要让它走我们 App 的服务端，得先看 Muse 的 endpoint
     能不能被屏上那四个字段改掉。而且 §2 记过一条硬坑：`muse_audio_read()` 开机 5.7 秒之后一律失败，
     强行重开会挂死串口任务 —— 真要做"听"，那一本账先还掉。
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


