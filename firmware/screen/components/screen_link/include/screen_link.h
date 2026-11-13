/**
 * Screen-Link 展示端客户端（契约 v3）。
 *
 * 拓扑是反的：**手机是服务端，这块屏是客户端**。所以本组件不 listen，只 connect，
 * 并且首帧必须自己发 hello —— 手机那边在 hello 之前认不出你是谁，
 * 白名单没过就直接 close(1000, "unknown screen")。
 *
 * 契约全文见仓库 protocol/screen-link.md，字段定义见 screen-link.schema.json。
 * 这个头文件里的每一条注释都对应契约里的一句话，改代码前先读那份文档。
 */
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "cJSON.h"
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/** 服务端（手机 App）固定端口。名字里带 443 但**它是明文 ws**，别写成 wss:// */
#define SCREEN_LINK_PORT 8443

/** 出厂默认 id。演示现场一定要能被 Kconfig 改掉：烧错了今天就没有展示端 */
#define SCREEN_LINK_DEFAULT_SCREEN_ID "WS175B-A3F7"

/** 本端能解析的最高契约档。
 *  v1→v2 多一种 dialog 帧；v2→v3 多三样，全是**加法**，前两档的字段一字节没动：
 *  `ui.card=maint`、`hello.screen`（屏幕几何）、下行 `command{cmd:"audio_chunk"}`。
 *
 *  音频刻意挂在 command 里而不是新开一种 `t`：本组件对**解不了的帧类型**的处理是关连接，
 *  一条 `t:"audio"` 会让任何没升到 v3 的屏当场掉线；而认不出的 `cmd` 只是静默忽略。
 *  省的是"升级顺序必须两端同步"这条隐性约束，代价是音频帧要走一遍 cmd 白名单。 */
#define SCREEN_LINK_CONTRACT_MAX 3

/** v4 只做一件事：屏可以往手机推 `command{cmd:"mic_chunk"}`（「对着屏说话」）。
 *  它**不是** SCREEN_LINK_CONTRACT_MAX 的下一档，而是单独申报的一个能力位：
 *  - 屏自己出站的帧 `v` 不变（还是 3），下行一个字节没动；
 *  - hello.protocols 里多这一个数，让会读的手机知道"这块屏有耳朵"；
 *  - 反过来手机在 `ack.protocols` 里带 4，就是屏侧上行的**全部**许可。
 *
 *  【2026-11-13：这里原来还要求"这一帧 envelope 的 `v` >= 4"，那条判据是错的，已删。】
 *  真机称到的是：ack 外层**没有 `v` 这个键**。App 的 `ScreenMessage.v` 带默认值，
 *  而它的 Json 配了 `encodeDefaults = false`（`core/.../protocol/ScreenLink.kt:69-73`），
 *  所以出站帧一个 `v` 字节都不写；屏这边 `num_or(root,"v",1)` 于是永远读到兜底 1。
 *  契约自己就写着这一条（`protocol/screen-link.schema.json` 里 `v` 的 description、
 *  `ScreenLink.kt:20`）。要求 v>=4 = 要求一个按设计不上线的字段，
 *  结果是**任何版本**的 App 都被挡在门外，按键和唤醒词两条路一起报「对着屏说话还没开」。
 *  当天装机那版报的是 `protocols=[4,4]`（`CONTRACT_VERSION` 已是 4 ⇒ 按发布顺序
 *  `mic_chunk` 解码应已补完；屏还没称到"上行被对面收下"这一步，所以不写成已验证）。
 *  残留保护只剩 carpet_mic 那道熔断：发过上行后 5 秒内掉线 ⇒ 本会话不再试。
 *  组件仍然把 `v` 一起交给 `on_ack(ack, v)`，但只当证据打印，不当判据。 */
#define SCREEN_LINK_MIC_UP 4

/** 一条 state 帧在 1Hz 下最大约 1.2KB；v3 的音频帧是这里最紧的一条：
 *  2560 字节 PCM → base64 3416 字符 → 整帧约 3.5KB，所以这个数不能往下调。
 *  发送侧 core 有 `AudioChunks.JSON_LIMIT=3800` 逐帧断言，两边对着这一个数。 */
#define SCREEN_LINK_MAX_FRAME 4096

