# 圆屏接入点 · App 侧待改文案

**状态**：待执行。给改 `apps/android` 的那个 agent。一份文档、一处改动。
**范围硬约束**：只改 §1 那一行文案。除 §3 那条明确标了「建议」的，不许动别的代码。

---

## 0. 事实基线（屏侧现在就是这个样子，文案要对齐它）

1. 圆屏的接入点地址是**运行期可设**的：屏上 设置 → WI-FI → 那一格（英文构建的标签是
   `Access point`，中文构建是「接入点」）。填完存进 NVS，**立刻重拨**，不用重启、不用重刷固件。
2. 那一格**留空 = 用固件出厂那条**；出厂值是 `ws://192.168.43.1:8443`，也就是手机在自己
   **2.4GHz** 热点里的那个地址（S3 不认 5GHz）。
3. 所以主路（手机开热点）**屏上一个字节都不用填**。
4. 退路才填：热点开了客户端隔离、`.1` 拨不通时，把 App 那张卡里「接入点」那一行显示的地址
   抄进屏上那一格。
5. 白名单出厂就带 `WS175B-A3F7`（`core/src/main/kotlin/com/carpet/satdemo/core/screen/ScreenLinkServer.kt:22`
   和 `:206` 的 `DEFAULT_SCREEN_ID`），**不用手填编号**。
6. App 侧挡这条路的只有一个东西：开关「允许圆屏接入」
   （`apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/HomeShell.kt:577` →
   `core/src/main/kotlin/com/carpet/satdemo/core/runtime/DemoRuntime.kt:331`；
   `apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/Tabs.kt:129` 是同一个开关的第二个入口）。

---

## 1. 要改的唯一一处

**文件**：`apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/HomeShell.kt`
**行**：`641` —— `CollapsableNote("配对怎么做", …)` 的第二个参数。

现状（**错的**）：

```kotlin
"手机这边放开接入、把圆屏编号加进白名单，再到圆屏上填上面那个接入点地址。",
```

错在最后半句「再到圆屏上填上面那个接入点地址」。那是换底座之前那块屏的能力，现在这块屏出厂就拨对了
地址，主路上屏上零输入；这句留在原位，现场就会有人在 1.75 寸圆屏上敲一个根本不需要的 IP。

**整行替换成这一行**（照抄，含缩进和行尾逗号）：

```kotlin
                "手机开热点（要 2.4GHz）、上面那个开关放开就够了 —— 圆屏出厂就拨 ws://192.168.43.1:8443，那是手机自己在热点里的地址，屏上不用填。只有拨不通（热点把连上的设备互相隔开了）时，才在屏上 设置→WI-FI→接入点 那一格改成上面那行接入点显示的地址。",
```

**不许**顺手加的：新按钮、新开关、新输入框、新的 `Row`/`RowLine`、把这句话拆成多条折叠项。
`CollapsableNote` 的标题 `"配对怎么做"` 保持原样。

---

## 2. 产品原则（比文案本身重要）

除 §1 那一行，App 这边**不加新按钮、不加新开关、不加新页面**。屏侧已经把「输入地址」这一格承担了，
手机侧的动作越少越好。

判据（跑一条就够，别靠眼睛）：

```
git diff --stat -- apps/android     →  只有 HomeShell.kt | 2 +-
git diff        -- apps/android     →  + 行只有 §1 那一句，- 行只有原来那一句
```

---

## 3. 建议（不硬性，不做也算验收通过）：「接入点」那一行长按复制

- **位置**：`apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/HomeShell.kt:566-571` 那个
  `RowLine("接入点", …)`。
- **做什么**：长按把 `ws://<ip>:8443` 那串复制进系统剪贴板，让现场的人不用对着手机屏幕敲字。
- **为什么只是建议**：主路用不上它 —— 只有 §0 第 4 条那个退路才需要抄地址，而退路本身少见。
- **现成的口**：`apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/SceneCards.kt:206`
  已经在用 `LocalClipboardManager.current`，不引新依赖。
- **若做，就这样做**：给 `apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/Primitives.kt:287`
  的 `RowLine` 加一个**默认 null** 的 `onLongClick: (() -> Unit)? = null`，`Modifier` 上换成
  `combinedClickable(onClick = …, onLongClick = …)`；其它调用点行为不变。复制成功要给一行可见反馈，
  用现有的 caption 文本，不要引入新组件。
- 这条会破 §2 那个 `2 +-` 判据，所以**单独一个 commit**，别和 §1 混在一起。

---

## 4. 验收

1. `grep -n "再到圆屏上填" apps/android/src/main/kotlin/com/carpet/satdemo/app/ui/HomeShell.kt`
   → **无命中**。
2. `./gradlew :app:compileDebugKotlin` 过（改到 §3 就再跑 `./gradlew :core:test`）。
3. **真链路称一次**（这条才是文案对不对的判据）：手机开 2.4GHz 热点 → App 里把「允许圆屏接入」放开 →
   圆屏开机、**屏上什么都不动** → 配对那张卡里 `WS175B-A3F7` 变绿（在线）。
   称不到这条就只能说「文案改了」，不能说「文案对了」。
4. 别碰：`protocol/screen-link.md`（契约）、固件仓、`docs/MAINTENANCE.md`。

---

## 5. 排查口（在屏那边，App 不用改代码）

现场分不清「改了没生效」和「生效了但对面没起服务」时，接电脑走串口更快：

**为什么留 `>url` 这个口**：屏上那一格要人在 1.75 寸圆屏上用软键盘敲一个带冒号和端口的 IP，
这本身就是罚人；调试和现场救急时不该逼任何人受这一罚，所以同一条 NVS key 必须有一条不用碰屏的写法。
它不改变 §1 的文案，也不给 App 增加任何东西。

- `>url` → `@url now=… src=nvs|factory saved=… linked=… dialed=… wifi=… ssid=…`（密码不印）
- `>carpet` → 多一行 `@carpet url=… src=…`
- `>url ws://a.b.c.d:8443` 写一条，`>url clear` 抹掉、回到出厂那条

