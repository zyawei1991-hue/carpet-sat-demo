# GT1005 BLE 直连：从自研 Lite APP 反编译得到的通道判据

**状态**：判据已称到（2026-11-11）· 来源：`GEESATCOM通信助手-Lite_3.0.0_R.apk`（我司自研，负责人在群里提供）

负责人已用这个 APP 通过 **BLE** 连上了 GT1005 真机。这份文档记录从 APK 里解出来的
通道事实，以及我们 App 接同一根通道的落地方案。

---

## 1. 反编译得到的事实（每条都是 dex/资源里的实据，不是猜）

对 APK 做 unzip + dex 字符串扫描（jadx 全量反编译补 UI 细节，见 §5）：

### 1.1 BLE 通道：标准 GATT 双特征，无配对码

dex 里只出现四个 GATT UUID：

| UUID | 角色 |
|---|---|
| `0000fff0-0000-1000-8000-00805f9b34fb` | Service |
| `0000fff2-…` | **写特征（App → 模组）**：`BleManager` 里存为发送目标，`setValue+writeCharacteristic` 都落在它上面 |
| `0000fff1-…` | **Notify 特征（模组 → App）**：对其 2902 描述符写 `ENABLE_NOTIFICATION_VALUE`，`onCharacteristicChanged` 从它收 |
| `00002902-…` | CCCD（给 fff1 开 notify 用） |

