# 圆屏固件两件事：手机单独完成配对（蓝牙配网）+ 换成小轨那张脸

给圆屏侧的 agent 执行，App 侧由我这台机器配合。**本方案不改 `protocol/screen-link.md`，不改 App 的协议层**：Bluetooth 只用来传 Wi-Fi 凭据和接入点地址，真正的话音/画面仍走现有 Screen-Link v3（`ws://…:8443`）。

---

## 0. 现状判断：现在为什么必须点一下屏

- 屏拨号由 `gate_task` 发起：它先 `while (!muse_wifi_connected())`，连上 Wi-Fi 后读 NVS `link_url`，没有就用出厂 `ws://192.168.43.1:8443`，再 `link_open()`。**拨号本身不需要屏上任何操作。**
- 现在缺的唯一一环是 **Wi-Fi 凭据**：屏只有通过自己的「WI-FI」页用 1.75 寸键盘输密码这一条消费级路径。手机热点 / 家里路由器对屏都是"陌生网"，必须人工输一次。
- 所以"手机单独完成配对"= **手机把 SSID/密码/可选接入点地址交给屏**，屏自己连网、自己拨号。最合适的外设通道是 BLE：App 已经有 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` 权限和 `neverForLocation` 申报（`AndroidManifest.xml`），但没有一行 BLE 客户端代码——这次两边一起补。
- 另一个前提：**`CONFIG_HOMEHUB_WIFI_SSID` 的开发覆盖会盖掉配网**。现有条件是 `use_override = ovr_ssid[0] && !have_nvs`，即 NVS 里有凭据时以 NVS 为准。出厂构（`devices/sdkconfig.carpet`）里这个值应为空，配网才生效；台架机上它是 `aa123`，验收前要确认清空。

---

## 1. 目标与非目标

**目标**：全新屏（或换网后）从"拆机通电"到"App 显示已连接"，**用户只碰手机**。屏可以完全不动，最多看一眼屏上显示的 6 位配对码。

**非目标**：不作为 Muse 云账号配对的替代；不传 API Key / 模型地址；不做公网配网；不改变现有串口调试口的能力。

---

## 2. 方案 A（推荐）：车联自有 BLE 配网服务

### 2.1 为什么独立于 Muse 云那条 BLE

`muse_ble.c` 那套是 Muse 云的 token 配对（`muse_link.c` / `muse_chat_session`），跟"手机连屏"无关。**新开一个独立 GATT 服务**，不改 `muse_ble` 的任何行为，避免云配对和车联配网抢广播、抢连接。

### 2.2 GATT 契约（固定下来，App 按这个写）

建议放进 `esp32/components/muse/carpet/carpet_prov.c/.h`，Kconfig 开关 `CONFIG_MUSE_CARPET_PROV`（车机构建默认 y）。

- 广播名：`CARPET-XXXX`（XXXX = MAC 后两字节，大写十六进制）。广播只在「未配网」或「用户主动进入配网态」时开。
- Service UUID：`c0a70001-7a11-4b7d-9f10-6a5f00000001`

| 特征 | UUID 尾段 | 属性 | 内容 |
| --- | --- | --- | --- |
| SSID | `…0002` | Write | UTF-8，≤32 字节 |
| PASS | `…0003` | Write | UTF-8，≤64 字节，**不回读、不打印** |
| URL | `…0004` | Write | 空串 = 用出厂地址；否则必须 `ws://` 或 `wss://`，≤63 字节 |
| COMMIT | `…0005` | Write | 6 位十进制配对码，触发保存与连接 |
| STATE | `…0006` | Read + Notify | JSON 状态（见 2.4） |

- 建议协商 MTU 247；未协商时按默认 23 字节分片，SSID/密码分多次写。
- COMMIT 的配对码来自屏上显示，**每次进入配网态重新生成**，有效期 5 分钟或一次成功提交，二者先到。
- 写入顺序固定：SSID → PASS → URL（可选）→ COMMIT。未收到 COMMIT 前所有写入只放内存，掉电即丢。

