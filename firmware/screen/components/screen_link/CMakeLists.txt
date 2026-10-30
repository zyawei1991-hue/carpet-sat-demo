idf_component_register(
    SRCS "screen_link.c" "screen_link_redline.c"
    INCLUDE_DIRS "include"
    # esp_timer 只在 5.3+ 才是公开组件。小智现在跑 5.5，所以能写；
    # 真报 "unknown requested component" 就从这行删掉 —— 本组件只用到 esp_timer_get_time()，
    # 那个符号在 5.3 之前随 esp_common 一起给
    REQUIRES esp_websocket_client json esp_timer
)
