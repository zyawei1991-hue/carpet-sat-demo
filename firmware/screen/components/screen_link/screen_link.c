/*
 * Screen-Link 展示端客户端实现。契约见 protocol/screen-link.md。
 *
 * 三条不成文的规矩，写代码时容易破，先列在这：
 * 1. **退避阶梯由本文件掌握**，不用 esp_websocket_client 自带的自动重连 ——
 *    它只有一个固定间隔，而契约要 1/2/5/10/30s 封顶。
 * 2. **每次连上都要重发 hello**。手机侧的白名单校验只发生在 hello 之后，
 *    不重发就等于一条"连着但收不到东西"的僵尸连接。
 * 3. **ws 回调线程里不解析 JSON**。分片攒齐就丢进队列，解析在链路任务里做，
 *    否则一次 cJSON 慢过 1Hz 推送，链路图标会莫名变灰。
 */
#include "screen_link.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_mac.h"
#include "esp_timer.h"
#include "esp_websocket_client.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "freertos/task.h"

static const char *TAG = "screen_link";

/* 契约规定的重连退避，最后一档封顶。单位秒 */
static const uint32_t BACKOFF_S[] = {1, 2, 5, 10, 30};
#define BACKOFF_STEPS ((int)(sizeof(BACKOFF_S) / sizeof(BACKOFF_S[0])))

/* 断线时先等这么多秒再重连；连着的时候 state 超过这么多秒没更新就主动断开 */
#define STATE_TIMEOUT_S 10

#define BIT_UP BIT0
#define BIT_DOWN BIT1
#define BIT_STOP BIT2

/* event_id 去重环形缓冲。契约说留 32 个，够小 */
#define DEDUP_RING 32
#define EVENT_ID_MAX 40

typedef struct {
    char *json;
} rx_item_t;

static screen_link_cfg_t s_cfg;
static screen_link_callbacks_t s_cb;
static esp_websocket_client_handle_t s_ws = NULL;
static EventGroupHandle_t s_eg = NULL;
static QueueHandle_t s_rx_q = NULL;
static TaskHandle_t s_task = NULL;

static volatile bool s_up = false;
/*
 * 本会话按哪一档说话。ack 帧里没有版本字段（契约的协商看 hello.protocols，不回读），
 * 所以这里只能取"我申报的那一档"。代价：连到只会 v1 的老 App 时，屏会以为能说话却收不到答。
 * UI 侧要按"这句问出去 3 秒没答就标未接"兜这一条，别指望这里能提前知道。
 */
static volatile int s_negotiated = SCREEN_LINK_CONTRACT_MAX;
static volatile int64_t s_last_state_us = 0;
/**
 * 看门狗踢过几次。放这里是为了让它能从串口被称出来：
 * "我改了超时判断"和"那条半开链路真的会在 10 秒内自愈"是两件事，
 * 只有这个数能证明是它起的作用，而不是又一次复位。
 */
static volatile uint32_t s_wd_fires = 0;
/**
 * 上一帧 state 里手机给的 ts（Unix 毫秒）。0 = 这轮会话还没收到过 state。
 *
 * 这值由链路任务写、UI 任务读，而 S3 上 64 位读写不原子 —— 一次读到"半新半旧"就是
 * 屏上的钟跳一分钟。所以它配一把自旋锁，读写都在里面。旁边那个 s_last_state_us 没有这个
 * 待遇不是漏了：它偏一点只让 gap 的秒数抖一下，看不出来
 */
static volatile int64_t s_state_ts_ms = 0;
static portMUX_TYPE s_ts_mux = portMUX_INITIALIZER_UNLOCKED;
static volatile bool s_stopping = false;

/* 攒分片用的缓冲：只在 ws 回调线程里碰，所以不用锁。
 * 这 4KB 明确要 PSRAM，不留在 .bss：.bss 的每一字节都是从内部 RAM 里挖的，而 Muse
 * 这一版的内部 RAM 只剩十几 KB、最大连续块 4K —— esp_websocket_client 那个任务的栈
 * 就是从内部 RAM 要的连续块，2026-11-05 真机就是因为这个开不出来（见 link_task 的注释）。 */
static char *s_asm;
static int s_asm_len = 0;

static char s_seen[DEDUP_RING][EVENT_ID_MAX];
static int s_seen_n = 0;

static uint32_t s_seq = 0;
static uint32_t s_boot_tag = 0;
static uint32_t s_turn_n = 0;

static uint32_t next_seq(void) { return ++s_seq; }

