/*
 * 屏侧那道文案闸。词表与手机端同源：
 *   core/.../link/SosCopy.kt      -> BANNED
 *   core/.../ephemeris/Ephemeris.kt -> FORBIDDEN
 *
 * 同步检查（改任何一边之后在仓库根跑，最后必须打印 SYNC OK）：
 *   K=core/src/main/kotlin/com/carpet/satdemo/core
 *   grep -h 'listOf(' $K/link/SosCopy.kt $K/ephemeris/Ephemeris.kt | grep -o '"[^"]*"' > /tmp/kt.txt
 *   sed -n '/const char .*BANNED/,/};/p' firmware/screen/components/screen_link/screen_link_redline.c \
 *     | grep -E '^[[:space:]]*"[^"]*",$' | grep -o '"[^"]*"' > /tmp/c.txt
 *   diff /tmp/kt.txt /tmp/c.txt && echo SYNC OK
 * 2026-10-30 跑过，11 条一字不差。
 *
 * 这条检查换过两次写法，理由记在这儿免得有人再踩：
 *   ① 原来那三条是"grep 全文再比 head/tail"，比不出来 —— 本文件头注释里就带着 grep 命令本身，
 *      下面每条禁词上方还各有一句带引号的理由，两边各多出一截，永远不一致。
 *   ② 第二版想用 sed 把行内注释剥掉，写成了反斜杠+星号+斜杠连排的那三个字符 —— 那是 C 的块注释
 *      结束符，注释当场被截断，后面半句变成代码，编译器报的是 "stray '\343'" 这种看着完全无关的错。
 *      现在这版不碰注释：只收整行就是"一条禁词加一个逗号"的行，中间那些理由自然被 grep 掉。
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

/* 按字节截断会从 UTF-8 多字节序列中间劈开，LVGL 拿到半个序列就在屏上画出半个字或一串方块，
   而串口日志里这句看着完全正常。所以抄完之后要往回退到最后一个**完整码点**。 */
static void copy_clip(char *out, size_t out_n, const char *src)
{
    size_t n = strlen(src);
    if (n > out_n - 1) n = out_n - 1;
    memcpy(out, src, n);
    out[n] = '\0';

    if (n == 0) return;
    size_t i = n;
    bool lead_found = false;
    while (i > 0 && n - i < 3) {           /* 尾部最多回看 3 个字节（一个码点最长 4 字节） */
        unsigned char c = (unsigned char)out[i - 1];
        if ((c & 0xC0) == 0x80) { i--; continue; }    /* 续字节，接着往前 */
        lead_found = true;
        size_t need = (c >= 0xF0) ? 4 : (c >= 0xE0) ? 3 : (c >= 0xC0) ? 2 : 1;
        if (n - (i - 1) < need) n = i - 1;            /* 首字节在，但序列被截短了：整条丢 */
        break;
    }
    if (!lead_found) n = i;                          /* 尾部压根没有首字节 = 坏字节，丢掉 */
    out[n] = '\0';
}

bool screen_link_guard_copy(const char *in, char *out, size_t out_n)
{
    if (out == NULL || out_n == 0) return false;
    if (in == NULL) {
        out[0] = '\0';
        return false;
    }
    if (!screen_link_copy_is_banned(in)) {
        copy_clip(out, out_n, in);
        return false;
    }
    /* 兜底文案自己不能含任何被禁词，也不能反过来把话说小（"没送出去"同样是猜）。
     * 这句 11 条词表 0 命中、也不含"没送出去/发送失败/发不出去"，2026-10-30 核过。
     * 它 UTF-8 下 51 字节：out_n 小于 52 会被截断（现在按码点截，不会劈开半个字），
     * UI 侧缓冲至少给到 64。 */
    copy_clip(out, out_n, "这句我不能替平台说，以手机台账为准");
    return true;
}
