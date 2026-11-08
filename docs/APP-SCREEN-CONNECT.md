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