static int64_t now_us(void) { return esp_timer_get_time(); }

static const char *str_or(const cJSON *obj, const char *key, const char *fallback)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (cJSON_IsString(item) && item->valuestring != NULL) return item->valuestring;
    return fallback;
}

static uint32_t num_or(const cJSON *obj, const char *key, uint32_t fallback)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (cJSON_IsNumber(item)) return (uint32_t)item->valuedouble;
    return fallback;
}

/* Unix 毫秒是 13 位数，塞不进 uint32_t。
 * `ts` 以前走的是上面那个 num_or，`(uint32_t)valuedouble` 把它绕回 49.7 天以内 ——
 * 于是钟面画的是 1970 年 1~2 月里的某个时刻，而串口日志里这个数看着仍是个正常正数。
 * 大数一律走这里，别再拿 32 位的尺去量。 */
static int64_t i64_or(const cJSON *obj, const char *key, int64_t fallback)
{
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(obj, key);
    if (cJSON_IsNumber(item)) return (int64_t)item->valuedouble;
    return fallback;
}

static bool send_json(cJSON *root)
{
    char *text = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    if (text == NULL) return false;
    bool ok = false;
    if (s_up && s_ws != NULL) {
        int sent = esp_websocket_client_send_text(s_ws, text, (int)strlen(text), pdMS_TO_TICKS(2000));
        ok = (sent == (int)strlen(text));
        if (!ok) ESP_LOGW(TAG, "帧没发完：%d / %u", sent, (unsigned)strlen(text));
    }
    free(text);
    return ok;
}

static void contract_error(const char *raw, const char *reason)
{
    ESP_LOGE(TAG, "契约错：%s", reason);
    if (s_cb.on_contract_error != NULL) s_cb.on_contract_error(raw, reason);
}

/* ---------------- 出站帧 ---------------- */

static void send_hello(void)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "hello");
    cJSON_AddNumberToObject(root, "seq", next_seq());

    cJSON *h = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "hello", h);
    cJSON_AddStringToObject(h, "screen_id", s_cfg.screen_id);
    cJSON_AddStringToObject(h, "fw", s_cfg.fw);
    cJSON_AddStringToObject(h, "dock_type", s_cfg.dock_type);

    /* 电量：没读出来就整项不发，不要发 0 —— 手机那边 0V 和"没装电池"是两回事，
       而 Kotlin 侧对 null 的显示是"外接供电"，这才是我们要的那句话 */
    int mv = s_cfg.read_battery_mv != NULL ? s_cfg.read_battery_mv() : -1;
    if (mv > 0) cJSON_AddNumberToObject(h, "battery_mv", mv);

    /* 申报而不是断言：省略 protocols 等于按 v1 待，这条默认值不能是本端最高档 */
    cJSON *protos = cJSON_CreateArray();
    cJSON_AddItemToObject(h, "protocols", protos);
    cJSON_AddItemToArray(protos, cJSON_CreateNumber(1));
    cJSON_AddItemToArray(protos, cJSON_CreateNumber(SCREEN_LINK_CONTRACT_MAX));

    /* v3：屏幕几何。填了 w/h 才发这一项 —— 老服务端解不了也不报错（它按 schema 的
       "可选"处理），而"没申报"和"报了一个编的尺寸"是两件完全不同的事：
       前者手机按默认排版，后者会在圆屏上少半个字。 */
    if (s_cfg.screen_w > 0 && s_cfg.screen_h > 0) {
        cJSON *g = cJSON_CreateObject();
        cJSON_AddItemToObject(h, "screen", g);
        cJSON_AddNumberToObject(g, "w", s_cfg.screen_w);
        cJSON_AddNumberToObject(g, "h", s_cfg.screen_h);
        cJSON_AddBoolToObject(g, "round", s_cfg.screen_round);
        cJSON_AddBoolToObject(g, "touch", s_cfg.screen_touch);
        if (s_cfg.screen_ppi > 0) cJSON_AddNumberToObject(g, "ppi", s_cfg.screen_ppi);
    }

    if (!send_json(root)) return;
    if (mv > 0) {
        ESP_LOGI(TAG, "hello 已发：%s  电量 %dmV", s_cfg.screen_id, mv);
    } else {
        ESP_LOGI(TAG, "hello 已发：%s  电量未知", s_cfg.screen_id);
    }
}

static void send_bye(void)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "bye");
    cJSON_AddNumberToObject(root, "seq", next_seq());
    send_json(root);
}

