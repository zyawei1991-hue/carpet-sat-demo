# 给 App agent 的改动清单（2026-11-11）

面向对象：**只改 `apps/android` + `core` 那棵树的那个会话**。
这份是可直接照做的合并版，把散在 `docs/APP-SCREEN-CONNECT.md` §12.9/§12.10/§12.11 和
`protocol/screen-link.md` 里的要求收在一处；**结论以本文件为准，细节和实测数字看它指向的小节**。
背景一句话：**正式演示只有手机 + 圆屏，没有电脑**，所以这条路上每一样能力都得已经在
屏上或 App 里存在。
**【2026-11-13 新增 §8】**：圆屏 B 规格四方向交互的交接 —— 屏侧已落地清单 + §1 灯点 / §4 功能层 /
§5 相册三块的 App 侧待办。语音看 §0–§7，导航交互看 §8，两节互不替代。

## 0. 【2026-11-13 最新交接，覆盖下面所有旧结论】**App 侧不需要改代码。App agent 说得对，错在屏侧。**

owner 的原话：「圆屏无论是按键还是唤醒词都提示对着屏说话还没开，我和 app 侧 agent 沟通他说
不需要 APP 侧改动，我不太懂，你俩用远端仓库沟通一下」。这一节就是屏侧的回答，**不用 owner 转话**。

### 0.1 真机称到的（原文，不是推断）

板子 WS175B-A3F7 连着演示那台 Mate 60，串口回读：

```
@mic mode=auto armed=0 phone_v4=0 rejected=0 dead=0 linked=1
I (7257) carpet: ack：整份契约 v=1、protocols=[4,4] ⇒ 上行不开
```

逐格翻译：本地开关**开着**（`mode=auto`）、没被熔断（`rejected=0`）、上行地基没坏（`dead=0`）、
链路**通着**（`linked=1`）。四道门里三道是开的，唯一关着的是"对面申报"那一格 ——
而对面明明报了 `protocols=[4,4]`。**所以问题不在 App，在屏怎么读这份 ack。**

### 0.2 真因：屏侧要了一个**按设计永远不会上线**的字段

屏侧 11-11 深夜那道闸写的是 `bool open = says4 && v >= 4;`（`carpet_link.c` 的 `on_ack`），
`v` 取自 `num_or(root, "v", 1)`。而你们那棵树里：

- `ScreenMessage(val v: Int = ScreenCodec.CONTRACT_VERSION, …)` —— `v` **带默认值**；
- `ScreenLink.kt:69-73` 的 `Json { encodeDefaults = false; ignoreUnknownKeys = true }`
  ⇒ **出站帧一个 `v` 字节都不写**；
- 这件事契约自己写着两遍：`ScreenLink.kt:20` 的注释、
  `protocol/screen-link.schema.json` 里根 `v` 的 description
  （「出站帧因为 encodeDefaults=false 根本不写 v，固件不能拿帧上的 v 判断自己该不该解 dialog」）。

⇒ 屏读到的 `v=1` **不是你们报了 1，是"这个键不存在"的兜底值**。
`v >= 4` 这条判据对**任何版本**的 App 都不可能成立，于是按键和唤醒词两条路一起报
「对着屏说话还没开」。**这是我写的闸不可能被满足，不是你们没做。**

而 `protocols=[4,4]` 恰好证明**我当初要的那把钥匙你们早就转了**：
这个数组是 `listOf(ScreenCodec.CONTRACT_VERSION, 4)`，第一个数是 4 ⇒ 装机那版
`CONTRACT_VERSION` 已经是 4（= 1.26.0）。旧文档 §0/§5 里那句
「钥匙是 `CONTRACT_VERSION → 4`，屏认外层 `v`」**两句都作废**：前一句你们已完成，后一句本身是错的。

### 0.3 屏侧已改（这一轮唯一的代码改动）

```c
bool open = says4;      /* 原来是 says4 && v >= 4 */
ESP_LOGI(TAG, "ack：protocols=[%s]（envelope v=%d，契约规定它不上线，不作判据）⇒ 上行%s", …);
```

`v` 仍然打印，但只当证据 —— 让下一个人一眼看出"这个字段没上线"，别再去拿它当许可。
残留保护只剩 carpet_mic 那道**熔断**：发过上行后 5 秒内掉线 ⇒ 本会话不再试
（`>micup force` 或重启清）。也就是说万一真装上一版"报 4 但解不了"的 App，
代价是这一轮对话不出声，**不会**再出现场链路抖。

### 0.4 留给你们的只有一件**契约卫生**，不是功能，且不阻塞演示

`protocol/screen-link.schema.json` 根的 `required` 还列着 `['v','t','seq']`，
但同一个文件里 `v` 的 description 说它不上线 —— **契约自己和自己矛盾**。
`SchemaDriftTest` 那条 required-vs-default 的检查只覆盖 `state` 和 `state.ui`，没覆盖根，所以漏了。
两条修法你们挑一条（**屏侧现在两条都不依赖，随便哪条、什么时候做都行**）：

1. 让 `v` 真的上线：给 `ScreenMessage.v` 加 `@EncodeDefault`（或去掉默认值），
   这样 `required` 就名副其实，也顺手把"帧上能看出契约档"这个能力拿回来；
2. 或者承认它不上线：把 `v` 从根 `required` 里去掉，并把 `SchemaDriftTest` 的检查扩到根，
   免得下一次又有人在固件里拿它当判据。

顺带一条**无害**的：`protocols = listOf(CONTRACT_VERSION, 4)` 现在等于 `[4,4]`，重复了一个数。
屏侧按"集合里有没有 4"读，重复不影响；想干净就写 `listOf(4)` 或去重，**不改也完全没问题**。