### 2.3 配网态怎么进（三选一，建议全做）

1. **未配网自动进入**：开机后 NVS 无 Wi-Fi 凭据且 10 秒内没连上 → 广播 + 屏上显示"等着配对"和 6 位码（复用现有 Muse 配对文案，不新造词）。
2. **设置页主动进入**：现有「接入点」那一行旁边加「蓝牙配网」，或长按该行 2 秒进入；这行本来就是给现场用的，位置现成。
3. **串口**：`>prov` 进入 / `>prov off` 退出，给台架和自动化测试用。

### 2.4 状态机与 STATE 通知

```json
{"state":"need_creds","name":"CARPET-A3F7","code":true}
{"state":"connecting","ssid":"MyPhone","url":"ws://192.168.43.1:8443"}
{"state":"wifi_ok","ip":"192.168.43.23"}
{"state":"online","linked":true}
{"state":"bad_pass","err":-1}
{"state":"bad_url","err":-2}
{"state":"timeout","err":-3}
```

- 连接成功：屏停广播、停配网服务、`carpet_link_set_url()` 触发现有 `url_restart_task` 重拨（Wi-Fi 未起来时它只存不拨，这是既有逻辑，别改）。
- 失败：保持配网态，允许重试；错误码只在 STATE 里出现，日志里**不带密码**。
- 保存走现有口子：`muse_settings_set_wifi(ssid, pass)`、`muse_settings_set_link_url(url)`。不要另建一套 NVS key，否则串口 `>url` / `>wifi clear` 两条回滚路径就对不上了。
- 成功后若编译期 `CONFIG_HOMEHUB_WIFI_SSID` 非空，NVS 已存在会按现有优先级胜出（`use_override = ovr_ssid[0] && !have_nvs`），不需要额外改判断。

### 2.5 为什么不用"屏上输密码"那种兜底

保留现有 WI-FI 页，但不再作为主路径。客户隔离的酒店/公司网仍需手输，那时现状那条路就是兜底。

---

## 3. 方案 B（兜底）：屏开 SoftAP + 手机网页配网

屏开 `CARPET-XXXX` 热点，手机连上后打开 `http://192.168.4.1` 提交 SSID/密码/URL，屏存 NVS 后切回 STA。

**不建议作为主路径**：手机连屏热点会断当前网络（App ↔ 屏的 ws 也断），2.4GHz only，部分定制系统还会提示"此网络无法上网"并自动切回。只在 BLE 因射频/权限不可用时作为后备，并且要处理"配网成功后 App 自动切回原网"的等待逻辑。固件侧工作量与 A 相当，收益更小。

---

## 4. 方案 C（台架/工厂）：串口预置

已有：`>url ws://…`、`>url clear`、`>wifi clear`。本次只建议补一条 `>wifi <ssid> <pass>`（密码从串口读入，不 echo、不打日志），让产线能不开蓝牙批量预置。**不是消费级路径**，不向用户暴露。

---

## 5. 日志与自检（照着这几条验，不用猜）

新增统一前缀 `@prov`，一行说清：

```
@prov adv=1 name=CARPET-A3F7 code=1 creds=0
@prov set ssid=MyPhone pass=*** url=ws://192.168.43.1:8443
@prov commit code_ok=1 err=0
@prov wifi ip=192.168.43.23
@prov done linked=1
```

既有回读仍以 `>url` 为准：`@url now=… src=nvs|factory saved=… linked=… dialed=… wifi=… ssid=…`。
`>carpet` 仍报 `wifi=` / `linked=`。**任何一行都不许出现密码或完整配对码以外的凭据。**

---

## 6. 验收（每条都要能当场复现）