typedef struct {
    /** 完整 URL，例：ws://192.168.43.1:8443 —— 手机开热点时它自己是 .1 */
    const char *url;
    const char *screen_id;
    /** 固件版本，会原样出现在手机台账那一行 */
    const char *fw;
    /** desk_stand / collar / dash_mount */
    const char *dock_type;
    /**
     * 底座电池电压，毫伏；返回负数 = 不知道，那这一项就不进帧（schema 里它不是必填）。
     * 建链时现读一次，所以链路断开重连后带的是新值 —— 中途换电池要等重连才报得出去。
     */
    int (*read_battery_mv)(void);
    /**
     * v3：这块屏长什么样（schema `hello.screen`）。手机拿它做的唯一一件事是
     * "字该多大、一页装几行" —— 它自己不猜尺寸，猜错了就是圆屏上少半个字那种事。
     *
     * w/h 传 0 = 不申报这一整项（**别填一个看着像真的的数**，手机会照着排版）。
     * ppi 传 0 = 这一项不进帧。round/touch 是布尔，没有"不知道"这一说，
     * 所以它们只在 screen 对象真的进帧时才有意义。
     */
    int screen_w;
    int screen_h;
    bool screen_round;
    bool screen_touch;
    int screen_ppi;
} screen_link_cfg_t;

typedef struct {
    /**
     * 1Hz 快照。回调在链路任务里跑，**不许在里面做长活**：
     * 阻塞超过 10 秒，本组件会以为链路死了，主动断开重连。
     * 要渲染就把 cJSON 拷进自己的队列，或者只置脏标记交给 UI 任务。
     */
    void (*on_state)(const cJSON *state, uint32_t seq);
    /** 契约 v2。dir 可能是 up（手机/另一块屏起的头）也可能是 down（本屏那轮的答） */
    void (*on_dialog)(const cJSON *dialog);
    /** 已经按 event_id 去过重：重发的那条不会再回调一次，但 ack 已经回了。
     *
     * 过期的那条**不会走到这里**（契约：收到时若已过期则丢弃且不弹提示），但 ack 照回 ——
     * 屏确实收到了，只是不该再画。判定要用整帧的 ts 和"上一帧 state 的 ts + 它过了多久"
     * 对表，这两份时钟算术都在本组件里，别再让调用方各写一遍。
     * 所以 left_ms 是**还剩多少毫秒**，不是帧里的 ttl_ms；0 = 帧里没给 ttl，显示多久由展示端定。 */
    void (*on_event)(const cJSON *event, const char *event_id, uint32_t left_ms);
    void (*on_command)(const char *cmd, const cJSON *args);
    /** ack 那一格原样交出来，另外带上**这一帧自己申报的契约档**（整帧的 `v`，缺 `v` 按 1 算）。
     *  为什么要把 `v` 一起给：上行那道闸要判的是"对面整份契约到 v4 了没有"，
     *  不是"protocols 里有没有 4"这个数 —— 2026-11-11 读 App 那棵树称到，装机那一版
     *  已经在 protocols 里先报了 4，但它的 CommandType 还解不了 mic_chunk
     *  （`core/.../runtime/DemoRuntime.kt:513` + `ScreenLink.kt:112`），而它解不了的 cmd 是直接关线。
     *  所以"报了 4"不能单独当许可；组件自己不懂这套判断，只负责把两个数一起交出去。 */
    void (*on_ack)(const cJSON *ack, int v);
    /** negotiated 是本会话谈下来的契约档；只有它 ≥2 才能走对话 */
    void (*on_connected)(int negotiated);
    void (*on_disconnected)(void);
    /** 解不开的帧：原文 + 原因。现场排查全靠这一句，别在 UI 里丢掉 */
    void (*on_contract_error)(const char *raw, const char *reason);
} screen_link_callbacks_t;

esp_err_t screen_link_start(const screen_link_cfg_t *cfg, const screen_link_callbacks_t *cb);
/** 发一条 bye 再关：不发，手机台账上那台屏要挂几十秒才变离线 */
esp_err_t screen_link_stop(void);

bool screen_link_connected(void);
int screen_link_negotiated(void);

/**
 * 距上一帧 state 过了多少秒。没连上过返回 UINT32_MAX。
 * 契约那两条线由调用方画：**>3s 把链路图标变灰但保留最后一帧，>10s 本组件自己断线重连**。
 * 一个坑留给调用方：**连上了但一帧 state 都没收到时也返回 0**（刻意不报灰，免得刚连上
 * 就显示掉线）。所以它不能用来判"服务端活着但不推首帧"——那种死法由组件内部看门狗兜。
 */
uint32_t screen_link_state_gap_s(void);

/**
 * 那句「>10s 本组件自己断线重连」真踢过几次。0 = 从没踢过。
 * 只统计**超时**踢的：对端下线那种正常断线不算（否则每次服务端重启都 +1，数就没意义了）。
 * 串口回读用得上：半开链路在现场是演砸的那一类，改完要能证明自愈真发生了，
 * 而不是又一次"看起来好了"。
 */
uint32_t screen_link_watchdog_fires(void);

/**
 * 连着拨不上多少次：从上一次真的连上（WEBSOCKET_EVENT_CONNECTED）开始数的连续失败次数，
 * 一连上就归零。它是「屏的 Wi-Fi 说自己连着、其实已经没有路由」在现场唯一的证据 ——
 * 手机热点把这台站踢掉以后 esp_wifi 仍报 connected，而每一条拨号都以 ENETUNREACH(119)
 * 死掉，网络那一侧不动就永远出不来。muse_glue 的 keeper 读它来决定要不要重新关联。
 */