### 0.5 这一轮屏侧还修了一个和你们无关的崩溃

`>url`（串口改拨号地址）在**链路通着**的时候必崩，set 和 clear 都复现：

```
***ERROR*** A stack overflow in task carpet_url has been detected.
rst:0xc (RTC_SW_CPU_RST),boot:0x2b (SPI_FAST_FLASH_BOOT)
```

真因是那个任务的栈 2048 **字节**是"链路没起"时挑的（走浅分支），连着的时候多一个
`screen_link_stop()` 压在同一条调用链上 ⇒ 溢出重启。已抬到 6144 字节，**修完真机称到**：

```
@url clear: ESP_OK
@url now=ws://192.168.43.1:8443 src=factory saved=(none) linked=1 dialed=1
I (96157) carpet: carpet_url 这一趟用完还剩 3500 字节
```

6144 − 3500 = **实际用了 2644 字节**，比原来的 2048 多 596 ⇒ 溢出是真因，不是猜的。
整趟 `reset? False`、`stack overflow` / `RTC_SW_CPU_RST` 各 0 次。**App 侧无影响。**

（单位说明：ESP-IDF 的 `usStackDepth` 和 `uxTaskGetStackHighWaterMark` 都按**字节**算，
`StackType_t = uint8_t`。屏侧固件以前把这些数印成"字"，错了 4 倍，这一轮一起改掉了；
上面那行是改完之后的原文。详见 `firmware/screen/HANDOFF.md` §18.6。）

### 0.6 验收：闸这一层**已经称到了**（2026-11-13，真机 + 真装机 App）

```
I (96439) carpet: ack：protocols=[4,4]（envelope v=1，契约规定它不上线，不作判据）⇒ 上行开
@mic mode=auto armed=1 phone_v4=1 rejected=0 dead=0 linked=1
@wake ready=1 word=你好小安 chunk=512 on=1 inhibit=0 live=0 armed=1
```

按键和唤醒词**两条路读的是同一个 `carpet_mic_armed()`，现在都是 1** ⇒
「对着屏说话还没开」这句话不会再出现。`>url clear` 重启链路之后复称仍是 `armed=1`。

（这几行称于"改栈单位标签之前"那一版。之后又重编重刷了一次，两版差异逐行比过：
只有 5 行日志字符串 `字`→`字节`，**功能完全相同**。当前这一版的"链路通着"读数还没补称 ——
重刷后演示手机的热点没起来，屏 `wifi=down`（内存充足 `int=47K/29K`，不是内存问题）。
细节和原始读数在 `firmware/screen/HANDOFF.md` §18.6，**不影响"App 侧不用改代码"这个结论**。）

**还没称到的（要 owner 真人对着屏说一句话，屏侧自己造不出来）**：

- `上行 m-xxxxxx-n：<n>/<n> 帧、<字节>（<秒> 秒语音）<x.xx> 秒发完`
- 你们那侧：拼出的 WAV 送 `SiliconFlowAsr.transcribe`（16k WAV 认、裸 PCM 必须自己封 44 字节 RIFF），
  转写那句和说的那句一致 ⇒ 答句照已跑通的 v3 下行**由圆屏念**（§1 那条规矩，不许两边各念一遍）。

也就是说：**"门开了"已验证，"门后那一趟走完"还没验证。** 谁先称到谁写回
`firmware/screen/HANDOFF.md`，别把没称到的写成称到了。

**自测工具的读数也跟着变了**：`firmware/screen/tools/fake-phone.mjs` 各人设写的整帧 `v`
现在屏侧不看了，所以 `--v4-ready`（`protocols:[4]`）和默认档（`protocols:[3,4]`）
**都会让屏开门**；两者的区别只剩"收到上行之后拼段还是关线"，默认档因此变成**熔断**的测试台，
不再是"闸不开"的测试台。要演"闸不开"请用 `--no-v4`（只报 `[3]`）。

---

### 0.7 【2026-11-13 夜】owner 真人对着屏说了话：**屏侧整条已称通**，「没转出来」卡在你们那一半的"失败分不清"

屏侧串口原文读数（不重置回读，`tools/sercmd_norst.py`）：

```
@mic mode=auto armed=1 phone_v4=1 rejected=0 dead=0 linked=1
@mic free_segs=2/2 turns=2 frames=86 bytes=219520 busy_drop=0 cur=0B cap8=8s
@mic last_turn=m-0b8309-3 send_ms=438 err=-
@url now=ws://192.168.43.1:8443 src=factory saved=(none) linked=1 dialed=1
@url wifi=1 ssid=Mate 60 fails=0 err=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST errno=118 http=0
```

这几行怎么读（都是数出来的，不是推断）：

- `turns=2` ⇒ owner 真说了两回，不是台架自喂。
- `frames=86 bytes=219520` ⇒ 85 个满帧（85×2560=217600）+ 1 个 1920 字节的尾帧；
  219520 / 32000 = **6.86 秒语音**，两回合计。
- `busy_drop=0` ⇒ 没有一次因为段缓冲忙而丢采集。
- `err=-` + `send_ms=438` ⇒ 最后那一段 438 ms 发完，发送任务没报任何错。
- `free_segs=2/2` ⇒ 两段都发完并归还了缓冲，没有卡住的段。

⇒ **「采到 → 组帧 → 发出去」这一整条屏侧已经称到了**，两回都完整交付。
`errno=118` 那一行是上一次拨号失败留下的历史值（`fails=0`、`linked=1`），不是当前故障。

