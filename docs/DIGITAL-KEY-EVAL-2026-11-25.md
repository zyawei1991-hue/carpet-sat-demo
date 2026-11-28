# 数字钥匙合作方结合评估（2026-11-25）

> 来源：owner 在飞书清单文档（VnyZdUbJDoHF9RxAITCcitkEnjT）「车况卡手动录入增强」一节贴出的
> 产品文档链接 `https://open.gpswv.com/doc/web/#/3/14`（「风控在线」开放平台，服务域名
> open.figps.com，设备侧 API），及 11-25 多维表记录 21 评论给的正式凭据。
> 凭据口径：APPID 配置主体=浙江时空道宇科技有限公司；**KEY 只存仓库外本机凭据文件，
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
| 数字钥匙本身 | `/xinye/instruction/send` | 解锁/锁门/尾箱/一键启动/熄火，historyId 回执；比桩数据有说服力，12-20 演示可当真交互 |
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
凭据已到位（正式 appid/key，非文档示例 testdemo），但协作文档里出现过的那把 `key` 必须先轮换；轮换前只做本地构建与假服务验收，不对真车发指令。

## 4. 已实装（1.29.15，真车未验）

- 复用现有「常用操作 → 远程熄火」按钮，没有新增页面、按钮或屏侧协议帧。未配置时第一次点该按钮打开系统文件选择器，导入成功后才允许第二次点击发送。
- `core` 新增 OkHttp 客户端：双层 MD5 签名、令牌最多缓存 90 分钟、认证间隔至少 30 秒、功能调用至少间隔 100 ms、并发串行化。默认客户端显式关闭 `retryOnConnectionFailure`、`followRedirects`、`followSslRedirects`，不让 OkHttp 在一次 `send()` 内隐式重放可恢复连接异常、408/503 或 307/308 POST；需要重拨只能由上层持久化闸与显式轮询决定。
- 正式发送枚举为 `ENGINE STOP`；发送回包兼容文档里 `details`/`data` 和数组/对象两种写法，但指令条目、`code`、`status` 缺失或 `status` 未知时按协议不确定处理，不默认“已发送”。
- 发送、合作方回复、车辆回复、执行结果严格分开：发送回包 `status=0` 只说“已发送”，`status=1` 只说“合作方已回复请求”，都不能当车辆已执行；只有 `/instruction/relay` 回执 `status=1` 才说“车辆已回复”。回执文字按去标点后的完整短语识别熄火成功/失败，`unsuccess` 按失败；`success=false`、`success not confirmed`、带前缀的通用“发送成功”和未识别文字都保留重发闸，不猜执行结果。
- 发送回包优先使用 `uuid` 查回执；只有 `historyId` 时保留编号供排查，但**不把 historyId 冒充 uuid 查询**，界面明示执行未确认并阻止重发。两套 ID 的正式对应关系仍要合作方澄清。回执必须只有一条条目且带与查询完全一致的 `uuid`；缺失、不一致或同 `uuid` 多条都拒绝承认当前指令结果。
- 不可逆动作复用现有权限档：默认只读本机；未放开「不可逆」权限时，按钮在本地直接拦住，不出网。即使鉴权期间权限被降档，客户端在鉴权完成、功能限速之后、真正发出指令 HTTP 请求之前还会再查一次当前权限；此时关闭则返回“本次未发送”，不会发指令。
- 发送前先用 `SharedPreferences.commit()` 确认“未确认尝试”已落盘，落盘失败则不出网。网络、HTTP 或协议层的不确定结果会阻止自动重发；已有尝试时再次点击只查回执。只有明确未发送、合作方拒绝，或车辆回执文字可识别为成功/失败后才清账；回执缺失或文字未识别时继续保留闸门。
- owner 核对车辆后把权限降档再开回「不可逆」，才显式解除重发闸；本次发送仍在确认中时不接受这种切换清账，必须等有明确结果后重新切换。
- 凭据只从应用私有且排除系统备份的 `noBackupFilesDir/digital-key-partner.properties` 读取，格式为 `appid`、`key`、`licenseNumber` 或 `imei`（至少一个）、可选 `mobile`。导入文件限 64 KB，非法或超限文件不覆盖旧配置；配置不写 SharedPreferences、不进 APK、不进日志，`toString()` 脱敏。
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
4. 导入成功只显示“配置已导入”，不会发送。核对车辆后**再点一次**「远程熄火」才发送；此后结果未确认期间再点，有 `uuid` 时只查回执，没有 `uuid` 时只阻断重发。
5. 真车验收必须先明确授权车辆与动作。发送成功、合作方回复、车辆回复、执行成功是四层，只认最后一层回执，不拿界面提示代替车辆状态。