## 6. 现场那个红字：`can only be started once.`（11-08 晚，这条要 App 改）

现场回读（用户手机截图 + 屏侧串口同一时刻，抓在 `D:/dev/esp/m1108v/tap3.txt`）：

- App「接入点」那一格红字：`起不来: com.carpet.satdemo.core.screen.ScreenLinkServer can only be started once.`
- 同一屏：右上「未连接」、白名单里已有 `WS175B-A3F7`、「允许圆屏接入」是开的、卫星那圆 `GW-06 已锁定 开窗 CNR 42.5 dB`。
- 屏侧每 11 秒重复：
  ```
  I screen_link: ws 接入 ws://192.168.43.1:8443
  I screen_link: hello 已发：WS175B-A3F7  电量未知
  I carpet: linked, contract v3
  W screen_link: state 超过 10 秒没来（gap=11 秒），主动重连 第25 次
  W websocket_client: Client was not started
  ```

### 6.1 先纠正一条会被当判据用的误读（屏侧的锅，已记进屏侧 HANDOFF）

**`contract v3` 不是谈下来的，它证明不了服务端回了 hello。** 原文：

- `screen_link.c:63` —— `static volatile int s_negotiated = SCREEN_LINK_CONTRACT_MAX;`
  （`include/screen_link.h:37` = `3`）。全文件里 `s_negotiated` 只有这一处写，
  553 / 674 / 705 三处读，**没有任何一条收到的帧往它赋值**。
- `screen_link.c:552-553` —— `send_hello();` 之后**立刻** `on_connected(s_negotiated)`，
  中间不等回执。

所以 `I carpet: linked, contract v3` 只是屏把自己声明的上限印了一遍。
**这一串里真正算数的是 `ws 接入`**：它打在 `WEBSOCKET_EVENT_CONNECTED`
（`screen_link.c:437-441`），esp_websocket_client 只在收到 HTTP 101 升级响应之后才派这个事件 ——
所以能确定的是 **TCP 通、8443 上确实有个东西在应答 WS 握手**；
不能确定的是那个东西是不是当前这个 runtime 的 `ScreenLinkServer`。
`has_state=0` + 每 11 秒重连，恰恰说明 app 层那一帧没推过来。
**别拿 `contract v3` 当"对面活着且认识我"的证据**，App 侧改完的验收也不许用它。

**还能再缩小一步**：服务端收到 hello 的那条分支一旦跑到，屏就会立刻看到一帧 state ——
`ScreenLinkServer.kt:105-111`（白名单过了 → `known[hello.screenId] = conn` → `onHello(msg)`）
接到 `DemoRuntime.kt:189` 的 `pushState()`。
现场是连接**没被关**（屏撑满 11 秒才自己重连，说明既没吃到 `demo lock`
（`ScreenLinkServer.kt:89-93`）也没吃到 `unknown screen`（`:104-107`）），
但也**没收到任何 state**。两件事同时成立，只可能是：应答握手的不是当前这个 runtime 接好的那条通道
（旧实例的 accept 还活着 / 另一个进程占着 8443），或者 hello 那一帧根本没进到 `onMessage`（`:97`）。
这一条不用改代码就能分：**改完之后 `>carpet` 里 `has_state` 必须从 0 变 1**，
`linked=1` 单独变绿不算过。

### 6.2 硬事实：同一个实例第二次 `start()` 必抛，且 `stop()` 救不回来

依赖：`core/build.gradle.kts:16` → `api("org.java-websocket:Java-WebSocket:1.5.6")`。
下面是这台机器上对那份 jar 里的 `org/java_websocket/server/WebSocketServer.class`
跑 `javap -p -c` 读出来的字节码（不是从源码网站抄的）：

```
public void start();
   0: getfield  #137  // Field selectorthread:Ljava/lang/Thread;
   4: ifnull 40
   7: new  java/lang/IllegalStateException
  28: ldc    #162     // String  can only be started once.
  36: invokespecial IllegalStateException.<init>(String)
  39: athrow
  40: new  java/lang/Thread ... 58: Thread.start()  61: return
```

抛不抛只看 `selectorthread` 这个字段是不是 null。而 `stop(int, String)` 里做的是
`isclosed.compareAndSet(false,true)` → 逐个 `WebSocket.close(1001)` → `wsf.close()` →
`selector.wakeup()` → `selectorthread.join(timeout)` —— **整份 class 里对 `selectorthread` 只有一次写**
（在 `run()` 里赋成当前线程），`stop()` 没有把它清回 null。
结论：**这个对象一旦起过，`start()` 就永远抛这一句**；
（那边给的机制写的是 "`stop()` 不重置 `startcalled`" —— 方向对，但 1.5.6 里没有 `startcalled`
这个字段，判据字段是 `selectorthread`，别照着 `startcalled` 去搜。）

对上 App 的三行：

- `DemoRuntime.kt:70` —— `val server: ScreenLinkServer = ScreenLinkServer()`，构造默认值，一个对象活一辈子。
- `DemoRuntime.kt:216` —— `val bindFail = runCatching { server.start() }.exceptionOrNull()`
- `DemoRuntime.kt:238` —— `runCatching { server.stop() }`
- `AppRuntime.kt:86` 一个进程只有一个 `DemoRuntime`；`AppRuntime.kt:576-585`
  `setGuard(true)` → `runtime.start()`，`setGuard(false)` → `runtime.stop()`。

**「停止演示 → 再开始演示」正好就是同一实例上的 `stop()` 然后 `start()`** —— 第二次永远起不来，
和端口有没有被占、和 Wi-Fi、和屏都没关系。

### 6.3 要改的方向（两条都行，验收是同一条）

