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