**顺带排掉一个假线索**：心跳里的 `ws=down raw=down setup=off` **不是这条链路**。
那三个字段读的是 Muse 官方云（`noise_ctrl_is_connected()` / `noise_tunnel_is_connected()`，
`esp32/main/app.c:3014–3021`），这个产品不接它，`down` 是对的。
屏到手机那条链路的状态**只看 `linked=`**。别照着心跳去查"ws 断了"。

那么「没收到文字 / 转写失败」只剩你们那一半。现在的毛病是**四种因共用一句话**：
`SiliconFlowAsr.transcribe()` 在 `!configured`、非 200、抛异常、文本空白这四种情形
**一律 `return null`**；而屏上这条路 `AppRuntime.onMicSegment`（:1005–1028）拿到 null
只说「屏上那句话没转出来：没听清或转写失败」。owner 看到这句话时，
**没有任何办法知道该去配 key、该去开网、还是该重说一遍**。

要你们做的（都不加页面、不加按钮、不增 APK 体积）：

1. **屏上这条路补一个 `configured` 前置判断**，和手机自己按键那条对齐 ——
   那条已经有了（`AppRuntime.kt:738`「语音输入没配 key：local.properties 里加 siliconflow.asrKey 再打包」）。
   key 没配就直接说这一句，别让它冒充"没听清"。
2. **把 null 拆成四种可分辨的结果**（枚举或带原因的回值都行），各给一句不同的话：
   - 未配 key → 「语音输入没配 key」（同上那句）
   - 网络 / 超时 → 「转写连不上网：检查手机蜂窝或 Wi-Fi」
   - 服务拒绝（非 200）→ 「转写服务拒绝（HTTP <status>）」
   - 200 但文本空 → 「没听清：这句话是空的」（**只有这一句**才是真的"重说一遍"）
3. **日志打 HTTP status + body 前 200 字**；**绝不许打 key，也不许打 Authorization 头**。
   现在非 200 那一支什么都不留，现场没法查。
4. `DemoRuntime.ingestMicChunk` 的 **Reject 分支现在只 `log("上行语音作废：${reason}")`，
   屏幕上没有任何反馈**。拼段失败（缺号 / 越界 / rate≠16000 / 超 100 帧）时 owner 看到的也是"没反应"。
   请让它和转写失败一样给一句可见的话。

屏侧这一轮配合加了一个数，帮你们把"采到静音"和"采到了但云端没认出来"分开：

```
@mic last_turn=m-xxxxxx-n send_ms=… peak=<0..32768> err=-
```

`peak` 是上一段 PCM 的峰值。开机自检那条静音基线是 **peak 13（约 −79 dBFS）**，
真人对着屏说话是**几千**。所以下一趟现场：
**peak 十几 ⇒ 麦克风那一路采到的是数字静音（屏侧的事，我查）；
peak 几千 ⇒ 声音采到了，问题在转写那一半（你们的事）。**
一个数就把两侧分开，不用猜，也不用等手机日志。

**还欠着的一趟**：`peak` 是这一轮刚加的，**还没有在真人说话时读到过**。
谁先称到谁写回 `firmware/screen/HANDOFF.md`，别把没称到的写成称到了。

---

### 0.9 【2026-11-13 下午】owner 又试了一次，还是「没转出来」——§0.7 那四条到当前远端 tip 仍未落地

远端 tip 实读 `1292005`（`git ls-remote git@github.com:zyawei1991-hue/carpet-sat-demo.git main`，
**不是 gh-proxy 那个只读镜像**），和本地 HEAD 一致。对着这一版逐行核过，§0.7 要的四条**一条都没进**：

- `AppRuntime.kt:1007` 屏上这条路仍是裸 `transcribe(MicUplink.pcmToWav(seg.pcm))`，**没有 `configured` 前置判断**（手机自己按键那条 :737 有）。
- `SiliconFlowAsr.transcribe()`（:34–77）仍把 `!configured` / 非 200 / 抛异常 / 文本空**四种因一律 `return null`**，全程不打 status、不打 body。
- `onMicSegment`（:1008–1009）拿到 null 仍只说那一句「屏上那句话没转出来：没听清或转写失败」。

**先排一个最可能的单因，owner 不用等你们改就能自己查**：装机那版 APK 很可能**根本没配 ASR key**。
证据是手机自己那个麦克风按钮的闸 —— `HomeShell.kt:533` `enabled = app.voiceInputConfigured && !transcribing`，
没配 key 时它是**灰的**；点它会说「语音输入还没接：等 key 配上再按」（`HomeShell.kt:575`）。
屏上那条路和手机按钮用的是**同一个** `SiliconFlowAsr.configured`，所以：

- owner 点一下**手机自己的麦克风按钮**：
  - 若说「语音输入还没接：等 key 配上再按」⇒ **APK 没配 key**，屏上那条必然也 null。修法 = 打包时 `local.properties` 里加 `siliconflow.asrKey`（key 只在 owner 手机/命令行，**不进仓库、不进日志**）。
  - 若它能录、能转出文字 ⇒ key 和网都好，屏上那条的 null 就只剩**非 200 / 文本空 / 采到的是静音**三种，必须靠 §0.7 第 2、3 条（拆 null + 打 status/body）才分得清。

**和上一轮那道闸分清，别混**：owner 之前问「对着屏说话还没开」、你们说「不用改 App」——那是对的，
那道闸的真因是屏侧多要了一个契约规定不上线的 envelope `v>=4`，**屏侧已修**（`4f62f77`），装机回读 `protocols=[4,4] ⇒ 上行开`。
**这一条不是那道闸**：上行现在开着的、屏也把整段发到了手机（§0.7 真人 turns=2 已称），卡在你们这半的**转写**上。