**A. 每次起都换一个新对象**（推荐，改动面最小）：`DemoRuntime.kt:70` 的 `val server` 改成
`private var server`，然后在 `start()` 的第一行（`:140` 的 `fun start(autoTick…)` 打开花括号之后、
`:143` 那第一条 `server.onMessage = …` 之前）加一句 `server = ScreenLinkServer()` —— 不用判断状态，
换了就换。回调本来就全在 `start()` 里按 `server` 这个属性接的（`:143` `onMessage`、`:157` `onHello`、
`:193` `onDisconnect`、`:206` `onContractError`、`:208` `onSendError`），换新对象后这些行一个都不用动。
`stop()`（`:238`）保持原样，但**不许**再复用旧对象。
（注意 `:70` 那个默认值现在是 `DemoRuntime` 构造参数的一部分：换成 `var` 之后要么把它从构造参数里
拿掉、要么保留参数但每次 `start()` 都覆盖 —— 别让它变成"外面传进来的对象被偷偷换掉"。）

**B. 把 `start()` 做成幂等**：`runCatching` 拿到的异常如果是
`IllegalStateException` 且这个 server 已经在跑，就当成功处理 —— `serverError` 置 null、
照旧打 `ws://<地址>:8443`，不许报「起不来」。

两种都算过，但**不许**第三种（只把红字文案改好看 / 只在 UI 上重试）。

### 6.4 两种失败要报成两句话（现在共用一句，现场分不开）

`:213-216` 那段注释只考虑了「端口被占」，没考虑「同实例二次 start」。
`_status.serverError`（`:221`）现在把两者都汇成「接入点起不来」，现场只能靠猜。
要分成两句：

- `java.net.BindException`（地址被占：上一次退出没干净、或桌面上还开着另一个实例）→
  「接入点起不来：端口 8443 被占了，先关掉另一个实例」
- `java.lang.IllegalStateException`（同一对象二次 start）→
  「接入点起不来：这个接入点已经起过一次，停止演示再开始也不会重来 —— 需要完全退出 App」
  （修完之后这一句应该永远不出现；它出现了就说明 §6.3 没落地）

判据：`serverError` 的原文（`it.message`）要进日志，UI 上那句要能看出是哪一种。

### 6.5 怎么算过

1. **单测**（`core` 里，不用手机）：`DemoRuntime.start()` → `stop()` → `start()`，
   断言第二次的 `_status.value.serverError == null`，且 `server` 不是第一次那个对象
   （A 方案）或没有抛（B 方案）。这条测试在改之前必须是红的。
2. **现场**（屏不上电、不重刷）：停止演示 → 再开始演示 → 30 秒内屏侧串口
   `>carpet` 回读 `has_state=1` 且 `gap_s` 回到个位数；App 那一格从红字变成 `ws://<地址>:8443`。
   **`contract=v3` 不算数**（§6.1），只认 `has_state=1`。
3. 顺带：那一格不许再让人输设备编号 —— 白名单里已经有 `WS175B-A3F7`，
   文案要指「屏上的编号已经在列表里」，不是「请输入编号」。

## 7. 第二次取样（11-08 13:0x）：App 那一格报的是对的地址，屏却连 TCP 都到不了

现场三条同时成立（用户手机 + 屏侧串口）：

- App「接入点」：`ws://192.168.43.1:8443` —— **不是红字**，§6 那个二次 `start()` 这一轮没复现（这次是干净启动）。
- 手机热点的**已连接设备列表里没有这块屏**。
- 屏自己说它连着：`@url wifi=1 ssid=Mate 60`、`>status` 里
  `"state":"connected","ip":"192.168.43.13","rssi":-31`，每 30 秒拨一次，每次都停在 TCP 层：
  ```
  E websocket_client: esp_transport_connect() failed with -1,
    transport_error=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST, errno=119
  W screen_link: ws 断开/未接通，下一次按退避阶梯再来
  ```

`errno=119` 是 ENETUNREACH（同网段 ARP 不到那台主机），不是 113 ECONNREFUSED（对面在、只是不听这个口）。
**这一类失败 App 改什么都不会好** —— 屏根本没在那张网里，包到不了手机。屏侧要修的两件事记在
`firmware/screen/HANDOFF.md` §14.2（keeper 认这个形状做全量重连；把 errno 印进回读）。

所以这一节不是要 App 修 bug，是要 App 能在现场**自证清白**：把「屏没进热点」和
「屏进了但被白名单挡」和「接入点压根没起来」这三种现在长得一模一样的样子分开。

### 7.1 要 App 加的三条（都不许变成新的按钮或开关）

1. **每条 TCP accept 记一行、每条 hello 记一行**（带 `screen_id` + 时间）。现场只看一个数：
   *有没有任何一条接入尝试到达过这台手机*。上面那次是 0 条 —— 一句"0 条"就把 App 摘干净了。
2. **「接入点」那一行要能分开说三种状态**：没起来（§6.4 那两句）/ 起来了但 0 台接入 /
   有 hello 但这个编号不在白名单。现在这三种在 UI 上都是右上那句「未连接」，现场只能靠猜。
3. 可选（建议，别做成新按钮）：那一行长按菜单里加一条「本机自测」—— App 自己拨一次
   它刚报出来的那个 `ws://…:8443`，把结果打成一行。它证明的是"这个端口在这台手机上确实在应答"，
   正好把"服务端没起"和"屏进不了网"切成两半。

### 7.2 判据

- 屏在热点里、App 在跑：日志至少一条 accept + 一条 hello，屏侧 `>carpet` 回读 `has_state=1`
  （**只认这一条**，`linked=1` / `contract=v3` 都不算数，见 §6.1）。
- 屏不在热点里（把屏的 Wi-Fi 关掉复现，或照 §14.2 那个形状）：日志 **0 条 accept**，
  UI 那句要说「没有一台屏接入过」，不许说「屏被拒绝」。
- 白名单挡掉时：日志有 accept + hello（带那个编号），UI 要说「这台编号不在白名单」，
  不许说「没有屏来连」。

## 8. 「每次要人手点一下屏才连得上」：机制在屏的 nap，不在 App（11-09 实测 + 源码定位）

机主转的那份飞书方案文档（`ocnvqlj4rir4.feishu.cn/wiki/Q5S1wchdAi3TltkuNtkcuxDznPg`）这边打不开
（302 → 飞书扫码登录），所以这一节先把我这一侧的事实和结论落到仓库；
如果那份文档里有结论，请 App 侧把它写进本仓库或 Discussion，别只放飞书。