esp_err_t screen_link_send_dialog_up(
    const char *text, const char *source, char *turn_id_out, size_t turn_id_out_n)
{
    if (text == NULL || text[0] == '\0') return ESP_ERR_INVALID_ARG;
    if (!screen_link_dialog_ready()) return ESP_ERR_INVALID_STATE;

    char turn[32];
    snprintf(turn, sizeof(turn), "s-%06lx-%lu", (unsigned long)(s_boot_tag & 0xffffffUL),
             (unsigned long)(++s_turn_n));
    if (turn_id_out != NULL && turn_id_out_n > 0) {
        strncpy(turn_id_out, turn, turn_id_out_n - 1);
        turn_id_out[turn_id_out_n - 1] = '\0';
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "dialog");
    cJSON_AddNumberToObject(root, "seq", next_seq());
    cJSON_AddNumberToObject(root, "ts", (double)time(NULL));

    cJSON *d = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "dialog", d);
    cJSON_AddStringToObject(d, "turn_id", turn);
    cJSON_AddStringToObject(d, "dir", "up");
    cJSON_AddStringToObject(d, "text", text);
    /* source 和 origin 都要显式写。手机端出站走 encodeDefaults=false 会省掉缺省值，
     * 屏这边再跟着省，手机就会把"屏说的"读成"手机说的" */
    cJSON_AddStringToObject(d, "source", source != NULL ? source : "asr");
    cJSON_AddStringToObject(d, "origin", "screen");

    return send_json(root) ? ESP_OK : ESP_FAIL;
}

esp_err_t screen_link_ack_event(uint32_t for_seq, const char *event_id)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "ack");
    cJSON_AddNumberToObject(root, "seq", next_seq());

    cJSON *a = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "ack", a);
    cJSON_AddNumberToObject(a, "for_seq", for_seq);
    if (event_id != NULL) cJSON_AddStringToObject(a, "for_event_id", event_id);

    return send_json(root) ? ESP_OK : ESP_FAIL;
}

esp_err_t screen_link_ack_command(uint32_t for_seq, const char *error)
{
    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "ack");
    cJSON_AddNumberToObject(root, "seq", next_seq());

    cJSON *a = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "ack", a);
    cJSON_AddNumberToObject(a, "for_seq", for_seq);
    if (error != NULL) cJSON_AddStringToObject(a, "error", error);

    return send_json(root) ? ESP_OK : ESP_FAIL;
}

esp_err_t screen_link_send_command(const char *cmd, cJSON *args)
{
    /* 合法值抄自 protocol/screen-link.schema.json 的 command.cmd 枚举
     * 和 core/.../protocol/ScreenLink.kt 的 CommandType。多一个、少一个、拼错一个都不行：
     * 手机端解不开入站帧的处理是**关连接、一句话不说**，屏这边的现象是"按了没反应、过几秒掉线"。
     * 所以这一关在装配帧之前，非法值当场退回，绝不上线。
     * card_next / card_auto / poke 是 #39 加的三条**请求**：屏不指定画哪张卡，
     * 也不指定画哪张脸，只把"用户在这块玻璃上做了什么"告诉手机，由手机算出结果再下发。
     * page_report 是那一刀切开的反面：**它不是请求，是通报**。哪一页归屏自己定（触摸驱动，
     * 不等手机往返），报上来只是让手机上那块镜像别再替屏编位置。手机端不许驳回这条。
     * core 的 SchemaDriftTest 会把这张表和 schema 的 enum 逐条比 */
    static const char *const ALLOWED[] = {
        "sos_press", "sos_cancel", "track_pull", "set_demo_mode", "set_fence", "reboot_ui",
        "card_next", "card_auto", "poke", "page_report",
    };
    bool ok = false;
    for (int i = 0; i < (int)(sizeof(ALLOWED) / sizeof(ALLOWED[0])); i++) {
        if (cmd != NULL && strcmp(cmd, ALLOWED[i]) == 0) { ok = true; break; }
    }
    if (!ok) {
        cJSON_Delete(args);
        return ESP_ERR_INVALID_ARG;
    }

    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "command");
    cJSON_AddNumberToObject(root, "seq", next_seq());
    cJSON_AddNumberToObject(root, "ts", (double)time(NULL));

    cJSON *c = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "command", c);
    cJSON_AddStringToObject(c, "cmd", cmd);
    /* args 在 schema 里不是必填，但手机端 CommandPayload.args 有缺省值；
     * 给一个空对象比省略更保险 —— 省略时 kotlinx 那边 decodeDefaults 的行为和屏这边不一样 */
    cJSON_AddItemToObject(c, "args", args != NULL ? args : cJSON_CreateObject());

    return send_json(root) ? ESP_OK : ESP_FAIL;
}

