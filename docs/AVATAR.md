# 形象规范：一个角色，画两遍

这份是给两台机器共同照着做的。**先定角色，再各自画** —— 手机和圆屏现在最大的不一致
不是配色，是"屏上有一张会演的脸、手机上一次都没出现过它"。

基准事实来自上游公开仓库 [facebookincubator/muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk)
（2026-11-05 亲自核对，不是转述）：

- 许可：**Apache-2.0**（仓库根 `LICENSE`）。所以代码、文档、格式规范我们都能用。
- **但那个默认角色本身不在许可范围内**。`esp32/tools/muse/AVATAR_RECIPE.md` 第 25–27 行原话：
  > The default avatar is Meta's character, and the Apache License doesn't grant rights to it.
  所以 `esp32/avatar/muse_pixel.c`（那只带兜帽的毛茸茸）和 `esp32/avatar/jollybot.gif`
  **一个像素都不许搬进我们的 App，也不许当屏的默认形象交出去**。
  上游自己给的路子就是"换成你自己的角色"：`components/muse/avatar/muse_pixel.c` 这个文件
  存在时固件就用它替换默认形象，而且那个目录是 gitignore 的。
- 上游给的**格式**是可以照抄的，而且它就是我们要的那套语言：64×64 像素画、
  调色板 ≤32 条且按语义命名（outline / 深中浅高光 / 脸部 / 眼珠 / 腮红 / 嘴 / 每档状态的 4 级辉光 /
  光晕 ×2 / 火花 / accent / 影子 / 心 / 白）、4×4 Bayer 有序抖动、剪影 1px 硬描边、
  左上光源、抖动地面影子、按状态染色的边缘光、眼睛嘴用 2–5px 的小图章。
  完整清单在 `AVATAR_RECIPE.md` 和 `avatar_prompt.md` 里，别凭记忆。

---

## 1. 角色：小轨（Xiǎoguǐ）

车宠那颗装在仪表台上的小家伙。设定一次，两边共用：

| 项 | 定死 |
|---|---|
| 剪影 | 一颗站立的卵圆卵石形身体（超椭圆），头顶两根**短圆耳**，正中一根**细天线**，天线头那一颗点是它的卫星接收器 |
| 脸 | 身体内侧一块更暗的**正椭圆**面板，两只圆豆眼 + 一条嘴；腮红两枚压在脸板外的身体上 |
| 脚 | 两只 stub，脚底约在 y=56 |
| 尺寸 | 32–36px 宽 × 46–48px 高，中心 x=32（照上游的排版约束，不另起一套） |
| 本体色 | 浅石板：高光 `#E6EEF8` / 浅 `#C9D4E3` / 中 `#8FA3BF` / 深 `#5A6B85`；描边 `#05070A`；脸板亮 `#1B2330` 暗 `#10161F` |
| 五官色 | 眼 `#EAF2FF`；嘴 `#DCE6F5`；腮红 `#FF8FA3`；心 `#FF5C7A`（心不跟着状态色走，64px 上只有粉色才读得出是心） |
| 天线头 | 永远吃**当前状态的 accent 色** —— 它就是这块屏的链路指示灯 |
| 性格 | 稳、慢、认路。不卖萌不张嘴笑到变形；happy 也只是眼睛弯成 ^^ |

手机这一版已经按下面这套几何画出来了（`apps/android/.../ui/PixelAvatar.kt`，
`avatarPixels()` 是纯函数，屏那边要核对就直接读它）：身体超椭圆中心 `(32, 33)`、
半轴 `17 × 23`（三次方超椭圆，不是圆）；耳 `r=4.2` 圆心 `(23.5, 10)` 与 `(40.5, 10)`
—— **耳必须冒出头顶**，垂耳（sad）挪到 `(21.5, 16)`；脸板正椭圆 `10.5 × 7.6` 中心 `(32, 30)`；
眼珠 `r=2.1` 圆心 `(27.2 / 36.8, 30)`；嘴中心 `(32, 34.5)`；脚 `r=3.4` 圆心 `(25.5 / 38.5, 56)`；
天线头 `r=2.2` 圆心 `(32, 6.5)`，杆宽 1.4px。**跳一下最多 2px**，再多天线就顶出画布。

为什么不用现成的猫狗形象：这只得在 64×64 上读得出来，还得在 224dp 的手机镜像里读得出来。
卵圆 + 天线 + 两只耳，是**在 4px 精度下仍然唯一**的剪影（猫狗和市面上所有 AI 玩具都撞）。

## 2. 状态色：直接用上游那张表

`avatar_prompt.md` 的 PER-MODE SCHEMES 就是 Muse 界面围绕形象用的那套语言，
我们照抄（颜色本身不受版权保护，且那份文档是 Apache-2.0）：

```
BOOT      ffffff cfe0ff 8fa8ff 5a5fe0  accent a9c0ff
IDLE      f4e8ff c7a4ff 9a6bff 5b3fd9  accent a77dff
LISTENING e8faff 8fdcff 3fa2ff 2a5bd7  accent 5cb8ff
THINKING  ffe6ff ff9cf0 d35bff 7a2bd9  accent e07bff
SPEAKING  eafff4 9ff5cf 3fd9a0 1f9a7a  accent 6ff0bf
ERROR     ffd6d6 ff6b6b c7304a 6b1a3a  accent ff5c5c
OFF       d8d4ff 8f86d9 5a4fb0 2e2870  accent 7c72d0
```