### 8.1 现场实测：插着 USB、全程没碰屏，屏自己连热点、自己拨 8443

被动抓串口（打开端口会顺带复位一次，所以这一轮是从开机开始的；抓在 `D:/dev/tmp/listen1.txt`）：

```
I (3668) wifi:state: auth -> init (0x200)
W (3676) link.wifi: station disconnected reason=2 rssi=-49
I (3684) link.wifi: retry connect (1/3)
I (41988) link.heartbeat: hb t=41s setup=advertising wifi=up ws=down ...
W (55450) screen_link: ws ... transport_error=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST, errno=119
W (55468) screen_link: ws 断开/未接通，下一次按退避阶梯再来
I (82015) link.heartbeat: hb t=81s setup=advertising wifi=up ws=down ...
@carpet wifi=1 linked=0 contract=v0 gap_s=4294967295 has_state=0 wd=0
@carpet url=ws://192.168.43.1:8443 src=factory
```

- `wifi=up` / `@carpet wifi=1`：**没有任何人手点屏**，屏自己关联上了热点。
- `ws=down` + `errno=119`（ENETUNREACH）：到 `192.168.43.1` 没有路由 —— 手机热点没在应答
  （App 开关没开、或热点把这个站踢了），**不是屏没连上网**。
- `has_state=0`：对面一帧 `state` 都没推过来，和 §7 那一轮同一个形状。
- 开机 3.6 s 那次 `reason=2`（关联被判无效）由屏自己 `retry connect (1/3)` 重试掉了，没让人介入。

所以：**"只操作 App、不碰屏"在今天这套固件上，USB 供电时已经成立。**

### 8.2 「要点一下屏」什么时候才成立：电池 + 屏幕熄灭满 2 分钟

- `muse_input.c:84` —— `#define WIFI_NAP_MS (2 * 60 * 1000)`（注释：低功率满这么久，Wi-Fi 关到结束）。
- `muse_input.c:583` —— nap 条件 = `paused && !muse_voice_notes_waiting() && (s_nap_now || (s_cpu_low && 到 2 分钟))`。
- `muse_input.c:541` —— `pause` 要求 `muse_state_on_battery()`；**USB 上不着**，所以插着电永不 nap。
- nap 一变真，keeper 直接整机离开 Wi-Fi：`muse_glue.c:572-578` `app_wifi_nap()`，
  拨号停在原地；直到屏幕唤醒或 USB 到位才回来（`muse_glue.c:226-228` `op_wifi_nap` → `keeper_kick(KEEP_WIFI)`）。

这是 SDK 里故意的省电行为（注释原话：keeping it associated costs more than the rest of the chip），
**App 侧任何改法都修不了它**。

### 8.3 「不碰屏」的三条路，按代价排

1. **演示机走 USB 供电**：不刷机、不改 App，永不 nap（8.2）。今天就能演示。代价：拖线或带充电宝。
2. **电池下也不碰屏**：nap 时不再整机离开 Wi-Fi，而是保持关联、把 modem sleep 拉满
   （`muse_link.c:203-207` 的 `MUSE_WIFI_REST` → `WIFI_PS_MAX_MODEM` 已经是这个语义）。
   我这边倾向把它做成设置页里一条可开关的项（默认保持现在的省电行为），
   这样换策略不用重刷 —— **代价是熄屏待机电流上升，要不要接受由机主定**。
3. **BLE 喊屏**（不碰屏地叫它重连）：不划算 —— CMD 特征带 `WRITE_ENC | WRITE_AUTHEN`
   （`muse_ble.c:233-251`），要先按键配对；这一轮 `hb ... setup=advertising ble=advertising`
   说明这台还没配对。

### 8.4 两句要先纠正的（别把这两条算到屏上，也别拿它当必须刷机的理由）

- **「必须刷机才能改地址」不成立**：地址走屏自己的设置页 Wi-Fi 那一格（串口 `>url` 可读写、
  NVS `muse/link_url` 覆盖编译期默认值），8.1 的回读 `url=ws://192.168.43.1:8443 src=factory` 就是证据。
  刷机只在 8.3-2 那条省电策略上才是必要的，而且是一次性的，之后换网络仍然只动设置页。
- 任何"不用碰屏"的方案，验收只看屏侧串口两条：`hb ... wifi=up` **且** `@carpet has_state=1`。
  `linked=1` / `contract=v3` 都不算（§6.1）。

## 9. 8.3 那两条已经落地（11-09）：设置页多一个开关，App 侧不用改任何东西

机主定的两件事都做完了：**「两种都要，做成可切换」**（USB 照今天这样永不 nap，电池下靠一个开关保持联网）、
**「继续用手机热点」**（所以屏侧加的是"拨号连续失败就重新入网"的自愈，不是换路由器）。

- 屏上多的一格：设置 → 熄屏（`AUTO-SLEEP`）→ **`熄屏后保持联网`**，就在五个时长选项**上面第一格**，
  不用滚。默认**关**（= Muse 官方省电行为）。开了以后熄屏也不再整机离开 Wi-Fi，
  代价是电池 —— 现场要不要开由机主定，**App 不需要为它加任何按钮或说明**。
- 串口侧：`>stay` 回读 / `>stay on` / `>stay off`（台架用，现场不需要）。
- 8.2 那几条行号在这一轮之后有位移，现号见 `firmware/screen/HANDOFF.md` §15.2（闸门在 `muse_input.c:591`）。

### 9.1 给 App 排查用的两个新字段（只是多印几个字，不改行为）

`>url` 第二行和 `>carpet` 第一行现在长这样：

```
@url wifi=1 ssid=Mate 60 fails=0 err=ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST errno=119 http=0
@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0 fails=0
```

- `fails=` = **连续拨号没一次的次数**，拨通即清零。`fails` 一路涨 = 屏在反复撞同一个到不了的地址。
- `err=` = 最后一次 transport 错误的原文，**现场就靠这三个数分锅**：
  `errno=119`（ENETUNREACH）→ 屏根本 ARP 不到手机，问题在网（热点把屏踢了 / 屏连的是另一张网），
  **不是** App 没监听；`errno=113`（ECONNREFUSED）或 `http≠0` → 手机在、但那个口没人听，是 App 侧。
