/*
 * 屏侧那道文案闸。词表与手机端同源：
 *   core/.../link/SosCopy.kt      -> BANNED
 *   core/.../ephemeris/Ephemeris.kt -> FORBIDDEN
 *
 * 同步检查（改任何一边之后在仓库根跑一次，三行输出必须一致）：
 *   grep -o '"[^"]*"' core/src/main/kotlin/com/carpet/satdemo/core/link/SosCopy.kt | head -12
 *   grep -o '"[^"]*"' core/src/main/kotlin/com/carpet/satdemo/core/ephemeris/Ephemeris.kt | tail -8
 *   grep -o '"[^"]*"' firmware/screen/components/screen_link/screen_link_redline.c | head -12
 *
 * 为什么屏那边还要再守一次：小智官方云那条路是它自己把话画到屏上的，手机的闸管不到。
 * 现场冒出一句"救援已在路上"是不可挽回的那种错。
 */
#include "screen_link.h"

#include <string.h>
#include <stdio.h>

static const char *const BANNED[] = {
    /* 把"交出去了"说成"救到了" */
    "已送达",
    "发送成功",
    "送达成功",
    "救援已在路上",
    "救援队已在路上",
    "平台已受理",
    "一定没问题",
    /* 把"星历没到手"说成"天上没卫星"：用户会举着天线在空旷处白等 */
    "无卫星信号",
    "没有卫星",
    "搜星失败",
    "卫星不可用",
};

#define BANNED_N ((int)(sizeof(BANNED) / sizeof(BANNED[0])))

bool screen_link_copy_is_banned(const char *s)
{
    if (s == NULL) return false;
    for (int i = 0; i < BANNED_N; i++) {
        if (strstr(s, BANNED[i]) != NULL) return true;
    }
    return false;
}

bool screen_link_guard_copy(const char *in, char *out, size_t out_n)
{
    if (out == NULL || out_n == 0) return false;
    if (in == NULL) {
        out[0] = '\0';
        return false;
    }
    if (!screen_link_copy_is_banned(in)) {
        snprintf(out, out_n, "%s", in);
        return false;
    }
    /* 兜底文案自己不能含任何被禁词，也不能反过来把话说小（"没送出去"同样是猜） */
    snprintf(out, out_n, "这句我不能替平台说，以手机台账为准");
    return true;
}