**这一条不许两边各调一套。** 手机 App 的 [Tone] 里那支卫星蓝 `#388BFD` 和这里的 accent
不是同一个东西：Tone 管的是**卡片上的语义色**（链路、额度、告警），这张表管的是**形象周围的光**。
两套各司其职，别去统一它们 —— 统一的结果是把 SOS 的红和 ERROR 的红变成两个值。

## 3. 情绪 ↔ 模式：这张表已经有了，别新造

契约里 `state.ui.emotion` 是 7 档（`neutral/happy/excited/sad/worried/sleepy/thinking`），
`firmware/screen/muse/README.md` §5.4 已经给过映射，这里只是把它钉成规范：

| emotion | Muse mode | 屏那边 | App 镜像里画什么 |
|---|---|---|---|
| neutral | IDLE | 慢浮动 + 眨眼 | 直立、圆豆眼、天线头 IDLE accent |
| happy | IDLE + `make_happy()` | 跳一下、^^ 眼、两颗心 | 同一姿势的静帧：^^ 眼 + 笑 + 心 |
| excited | IDLE + happy 满档 | 跳更高、火花更多 | 同上但火花 4 颗、耳朵立起 |
| sad | IDLE + 低 level | 垂耳、眼睛半闭 | 耳朵下垂、眉角下、嘴一条平线 |
| worried | THINKING（先不给 ERROR，ERROR 有报警脸） | 眼睛瞪圆、嘴发抖 | 瞪圆 + 嘴波浪 + 一滴汗 |
| sleepy | `set_asleep(true)` | 闭眼 + Zzz | 闭眼弧线 + 两颗 Z |
| thinking | THINKING | 眼珠上瞟 + 三个想点 | 眼珠偏上 + "hmm" 嘴 + 三颗点 |

`emotion = null`（这一帧不带情绪）**必须落回 neutral**，不是保持上一帧 —— 这条 §5.4 已经踩过。

还有 §5.4 那条硬警告，规范里一并留着：**Muse 自己的语音链路也在写 mode**。
无条件每 0.25 秒写一次会把正在播的口型脸拽回中性。所以链路只在**值真的变了**时写一次，
且**当前 mode 是 SPEAKING/LISTENING 时不写**。

## 4. 两边各交付什么

**圆屏那台机器**（走上游给的正路，别手改上游文件）：

1. 把本文件当 prompt 的输入：`python3 tools/muse/avatar.py`，或在 `avatar_prompt.md`
   后面附上第 1 节那段角色设定，让 Muse 产出一份 `muse_pixel.c`。
2. 产出物存到 `esp32/components/muse/avatar/muse_pixel.c`（上游那个 gitignore 的目录），
   **同时把这份文件和它的 GIF 提交到我们仓库的 `firmware/screen/muse/avatar/`** ——
   那个目录是我们的记录，不是上游树的一部分。
3. 验收判据：`python3 tools/muse/make_gifs.py /tmp/avatar_gifs` 出来的
   boot/idle/listening/thinking/speaking/happy/off/error 每张 GIF，
   对着 `avatar_prompt.md` 的 ANIMATION BEATS 逐条核。
   在 64×64 上认不出"这是一只带天线的卵石宠物"就是没做完。

**手机这台机器**（已落地，`PixelAvatarRenderTest` 三条判据守着）：

1. `ui/PixelAvatar.kt`：同一套 64×64 网格、同一份调色板、同一条 Bayer 抖动和描边规则，
   逐像素算完再**不插值**放大画，**不另起一套矢量形象** —— 两边像不是
   因为画得像，是因为同一张网格同一份色表。
2. 首页那块圆屏镜像在 `ui.card == emotion` 时画它（此前这种时候画的还是卫星那五行字）。
3. 截图 harness 一次拍 7 档情绪拼成一张对照表，跟屏那边的 GIF 并排比。
   这张表已经提交在 `docs/reference/avatar-7-emotions.png` —— 屏那边画完，
   第一眼看的就是它跟这张图是不是同一只。
4. **没屏接入时（`live=false`）状态色一支都不许出现**：辉光、边缘光、天线头、火花全退成
   结构灰，只留本体那四档石板。这条和镜像卡那条老红线是同一件事，测试直接拿
   `SCHEME` 那张表比对，不许用"颜色种类数"糊过去（石板蓝本身就带色相，那种判据是假的）。

## 5. 红线（不因为换了底座而放松）

- Meta 那只默认形象：不许出现在我们的任何产物里，包括截图和文档配图。
- 上游那份 `muse_pixel.c` / `jollybot.gif`：不许复制进本仓库。
- 我们这份形象的**源文件必须在本仓库里**（`firmware/screen/muse/avatar/`），
  这样"两边画的是同一个角色"这件事有第三方可核对的凭据。
- App 仓库现在是 public：任何第三方素材进这个仓库之前，先在本文件里记一条许可出处。
  没记录的，不进。
