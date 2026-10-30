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
    /** 已经按 event_id 去过重：重发的那条不会再回调一次，但 ack 已经回了 */
    void (*on_event)(const cJSON *event, const char *event_id, uint32_t ttl_ms);
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
 */
uint32_t screen_link_state_gap_s(void);

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

/** 屏侧发起的触摸事件（SOS 长按那一路）。payload 所有权交给本函数，失败时也已被释放 */
esp_err_t screen_link_send_event(const char *kind, cJSON *payload, uint32_t ttl_ms);

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