- 屏侧现在会在 `fails` 到 4 时自己做一次全量重连（断开 → 重扫 → 重连存过的那张网），
  所以"屏说自己连着、手机却看不见它"这一类不用人手点屏了。

### 9.2 验收（11-09 已在台上称到）

```
@carpet wifi=1 linked=1 contract=v3 gap_s=5 has_state=1 wd=0 fails=0
@carpet wifi=1 linked=1 contract=v3 gap_s=3 has_state=1 wd=0 fails=0
@carpet wifi=1 linked=1 contract=v3 gap_s=0 has_state=1 wd=0 fails=0
```

`has_state=1` 且 `gap_s` 稳在 0-5 = App 的 1 Hz `state` 真的在到，§4 / §6.5 那条判据到此满足。
同一轮还拍到了屏上那一行 **`已接入`**（`shot_carpet_live.png`，只在链路 linked 时才画），
而且是复位之后没碰屏自己回到这个状态的。
**电池那条只能现场称**：电池供电 + 开关打开 + 熄屏 3 分钟，**再插回 USB** 读一次 `>stay`，
期望 `on=1 nap=0 naps=0 drops=0`。`naps` / `drops` 是屏在 RAM 里记的累计数，拔掉 USB 期间它不断电、
数还在，所以这一趟不需要任何人在旁边盯串口（屏侧 HANDOFF §15.7）。
**这一条 11-09 晚跑过一次，什么都没称到**：那一趟里板子重启了，`naps`/`drops` 被清零，读到的 0 是
"开机以来的 0"（屏侧 HANDOFF §15.9）。当时我据此写的"这块屏没有电池"是错的，后面用 10 秒短拔
+ flash 里的电池窗口读数对照着称回来了：**3.7V 电池是好的、确实在供电**（§15.10）。
重跑那一趟称到的是别的东西：**电池 + 让它自己熄屏 = 整机会断一次电，两次都这样**（§15.11）——
`uptime` 归零、`naps`/`drops` 被清零、而且 `@power.saved` 里那条电池窗口记录还是上一轮的旧数
（窗口没合上，说明板子在窗口里就没了）。所以 `>stay` 那一台仪器在这一档上失效，
"电池 + 熄屏 3 分钟"这一条当时改排在"为什么会整机断电"后面。
为此 11-09 深夜刷了一版带**断电验尸**的固件：每个可疑转折点（熄屏、进低功耗、USB 通断、nap、POWER 键、
真要关机）当场写一条进 NVS，环 24 条，插回 USB 用 `>diag` 翻 —— 断电也留得住（§15.12）。
**这一条被下一趟推翻了（§15.13）**：刷上断电验尸那一版后原样重跑，环里从 t=1026 到 t=1500
每 30 秒一条心跳**连续没断**，7.9 分钟、电池供电、熄屏、light sleep，VBAT 只掉 18 mV。
所以它不是"放着放着在电池上死了"；断电发生在**插回 USB 之后**（第一次开机 `rst=1 power on`），
而且那次回来**第一次开机还 panic 了一回**（`rst=4`，起来约 1 秒就崩、自己重启）。
两问都没称死，共同的下一步是同一趟：**掐表 3 分钟 + COM8 循环重开的实时抓取**，
一次同时抓 backtrace、断的那一刀、和环里有没有 `nap+`（`nap+` 一条都没有 = 那 3 分钟没进 nap；
"热点列表里屏还在"那一半只能在插回 USB 后用 `has_state=1` 补称）。
现场可用的一条没变：**充电宝 / USB 一直在就没有这一档风险**，所有插着 USB 的熄屏、nap、
拨号、断网恢复都跑过，没出现过重启。
同一轮称到的是现场更常用的一条：**拔插电源之后屏自己回得来，全程不用碰屏**，
`@carpet wifi=1 linked=1 contract=v3 gap_s=1 has_state=1 wd=0 fails=0`，
代价是大约 **3 分钟**（要先攒够 12 次拨号失败才走升级重连那条路，`errno=119` = 路由没了）。
**别用电脑去 ping 屏**——这台华为热点上电脑→屏本来就不通，屏明确在线（`linked=1 has_state=1`）的时候
照样 ping 不到，用它判断会把"仪器不通"读成"屏掉线"（屏侧 HANDOFF §15.6 记了这次误判）。


## 10. 语音叫醒熄屏的圆屏 + 手机熄屏还在提供服务（11-10 提给 App 侧）

owner 那两句话拆成两个问题：**熄屏状态下能不能用语音把圆屏叫醒**、**手机自己熄屏时 App 还在不在服务**。
两条都不靠"再加一个按钮"解决：第一条靠一条下行帧（屏侧已经做完并上板称到），第二条靠一条从没真正
拿过的 WakeLock 和"守护在跑就别停服务"。

### 10.1 屏侧已经做完的那一半（不需要 App 配合）

- 屏收到 `command{cmd:"wake"}` 走的是 **POWER 键自己那条路**（`muse_input.c` 的 `set_asleep(false, …)`），
  不是更轻的一条：熄屏清掉、面板重新起来、Wi-Fi 立刻重连，不等拨号退避那几档。
  实现落在 `carpet_link.c` 的命令队列任务上，不在 WebSocket 回调里办（回调里不能干重活）。
- **上板称到**（COM8，`cmd_task` 栈 6144 字那一版，连着三趟 `>nap` → 叫醒，原文）：

  ```
  I (111318) muse_input: waking (ws say)
  I (111322) carpet.diag: e3 t=110 lit
  I (111325) carpet: woke by link, carpet_cmd 还剩 3732 字节
  I (111439) muse_input: Wi-Fi waking
  @stay on=1 nap=0 naps=1 drops=1 wifi=1
  ```

  三趟的 `还剩 3732 字节` 一模一样，全程没有溢出、没有复位。
