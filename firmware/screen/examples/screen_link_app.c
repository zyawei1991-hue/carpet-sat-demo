/*
 * 接线示例：把 screen_link 挂进固件的 app 主循环。
 *
 * **这个文件不参与编译**（没进 CMakeLists 的 SRCS）。它的用处是照抄：
 * 板级那三样（屏怎么画、麦克风怎么取、按钮怎么读）每个工程都不一样，
 * 但链路侧该做的事全在这几行里。
 *
 * 验收三条（对应打通第 2 步）：
 *   1. 连上后 1Hz 有画面；
 *   2. 拔网线/关热点 3 秒 → 链路图标变灰、**保留最后一帧** + "链路中断 mm:ss"，不许黑屏；
 *   3. 10 秒 → 本组件自己断开，按 1/2/5/10/30s 退避重连。
 */
#include <stdio.h>
#include <string.h>

#include "screen_link.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

static const char *TAG = "screen_app";

/* 最新一帧状态的**副本**。链路任务在回调返回后就把那棵 cJSON 释放了，
 * 所以想跨帧读（UI 任务下一拍才画）必须自己 Duplicate 一份，别存原指针 */
static cJSON *s_state_copy = NULL;
static volatile bool s_dirty = false;
static uint32_t s_state_seq = 0;

static void on_state(const cJSON *state, uint32_t seq)
{
    cJSON *dup = cJSON_Duplicate(state, cJSON_TRUE);
    if (dup == NULL) return;           /* 拷不动就沿用上一帧，反正 1 秒后还有 */
    cJSON *old = s_state_copy;
    s_state_copy = dup;
    s_state_seq = seq;
    s_dirty = true;
    /* UI 任务：看到 s_dirty 就画，画完清 0，然后 cJSON_free 掉它拿走的那份由 UI 负责释放。
     * 更省事的做法是 UI 只读 s_state_copy 不释放，由这里在换帧时释放 —— 上面就是这么写的 */
}

static void on_dialog(const cJSON *dialog)
{
    const cJSON *dir = cJSON_GetObjectItemCaseSensitive(dialog, "dir");
    const cJSON *text = cJSON_GetObjectItemCaseSensitive(dialog, "text");
    const cJSON *src = cJSON_GetObjectItemCaseSensitive(dialog, "source");
    if (!cJSON_IsString(text)) return;

    char shown[256];
    /* 每一句上屏的话都过一遍闸，包括小智官方云那一路。这不是重复劳动 */
    screen_link_guard_copy(text->valuestring, shown, sizeof(shown));
    ESP_LOGI(TAG, "%s · %s · %s", cJSON_IsString(dir) ? dir->valuestring : "?",
             cJSON_IsString(src) ? src->valuestring : "?", shown);
    /* TODO：把 shown 画进对话列表。dir=down 且 turn_id 是本地起的那一轮 → 补在原问题下面 */
}

static void on_event(const cJSON *event, const char *event_id, uint32_t ttl_ms)
{
    (void)event_id;
    (void)ttl_ms;
    const cJSON *kind = cJSON_GetObjectItemCaseSensitive(event, "kind");
    ESP_LOGI(TAG, "event %s", cJSON_IsString(kind) ? kind->valuestring : "?");
    /* ack 已经由本组件回过了，这里只管弹提示 */
}

static void on_connected(int negotiated)
{
    ESP_LOGI(TAG, "已连上手机，契约 v%d%s", negotiated,
             negotiated >= 2 ? " · 可对话" : " · 只能看状态");
}

static void on_disconnected(void)
{
    ESP_LOGW(TAG, "链路断了，等退避重连");
    /* UI 在这那一刻切到"链路中断"水印，之后每秒按 state_gap 刷新秒数。
     * **不要清屏**：保留最后一帧是契约里写死的演示现场要求 */
}

static void on_contract_error(const char *raw, const char *reason)
{
    ESP_LOGE(TAG, "契约错：%s / %s", reason, raw);
}

void screen_app_start(void)
{
    screen_link_cfg_t cfg = {
        .url = CONFIG_SCREEN_LINK_URL,
        .screen_id = CONFIG_SCREEN_LINK_ID,
        .fw = CONFIG_SCREEN_LINK_FW,
        .dock_type = CONFIG_SCREEN_LINK_DOCK_TYPE,
    };
    screen_link_callbacks_t cb = {
        .on_state = on_state,
        .on_dialog = on_dialog,
        .on_event = on_event,
        .on_connected = on_connected,
        .on_disconnected = on_disconnected,
        .on_contract_error = on_contract_error,
    };
    if (screen_link_start(&cfg, &cb) != ESP_OK) ESP_LOGE(TAG, "链路起不来");
}

/* 麦克风那段（小智的 ASR 已经给了文字）往手机推。走的是和"手机里问"同一条记录 */
void screen_app_ask(const char *asr_text)
{
    char turn[32];
    esp_err_t err = screen_link_send_dialog_up(asr_text, "asr", turn, sizeof(turn));
    if (err != ESP_OK) {
        /* 没连着 / 没谈到 v2：屏上这句话还是要画，但要标"未送达手机" ——
         * 不然用户以为问了，其实没人听见 */
        ESP_LOGW(TAG, "这句没送出去：%d", err);
        return;
    }
    ESP_LOGI(TAG, "已推给手机，turn_id=%s", turn);
}

/* SOS 那颗键长按到底之后走这条：触摸事件上行，App 侧才会真去排队发报文 */
void screen_app_sos_pressed(void)
{
    cJSON *payload = cJSON_CreateObject();
    cJSON_AddStringToObject(payload, "from", "screen_sos_key");
    /* ttl 给 30 秒：过顶窗口之外这条只能排队，说清"多久之内算数"比"发出去了"要紧 */
    screen_link_send_event("sos_press", payload, 30000);
}