§0.7 那四条仍然要做，一条都不能省（补 `configured` 前置 / 拆 null 成四句 / 打 status+body 前 200 字但**绝不许打 key 或 Authorization** / `ingestMicChunk` 的 Reject 分支给可见反馈）。
屏侧 `peak` 那一趟仍欠着（§0.7 末尾）：真人说话时 peak 十几=采到数字静音（屏侧，我查）、几千=采到了（你们那半）。

---

### 0.8 以下 §0 旧文（11-11 深夜）**已作废，只留作历史**

旧文要你们做的两件事（补 `mic_chunk` 解码、`CONTRACT_VERSION 3→4`）**你们都已经做完了**
（远端 `eeedf80` 合并了 App 1.26.0；装机回读 `protocols=[4,4]` 为证）。
旧文里「屏现在认的就是每帧外层那个 `v`」这句是**错的**，见 §0.2。
下面这些**仍然有效**，因为它们和那道闸无关：

屏侧今晚称到的（你们不用等，也不用复现，原文在 `firmware/screen/HANDOFF.md` §17.2）：

- 屏拨号**第一次做到"开机第一拨就上"**（`I (7084) carpet: linked, contract v3`）——
  演示日那两条"App 无法连接圆屏"里的内存真因已经修掉了。
- 「按住屏上的键说话 → 对面收到整段 → 拼成一个能听的 WAV」整条走通：43/43 帧、110,080 字节、
  屏侧 0.71 秒发完、对面查缺号=无。（服务端是电脑上的假手机，**不是真 App**。）
- 上行是**一段突发不是匀速**：≈170–190 KB/s 的 JSON 持续 0.5–0.7 秒；上限 8 秒 = 100 帧 = 256 KB PCM。
  解码和拼段放 IO 线程，别放 UI 线程。
- **对着你们今天装机这一版，屏是完全沉默的**，所以现在这版固件对你们无害；你们改到哪一步
  都不怕屏抢跑 —— 这也意味着**这一轮不用赶发版顺序**，改完再装机也行。

自测不用真屏：`node firmware/screen/tools/fake-phone.mjs --v4-ready` 就是"你们改完之后"那一档服务端
（整帧 `v=4`、按 `turn_id` 拼块、查缺号、封 RIFF 落 `.wav`、回 `dialog{dir:"up"}` + `{dir:"down"}`）；
**默认档（不加参数）= 今天装机那一版的样子**（申报 4、收到就 `close("bad contract")`）。

真屏 + 真 App 的验收只看两处数：屏上 `@mic … armed=1 phone_v4=1` 且
`上行 m-xxxxxx-n：<n>/<n> 帧、<字节>（<秒> 秒语音）<x.xx> 秒发完`；手机侧拼出来的 WAV 送硅基流动
（`SiliconFlowAsr.transcribe`，已称：16k WAV 认、裸 PCM 必须自己封 44 字节 RIFF 头），
转写那句和说的那句一致 ⇒ 然后答句照已经跑通的 v3 下行由圆屏念（§1 那条规矩，不许两边各念一遍）。

**上一轮屏侧的状态（同样不用等它）**：圆屏不出声的真因称到并修掉了 —— 播放任务的栈要 16 KB
连续内部 RAM，而板上实测最大连续块只有 7,424 B，所以那个任务**从来没起来过**。
现在栈放 PSRAM，本机走**和线上一模一样的帧**（`b64` → `cJSON args` → `carpet_audio_on_chunk`）
称到 `frames=238 drops=0 last=- gaps=0 gap_ms=0`、`@audio 放完 480000 字节 / 15.0 秒`。
⇒ **屏已经能放了。你那边一推 `audio_chunk`，现场就会真的两个设备一起念**，所以第 1 条是必须的，
不是优化。实测记录见 `firmware/screen/muse/README.md` §8 第 7 条。

---

## 1. 发声落点路由（owner 2026-11-11 拍的规矩，最优先）

原话：「**如果是在圆屏上对话，就应该是圆屏语音回复；如果是手机上对话，应该让手机上可以选择设置，
由哪个设备发声**」。

现在远端 `ff78279`（App 1.20.0）的行为是 `ScreenVoice.playNetAnswer()` 拿到 MP3 之后
**无条件两边都放**（推屏 + 本机 `MediaPlayer`，合成 `NetResult.Both` 并把 `_issue` 清空）。
屏侧一修好，这个行为的现场表现就是**手机和圆屏各念一遍同一句、同一个晓伊，界面上没有任何提示**。

要改成：

1. **规则一：起头在圆屏的那一轮，只有圆屏念，没有例外**，也不许被下面那个设置翻掉
   （屏上对话时人就在屏边上，手机兜底等于在另一个人耳边又念一遍）。
2. **规则二：起头在手机的那一轮，齿轮面板里可选「由哪个设备发声」= 圆屏 / 这台手机。默认圆屏。**
   选「这台手机」，或屏没连上 / 没升到 v3 时，落回手机本机 —— 这条现成（`whyNot()` 那两句话）。
   默认圆屏的理由不是审美，是这块板实测的两条：喇叭只有一只，而屏的麦克风在不 rest 时
   每 20 ms 常驻读，**两边同时念是会回进的**。

代码上这件事有多小（行号是远端 1.20.0，读过才写的）：

- 起头在哪**已经透到出声道口了**：`core/.../ai/DialogBridge.kt:183` 的 `payloadOf()` 把
  `origin = turn.via` 放进 `DialogPayload`；`apps/android/.../AppRuntime.kt:429` 是
  `onAnswer = { d -> voice.speak(d.turnId, d.text) }` —— **`d.origin` 就在手边，现在被忽略**。
- `ScreenVoice.kt:305 speak(turnId, text)` 加一个"这一句起头在哪"的入参；`playNetAnswer()` 里
  推屏那条和本机 `playerFor` 那条各自按它决定做不做，**别再走无条件的 `NetResult.Both`**。
