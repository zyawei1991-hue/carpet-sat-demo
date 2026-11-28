# 数字钥匙合作方结合评估（2026-11-25）

> 来源：owner 在飞书清单文档（VnyZdUbJDoHF9RxAITCcitkEnjT）「车况卡手动录入增强」一节贴出的
> 产品文档链接 `https://open.gpswv.com/doc/web/#/3/14`（「风控在线」开放平台，服务域名
> open.figps.com，设备侧 API），及 11-25 多维表记录 21 评论给的正式凭据。
> 凭据口径：凭据曾按公司主体记录，但 11-28 在线自检证明 `appid` 必须是平台**登录账户名**，正式账户名仍待合作方提供；**KEY 只存仓库外本机凭据文件，
> 不落库、不在任何文档/评论/日志里复述**。原评估记录中的本机路径已不作为运行时路径；
> 当前实现只把凭据存在应用私有且不进系统备份的目录（见 §4）。该 KEY 出现在过协作文档，已建议合作方轮换、今后走私聊交付。
> 本评估同时并入「寻车 GNSS + 车位桩」（记录 10）——自动记车位、寻车正是数字钥匙的基础能力。

## 1. 能换来什么（对照现有台账）

| 我们的需求 | 平台接口 | 结论 |
|---|---|---|
| 里程手填增强（车况卡） | `/device/miles`、`/device/miles/batch` | **区间里程而非总里程**：我们存基准值+每日累加，手填降级为校准入口；当日里程不带入批量、跨度≤1月、只能取9个月内 |
| 油量 | `/device/status.oilVolume`（毫升） | 仅带油感线的特定型号有——要对方给型号清单，不能当默认能力 |
| 胎压 | 无 | 接口表和报警代码表都没有；维持手填或提 TPMS 配件新需求 |
| 车况白捡项 | extVoltage/chargePercentage/ACC/速度/停留/中文地址 | 电瓶状态、静止/行驶判定可直接用 |
| 新车录入免手填 | `/dms/orders/vin`、`/dms/xstar/vehicle/info` | 车架号/车牌/车型/车主+安装照片 → 改成输手机号带出、用户确认 |
| 寻车 | `/vehicle/status/v2`、`/api/vehicle/stay/v2` | 经纬度+地址+停留点（仅有线设备）；**替代现桩数据** |
| 熄火自动记车位 | `/api/vehicle/acc` + accStatus=false 那一帧位置 | 可做，不用等吉利；地址反解析禁轮询（日300万额度，单次调用） |
| 数字钥匙本身 | `/xinye/instruction/send` | 解锁/锁门/尾箱/一键启动/熄火；发送示例给 historyId，回执接口要 uuid，二者对应关系待对方澄清；当前只实装既有「远程熄火」 |
| 保养工单闭环 | `/dms/orders`（安装/拆改工单、门店、师傅） | 可考虑复用其 DMS 省自建门店 |

## 2. 接入前必须谈清（六项，任一没落都不上生产）

1. **鉴权与配额**：appid+时间戳+双层 md5 签名；accessToken 2h 有效、官方建议本地缓存 1.5h；认证≤2次/分、功能≤10次/秒，超频/非法即限流，放开要签保障协议——**配额上限必须写进合作协议**。
2. **无推送**：全篇没有 webhook/回调/长连接，车况只能轮询；拉取频率与缓存策略我们定，圆屏侧不能指望被动收到。
3. **指令回执口径不一**：发指令返 historyId，查结果接口走 uuid，两套不通；枚举写 LOCK DOOR、示例写 LOCK——**要对方澄清并给准接口**。
4. **坐标系口径不一**：同名 mapType 一处写「不传火星/传一地图」、另一处写「1百度/0火星」。建议统一 wgs84 落库、展示本地转换。
5. **空字段不返回**：所有接口值为空的字段整个省略——客户端必须按 key 可能不存在写，油量/电压这类条件字段不能假设在。
6. **无 DTC/OBD 故障码**：车况那块故障码继续等数据源。

## 3. 第一步（建议，待 owner 拍板）

先验三接口：`/device/miles`、`/device/status`、`/xinye/instruction/send`。
用一台真车设备跑 3 天，核对：①里程日累加对齐实表；②oilVolume 在对方主推型号上是否有值；
③钥匙指令端到端回执时延与成功率。通过后排接入排期，与寻车/熄火记位合并交付。
协作文档给过 `appid`/`key`，但在线自检证明该 `appid` 不是登录账户名，且协作文档里出现过的那把 `key` 必须先轮换；拿到正确账户名与新 key 前只做本地构建和假服务验收，不对真车发指令。

## 4. 已实装（1.29.16，真车未验）