1. **冷启动无凭据**：`>wifi clear`、`>url clear`、重启。期望：屏显示"等着配对"+6 位码；`@url` 里 `ssid=(none) wifi=0`；手机能扫到 `CARPET-XXXX`。
2. **手机完成配对**：App 里选 Wi-Fi、输密码、提交 6 位码。期望：STATE 依次 `connecting → wifi_ok → online`；屏上不再显示配网态；`@url` 里 `ssid=<热点名> wifi=1`、`src=factory`（未传 URL 时）。
3. **拨号成功**：App 上圆屏卡从灰变亮；`>carpet` 在 30 秒内出现 `linked=1`；`@prov done linked=1`。
4. **传自定义地址**：App 里填 `ws://192.168.43.1:8443`（或其他手机地址），提交后 `@url` 显示 `src=nvs`，重拨后仍 `linked=1`。
5. **错密码**：提交错误密码。期望：STATE `bad_pass`，屏不崩、不重启、可立即重试；日志里没有密码。
6. **非法地址**：提交 `http://…` 或不带 `ws://` 的串。期望：`bad_url`，不写入 NVS，`>url` 仍是原值。
7. **重复开机**：已配网后重启。期望：**不再广播**，直接连 Wi-Fi 并拨号；`@prov adv=0`。
8. **不影响 Muse 云**：`>status` 里 hatch 状态、设置页「Muse 云」那组与配网前一致；Muse 云 BLE 配对照旧可用。
9. **回滚路径**：`>wifi clear` + `>url clear` + 重启，回到第 1 条的初始状态。
10. **回归**：现有 Wi-Fi 页手输密码仍能连；`>url` / `>carpet` / `>say` / `>audio` 不受影响。

---

## 7. 回滚

- 编译开关 `CONFIG_MUSE_CARPET_PROV=n` 即可整块关掉，其他代码零改动。
- 运行时在设置页提供「关闭蓝牙配网」（可选，第二优先级）。
- NVS 只使用既有 `muse` 命名空间里的 Wi-Fi 与 `link_url` 两格，回滚不需要迁移数据。
- 现有主路径（屏上 WI-FI 页 + 出厂地址）**这一轮全部保留**，新方案失败不影响它。

---

## 8. 安全边界

- 配对码必须由**屏上显示**后人工输入 App，不接受无码写入；5 分钟失效、失败 5 次锁定直到重启。
- 广播只在配网态开启；平时不广播设备名，避免被当信标扫描。
- 密码只在内存与 NVS 各存一份，不写日志、不回读、不进崩溃转储；提交后立即清掉内存缓冲。
- 不通过 BLE 传 API Key、模型地址或任何账号 token。**模型密钥只存在手机**。
- 配网服务无网络监听端口，不引入新的 HTTP 攻击面（方案 B 才有 80 端口，故不作主路径）。

---

## 9. 第二件事：把屏上的脸换成小轨

### 9.1 现状

- 手机画的是小轨（`apps/android/.../ui/PixelAvatar.kt`，几何与色表见 `docs/AVATAR.md` §1）。**V1.18 已按"大头毛绒"重画**：大脑袋 `16×14.5` 超椭圆 + 缩在下面的小身体 `11×9` + 两只 `r=5.2` 短圆耳 + 天线球 `r=2.7`（吃状态 accent）+ 奶白脸板 `12.5×11` + 竖椭圆眼珠 `3.1×3.6` 带双高光 + 腮红。本体是暖奶油 `#FBF0DC/#EFE1C6/#D6C09B/#B49B76`，不再是石板蓝。**屏侧以 `docs/AVATAR.md` §1 的这套数字为准**，旧的"单颗卵石"几何已作废。
- 屏上仍是上游 Muse 的默认生成脸。用户看到"屏上更好看"就是这个差异：**不是同一张脸，屏上那只是 Muse 自带角色**。
- 上游默认角色是 Meta 的，**不许搬运**；正路是生成我们自己的 `muse_pixel.c`。

### 9.2 要做的