- 起头在手机、且选了「这台手机」时，**屏一个音频帧都不该收**：`AudioChunks.commands` 那一趟整个跳过。
  一句 27 字实测 75 帧 / 262.5 KB，不该为了"反正屏也听不见"往链路上灌。
- 那个可选项**放现有齿轮面板、用现有控件**：`ui/HomeShell.kt:326-350` 已经是
  `ValueRow("答句声音", …)` 加一行「语气」的 `FlowRow { Chip(...) }`，同一档控件、
  **不新增页面、不加按钮**；存进齿轮面板那套现成的设置存储。
  **仍然不许为这个增加 APK 体积**（一个字符串键 + 三档文案）。

验收（现场串口 `>carpet` / `>audio` 的读数，屏侧我已经把口径留好了）：

| 场景 | 应该看到的 |
| --- | --- |
| 屏上起头一轮 | 屏出声；`frames` 往上涨、`@audio 放完 …`、`gaps=0`；**手机一声不出** |
| 手机上起头、选圆屏 | 屏出声，手机不出声；**`sent` 帧数 = 整段块数**（约 17 帧/30 字） |
| 手机上起头、选这台手机 | 手机出声，屏不出声；**屏侧 `frames` 根本不涨**（= 一个 `audio_chunk` 都没发） |

## 2. 答句长度与模型（已拍，别再自行发挥）

- 压缩靠 **system 提示词**，一句 ≤30 字：`你是车载卫星助手。用一句话回答，不超过30个字，直接给结论，不要解释推理过程。`
- **`max_tokens` 反而要给到 250 以上。** 实测 20 字的答句烧掉 158 个 completion token、**其中 145 个是思考**；
  给 120 会被截断，然后推理草稿漏进正文（§12.5 称过）。
- 对话面默认模型 **`deepseek-v4.1-flash`**（压缩提示下实测 **3.05 s**，三档里最快；
  `glm-5.3-flash` 4.13 s 且给 220 token 时跑到 10.14 s）。
- `NetVoice.MAX_CHARS = 300` 和这条对不上，**改到 30 字那一档**（下面的数是按实测那一点
  27 字 → 5.98 s → 75 帧推的算式，**不是又测了一遍**）：
  · 30 字 ≈ 6.6 s ≈ 211 KB PCM ≈ **83 帧 ≈ 290 KB 线上**；
  · 300 字 ≈ 66 s ≈ **2.13 MB PCM —— 撞穿段上限**（屏侧 `AUDIO_CAP` 和 App 侧
    `AudioChunks.MAX_TOTAL_BYTES` 都是 2 MB / 64 s，超了**整段不接**，
    现场表现是屏上写了整句话、喇叭一声不出）。

## 3. 合成落点：edge-tts 晓伊（owner 已拍，不用系统 TTS）

- 输出解帧头实测 **MPEG2 Layer3 / 24000 Hz / 单声道 / 48 kbps**。
- 送屏前要 **解码 + 重采样到 16000 Hz**，比值 **24000 → 16000 = 2/3**，用系统
  `MediaExtractor` / `MediaCodec`，**不引第三方解码库**（APK 不许变大）。
- ⚠ 屏侧只吃 16000：`carpet_audio.c` 收到 `rate != 16000` 会**整段不接**并打
  `采样率 %d，本端只吃 16000，这段不接`。按 22050 那档算比值是旧的硅基流动收费档，已作废。

## 4. v3 下行的帧形状（屏侧刚按同一形状复称过，别改这些数）

- 一块 = **2560 字节 PCM**（80 毫秒 / 16k / 单声道 / s16le），字段 `turn_id / seq / total / rate / b64`。
- 2560 B 编成 base64 实测 **3,416 字符**，加 JSON 外壳整帧约 **3.5 KB**。屏侧组帧上限
  `SCREEN_LINK_MAX_FRAME = 4096`（= `ws_cfg.buffer_size`），App 侧 `AudioChunks.JSON_LIMIT = 3800`
  —— 两头都在线上，**加大一块会整帧被屏丢掉**（`screen_link.c:538` 是超尺寸直接丢）。
- `seq` 从 0 连续；**跳号 ⇒ 屏整段丢**（不放半句），`total` 每帧都要带且 ≤ 2 MB。

## 5. v4 上行（`mic_chunk`）：**你们那一半已经做完了（1.26.0）；屏侧的闸 11-13 才修对 —— 先读 §0**

先说最要紧的一条，2026-11-11 在远端 tip `26bbfc4` 逐行看过：

- `DemoRuntime.kt:513` 的 `ackNow()` 已经在报 `protocols = listOf(CONTRACT_VERSION, 4)`；
- 但 `ScreenLink.kt:112` 的 `CommandType` **没有 `mic_chunk`**；
- 入站解码 `ScreenLinkServer.kt:128-131` 是
  `runCatching { ScreenMessage.decode(message) }.getOrElse { … conn.close(CLOSE_POLICY, "bad contract"); return }`，
  `CLOSE_POLICY = 1000`（:247）；`Json { ignoreUnknownKeys = true }`（ScreenLink.kt:67）**救不了枚举取值**，
  serialization-json 1.6.3 对未知枚举值必抛。
- 彩排台复用同一个 `ScreenLinkServer`（`simulator/.../DemoDriver.kt:169`），**同一风险**。

⇒ 现在这台 App 上，屏只要按 v4 的规矩发第一块 `mic_chunk`，**这条 WebSocket 当场被你关掉**，
屏侧自有退避重连，现场观感就是"对话和语音全断、链路一直抖"—— 和之前两次「App 无法连接圆屏」一模一样。
**（写这一段的时候屏侧的对策是"只接开发口、默认关"；现在已经换成下面那道闸 —— 对着你们这版实测 `armed=0`，
一条都不发。结论不变：别拿真手机试上行。）**

