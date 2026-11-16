# 屏侧待办：photo_chunk 接收实装（2026-11-16）

App 侧推图通道已落地（commit `b56662b`，已推远端 main）。屏侧现在把接收端补上，
相册轮播（11-11 清单 §8.3 / B §5，#45）就能亮。

## 契约（App 侧已按这个发，四张表已同步）

- **不新开 frame `t`**：`photo_chunk` 骑在已有 `command` 帧上（和 `audio_chunk`/`wake` 同族）。
  屏侧在 `on_command`（muse-carpet.patch，`strcmp(cmd,"audio_chunk")` 那一段）加一个
  `strcmp(cmd,"photo_chunk")` 分支即可。
- args：
  - `album_id`：标识**一张**照片（不是相册）。缺号整张丢、下一张换 id 重来 ——
    口径和 audio_chunk 的「整轮 total」不同：照片没有接续念下一段的需求。
  - `seq`：从 0 连续，缺一块丢**整张**。
  - `total`：这一张 JPEG 的总字节数。`complete = 收到字节数 >= total`。
  - `fmt`：恒为 `"jpeg"`（预留，别的编码以后再说）。
  - `b64`：块的 base64。
- 块 2048 字节；每帧编码后 ≤3800 字节（App 侧 `PhotoChunks.commands` 里逐帧 check 过，
  屏侧不用再防超帧）。
- 完整 schema 描述见 `protocol/screen-link.schema.json` `command.cmd` 枚举；
  `protocol/screen-link.md` 下行指令节也有一段说明。

## 屏侧要做的事

1. `on_command` 加 `photo_chunk` 分支：校验 → base64 解码 → 追加到一张照片的拼装缓冲
   （参照 `carpet_audio_on_chunk` 的换 id 重置 + seq 连续校验；只是 here 换 album_id）。
2. JPEG 解码：LVGL 自带（11-11 清单原文「不引第三方库」）。472×472 圆屏，
   App 侧已经压成 472×472 方图 + EXIF 转正，屏侧不用再转方向、不用再裁。
3. PG_PHOTO 页渲染 + 轮播：一张解码完就画；App 会连续推多张（每次选图各推一张），
   `album_id` 不同即为不同张。轮播节拍、和 clock 互换的规则屏侧自定（B §5）。
4. 守护事件打断走现有 Avatar 顶出那条路（B §5），不用为照片加新逻辑。

## 验收口径

- 手机端「我的」→「圆屏相册」选一张照片 → 屏在 photo 页时（或翻到 photo 页时）
  显示这张照片；多选几张能看到轮播。
- 老固件收到 `photo_chunk`：静默忽略、不掉线（现有 `on_command` 兜底分支就是干这个的）。
- App 日志会出现「照片已推给圆屏：N KB JPEG，M 块」——屏收到但没显示时先查屏侧日志
  有没有「没听过这条下行指令：photo_chunk」（有 = 固件没刷；没有 = 屏侧 handler 有 bug）。

## App 侧锚点（给屏侧 agent 读代码用）

- 发送：`core/src/main/kotlin/com/carpet/satdemo/core/ai/PhotoChunks.kt`
- 触发：`apps/android/.../app/AppRuntime.kt` 的 `onScreenPageReported` / `setAlbumPick`
- 压图：`apps/android/.../app/PhotoPush.kt`（472×472 q85，1MB 上限）
