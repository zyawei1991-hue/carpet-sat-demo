/*
 * 接线示例：把 screen_link 挂进固件的 app 主循环。
 *
 * **这个文件不参与编译**（没进 CMakeLists 的 SRCS）。它的用处是照抄：
 * 板级那三样（屏怎么画、麦克风怎么取、按钮怎么读）每个工程都不一样，
 * 但链路侧该做的事全在这几行里。
 *
 * 2026-10-30：这份文件在真 IDF 5.5.2 上单独编过一遍（工程在 `D:\dev\screen-link-probe`，
 * 用绝对路径把它塞进 main 的 SRCS，仓库这边仍然不编它）。编出两个错，都改了：
 * `cJSON_TRUE` 在 IDF 那份 cJSON 里不存在，换帧时摘下来的旧树没人释放（1Hz 一秒漏一棵）。
 * 改完的道理写在各自那行旁边的注释里。
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
#include "freertos/semphr.h"
#include "freertos/task.h"

static const char *TAG = "screen_app";

/* 最新一帧状态的**副本**。链路任务在回调返回后就把那棵 cJSON 释放了，
 * 所以想跨帧读（UI 任务下一拍才画）必须自己 Duplicate 一份，别存原指针 */
static cJSON *s_state_copy = NULL;
static volatile bool s_dirty = false;
static uint32_t s_state_seq = 0;
/* UI 任务和链路任务碰同一棵树，必须有锁。没锁的"换帧时删旧的"就是 use-after-free */
static SemaphoreHandle_t s_state_lock = NULL;

static void on_state(const cJSON *state, uint32_t seq)
{
    /* IDF 那份 cJSON 只有 cJSON_True（类型位枚举），没有上游 1.7.18 才加的 cJSON_TRUE，
     * 抄的时候写 true 或 cJSON_True 都行，写 cJSON_TRUE 直接编译不过 */
    cJSON *dup = cJSON_Duplicate(state, cJSON_True);
    if (dup == NULL) return;           /* 拷不动就沿用上一帧，反正 1 秒后还有 */
    xSemaphoreTake(s_state_lock, portMAX_DELAY);
    cJSON *old = s_state_copy;
    s_state_copy = dup;
    s_state_seq = seq;
    s_dirty = true;
    xSemaphoreGive(s_state_lock);
    /* 旧树在锁外删：UI 只能通过 s_state_copy 拿到树，摘出来之后它已经碰不到了 */
    if (old) cJSON_Delete(old);
    /* UI 侧读法：拿同一把锁，把要画的字段抄进自己的局部变量再放锁，别把指针带出临界区 */
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

static void on_event(const cJSON *event, const char *event_id, uint32_t left_ms)
{
    (void)event_id;
    (void)left_ms;
    const cJSON *kind = cJSON_GetObjectItemCaseSensitive(event, "kind");
    ESP_LOGI(TAG, "event %s", cJSON_IsString(kind) ? kind->valuestring : "?");
    /* ack 已经由本组件回过了，过期的那套判定也做过了（过期的一条根本不会走到这里）。
     * left_ms 是**还剩**多少毫秒，弹出来的提示按它自己收：0 = 帧里没给 ttl。 */
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
    if (s_state_lock == NULL) s_state_lock = xSemaphoreCreateMutex();
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
    /* kind 必须是 EventKind 里的名字。这里原来写的是 "sos_press" —— 那是 **CommandType**
     * （手机下发给屏的指令）里的值，不是 EventKind；服务端 ScreenMessage.decode 撞上它
     * 会直接 close("bad contract")，链路自己打断，2026-10-31 真机复现过同一类错。
     * ttl 给 30 秒：过顶窗口之外这条只能排队，说清"多久之内算数"比"发出去了"要紧。 */
    screen_link_send_event("sos_triggered", payload, 30000);
}