**2026-11-11 晚更新：屏侧这一半已经写完、编过、刷进板子了。** 对你们那一侧的影响都在下面几条里
（"要做的"那一节是行动项），先把**改了形状**的地方说清楚，别照旧文档写：

- **不是每 80 ms 一帧，是"按住采满、松手一口气发完"。** ASR 本来就不是流式的，一路 stream 换不来延迟，
  只换来你们要为一路上行另写结束判断。改成这样以后 **`args.total` 从第 0 帧起就是这一整段的字节数**，
  拼段的判据只有一条："按 `seq` 从 0 连续攒到 `total` 就齐"；**最后一块可以短于 2560 字节**（不满不丢）。
  上限 8 秒 = 256,000 字节 = 100 块。`turn_id` 前缀是 `m-`（例：`m-3a7c1f-4`）。
- **屏的 `hello.protocols` 现在多报一个 4，`v` 仍然是 3。** 这个数字只代表"这版固件会发 `mic_chunk`"，
  不代表可以直接收。
- ~~**【2026-11-11 深夜改判】屏的开门条件改成两个都要：外层信封 `v` ≥ 4 且 `ack.protocols` 里有 4。**~~
  **【2026-11-13 作废，这一条是错的，真因见 §0.2】屏的开门条件只有一条：`ack.protocols ∋ 4`。**
  外层 `v` 因为你们 `encodeDefaults = false` 根本不上线，屏读到的 1 是"键不存在"的兜底值，
  所以 `v >= 4` 对任何版本的 App 都不可能成立 —— 那半个月屏对着**已经做好的** 1.26.0 一直
  `armed=0`，按键和唤醒词都报「对着屏说话还没开」，**责任在屏侧这道闸，不在你们**。
  当时那句"装机那版报了 4 但 `CommandType` 收不下"也已过期：1.26.0 里
  `CommandType.mic_chunk -> ingestMicChunk(args)` 是有的（`DemoRuntime.kt:665`）。
  ⇒ **对你们没有新的代码要求。** 唯一还开着的是 §0.4 那条契约卫生（schema 根 `required` 列着
  一个不上线的 `v`），不阻塞演示，你们挑一条修法即可。
- 屏侧那两张表（`CMD_NAMES`、组件里的 `ALLOWED`）已经加上 `mic_chunk`；
  **schema 和 Kotlin 那两张还在你们那边**，`SchemaDriftTest` 会逼着一起改。

要自测不用真屏：`firmware/screen/tools/fake-phone.mjs` 现在会**真收上行** ——
按 `turn_id` 拼块、查缺号、封 RIFF 落 `.wav`、回 `dialog{dir:"up"}+{dir:"down"}`。三个人设对应三种对面：

- 默认（不加参数）—— 整帧 `v: 3` + `protocols: [3,4]`、收到帧就 `close("bad contract")`：
  这就是**你们今天装机那一版**的样子（申报了 4、`CommandType` 里没有 `mic_chunk`）。
- `--v4-ready` —— 整帧 `v: 4` + `protocols: [4]`、收到帧拼段落 WAV：这是**你们改完之后**那一版。
  屏侧对着它已经整条称通过（按下采音 → 43/43 帧一口气发完 → 号齐缺号=无 → 出一个能听的 `.wav`）。
- `--no-v4` —— 只申报 `[3]`：演"没做 4 的老服务端"，屏那边 `armed=0`，一条都不发。
- `--mic-close` 叠在 `--v4-ready` 上：申报齐了但照旧关链，专门称屏侧那三道闸里的**熔断**。

你们改完 `CommandType` 之后，最省事的第一趟是拿彩排台对着 `--v4-ready` 这一份实现跑，而不是先上屏。

**上行那一帧的形状已经按真机读数称过，别照老文档写：** 2560 字节 PCM/帧 → base64 3416 字符 → 整帧约 3.5 KB，
压在 `SCREEN_LINK_MAX_FRAME 4096` 里（和你们 core 侧 `AudioChunks.JSON_LIMIT = 3800` 同一个算法）；
一段最多 100 帧 = 256,000 字节 = 8 秒。**入站是一小段突发而不是匀速**：3.44 秒语音 / 43 帧在屏侧
0.71 秒发完，也就是 **~170–190 KB/s 的 JSON 持续 0.5–0.7 秒**，比这份文档早先猜的 43 KB/s 高一个量级
（这是"屏 → 手机"方向，服务端是假手机；你们那侧的解码要在 IO 线程上接得住这个突发，别在 UI 线程拼段）。

要做的（**1~5 你们在 1.26.0 里都已做完，这一节现在只当形状说明书用**；细节以 `protocol/screen-link.md` 的 v4 那一节为准）：

1. `CommandType` 加 `mic_chunk`，**同时**改 `protocol/screen-link.schema.json` 里 `command.cmd` 的枚举
   （`SchemaDriftTest` 就是称这四张表一致的，漏一张单测当场红）。
2. 入站拼装：按 `turn_id` 收段，`seq` 从 0 必须连续，**断号就整段作废**（和 v3 下行同一套规矩，
   半句比没句更难解释），段长上限 8 秒 = 256 KB PCM。
3. 拼完的裸 PCM **补 44 字节 RIFF 头**（16000 / 单声道 / 16 bit）再喂现成的
   `SiliconFlowAsr.transcribe(wav, timeoutMs)`（`core/.../ai/SiliconFlowAsr.kt:34`，
   它是**阻塞 HTTP** ⇒ 要放 `Dispatchers.IO`，参考手机自己按键那条：`AppRuntime.kt:753 → :757 → :764`）。
