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
"电池 + 熄屏 3 分钟"这一条仍然没称到，但它现在排在"为什么会整机断电"后面。
11-09 深夜刷了一版带**断电验尸**的固件：每个可疑转折点（熄屏、进低功耗、USB 通断、nap、POWER 键、
真要关机）当场写一条进 NVS，环 24 条，插回 USB 用 `>diag` 翻 —— 断电也留得住（§15.12）。
下一趟那一读的判据：环里最后一条停在 `dark` / `low+` / 几条 `t=low` 的哪一个，
加下一次开机那条 `rst=`（`brownout` 还是 `power on`）和 `kl=`（PMU 开机时还锁着的按键事件）。
现场可用的一条没变：**充电宝 / USB 一直在就没有这一档风险**，所有插着 USB 的熄屏、nap、
拨号、断网恢复都跑过，没出现过重启。
同一轮称到的是现场更常用的一条：**拔插电源之后屏自己回得来，全程不用碰屏**，
`@carpet wifi=1 linked=1 contract=v3 gap_s=1 has_state=1 wd=0 fails=0`，
代价是大约 **3 分钟**（要先攒够 12 次拨号失败才走升级重连那条路，`errno=119` = 路由没了）。
**别用电脑去 ping 屏**——这台华为热点上电脑→屏本来就不通，屏明确在线（`linked=1 has_state=1`）的时候
照样 ping 不到，用它判断会把"仪器不通"读成"屏掉线"（屏侧 HANDOFF §15.6 记了这次误判）。