- **屏上不做常驻唤醒词**，这一条是硬件定的，不是省事的取舍：熄屏进 rest 的时候麦克风、codec、功放
  是**断了电**的（`muse_voice.c` 里 `rest` → `set_resting(rest)` → `muse_audio_power(!rest)`），
  而这套 SDK 里根本没有唤醒词引擎（esp-sr / WakeNet / MultiNet 一个都不在）。要做"屏一直听着"，
  等于推倒现在这一整套功耗模型重画。**唤醒词放手机侧**，屏只负责被叫醒。
- **这一条的硬前提：那条 WebSocket 还得活着。** 屏在电池上熄屏满 2 分钟会 `nap`
  （`muse_input.c` 的 `WIFI_NAP_MS`），Wi-Fi 一关 socket 就没了，这一帧送到空气里，而且
  **不报错** —— 只有按 POWER 能叫醒它。所以 §9 那条「熄屏后保持联网」不开着，
  语音叫醒这一整条就不成立；别把它实现成"发出去就算成功"。

### 10.2 要 App 改的四条（都不许变成新的按钮或开关）

1. **发那一帧。** `wake` 已经加进 `core/protocol/ScreenLink.kt:112` 的 `CommandType`
   （屏侧这轮连着 schema 一起同步的，`SchemaDriftTest` 那条 "CommandType 和 schema 的 cmd 对不上"
   是硬比集合相等，所以这一格不加，`:core:test` 直接红）。
   **App 侧要写的只是发送点**：唤醒词命中那一刻，或者用户点「叫醒圆屏」那一下。
   `DemoRuntime.handleScreenCommand` 那条 `when` 有 `else -> false`，加一个取值不会编不过，
   也不用去那儿给 `wake` 加分支 —— 它永远不该由屏发上来。
   `ScreenLinkServer.deliverable`（162 行）也不用为它加档：老固件收到不认识的 `cmd` 只是丢这一帧，
   那正是想要的降级方式。
   **答句的时候不要额外发** —— `dialog{dir:"down"}` 在屏侧本来就会点亮屏幕。
   契约里这条**不回 ack、不重试、不排队**（理由见 `protocol/screen-link.md` 的 v3 那节）：
   屏真醒了下一步会自己把 `state` / `page_report` 推过来，那才是证据。
   所以判定要反过来写：**发出去 3 秒内这条连接上没有任何动静，就当没叫醒，并把这句话报给用户**，
   别静默 —— 静默的失败在现场看起来就是"屏坏了"。
2. **守护在跑的那段时间持一条 partial WakeLock。**
   `apps/android/src/main/AndroidManifest.xml:10` 声明了 `WAKE_LOCK`，但**全仓没有任何地方 acquire 它**
   （grep `WakeLock` 只命中 manifest 和 build 目录里的合并产物）。
   `DemoGuardService.kt:91` 现在已经在自己承认这件事的后果：「守护没能常驻…锁屏后会被系统冻住」。
   要的是 `PARTIAL_WAKE_LOCK`（只保 CPU，不点屏幕），拿在 `DemoGuardService` 起来那一刻、
   释在 `onDestroy`（155 行那条），跟着守护的生命周期走。
   不拿的后果是具体的一条链：手机锁屏 → CPU 进 doze → 1 Hz 的 `state` 发不出去 →
   屏侧 3 s 变灰、10 s 判超时重连 → 而 ws 服务器就在手机上，doze 期间连 `accept` 都起不来。
3. **不要一退出就把服务停掉。** `MainActivity.kt:50` 是 `if (isFinishing) state.setGuard(false)`，
   而 `setGuard(false)`（`AppRuntime.kt:576`）连着 `DemoGuardService.stop` 和 `runtime.stop()`。
   表现：用户划掉卡片或返回键退干净，8443 那个监听就没了，屏从此连不上，直到有人再打开 App。
   要的状态是「**守护开着 = 服务在**」，Activity 的生死不该动那个监听。
4. **`acceptScreens` 跟着守护走**（11-10 拍板：守护开着就默认接受）。
   它默认 false（`AppRuntime.kt:98`），关着的时候白名单外一律
   `conn.close(CLOSE_POLICY, "demo lock")`（`ScreenLinkServer.kt:91`）。
   守护一起来就把它置 true，别让现场还要有人去设置里找那个开关。
   这一条和第 3 条是同一扇门的两半：第 3 条保证门不会自己关上，这一条保证门开着的时候屏进得来。

### 10.3 华为那一侧的手动步骤（代码改不了，只能写进现场清单）

`apps/android` 里没有 `TetheringManager`，热点不是 App 开的，所以我们既不能让它开也不能阻止它关。
现场要在手机上手动确认四项：**自启动**、**后台活动**、**电池优化豁免**、**热点不因无人连接而自动关闭**。
前两项和第三项对应 10.2 第 2 条（WakeLock 在国产 ROM 上要配合电池豁免才真拿得住），
第四项对应 10.1 那条硬前提：热点关了，屏那条 ws 一样没了。

**这条是推断，没称过**：空闲自动关热点和屏的 nap 会互相放大 —— 屏没人碰就熄、熄满 2 分钟断 Wi-Fi，
热点那边看到的是"长时间没有设备连着"，于是更想关。要证或证伪都在同一趟里（下面 10.4 那条顺序）。

### 10.4 还没称过的，和现场那一次验收

**手机熄屏以后 App 还在不在服务，从来没测过**：`apps/android/build.gradle.kts:95` 那行注释说得很明白，
这台机器上没有手机也没有 emulator。10.2 第 2、3 两条是按源码读出来的推断，不是称到的结论。

一次跑完的验收（按这个顺序，别跳）：

1. 屏：设置页里那一行「**熄屏后保持联网**」打开（英文原文 `Stay online when asleep`；
   等价串口 `>stay on`，同一个 NVS key）。
2. App：开守护。
3. 什么都不碰，等屏自己熄（默认 120 秒）。
4. **锁屏手机，放 3 分钟。**
5. 说一句唤醒词（或点「叫醒圆屏」）→ 屏要亮。
6. App 首页那一格要出现 `has_state=1`；屏侧对应的读数是串口 `>carpet` 那行的
   `linked=1 … has_state=1` 和 `>stay` 那行的 `nap=0`。