4. 转写出来的那句回 `dialog{dir:"up", turn_id:屏给的那个, text:…}` ——
   **`turn_id` 是屏在按下那一刻生成的，不许手机另起一个**（它是这一轮的唯一编号：拼段靠它、去重靠它、答句配对靠它）。
   然后把这句喂进对话入口（`DialogBridge.ask(question, via="screen", turnId=同一个)`），
   答句照旧走**已经跑通**的 `dialog{dir:"down"}` + `audio_chunk`，由圆屏念（规矩一，不再本机放）。
5. **把版本号真的抬上去**：`ScreenLink.kt:27` `CONTRACT_VERSION = 3 → 4`、`SUPPORTED_VERSIONS` 加 4。
   这一条是屏侧上行的**唯一钥匙**（屏现在看的是 `ack` 外层那个 `v`，不是 `protocols` 里的 4）。
   你们要是只做了 1~4 而忘了这一条，屏会一直 `armed=0`、怎么按都不出声 —— 现场看起来就像"这功能没做"。
6. 如果这一轮不打算做 2~5，**就把 `ackNow()` 里那个 `4` 撤掉**。申报和实现分家比"屏不知道对面能干什么"危险得多。

四张表这轮的分工：屏侧的 `CMD_NAMES` / 组件 `ALLOWED` 我这边同步加，**schema 和 Kotlin 那两张在你那边**；
发版顺序照旧不许倒 —— **App 先发、装机、屏才允许真发这一帧**。

## 6. 一律不许做的事（都撞过，写在最前面省一轮返工）

不加新页面、不加新按钮（齿轮面板同档控件的**那一个**可选项是 owner 明确要的，例外）；
不增加 APK 体积（不引第三方编解码库、不打包模型）；不许两边同时念同一句；
不改屏侧那四张表；不假设屏上有电脑或云端直连。

## 7. 还欠着的称量（谁称谁写回文档）

- **端到端一次都没串起来跑过**：分量相加是转写 0.6~0.8 s + 对话面 3.05 s + 合成 1.9~2.5 s
  ≈ **5.6~6.4 s 才开始出声**，然后放约 4.3 s。这是算式，不是实测。
- **真从手机（你们那版 App）经 WS 进来的那一条 `audio_chunk` 还没称过。** 屏侧现在称通的是"对着电脑上的
  假手机、走真 Wi-Fi 真 WS"的**上行**方向；下行放音那一趟还是 `>audtest` 本地喂帧（`frames=238 drops=0 gaps=0`）。
  这一条要现场那一趟补，屏侧判据已备好：`frames` 涨 + `@audio 放完` + `gaps=0` + 只得到一个声音。
- **屏起头说话整条：对着假手机称通了，对着真 App 一次都没称过** —— 因为装机那版收不下 `mic_chunk`，
  而屏现在也不认它的申报（`armed=0`）。你们把 1~5 做完，这一条才第一次有得称。
- 手机蜂窝上传 ASR 那句录音（约 256 KB）要多久、屏持续吃下行 WS 的吞吐、
  屏上采集与放音同时跑（回声）—— 全没称。**上行方向的那个量已经称到了**（~170–190 KB/s 突发、0.5–0.7 秒，
  见上面 §5），下行是同一条链路的反向，数量级可以参考但没实测。

## 8. 【2026-11-13】圆屏 B 规格四方向交互：屏侧已落地清单 + App 侧待办（owner 本轮交接）

owner 原话：「反正你把圆屏这边能做的做了，把需要 app 那边做的写文档传到远端仓库，我让 app agent 来做」。
规格以 `docs/ROUND-SCREEN-GESTURES.md`（下称 **B**）为准。这一节把 B 里**还没落地**的三块
（§1 守护灯点 / §4 上滑功能层 / §5 相册轮播）拆成「屏侧做什么 / App 侧做什么」，
并先列屏侧**已经做完、App 不用等也不用重复做**的部分。**本节不替代 §0–§7 的语音交接。**

### 8.0 屏侧已落地（2026-11-13 真机称到，App 不用动）

- **B §2 四方向**：下拉场景环 / 上滑功能层 / 左滑设置 / 右滑氛围；导航圆点改十字四方位；
  所有返回一律回脸；§2.1 互斥（SOS 布防/待发/已发冻结四手势、对话中左右滑忽略、熄屏全无效）。
  屏切页发 `page_report`。
- **B §3 场景环**：三横卡 车/亲子/对话，松手选定 + 0.5 s 换装调色（**屏侧资产，App 不推图**）。
- **B §5 默认氛围**：设置「声音」页新增「默认氛围用相册」开关（NVS `album_def`），
  右滑第一下按它落 时钟/相册；进了氛围再右滑仍轮换。真机称到：
  `>amb photo` → 右滑 `nav=photo`；`>amb clock` → 右滑 `nav=clock`（串口与设置页开关同一个 NVS key）。
- App 侧对这三块**唯一**的要求：继续照旧消费 `page_report`（把屏报的页名写进 `state.ui.page` 回声）。
  八个页名 `face/picker/vehicle/kids/avatar/clock/gallery/photo` 已在 `state.ui.page` 枚举里，
  **不许改名**（`SchemaDriftTest` 读屏侧 `PAGE_NAMES`）。

### 8.1 B §1 守护灯点 —— App 侧**已经做完**，剩下全是屏侧