- 复用现有「常用操作 → 远程熄火」按钮，没有新增页面、按钮或屏侧协议帧。未配置时第一次点该按钮打开系统文件选择器，导入成功后才允许第二次点击发送。
- `core` 新增 OkHttp 客户端：双层 MD5 签名、令牌最多缓存 90 分钟、认证间隔至少 30 秒、功能调用至少间隔 100 ms、并发串行化。默认客户端显式关闭 `retryOnConnectionFailure`、`followRedirects`、`followSslRedirects`，不让 OkHttp 在一次 `send()` 内隐式重放可恢复连接异常、408/503 或 307/308 POST；需要重拨只能由上层持久化闸与显式轮询决定。
- 正式发送枚举为 `ENGINE STOP`；发送回包兼容文档里 `details`/`data` 和数组/对象两种写法，但指令条目、`code`、`status` 缺失或 `status` 未知时按协议不确定处理，不默认“已发送”。
- 发送、合作方回复、车辆回复、执行结果严格分开：发送回包 `status=0` 只说“已发送”，`status=1` 只说“合作方已回复请求”，都不能当车辆已执行；只有 `/instruction/relay` 回执 `status=1` 才说“车辆已回复”。回执文字按去标点后的完整短语识别熄火成功/失败，`unsuccess` 按失败；`success=false`、`success not confirmed`、带前缀的通用“发送成功”和未识别文字都保留重发闸，不猜执行结果。
- 发送回包优先使用 `uuid` 查回执；只有 `historyId` 时保留编号供排查，但**不把 historyId 冒充 uuid 查询**，界面明示执行未确认并阻止重发。两套 ID 的正式对应关系仍要合作方澄清。回执必须只有一条条目且带与查询完全一致的 `uuid`；缺失、不一致或同 `uuid` 多条都拒绝承认当前指令结果。持久化记录若没有明确的 `Uuid` 来源，同样只阻断重发、不出网查回执。
- 不可逆动作复用现有权限档：默认只读本机；未放开「不可逆」权限时，按钮在本地直接拦住，不出网。即使鉴权期间权限被降档，客户端在鉴权完成、功能限速之后、真正发出指令 HTTP 请求之前还会再查一次当前权限；此时关闭则返回“本次未发送”，不会发指令。
- 发送前先用 `SharedPreferences.commit()` 确认“未确认尝试”已落盘，落盘失败则不出网。网络、HTTP 或协议层的不确定结果会阻止自动重发；已有尝试时再次点击只查回执。只有明确未发送、合作方拒绝，或车辆回执文字可识别为成功/失败后才清账；回执缺失或文字未识别时继续保留闸门。
- owner 核对车辆后把权限降档再开回「不可逆」，才显式解除重发闸；本次发送或回执查询仍在运行时不接受这种切换清账，必须等流程结束后重新切换。
- 凭据只从应用私有且排除系统备份的 `noBackupFilesDir/digital-key-partner.properties` 读取，格式为 `appid`、`key`、`licenseNumber` 或 `imei`（至少一个）、可选 `mobile`。导入文件限 64 KB，非法或超限文件不覆盖旧配置；配置不写 SharedPreferences、不进 APK、不进日志，`toString()` 脱敏。
- 导入成功后自动做一次只读自检：鉴权；有 `imei` 时再各查一次 `/api/device/status` 与 `/api/api/device/location`。状态回包必须是单元素 `data` 数组（不把对象当数组兼容），且 `imei` 必须与查询一致；缺失条件字段保持空值。定位取根字段（兼容 `data` 对象），至少要有经纬度之一或非空地址。自检不发任何车辆指令。
- 已加假服务单测与 Compose 守卫；**尚未对真车发送指令，合作方正式响应与车辆回执仍未验收**。现场测试必须先明确授权车辆与动作，并先核对回执 ID 口径。

### 4.1 真机导入与使用

1. 让合作方通过私聊给出新的 `appid`、`key` 和已授权车辆的车牌号或设备号；在协作文档出现过的那把 `key` 先轮换。
2. 在手机上新建一个纯文本 `.properties` 文件，内容只用占位符替换成真实值：
   ```properties
   appid=合作方APPID
   key=合作方KEY
   licenseNumber=已授权车牌号
   # 或改用 imei=已授权设备号；mobile 可选
   ```
3. 在 App「我的」里先放开「不可逆」权限，再到「常用操作」点一次「远程熄火」，在系统文件选择器里选该文件。
4. 导入成功立即提示“配置已导入，正在自检；不会发送车辆指令”，只读自检结束后提示“再点一次「远程熄火」才会发送”。核对车辆后**再点一次**「远程熄火」才发送；此后结果未确认期间再点，有 `uuid` 时只查回执，没有 `uuid` 时只阻断重发。
5. 真车验收必须先明确授权车辆与动作。发送成功、合作方回复、车辆回复、执行成功是四层，只认最后一层回执，不拿界面提示代替车辆状态。

## 5. 红线核对