esp_err_t screen_link_send_event(const char *kind, cJSON *payload, uint32_t ttl_ms)
{
    if (kind == NULL) {
        cJSON_Delete(payload);
        return ESP_ERR_INVALID_ARG;
    }
    char event_id[EVENT_ID_MAX];
    snprintf(event_id, sizeof(event_id), "scr-%08lx-%lu", (unsigned long)s_boot_tag,
             (unsigned long)(++s_turn_n));

    cJSON *root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "v", SCREEN_LINK_CONTRACT_MAX);
    cJSON_AddStringToObject(root, "t", "event");
    cJSON_AddNumberToObject(root, "seq", next_seq());
    cJSON_AddNumberToObject(root, "ts", (double)time(NULL));

    cJSON *e = cJSON_CreateObject();
    cJSON_AddItemToObject(root, "event", e);
    cJSON_AddStringToObject(e, "kind", kind);
    cJSON_AddStringToObject(e, "event_id", event_id);
    cJSON_AddNumberToObject(e, "ttl_ms", ttl_ms);
    if (payload != NULL) cJSON_AddItemToObject(e, "payload", payload);

    return send_json(root) ? ESP_OK : ESP_FAIL;
}

/* ---------------- 入站帧 ---------------- */

static bool event_seen(const char *id)
{
    for (int i = 0; i < DEDUP_RING; i++) {
        if (s_seen[i][0] && strcmp(s_seen[i], id) == 0) return true;
    }
    strncpy(s_seen[s_seen_n % DEDUP_RING], id, EVENT_ID_MAX - 1);
    s_seen[(s_seen_n++) % DEDUP_RING][EVENT_ID_MAX - 1] = '\0';
    return false;
}

/* ttl 从**手机发帧那一刻**起算，而这条链路没有本地 RTC 可对表。
 * 唯一能用的"现在"是上一帧 state 的手机 ts 加上它过了多久 —— 两个数走的是同一条链路，
 * 所以误差最多一帧（1 秒）。判"这条是不是 3 秒前就该弹的"够用，拿来显示倒计时不够。
 * 估不出来（这轮会话还没收到 state、或帧里没有 ts）返回 -1，调用方按"不过期"放行：
 * 把一条真事件丢掉，比多弹一条过期提示更糟 —— 现场丢的那条可能就是「卫星过境了」 */
static int64_t event_elapsed_ms(int64_t frame_ts_ms)
{
    if (frame_ts_ms <= 0) return -1;
    int64_t base;
    taskENTER_CRITICAL(&s_ts_mux);
    base = s_state_ts_ms;
    taskEXIT_CRITICAL(&s_ts_mux);
    if (base <= 0) return -1;
    int64_t e = base + (int64_t)screen_link_state_age_ms() - frame_ts_ms;
    return e > 0 ? e : 0;      /* 手机两帧之间顺序不保证：未来的 ts 当 0 秒算 */
}

