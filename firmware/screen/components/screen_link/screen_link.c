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
static volatile bool s_stopping = false;

/* 攒分片用的缓冲：只在 ws 回调线程里碰，所以不用锁 */
static char s_asm[SCREEN_LINK_MAX_FRAME];
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

    /* 申报而不是断言：省略 protocols 等于按 v1 待，这条默认值不能是本端最高档 */
    cJSON *protos = cJSON_CreateArray();
    cJSON_AddItemToObject(h, "protocols", protos);
    cJSON_AddItemToArray(protos, cJSON_CreateNumber(1));
    cJSON_AddItemToArray(protos, cJSON_CreateNumber(SCREEN_LINK_CONTRACT_MAX));

    if (send_json(root)) ESP_LOGI(TAG, "hello 已发：%s", s_cfg.screen_id);
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
            if (s_cb.on_state != NULL) s_cb.on_state(state, seq);
        }
    } else if (strcmp(t, "dialog") == 0) {
        const cJSON *d = cJSON_GetObjectItemCaseSensitive(root, "dialog");
        if (cJSON_IsObject(d) && s_cb.on_dialog != NULL) s_cb.on_dialog(d);
    } else if (strcmp(t, "event") == 0) {
        const cJSON *e = cJSON_GetObjectItemCaseSensitive(root, "event");
        if (cJSON_IsObject(e)) {
            const char *id = str_or(e, "event_id", "");
            /* 先 ack 再判重：App 3 秒收不到 ack 会重发，而重复的那条不该再弹一次 */
            screen_link_ack_event(seq, id);
            if (id[0] != '\0' && !event_seen(id) && s_cb.on_event != NULL) {
                s_cb.on_event(e, id, num_or(e, "ttl_ms", 0));
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

static void ws_handler(void *arg, esp_websocket_event_handle_t *evt,
                       esp_websocket_event_data_t *data)
{
    (void)arg;
    (void)data;
    switch (evt->event_id) {
    case WEBSOCKET_EVENT_CONNECTED:
        s_asm_len = 0;
        s_up = true;
        xEventGroupSetBits(s_eg, BIT_UP);
        break;
    case WEBSOCKET_EVENT_DISCONNECTED:
        s_up = false;
        xEventGroupSetBits(s_eg, BIT_DOWN);
        break;
    case WEBSOCKET_EVENT_DATA: {
        if (evt->data_len <= 0) break;
        if (s_asm_len + evt->data_len >= SCREEN_LINK_MAX_FRAME) {
            /* 超长帧：丢掉重来。宁可少画一帧，也不要越界写 */
            s_asm_len = 0;
            contract_error("<oversized frame>", "一帧超过 4KB，丢弃");
            break;
        }
        memcpy(s_asm + s_asm_len, evt->data_ptr, (size_t)evt->data_len);
        s_asm_len += evt->data_len;
        if (s_asm_len < (int)evt->payload_len) break;   /* 分片还没收齐 */

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

        esp_websocket_client_start(s_ws);
        EventBits_t bits = xEventGroupWaitBits(s_eg, BIT_UP | BIT_DOWN | BIT_STOP, pdTRUE, pdFALSE,
                                               portMAX_DELAY);
        if (bits & BIT_STOP) break;
        if (!(bits & BIT_UP)) continue;   /* 起来就断了：下一轮退避 */

        s_last_state_us = 0;
        step = 0;
        send_hello();
        if (s_cb.on_connected != NULL) s_cb.on_connected(s_negotiated);

        while (s_up && !s_stopping) {
            rx_item_t item;
            if (xQueueReceive(s_rx_q, &item, pdMS_TO_TICKS(250)) == pdTRUE) {
                handle_frame(item.json);
                free(item.json);
                continue;
            }
            if (screen_link_state_gap_s() > STATE_TIMEOUT_S) {
                ESP_LOGW(TAG, "state 超过 %d 秒没来，主动重连", STATE_TIMEOUT_S);
                esp_websocket_client_close(s_ws, pdMS_TO_TICKS(500));
                break;
            }
        }

        if (s_cb.on_disconnected != NULL) s_cb.on_disconnected();
        xEventGroupWaitBits(s_eg, BIT_DOWN, pdTRUE, pdFALSE, pdMS_TO_TICKS(3000));
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

    esp_websocket_client_config_t ws_cfg = {0};
    ws_cfg.uri = s_cfg.url;
    ws_cfg.buffer_size = SCREEN_LINK_MAX_FRAME;
    ws_cfg.task_stack = 8192;
    /* 阶梯退避要自己走，所以关掉库内那个固定间隔的自动重连 */
    ws_cfg.disable_auto_reconnect = true;
    ws_cfg.auto_reconnect = false;
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

bool screen_link_dialog_ready(void) { return s_up && s_negotiated >= 2; }