- 下行不加新 frame `t`：平台数据走 App 云端拉取，进屏仍用既有通道字段，不破协议红线。
- 不假造：以上每项都出自对方文档原文；带「要问对方」字样的在拿到答复前都按未知处理。
- APK 不新增二进制依赖；包体必须用同一构建条件下的实际产物比较，不拿估算或源码行数代替。

## 6. 在线自检结果（2026-11-28，本机直连，非真机）

用对方文档公开示例账号 `testdemo` 实测，**不是自洽推理**：

- **鉴权算法在线验证通**：`POST /api/auth`（双重 md5 签名，见 §2 口径）返回 `code=0`、拿到 accessToken、`expiresIn=7200000ms`。合并后的 `XinyeDigitalKeyClient` 签名实现与这条一致。
- **appid 是「账户名」不是主体公司名**（文档「认证」节原文：使用账户名和登录秘钥）。把配置主体公司名当 appid 提交，实测返回 `code=10004`。真机联调仍缺贵司后台的**登录账户名**。
- **功能接口封装可通**：示例账号 + 假 IMEI 调 `/api/device/status`、`/api/api/device/location`，HTTP 200、返回 `{code:57019}`。57019 不在已记录的码表（10001–10004/21002/21003/21010）里，**含义要问对方**——已加入 §2 待谈清清单之外单独记录；客户端按 `Api(code,msg)` 原样透出，不猜译。
- 合并时只保留只读状态/定位能力，并纠正为文档真实路径：状态 `/api/device/status`，定位 `/api/api/device/location`。远端工程页曾加的解锁/闭锁/尾箱/启动等任意指令按钮已删除；命令仍只由既有「远程熄火」按钮发出。

## 7. 11-28 最终构建与包体验收

单次 Gradle 无缓存全仓回归执行：

```text
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  --rerun-tasks --no-build-cache --no-daemon --console=plain \
  -Pkotlin.incremental=false -Dorg.gradle.jvmargs=-Xmx2g
```

`BUILD SUCCESSFUL in 8m 13s`，56/56 actionable tasks 实际执行；日志
`D:/dev/tmp/carpet-final-regression-20261128-12916.log`。按 XML 内部 timestamp：
core 50 套 / **427/427**（09:24:06–09:24:32），App 15 套 / **87/87**
（09:25:46–09:28:24），合计 **514/514**，失败、错误、跳过均 0；数字钥匙
core 23/23、App 11/11。

| 产物 | 1.29.14 基线 | 1.29.15 合并前 | 1.29.16 最终 |
|---|---:|---:|---:|
| APK 字节 | 19,796,438 | 19,237,142 | 19,247,234 |
| SHA-256 | `8593e012b77ec09649de8f8ab7d1c785a8f25babe7cfb0a966f49ddb9788a63f` | `7d8632c7c941e7b076d6d117f4320fe47e9c218902ad602e5504ff912588de21` | `24479547b89a4cb55c7eb13335a26d737e5312b3f7ccfe2aefa4cfaab52fc207` |

最终包比 1.29.14 基线少 **549,204 B**，比合并前 1.29.15 多 **10,092 B**；保留副本
`D:/dev/tmp/carpet-app-1.29.16-129160.apk`。`aapt dump badging` 实读包名
`com.carpet.satdemo.app`、versionName `1.29.16`、versionCode `129160`、minSdk 26、targetSdk 34；
manifest 实读 `usesCleartextTraffic=false`、`networkSecurityConfig` 存在、`extractNativeLibs=true`。
`apksigner verify --verbose --print-certs` 通过（v2 scheme，1 个 Android Debug signer），
`zipalign -c -v 4` 为 `Verification succesful`。`verifyCleartextPolicy` 与
`verifyApkAndroidCompat` 均实际执行；后者静态扫到 459 个 `java/javax` 类，android.jar 缺的
2 个 instrument 类按已知不加载跳过，不冒充真机加载验收。

`jniLibs.useLegacyPackaging=true` 把两份 arm64 原生库从 APK 内 stored 改为压缩：
7,112 B → 2,816 B、827,648 B → 264,022 B；安装时解压的原生库合计 **834,760 B**。
本机只验了 APK 字节、manifest、签名和对齐，**尚未在手机上验安装/升级占用与原生库加载**。
本机最终 APK 的 AI/Azure/ASR key、base、model 字段均为空，不能当 owner 装机云配置完整包
直接替换；真车指令本轮仍未发送。

最终 Compose 截图已重新生成并逐张实看：

- `apps/android/build/screenshots/digital-key-action-384.png`：未配置时显示「熄火待配置」；
- `apps/android/build/screenshots/digital-key-configured-384.png`：导入后显示「熄火已接合作方」。

截图只证明现有按钮区的两种本地状态，不证明文件选择器真机交互、合作方网络或车辆回执。