static void handle_frame(char *raw)
{
    cJSON *root = cJSON_Parse(raw);
    if (root == NULL) {
        contract_error(raw, "json 解不开");
        /* 解不开的帧说明两边对契约的理解已经分叉，继续连着只会一直错下去 */
        esp_websocket_client_close(s_ws, pdMS_TO_TICKS(500));
        return;
    }
    const char *t = str_or(root, "t", "");
    uint32_t seq = num_or(root, "seq", 0);

    if (strcmp(t, "state") == 0) {
        const cJSON *state = cJSON_GetObjectItemCaseSensitive(root, "state");
        if (!cJSON_IsObject(state)) {
            contract_error(raw, "state 帧里没有 state 对象");
        } else {
            s_last_state_us = now_us();
            /* ts 是整帧上的字段，不在 state 对象里（schema 的 ScreenMessage.ts）。
             * 钟面要用手机的时间，而回调只拿到 state 那一层，所以在这里顺手记下来 */
            taskENTER_CRITICAL(&s_ts_mux);
            s_state_ts_ms = i64_or(root, "ts", 0);
            taskEXIT_CRITICAL(&s_ts_mux);
            if (s_cb.on_state != NULL) s_cb.on_state(state, seq);
        }
    } else if (strcmp(t, "dialog") == 0) {
        const cJSON *d = cJSON_GetObjectItemCaseSensitive(root, "dialog");
        if (cJSON_IsObject(d) && s_cb.on_dialog != NULL) s_cb.on_dialog(d);
    } else if (strcmp(t, "event") == 0) {
        const cJSON *e = cJSON_GetObjectItemCaseSensitive(root, "event");
        if (cJSON_IsObject(e)) {
            const char *id = str_or(e, "event_id", "");
            /* 先 ack 再判重：App 3 秒收不到 ack 会重发，而重复的那条不该再弹一次。
             * ack 也在过期判定之前：这条屏**收到了**，只是不该再画出来，
             * 不 ack 的话手机会当成没送达、白重发两轮 */
            screen_link_ack_event(seq, id);
            uint32_t ttl = num_or(e, "ttl_ms", 0);
            int64_t fts = i64_or(root, "ts", 0);
            int64_t elapsed = event_elapsed_ms(fts);
            if (ttl > 0 && elapsed >= (int64_t)ttl) {
                ESP_LOGI(TAG, "event %s 过期 %.1fs，不弹", id, (double)(elapsed - ttl) / 1000.0);
            } else if (id[0] != '\0' && !event_seen(id) && s_cb.on_event != NULL) {
                /* 交出去的是**还剩**多少毫秒，不是帧里的 ttl：过期判定已经做完了，
                 * 再让调用方拿原始 ttl 自己减一遍就是两份时钟算术 */
                uint32_t left = (ttl > 0 && elapsed >= 0) ? (uint32_t)(ttl - elapsed) : ttl;
                s_cb.on_event(e, id, left);
            }
        }
    } else if (strcmp(t, "command") == 0) {
        const cJSON *c = cJSON_GetObjectItemCaseSensitive(root, "command");
        if (cJSON_IsObject(c) && s_cb.on_command != NULL) {
            s_cb.on_command(str_or(c, "cmd", ""), cJSON_GetObjectItemCaseSensitive(c, "args"));
        }
    } else if (strcmp(t, "ack") == 0) {
        const cJSON *a = cJSON_GetObjectItemCaseSensitive(root, "ack");
        if (cJSON_IsObject(a) && s_cb.on_ack != NULL) s_cb.on_ack(a);
    } else {
        contract_error(raw, "不认识的帧类型");
        esp_websocket_client_close(s_ws, pdMS_TO_TICKS(500));
    }
    cJSON_Delete(root);
}

/* ---------------- ws 回调 ---------------- */

/* 签名不是猜的，是对着组件头文件定的：esp_websocket_register_events 收的是
 * IDF 通用 esp_event_handler_t（四个参数），配置结构体里**没有** event_handler 字段，
 * `esp_websocket_event_handle_t` 这个类型也不存在 —— 1.5.0 到 1.8.0 全是这样。
 * event_id 从第三个参数来，帧数据从第四个参数 cast 出来的 esp_websocket_event_data_t 取。 */