1. 按 `docs/AVATAR.md` 第 1 节的 64×64 网格、语义调色板、4×4 Bayer、1px 硬描边、左上光，用上游 `python3 tools/muse/avatar.py` 生成小轨的 `muse_pixel.c`。
2. 产物放 `esp32/components/muse/avatar/muse_pixel.c`（上游 gitignore 目录），**同时把 `muse_pixel.c` 和生成的 GIF 提交到本仓库 `firmware/screen/muse/avatar/`**，作为"两边同一个角色"的可核对凭据。
3. 七个情绪映射按 `docs/AVATAR.md` §3：neutral / happy / sad / worried / sleepy / thinking / error。`emotion = null` 必须落回 neutral；链路只在值变化时写 mode，且当前为 SPEAKING/LISTENING 时不覆盖（现有 `face_tick` 的规矩不变）。
4. 面板尺寸、圆角、字号一律不改，只换形象资源。

### 9.3 验收

- `python3 tools/muse/make_gifs.py /tmp/avatar_gifs` 出 boot/idle/listening/thinking/speaking/happy/off/error 八张 GIF，逐个对照 `avatar_prompt.md` 的 ANIMATION BEATS。
- 在 64×64 上认得出"带天线的大头毛绒小宠物"，**头必须明显大过身子**（上一版头身不分，缩略图里是一台仪器）；和 `docs/reference/avatar-7-emotions.png`（1.18.0 已更新）并排看是同一只。
- 屏上实拍与手机镜像截图并排：轮廓、耳朵、天线位置、六档情绪一致；不一致以 `docs/AVATAR.md` 的几何为准改屏侧。
- 红线：不出现 Meta 默认形象的任何像素；不把上游 `muse_pixel.c` / `jollybot.gif` 复制进本仓库。

---

## 10. 分工

**屏侧（本方案执行方）**
1. `carpet_prov` 组件 + Kconfig + 设置页入口 + `>prov`。
2. 复用 `muse_settings_set_wifi` / `muse_settings_set_link_url`，成功后走 `url_restart_task` 重拨。
3. 状态通知与 `@prov` 日志。
4. 生成并提交小轨 `muse_pixel.c` + GIF。
5. 按第 6 节十条逐条自测，回执写上每条的实际输出。

**App 侧（我这台机器）**
1. BLE 客户端：扫描 `CARPET-*`、连接、写 SSID/密码/URL、提交配对码、显示 6 位码输入框。
2. 配对成功后等 `online`，再把现有接受圆屏接入的开关打开。
3. 这轮已先做完两件不依赖圆屏的事：模型端点自检（直接发一句最小请求，把 401/404/超时原样显示）；手机上直接试听答句（不经过圆屏，用来区分"引擎没声"和"圆屏没收"）。
4. 音色不占固件：答句的 PCM 由手机合成再按 v3 `audio_chunk` 上行，所以换 TTS 引擎、换引擎内的人声、调音高语速都只改 App（1.16.0）。屏侧不需要为"可爱女声"改任何代码。

**契约冻结后再动 App 的 BLE 代码**。GATT 表以本文 2.2 为准，若要改请先在群里说，避免两边同时动。

---

## 11. 风险与顺序

| 风险 | 处理 |
| --- | --- |
| Muse 云 BLE 与车联配网抢广播/连接 | 独立 Service UUID；配网态才广播；两者不共用连接回调 |
| 手机同时连热点又连 BLE 导致网卡切换 | 先写凭据、等 `online` 再停广播；App 侧不主动断 Wi-Fi |
| 2.4GHz / 5GHz | 屏只支持 2.4G；App 侧对 5G-only 热点给出明确提示，不静默失败 |
| 密码进日志 | 统一 `***`；验收第 5 条专门查 |
| 出厂构被开发覆盖顶掉 | 出厂 `CONFIG_HOMEHUB_WIFI_SSID` 置空；台架机验收前清掉 |

顺序建议：**先做 §9 的脸**（不依赖任何新协议，一天内能看到效果），再做 §2 的 BLE 配网（契约冻结 → 固件 → App）。两件事互不阻塞。