uint32_t screen_link_dial_fails(void);

/**
 * 最后一条拨号失败的原因，一行 ASCII，形如
 * `ESP_ERR_ESP_TLS_FAILED_CONNECT_TO_HOST errno=119 http=0`；从没失败过 = 空串。
 * 现场靠它把「端口上没人听」（errno=113）和「根本到不了手机」（errno=119）分开 ——
 * 前者是 App 的事，后者是网的事，屏侧的处理完全不同。
 */
void screen_link_last_error(char *out, size_t cap);

/**
 * 同上一条的毫秒版。给钟面用：**手机的 ts 加上这个 age，就是此刻该显示的手机时间**。
 * 这样屏不需要自己的 RTC 也不需要 SNTP —— 它没有电池-backed 时钟，
 * 而车载那个网一断，走 NTP 只会拿到一个更误导人的读数。没连上过返回 UINT32_MAX。
 */
uint32_t screen_link_state_age_ms(void);

/**
 * 上一帧 state 的整帧 `ts`（Unix 毫秒，手机时钟）。契约说不信任它、仅用于显示，
 * 所以这里原样给出去，不做任何校正。0 = 这轮会话还没收到过 state。
 */
int64_t screen_link_state_ts_ms(void);

/** 能不能说话：连着 + 谈到了 v2。App 上那句"圆屏可以说话"读的是同一个条件 */
bool screen_link_dialog_ready(void);

/**
 * 屏上按住说完的一段话，往手机推。
 * turn_id 由**起头那一方**生成，必须跨重启唯一（手机的记录按它去重且这份记忆跨连接有效），
 * 所以它是 `s-<开机随机段>-<自增>`，不是纯自增 —— 纯自增在重启后会撞上旧 id，
 * 表现是"重连之后头几句问了没反应"。
 * turn_id_out 可以传 NULL；要传就别传短于 24 字节的缓冲。
 */
esp_err_t screen_link_send_dialog_up(
    const char *text, const char *source, char *turn_id_out, size_t turn_id_out_n);

/** 收到 event 必须回 ack。for_seq 是**整帧**的 seq，不是 event 对象中字段 */
esp_err_t screen_link_ack_event(uint32_t for_seq, const char *event_id);

/** 屏侧上报一条 event。注意 kind 用的是**手机→屏那 11 个 EventKind**，
 *  长按呼救要走的是下面那条 `screen_link_send_command`，不是这里 */
esp_err_t screen_link_send_event(const char *kind, cJSON *payload, uint32_t ttl_ms);

/**
 * 屏 → 手机下指令，走 `t=command`。**这才是屏能让手机做事的那条路**：
 * `t=event` 的 kind 是手机→屏那 11 个（屏发出去手机按 EventKind 解，非法值直接断连），
 * 而 CommandType 是 schema 里 `command.cmd` 那一串：`sos_press`（屏上按钮被长按，手机立即执行双发）/
 * `sos_cancel` / `track_pull` / `set_demo_mode` / `set_fence` / `reboot_ui` /
 * `card_next`（左右滑请求切一张，args.dir = +1/-1）/ `card_auto`（把定卡权交回手机策略）/
 * `poke`（单击，手机换脸并可答一句）。
 * 后三条**都是请求**：画哪张卡、画哪张脸最终仍由手机写进 `state.ui.card` / `ui.emotion`，
 * 所以 SOS 进行中手机可以把 `card_next` 顶回去，观众切不走卫星卡。
 * 传别的字符串本函数当场退回 ESP_ERR_INVALID_ARG，不会把一条解不开的帧推给手机。
 * args 所有权交给本函数（传 NULL 发空对象），失败时也已被释放。
 */
esp_err_t screen_link_send_command(const char *cmd, cJSON *args);

/** 回一条 command 的执行结果，走 ack 帧：error 传 NULL 表示成功 */
esp_err_t screen_link_ack_command(uint32_t for_seq, const char *error);

/**
 * 屏侧那道文案闸：**画任何一句话之前过一遍**，包括小智官方云那一路。
 *
 * 这不是重复劳动。手机的闸管不到"小智云直接把话画在屏上"那条路，
 * 而现场冒出一句"救援已在路上"是不可挽回的那种错。
 * 命中词表时把 out 写成兜底文案并返回 true（表示"我改过了"）。
 */
bool screen_link_guard_copy(const char *in, char *out, size_t out_n);

/** 词表本身要不要在屏上出现，由这个决定：闸自己的词表不是往屏上画的话 */
bool screen_link_copy_is_banned(const char *s);

#ifdef __cplusplus
}
#endif