static void ws_handler(void *arg, esp_event_base_t event_base, int32_t event_id,
                       void *event_data)
{
    (void)arg;
    (void)event_base;
    esp_websocket_event_data_t *evt = (esp_websocket_event_data_t *)event_data;

    switch (event_id) {
    case WEBSOCKET_EVENT_CONNECTED:
        ESP_LOGI(TAG, "ws 接入 %s", s_cfg.url);
        s_asm_len = 0;
        s_up = true;
        xEventGroupSetBits(s_eg, BIT_UP);
        break;
    case WEBSOCKET_EVENT_DISCONNECTED:
        /* 连不上时也走这一条：组件里 abort_connection 先派 DISCONNECTED（它自己不打日志），
         * ERROR 只在有错误正文时才有。2026-11-05 就是被这条日志缺失坑的 —— 串口里 40 秒
         * 一个字没有，分不清"任务活着但在撞防火墙"和"任务压根没建起来"。 */
        ESP_LOGW(TAG, "ws 断开/未接通，下一次按退避阶梯再来");
        s_up = false;
        xEventGroupSetBits(s_eg, BIT_DOWN);
        break;
    case WEBSOCKET_EVENT_ERROR:
        if (evt->data_len > 0 && evt->data_ptr != NULL) {
            ESP_LOGW(TAG, "ws 错误：%.*s", (int)evt->data_len, (char *)evt->data_ptr);
        }
        break;
    case WEBSOCKET_EVENT_DATA: {
        if (evt->data_len <= 0) break;
        /* 只收 TEXT 和它的续片。以前这里不看 op_code：服务端关连接前发的那条 Close 帧，
         * 它的 reason（比如 "bad contract"）会被当成正文拼进 s_asm 并 dispatch 出去，
         * 屏侧就看到三条 `契约错：json 解不开` 而 raw 根本不是 JSON —— 真机实测到了。
         * 危害不只是难看：它会把**真正的**契约错盖在一堆假错里。
         * 常量名是 IDF 的 `esp_transport_ws.h` 里那套（WS_TRANSPORT_OPCODES_*），
         * 不是 RFC6455 文档里的 WEBSOCKET_OPCODE_* —— 后者在这份头文件里根本不存在，
         * 编译器直接报 undeclared，别照网络示例抄。文本=0x01，续片=0x00。 */
        if (evt->op_code != WS_TRANSPORT_OPCODES_TEXT
            && evt->op_code != WS_TRANSPORT_OPCODES_CONT) {
            ESP_LOGD(TAG, "忽略非文本帧 op_code=%d len=%d", (int)evt->op_code, (int)evt->data_len);
            break;
        }
        if (s_asm_len + evt->data_len >= SCREEN_LINK_MAX_FRAME) {
            /* 超长帧：丢掉重来。宁可少画一帧，也不要越界写 */
            s_asm_len = 0;
            contract_error("<oversized frame>", "一帧超过 4KB，丢弃");
            break;
        }
        memcpy(s_asm + s_asm_len, evt->data_ptr, (size_t)evt->data_len);
        s_asm_len += evt->data_len;
        /* 一条 WS 帧的正文如果跨了 TCP 段，esp_websocket_client 会把**同一帧**拆成好几个
         * WEBSOCKET_EVENT_DATA 往外发（源码 esp_websocket_client.c 里那个
         * `do { ...dispatch_event(DATA, rx_buffer, rlen); payload_offset += rlen; }
         *    while (payload_offset < payload_len)` 循环），而每个分片的 `fin` 都是
         * 从 transport 现取的同一个值 —— 也就是**每个分片都写着 fin=true**。
         * 只看 fin 就会在第一个分片上 dispatch 半条 JSON。
         * 2026-11-01 真机抓到：一条 607 字节的 state 拆成 566+41，屏侧连打两条
         * `契约错：json 解不开`，raw 正好互补拼成一条完整帧。
         * 所以判"这条消息收完"要两条一起看：这一帧收满（offset+本片长度 == 本帧总长），
         * 而且它是消息的最后一帧（fin）。payload_offset 是**本片之前**的偏移。 */
        if (evt->payload_offset + evt->data_len < evt->payload_len) break;   /* 本帧没收满 */
        if (!evt->fin) break;                                               /* 消息还有后续帧 */

        char *copy = (char *)malloc((size_t)s_asm_len + 1);
        if (copy == NULL) {
            s_asm_len = 0;
            break;
        }
        memcpy(copy, s_asm, (size_t)s_asm_len);
        copy[s_asm_len] = '\0';
        s_asm_len = 0;

        rx_item_t item = { .json = copy };
        if (xQueueSend(s_rx_q, &item, 0) != pdTRUE) {
            free(copy);   /* 队列满：丢这一帧。1Hz 的下一帧一秒后就来 */
        }
        break;
    }
    default:
        break;
    }
}

/* ---------------- 链路任务 ---------------- */