第 5、6 步哪一步不动，就把那一步的原始读数抄回来，别只说"连不上"：
屏亮但 `has_state=0` = 手机那侧没在服务；屏不亮且 `>stay` 显示 `nap=1` = 第 1 步那条没生效或者热点关了。
第 5 步那一按在屏侧应看到的原文是 `muse_input: waking (ws wake)` ——
**屏上没有常驻唤醒词**（熄屏后麦克风是断电的，理由见 `firmware/screen/HANDOFF.md` §15.14），
所以"说一句唤醒词"这一条不成立，能用的只有 App 那一帧 `command{cmd:"wake"}` 或者手按 POWER 键。

## 11. 2026-11-10 深夜：「App 显示无法连接圆屏」这一趟屏侧称到了什么

给 App 侧那一轮排查的结论，按重要程度排：**链路本身是通的，不用再查手机端的服务端。**
同一条出厂地址、同一块板子、手机热点开着的时候，串口原文：

```
I (3046) carpet: dialing ws://192.168.43.1:8443 (built-in)
I (3262) screen_link: ws 接入 ws://192.168.43.1:8443
I (3265) screen_link: hello 已发：WS175B-A3F7  电量未知
I (3266) carpet: linked, contract v3
```

`hello` 到了、契约谈到 v3、`page_report` 发出去了 —— 白名单、端口、`acceptScreens`、
契约谈判这几条如果哪条挡着，这段日志根本不会出现。所以 App 侧那句「无法连接圆屏」
在屏这边只有两种可能，而这一趟称到的是第三种谁都没想到的：**屏压根没拨号**。

判据只有两行串口，现场和 App 侧对话时抄这两行就够了：

| 读数 | 意思 |
| --- | --- |
| `>url` 的 `dialed=0` | 屏这一整次开机从没起过拨号 —— 问题在屏，且**已经在这一轮修**（下面第 1 条） |
| `>url` 的 `dialed=1 fails>0 err=…` | 屏在拨，拨不上：`errno=119` 是到不了手机（网/热点的事），`errno=113` 是端口没人听（App 的事），`http≠0` 才是真有一个服务端拒了 |

这一轮修的三条（都在固件，App 侧不需要跟着改任何东西）：

1. **拨号失败之后会重试了。** 之前 `gate_task` 是"等 Wi-Fi → 开链路 → 删掉自己"，
   开机那几十秒内部 RAM 最紧，一旦 `screen_link_start: ESP_ERR_NO_MEM` 就印一条 `E` 收工，
   此后 `dialed=0 fails=0` 可以挂满整个演示 —— 表现正是 App 那句「无法连接圆屏」。
   现在按 5 / 15 / 30 / 60 秒封顶重试。
2. **同一种死法的另一半：ws 句柄建不起来时不再原地重试**，而是整个换掉句柄再来
   （组件在 `Error create websocket task` 之后 state 停在 INIT，此后每次 `start()` 只回
   `The client has started`，永远拨不出去）。
3. **链路自身的三条任务栈按量减下来了**（`link_task` 原来写 8192，而那个数是**字**= 32 KB；
   现在 4096 字，实测还剩 2004 字）。这一条是给 ① 那种"内存不够"少发生的，
   顺带把开机 4 秒那一次 `i2s_alloc_dma_desc` 失败 → `i2s_rx_channel_start` 空指针 panic
   的概率压下去 —— panic 本身是 Espressif 组件在内存要不到时不检查失败造成的，我们不动它。

**现场仍要做的那一件事**：这一趟里屏有两次是 `no saved wifi network nearby`（扫到 3~6 个 AP，
没有一个叫「Mate 60」）—— 华为热点在没人连之后会自己关。10.3 那四项手动确认里，
**「热点不因无人连接而自动关闭」这一项现在最关键**：它一关，`dialed=1` 也会变成拨不上，
而 App 界面只会显示「无法连接圆屏」，看不出是热点那边的手。
顺序还是 **先开热点 → 再按一下屏**（按一下会让屏立刻重新全量扫描，实测；否则最长要等 10 分钟）。

## 12. 2026-11-10 提给 App 侧：「对着圆屏说话」这一档要 App 做的（契约 v4）

背景一句话：现在能对话的是手机（按住说、手机答、手机念），屏只能收文字。要把屏变成耳朵，
PCM 得从屏推到手机，手机转写成字再走已有的问答面。**形状的完整定义在 `protocol/screen-link.md`
「上行音频通道（契约 v4）」那一节，这里只列 App 侧要做的事和验收**；本节所有改动**都不许变成新的按钮或开关**，
设置页已有的齿轮面板加字段除外。

### 12.1 第一条不是音频，是「让屏知道对面是几版」——顺序不许倒

现在装在手机上的 v3 App，解不开任何陌生的入站 `cmd`：`CommandType` 是严格枚举，取不到值就抛，
`ScreenLinkServer.onMessage` 的失败分支是 `conn.close(CLOSE_POLICY, "bad contract")`。
也就是说**一块会发上行音频的固件，会把这台没更新的手机当场踢下线**，观感就是第 11 节那个「无法连接圆屏」。
而屏这边算不出对面是几版 —— 协商号是服务端算的，`ack` 帧里没有版本字段可以回读。

所以要的第 1 条：**hello 的回执 `ack` 里带上服务端实际支持的契约号**（`AckPayload` 加一个可选整数 `protocols`）。
老固件读 ack 用的是 cJSON，多出来的键看不见，不掉线；屏拿到之后只用它给"上行"把闸，
**不要拿它覆盖现有的 `s_negotiated`**（那个管的是收和画，对着老手机本来就安全）。没这个键 = 不许发任何 `cmd`。

**验收（这一条单独算过）**：装上带这一字段的 App，屏串口那边要能看到"对面申报到 vX、上行允许/禁止"
这类一行读数（**这一行读数屏侧现在还没有，随这一轮固件一起加**，App 侧只需把字段发出去）；
对着**没更新**的旧 App，屏必须一条上行 `cmd` 都不发、且链路保持连着（`hello 已发` 之后不掉）。

### 12.2 收上行音频并转写