没有自定义配对服务、没有配对码逻辑。签名侧权限是老式
`BLUETOOTH`/`BLUETOOTH_ADMIN` + 新式 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`/`BLUETOOTH_ADVERTISE`。

### 1.2 协议层：就是我们手里那份 GSAT，不是新协议

dex 里有整套 **`DispatchManager.java` + `Encipher*`/`Entity*`/`Parse*`**：

- Encipher：ACQ BAQ BTK BTS CEQ EHS EPQ EPS GEQ GES GMO INQ INS MDQ MDS MEQ
  PLQ PLS RET RMO RMQ RMS SIM SOQ SOS SPU TMO TMQ TMS UPR VEQ —— 语句前缀串
  `CCACQ…CCVEQ` 在 dex 里逐条出现，与《通用数据接口规范 V1.0》的语句表一致；
- Parse/Entity：ICI DII MCI PDI CEIS DAT GGA LTEMSI SPI VEX 等 —— 收信侧解析；
- `StarManager` / `StarManagerFactory` / `StarManagerImp` / `DispatchManager` ——
  就是厂商星历 SDK 那组类名。

**结论：自研 APP 没有另造协议，它就是「自己的 BLE 收发 + 厂商 SDK 的语义层」。**

### 1.3 传输层：App 自己做 GATT，SDK 不做 IO

dex 里没有 `ModuleOperation` 的任何 BLE 实现；传输相关只有自家的
`BleManager.java` / `BleFragment` / `BleAdapter` / `BleDeviceInfo`。这印证了
DECISIONS D7 的判断：**SDK 自己不做 IO，UART 和 BLE 是平级的两条正路** ——
自研 APP 选了 BLE 这条，我们自己组包喂字节。

### 1.4 设备选择

`D_SELECTOR_KEY` 后面的机型串：`GT1001 GT1002 GT1003 GT1005` —— 一个选择器
按机型分流。没扫到按名称前缀过滤的常量（设备名过滤逻辑待 jadx 确认，见 §5）。

### 1.5 与本仓库现有的对应关系（落点已经在）

- 我们手里有两份厂商资料包（`vendor/gt1005/基础模组SDK.zip`、
  `星历及基础通信SDK资料包.zip`）和已接进来的 `VendorSdkModem`（aar）；
- `ModemBytePipe`（`core/.../modem/ModemBytePipe.kt`）就是为"SDK 不做 IO、
  谁来回话由我们接"预留的口子，注释里写明的正是这个形状；
- 所以我们要补的**只有一根 `BleBytePipe implements ModemBytePipe`**：
  按 fff0 服务 UUID 扫描 → `connectGatt(TRANSPORT_LE, PHY_LE_CODED)` →
  `requestMtu(500)` → 发现服务 fff0 → 对 fff1 开 notify、`write()` 发到 fff2、
  `onBytes` 收自 fff1 → 喂给现有 `SentenceModem`/`VendorSdkModem` 那条链。
  业务层（链路状态机、SOS 重试、心跳、展示端）一行不改。

---

## 2. jadx 反编译已回答的连接细节（2026-11-11 全部确认）

来源：反编译 `l3/a.java`（compiled from `BleManager.java`）、`l3/s.java`（DispatchManager）、`u2/b.java`（分帧器）。

1. **扫描过滤**：按**广播里的 service UUID fff0** 过滤 —— `ScanFilter.setServiceUuid(0000fff0-…)`；
   `ScanSettings`：`scanMode(2)=LOW_LATENCY`、`callbackType(1)=FIRST_MATCH`、`matchMode(2)=AGGRESSIVE`。
   不按设备名过滤。
2. **连接参数**：API≥26 走 `connectGatt(ctx, false, cb, TRANSPORT_LE=2, PHY_LE_CODED=3)`；
   连上后先 `requestConnectionPriority(1)=BALANCED`，再 `requestMtu(500)`；
   `onMtuChanged` 记下分片大小 = **MTU-3**（默认 20），然后才 `discoverServices()`。
3. **写与收**：服务发现后取 fff1 开 notify（写 2902 CCCD），fff2 作发送目标；
   **单写在途** —— 下一分片等 `onCharacteristicWrite`/`onDescriptorWrite` 回调再发
   （发送缓冲 `ByteContainer` 每次 `k(chunk)` 取走前 min(count, MTU-3) 字节）。
4. **收帧路径（确凿）**：`onCharacteristicChanged` → `DispatchManager.T(bytes)` →
   `u2.b.g(bytes)` → 逐字节扫描：`$`(0x24) 重置缓冲、`\n`(0x0A) 且前一字节为 `\r`(0x0D)
   即成帧 → 校验后按偏移 3..6 的三字母操作码分发（"LTE" 再取 7..10 二级码）。
   **即 NMEA 风格 `$CC…*hh<CR><LF>` 语句跨 BLE 包重组** —— 与我们 `SentenceFramer`
   的分帧逻辑同构，字节流喂进去即可。

结论：自研 APP 就是「标准 GATT + 厂商 SDK 的字节流入口」，我们 `BleBytePipe`
照此实现后，`SentenceFramer` 之后整条链零改动。

---

## 3. UI 参考（自研 APP 的功能面）

从 dex 的 view/fragment 类名可以看清功能面（截图 UI 细节待 jadx + 负责人的图对照）：

| 自研 APP 有 | 类名实据 | 我们的对应 |
|---|---|---|
| BLE 状态页 + 雷达扫描动画 | `BleStateView` / `RadarView` | 开发者模式里加 BLE 连接状态行 |
| 卫星信号视图 | `SatelliteSignalView` | 已有：链路卡里的过境倒计时 |
| 过境预测页 | `StarForecastView` / `StarForecastFragment` / `satellite.html`(WebView + satellite.js 算 TLE) | 已有：`StarManagerEphemeris` 本地算 |
| SOS 页 | `SosView` / `SosFragment` / `SendSosFragment` / `SosViewModel` | 已有：SOS 卡 + 卫星双发 |
| 消息/预设短语 | `MsgFragment` / `MsgPresetsFragment` / `FragmentMsgPresetsBinding` | 我们有对话链；预设短语是卫星文本消息的形态，**没做** |
| 通讯录（卫星短消息收件人） | `ParcelableContact` / `ContactsEntity`（READ_CONTACTS 权限） | 没做，演示期不需要 |
| 电池/文件下载/地面站 | `BatteryView` / `DownloadView` / `GroundStationView` | 电量有示意；后两者演示期不需要 |
| 前台服务保活 | `ForegroundService` | 我们有 |

**给我们的两个 UI 提示**：
1. `MsgPresetsFragment`（预设短语）是卫星文本消息在自研 APP 里的主要消费形态 ——
   文本消息不是自由输入，是几条预设。如果演示要演"卫星发文本"，照这个形态做比
   自由输入快得多。
2. 过境预测它用 WebView + satellite.js 算 TLE，我们是 `StarManagerEphemeris`
   本地算 —— 已有，不用学。

---

## 4. 落地顺序

1. **`BleBytePipe`**（`apps/android/.../BleBytePipe.kt`）：扫描按广播 service UUID fff0
   过滤 → `connectGatt(TRANSPORT_LE, PHY_LE_CODED)` → `requestMtu(500)` →
   发现服务 fff0 → 对 fff1 开 notify（写 2902 描述符）→
   `write()` 走 fff2（单写在途，MTU-3 分片）、`onBytes` 收 fff1。权限走
   `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`（Manifest 已有）。
2. **接进 `VendorSdkModem`**：构造时传 `BleBytePipe` 而不是 `UartBytePipe`，
   开发者模式加一个"传输通道"切换（UART/模拟/BLE）。
3. **真机验证**：负责人手机装我们的 APK，GT1005 上电，看
   `SentenceModemTest` 那套语句组包在真管道上通不通（DII→ICI→TPI→PDI 会话）。
4. UI 参考 `MsgPresetsFragment` 的形态补一个"卫星文本消息"演示入口（可选，后置）。

---

## 5. jadx 补齐记录

- 2026-11-11：APK 已 unzip，dex 字符串扫描完成（§1 的全部事实来源）。
- 2026-11-11：jadx 全量反编译完成（`/tmp/geesat/out/sources/`），§2 三问全部确认；
  关键实据：`l3/a.java`（BleManager：扫描/连接/MTU/读写方向）、`l3/s.java`
  （DispatchManager：`T(byte[])` 入口 + 状态机 NO_ENABLED/AWAIT/LINKING/LINKED）、
  `u2/b.java`（$…\r\n 分帧 + 操作码分发）、`c3/b.java`（BleDeviceInfo）、
  `t3/e.java`（ByteContainer 发送缓冲）。