static void sleep_backoff(uint32_t seconds)
{
    for (uint32_t i = 0; i < seconds * 10 && !s_stopping; i++) {
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

static void link_task(void *arg)
{
    (void)arg;
    int step = 0;

    for (;;) {
        if (s_stopping) break;

        /* start() 自己建那个 ws 任务，而它的栈要从**内部 RAM** 要一块连续的 —— 组件没有
         * 把栈放 PSRAM 的开关（这个版本的 config 里就没有 task_stack_in_psram 这一项）。
         * 2026-11-05 真机抓到 "Error create websocket task"：那会儿内部 RAM 最大连续块
         * 只剩 4K。以前这里不看返回值，失败后直接撞进下面那个 portMAX_DELAY 死等，
         * UP/DOWN 两个位再没人置过，退避阶梯一次都没跑 —— 表现是"永远连不上，且再也不试"。
         * 重复 start() 是安全的：create_transport 进来先把上一轮的 transport_list 销毁。 */
        if (esp_websocket_client_start(s_ws) != ESP_OK) {
            if (s_stopping) break;
            sleep_backoff(BACKOFF_S[step]);
            if (step < BACKOFF_STEPS - 1) step++;
            continue;
        }
        EventBits_t bits = xEventGroupWaitBits(s_eg, BIT_UP | BIT_DOWN | BIT_STOP, pdTRUE, pdFALSE,
                                               portMAX_DELAY);
        if (bits & BIT_STOP) break;

        if (bits & BIT_UP) {
            s_last_state_us = 0;
            /* 首帧的截止线：连上那一刻。下面只看 s_last_state_us 会瞎 —— 见那里的注释 */
            int64_t up_us = now_us();
            taskENTER_CRITICAL(&s_ts_mux);
            s_state_ts_ms = 0;
            taskEXIT_CRITICAL(&s_ts_mux);
            step = 0;
            send_hello();
            if (s_cb.on_connected != NULL) s_cb.on_connected(s_negotiated);

            while (s_up && !s_stopping) {
                rx_item_t item;
                bool got = (xQueueReceive(s_rx_q, &item, pdMS_TO_TICKS(250)) == pdTRUE);
                if (got) {
                    handle_frame(item.json);
                    free(item.json);
                }
                /* 超时判断每轮都跑，不再躲在"这一轮什么都没收到"后面。
                 * gap 只在收到 t=="state" 那一帧时才更新（handle_frame 里 s_last_state_us），
                 * 而 event / ack / dialog 那些帧不算数 —— 所以旧写法下服务端只要还在发
                 * 非 state 帧，这个循环就一路 continue，看门狗永远轮不到。
                 * 2026-11-04 真机踩到：linked 一直报 1、gap_s 涨到 565 秒不重连，
                 * 屏上按哪条都发不出去（发送失败，sent 不涨），表现就是"按了没反应"。 */
                /* 对端已经把 socket 关掉时 s_up 是 false —— 那是正常断线，不是看门狗踢的。
                 * 以前不判这一条：每一次服务端下线都会让 wd +1，
                 * "自愈到底踢过几次"这个数就完全没有参考价值了。 */
                if (!s_up) break;
                uint32_t gap = screen_link_state_gap_s();
                /* gap_s() 在"连上了但一帧 state 都没来过"时**返回 0**（screen_link_state_gap_s
                 * 里那句"别一上来就报灰"），所以它自己堵不住首帧永不到这种死法。
                 * 2026-11-04 半开探针实测：服务端只发 ack、永不发 state 时，
                 * 屏上 linked=1、gap_s=0 卡了 45 秒不重连，还挂着上一轮的旧值 ——
                 * 这就是"按了没反应"的真身。这里改用"连上到现在"兜底。 */
                if (s_last_state_us == 0) {
                    gap = (uint32_t)((now_us() - up_us) / 1000000);
                }
                if (gap > STATE_TIMEOUT_S) {
                    s_wd_fires++;
                    ESP_LOGW(TAG, "state 超过 %d 秒没来（gap=%lu 秒），主动重连 第%lu 次",
                             STATE_TIMEOUT_S, (unsigned long)gap,
                             (unsigned long)s_wd_fires);
                    esp_websocket_client_close(s_ws, pdMS_TO_TICKS(500));
                    break;
                }
            }

            if (s_cb.on_disconnected != NULL) s_cb.on_disconnected();
            xEventGroupWaitBits(s_eg, BIT_DOWN, pdTRUE, pdFALSE, pdMS_TO_TICKS(3000));
        }
        /* 这条 else 分支以前是一行 `continue`，退避根本没跑到就撞回 start()。
         * "服务端还没起来"是演示现场最常见的一种断法，实测那时每 10 秒撞一次
         * （10 秒 = esp-tls 的 connect 超时，不是阶梯），永远升不到 30 秒封顶。
         * 现在两条失败出口共用同一个阶梯。*/
        if (s_stopping) break;

        sleep_backoff(BACKOFF_S[step]);
        if (step < BACKOFF_STEPS - 1) step++;
    }

    s_task = NULL;
    vTaskDelete(NULL);
}

/* ---------------- 公共 API ---------------- */

esp_err_t screen_link_start(const screen_link_cfg_t *cfg, const screen_link_callbacks_t *cb)
{
    if (cfg == NULL || cfg->url == NULL || cfg->screen_id == NULL) return ESP_ERR_INVALID_ARG;
    if (s_ws != NULL) return ESP_ERR_INVALID_STATE;

    s_cfg = *cfg;
    if (cb != NULL) s_cb = *cb;

    uint8_t mac[6] = {0};
    esp_read_mac(mac, ESP_MAC_WIFI_STA);
    s_boot_tag = (uint32_t)((esp_timer_get_time() & 0xffff) ^ ((uint32_t)mac[2] << 16) ^
                            ((uint32_t)mac[3] << 8) ^ (uint32_t)mac[4]);

    s_eg = xEventGroupCreate();
    s_rx_q = xQueueCreate(8, sizeof(rx_item_t));
    if (s_eg == NULL || s_rx_q == NULL) return ESP_ERR_NO_MEM;

    /* 攒分片那 4KB 从 PSRAM 要（理由见上面 s_asm 的注释）。要不到就明确失败，
     * 不悄悄退回 .bss —— 那正是这次把链路饿死的形状。 */
    s_asm = heap_caps_malloc(SCREEN_LINK_MAX_FRAME, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (s_asm == NULL) return ESP_ERR_NO_MEM;

    esp_websocket_client_config_t ws_cfg = {0};
    ws_cfg.uri = s_cfg.url;
    ws_cfg.buffer_size = SCREEN_LINK_MAX_FRAME;
    /* 4096 = 组件自己在 task_stack 不给时的默认档（esp_websocket_client.c 里
     * WEBSOCKET_TASK_STACK）。这里原来是 8192：内部 RAM 要一整块 8K，真机上开不出来。
     * 走的是明文 ws://，没有 TLS 握手要吃栈，4K 这一档就是它设计的用途。 */
    ws_cfg.task_stack = 4096;
    /* 阶梯退避要自己走，所以关掉库内那个固定间隔的自动重连。
     * 头文件里没有 auto_reconnect 这个字段（只有 disable_auto_reconnect / enable_close_reconnect），
     * 写了编不过；reconnect_timeout_ms 在 disable_auto_reconnect=true 时不起作用，留着只为以后好读。 */
    ws_cfg.disable_auto_reconnect = true;
    ws_cfg.reconnect_timeout_ms = 1000;

    s_ws = esp_websocket_client_init(&ws_cfg);
    if (s_ws == NULL) return ESP_FAIL;
    esp_websocket_register_events(s_ws, WEBSOCKET_EVENT_ANY, ws_handler, s_ws);

    BaseType_t ok = xTaskCreate(link_task, "screen_link", 8192, NULL, 5, &s_task);
    return ok == pdPASS ? ESP_OK : ESP_ERR_NO_MEM;
}

esp_err_t screen_link_stop(void)
{
    if (s_ws == NULL) return ESP_ERR_INVALID_STATE;
    s_stopping = true;
    send_bye();
    xEventGroupSetBits(s_eg, BIT_STOP);
    esp_websocket_client_close(s_ws, pdMS_TO_TICKS(500));
    /* 等链路任务自己退出：不等也行，但 esp_websocket_client_cleanup 会撞正在跑的任务 */
    for (int i = 0; i < 40 && s_task != NULL; i++) vTaskDelay(pdMS_TO_TICKS(50));
    esp_websocket_client_destroy(s_ws);
    s_ws = NULL;
    free(s_asm);
    s_asm = NULL;
    s_asm_len = 0;
    s_up = false;
    s_stopping = false;
    return ESP_OK;
}

bool screen_link_connected(void) { return s_up; }

int screen_link_negotiated(void) { return s_negotiated; }

uint32_t screen_link_state_gap_s(void)
{
    if (!s_up) return UINT32_MAX;
    int64_t last = s_last_state_us;
    if (last == 0) return 0;   /* 刚连上，还没到第一帧：别一上来就报灰 */
    return (uint32_t)((now_us() - last) / 1000000);
}

uint32_t screen_link_watchdog_fires(void)
{
    return s_wd_fires;
}

uint32_t screen_link_state_age_ms(void)
{
    if (!s_up) return UINT32_MAX;
    int64_t last = s_last_state_us;
    if (last == 0) return 0;   /* 刚连上、第一帧还没到：和 gap_s 同一个口径，别一上来就报灰 */
    return (uint32_t)((now_us() - last) / 1000);
}

int64_t screen_link_state_ts_ms(void)
{
    taskENTER_CRITICAL(&s_ts_mux);
    int64_t ts = s_state_ts_ms;
    taskEXIT_CRITICAL(&s_ts_mux);
    return ts;
}

bool screen_link_dialog_ready(void) { return s_up && s_negotiated >= 2; }
