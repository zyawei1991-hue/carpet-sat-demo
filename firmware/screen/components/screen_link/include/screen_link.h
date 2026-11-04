/**
 * Screen-Link 展示端客户端（契约 v2）。
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

/** 本固件申报的最高契约档。v1→v2 只多一种 dialog 帧，其余一字节未变 */
#define SCREEN_LINK_CONTRACT_MAX 2

/** 一条 state 帧在 1Hz 下最大约 1.2KB，留 4 倍余量给以后加的字段 */
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
    void (*on_ack)(const cJSON *ack);
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