## 5. 红线核对

- 下行不加新 frame `t`：平台数据走 App 云端拉取，进屏仍用既有通道字段，不破协议红线。
- 不假造：以上每项都出自对方文档原文；带「要问对方」字样的在拿到答复前都按未知处理。
- APK 不新增二进制依赖；包体必须用同一构建条件下的实际产物比较，不拿估算或源码行数代替。

## 6. 11-28 最终构建与包体验收

最终串行命令为 `:core:test :app:testDebugUnitTest :app:assembleDebug --rerun-tasks --no-build-cache --no-daemon -Pkotlin.incremental=false`，日志 `D:/dev/tmp/carpet-final-regression-20261128-final3.log`，`BUILD SUCCESSFUL`、56/56 个任务实际执行。按新鲜 XML 内部时间戳统计：core 50 套 / **422/422**、App 15 套 / **84/84**，失败、错误、跳过均为 0；其中数字钥匙 core 18/18、App 8/8，屏侧 SchemaDrift 17/17。core XML 时间戳为 08:46:27–08:46:33，App 为 08:47:03–08:49:18，不是聚焦轮旧结果。

| 产物 | 1.29.14 基线 | 1.29.15 最终 |
|---|---:|---:|
| APK 字节 | 19,796,438 | **19,237,142（少 559,296 B，约 2.83%）** |
| SHA-256 | `8593e012b77ec09649de8f8ab7d1c785a8f25babe7cfb0a966f49ddb9788a63f` | `7d8632c7c941e7b076d6d117f4320fe47e9c218902ad602e5504ff912588de21` |

两边都没有 `local.properties`，因此云配置字段同样为空；这是相同条件下的包体比较。最终 APK 实读 `versionCode=129150`、`versionName=1.29.15`、`usesCleartextTraffic=false`、`networkSecurityConfig` 存在，`verifyCleartextPolicy` 与 `verifyApkAndroidCompat` 都实际执行。APK 签名 v2 有效（Android Debug 单签名者），`zipalign -c -v 4` 全项通过。兼容任务静态扫到 459 个 `java/javax` 类；android.jar 缺的 2 个均为 `java.lang.instrument.ClassFileTransformer` / `Instrumentation`，脚本按已知不加载类跳过；这不是真机类加载验收。

为满足“APK 不增长”，`jniLibs.useLegacyPackaging=true` 把两份 arm64 原生库从 APK 内 stored 改为压缩：7,112 B → 2,816 B、827,648 B → 264,022 B。代价是安装时额外解压 **834,760 B** 原生库；本机只验了 APK 更小、manifest `extractNativeLibs=true`、签名和对齐，**尚未在手机上验安装/升级占用与原生库加载**。本机最终 APK 的 AI/Azure/ASR key、base、model 字段均为空，不能当 owner 装机云配置完整包直接替换；真车指令本轮仍未发送。

最终 Compose 截图已重新生成并实看：

- `apps/android/build/screenshots/digital-key-action-384.png`：未配置时显示「熄火待配置」；
- `apps/android/build/screenshots/digital-key-configured-384.png`：导入后显示「熄火已接合作方」。

截图只证明现有按钮区的两种本地状态，不证明文件选择器真机交互、合作方网络或车辆回执。