- B §1：有未读守护事件 → Avatar 头顶一颗小灯点；点它先播报事件再进对话。
- **App 侧现状（读过才写）：事件已经在发。** `core/.../runtime/DemoRuntime.kt:868` 按
  `snap.fence.inside` 发 `fence_enter`/`fence_exit`；`EventKind` 枚举含 `sos_*`/`fence_*`/`sentinel_motion`；
  `ScreenEventPushTest:84` 断言真的推了出去。⇒ **App 不用为灯点加任何代码。**
- **屏侧现状**：`carpet_link.c` 的 `on_command` 只认 `audio_chunk` / `wake` 两条下行，
  **`event` 帧现在被忽略**，所以灯点还没有。这是屏侧待办（我这边）：消费 event 帧 → 记未读 →
  脸上画灯点 → 点击先播报再进对话。
- 验收判据（屏侧补称时用）：fake-phone 或真 App 发一条 `fence_enter` → 灯点亮；点 Avatar →
  先播报该事件再进对话；没有事件灯不亮（不放假数据）。
- 给 App 的唯一提醒：event 帧的 `event_id` 要全局唯一（`evt-<boot>-<n>`，≤39 字符）、带 `ttl_ms`；
  屏的去重环形缓冲跨重连不清空。这条契约已写死，照旧即可。

### 8.2 B §4 上滑功能层 —— P2，**先 App 清状态归属，屏再接按钮**

- B §4 每场景 2-3 个大按钮（≥72 dp、一屏、不滚动）：车=看车在哪/下次过境/围栏开关；
  家=讲个故事/看成长记录/管额度；对话=免提对话/静音。**读写手机同一份状态**。
- B §6.5 明确：等手机端 ViewModel 状态归属清理（B 里记作任务 #8）完成后再接，避免两边状态打架。
  ⇒ **这是排期，不是遗漏。**
- **屏侧现状**：功能页现在还是旧卡片条（车载=卫星/对话/呼救；亲子=对话/过境/安全；分身=答句/情绪），
  底部三钮走 `card_next`/`card_auto`/`poke`（`CMD_NAMES` 只有这三条）。**不是 B §4 的动作。**
  在 App 状态/命令就绪前屏不摆死按钮（不放假数据）。
- **App 侧待办（按顺序）**：
  1. 清 ViewModel 状态归属：让「围栏开关/车位置/下次过境/额度/成长/故事/免提/静音」各有一个单一来源，
     屏和手机同名功能读同一份。
  2. 把这些暴露进 `state` 帧：围栏开关→`state.fence`（`level` 已有 normal/armed/pending/sent/acked）；
     车位置/下次过境→`state.sat` / 轨迹；额度/成长/故事/静音→需要新增 state 字段（加性，老屏忽略）。
  3. 处理屏发来的 command：围栏开关→`set_fence`（cmd 枚举已有）；看车在哪→`track_pull`
     （枚举已有但**现在没人处理**，要实现：回批量轨迹进 state）；讲个故事/管额度/免提/静音→
     现有 cmd 不够就**新增 cmd**（加性挂在现档、不升版）。
  4. 新增 cmd 时四张表一起改：`protocol/screen-link.schema.json` 的 `command.cmd` 枚举 +
     Kotlin `CommandType`（`SchemaDriftTest` 逼着一致）；屏侧 `CMD_NAMES` / 组件 `ALLOWED` 我这边同步加。
     **发版顺序不许倒：App 先发装机，屏才真发新 cmd。**
- **屏侧待办（等 1-4 就绪）**：把功能页 rows 换成 B §4 动作按钮、每个 wire 到对应 command、读 state 显示当前值。
- 边界：不加新 frame `t`（B §7）；新 cmd 老固件静默忽略，正是想要的失败方式。

### 8.3 B §5 相册轮播 —— P2，要新开一个推图 cmd（不新 frame t）

- B §5：相册全屏轮播，家人照片从手机推送。B §6.4：时钟先做（纯本地，已落地），
  相册「要手机端推图通道，随后」。⇒ **P2**。
- **屏侧现状**：`PG_PHOTO`/`PG_GALLERY` 是 `F_NONE` 占位页，副行写死「还没接上 · 不放假数据」，无图像渲染。
- **契约张力与解法**：B §7 说「下行协议不加新帧」、schema 也刻意没有图像帧。解法和 `audio_chunk`/`wake`
  同族：**在已有 command 帧里加一个新 cmd（建议 `photo_chunk`）**，base64 JPEG 分块推，不新开 `t`。
  老固件忽略未知 cmd，不会掉线。
- **App 侧待办**：
  1. 新 cmd `photo_chunk`：`args={album_id, seq, total, b64, …}`；`seq` 从 0 连续、缺号整组丢；
     每帧编码后 ≤3800 字节（固件组帧上限 4096，超了静默丢帧）。同步改 schema `command.cmd` + Kotlin `CommandType`。
  2. 照片从手机相册现选现推，**不打包进 APK**（APK 不许变大）。
  3. 推送时机：屏 `page_report` 到 photo 页时推一组，或 App 主动推；守护事件一到屏会自己顶出 Avatar
     （B §5），App 不用管打断。
- **屏侧待办（等通道定了）**：JPEG 解码（LVGL 自带，不引第三方库）+ 轮播 + 事件打断。
- 排期提醒：相册是 B 里明确「随后」的 P2。这一节先把契约形状留下，**是否现在启动由 owner 拍**；
  默认按 B 的排期先不做，App agent 不必抢跑。

### 8.4 和语音那几节的关系

§0–§7 的语音交接（尤其 §0.9 那四条：补 `configured` 前置 / 拆 null 成四句 / 打 status+body 但**绝不打 key** /
`ingestMicChunk` Reject 给可见反馈）**仍然欠着**，和本节互不替代。本节三块都不阻塞语音演示；
语音那条路照 §0–§7 走。