- `CommandType` 加 `mic_chunk`；它骑在 `command` 上，`args` 和 `audio_chunk` **同一组键**
  （`turn_id,seq,total,rate,b64`），所以分块/拼段那套代码应当和下行的 `AudioChunks` 复用同一条规则：
  `seq` 不连续 → 整段丢；超上限 → 整段丢；`turn_id` 变 → 清缓冲重来；断线 → 立刻 abort。
  **屏发的块是 2560 字节 PCM = 80 ms**，16k/单声道/s16le，和下行一模一样。
- **`turn_id` 是屏在按下那一刻生成的，手机不许另起一个。** 转写出来的那句要回
  `dialog{dir:"up", turn_id:屏给的那个, text:那句}`，屏靠它显示"我听到的是这句"、也靠它去重。
- **转写客户端要新写，`OpenAiCompatProvider` 走不了这条**：它把 `/chat/completions` 硬拼在 baseUrl 后面、
  只发 JSON body（`core/.../ai/Providers.kt:99-104`），multipart 的 `/audio/transcriptions` 在它里面没有形状。
  **HTTPS 是硬要求**——`network_security_config.xml` 的 base-config 全局禁明文，域名白名单那几条全是注释状态。
- 转写出来的文字喂现有问答面（`DialogBridge.ask(question, via = "screen", turnId)`），答句仍走已有下行那两帧。
- **吞吐是全新的**：约 12.5 帧/秒 ≈ 43 KB/s 持续入站 JSON，这台手机作为服务端从没被压过持续入站。
  第一次真跑要看的是"手机上收到的 `seq` 连续"，不是"屏上显示已发送"。

### 12.3 顺带必须补的那一条：`DialogBridge.onAnswer` 现在根本没接

`AppRuntime.kt:375-384` 构造 `DialogBridge` 时**没传 `onAnswer`**，而 `onAnswer` 正是"拿答句去合成语音"的钩子。
这一条不只是 v4 需要 —— **它是 v3 那条 `audio_chunk` 至今在屏上没声音的同一个缺口**：
文字答句早就上屏了，但没有任何代码去合成、去切块、去发。接上它，屏的喇叭才有东西可放。

未定，需要机子上称，别当成已具备：Mate 60 没有 GMS，`android.speech.tts` 的引擎在不在得实测；
云端 `/audio/speech` 有没有对应端点取决于齿轮面板里 `ai_url` 是哪家。**另外有一条已经和代码对不上的观测**：
owner 反馈"手机确实出声念了答案"，但仓库 main（92c6031）里没有任何 Android 音频 API、`onAnswer` 没接。
在查清那句话是谁念的之前，别在手机上再加第二个发声源 —— 展台上两个声音同时念同一句是事故。

### 12.4 两个要 owner 先答的问题（答不了这条就没法定实现）

1. ~~现场那台 Mate 60 走不走蜂窝数据？~~ **已答（2026-11-10 owner）：走，蜂窝是通的。**
   云端 ASR 这条路因此成立，**但仍然是 HTTPS 硬要求**——出不出网和明不明白文是两件事，
   `network_security_config.xml` 那条全局禁明文跟蜂窝无关
2. ~~齿轮面板里 `ai_url` 填的是哪家端点？~~ **已答（2026-11-10 owner 给了 `https://hanbao.party/v1`）：
   这家只有对话面，没有任何语音入口。** 当场称的原文（都在 `protocol/screen-link.md` v4 那一节）：
   `GET /v1/models` → 200，三档：`deepseek-v4-pro` / `deepseek-v4.1-flash-1017` / `glm-5.3-flash`；
   `POST /v1/audio/transcriptions`、`/v1/audio/speech`、`/v1/asr`、`/v1/stt`、`/v1/tts`、`/v1/audio`
   → **全部 `404 page not found`，和随手编一个路径的回话一字不差**；对照组 `/v1/embeddings`、`/v1/messages`
   回 `400`（路由在、body 不对）—— 所以那六个 404 是"真没有"，不是"我用错了方法"。
   **结论：契约 v4 的 ASR 落点不在这一家**，要么再加一家只做转写的供应商（要第二个账号/key），
   要么用手机上本地识别（不花钱、不出网，但 Mate 60 上有没有可用的中文离线识别**没称过**，先称它）。
   **key 一律不进仓库、不进文档**，只存在手机那一格里；owner 贴过一次，之后自己决定要不要换。

第 2 条的答案是"这家没有语音"，所以 12.2 那一节**仍然不要动工**，它现在卡的是"ASR 换哪家/换不换成本地识别"
这一个决策，不是技术问题。12.1 不受它影响，可以先到手就做——它同时也是防止新版本把老版本踢下线的那道闸。

### 12.5 与 v4 无关、但现在就该修的一条：对话面已经压着读超时

端点既然称到了，顺手称了它的用时。同一句中文问句（"过顶窗口是什么"），三次实测：

| 模型 | 用时 | 备注 |
| --- | --- | --- |
| `deepseek-v4.1-flash-1017` | 3.95 s | `max_tokens=120` 时**英文思考草稿被当成正文返回**（`reasoning_tokens=120`，正文全是 "We need answer in Chinese…"） |
| `glm-5.3-flash` | 4.13 s | 正常；另一次给 `max_tokens=220` 跑到 **10.14 s** |
| `deepseek-v4-pro` | 7.74 s | 答案最干净 |

而 `OpenAiCompatProvider` 的读超时写死 `Duration.ofSeconds(12)`（`core/.../ai/Providers.kt:71`），
`HttpURLConnection`、非流式。**也就是说一句稍长的答句就能把手机自己等到超时**，表现是"问了一句没回"。
要改的两件事，都不需要等新协议：

1. 读超时提到 30 s 左右，或者改流式（流式还能把首字时间从 4~10 s 压到观众受得了的那一档）
2. 推理型那两档要给 `max_tokens` 留够（120 会把思考算完、正文一个字不写），
   或者演示就固定用 `deepseek-v4-pro`；**并且正文里的思考草稿要拦掉**——屏上出现一段英文
   "We need answer in Chinese…" 是观众一眼就看穿的破绽，比不说话更糟
