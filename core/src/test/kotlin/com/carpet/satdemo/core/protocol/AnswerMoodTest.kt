package com.carpet.satdemo.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 脸跟着答句走。钉的是 #36 那条现场事故：hero 写着「卫星终端离线」而脸上是笑脸。
 *
 * 词表按"坏消息先判"排，所以这里每条都在验**同一句里两个词都出现时谁赢**，
 * 而不是只验孤零零一个关键词 —— 后者怎么排都对。
 */
class AnswerMoodTest {

    @Test
    fun `坏消息不许配笑脸`() {
        assertEquals(UiEmotion.sad, AnswerMood.of("卫星终端离线，这条现在发不出去。"))
        assertEquals(UiEmotion.sad, AnswerMood.of("GT1005 不在线，心跳还留在手机队列。"))
    }

    @Test
    fun `说不好就担心`() {
        assertEquals(UiEmotion.worried, AnswerMood.of("这件事我现在没有数据，不能凭印象答。"))
        assertEquals(UiEmotion.worried, AnswerMood.of("已经越界了。"))
        assertEquals(UiEmotion.worried, AnswerMood.of("现在还说不准卫星什么时候经过。"))
    }

    /** 现场最常出现的那一句：链路是好的，只是还没到时间 —— 读成好消息，不是担心 */
    @Test
    fun `在线就是在线，哪怕还要等一会儿`() {
        assertEquals(UiEmotion.happy, AnswerMood.of("当前相位 待命，GT1005 在线。还要 11 分 18 秒，下一颗卫星经过时才能发。"))
        assertEquals(UiEmotion.happy, AnswerMood.of("当前相位 已锁定，GT1005 在线。卫星正在头顶，现在就能发。"))
    }

    @Test
    fun `窗口打开那一刻是兴奋`() {
        assertEquals(UiEmotion.excited, AnswerMood.of("卫星过境了，这几分钟能发呼救。"))
    }

    @Test
    fun `没命中就说中性`() {
        assertEquals(UiEmotion.neutral, AnswerMood.of("已经记下了。"))
    }

    /** §3：亲子/助手页不认卫星词表 —— 「离线」在那儿不是坏消息，是没在聊的事 */
    @Test
    fun `亲子页卫星词不表情`() {
        assertEquals(false, AnswerMood.satelliteWords(ScreenPage.kids))
        assertEquals(false, AnswerMood.satelliteWords(ScreenPage.avatar))
        assertEquals(true, AnswerMood.satelliteWords(ScreenPage.vehicle))
        assertEquals(true, AnswerMood.satelliteWords(null))
        assertEquals(UiEmotion.neutral, AnswerMood.of("卫星终端离线了。", satelliteWords = false))
        assertEquals(UiEmotion.neutral, AnswerMood.of("过境了，可以发。", satelliteWords = false))
        // 真正的安全话还是要有脸：越界在亲子页照样紧张
        assertEquals(UiEmotion.worried, AnswerMood.of("已经越界了。", satelliteWords = false))
        // 屏没报页时照旧全表
        assertEquals(UiEmotion.sad, AnswerMood.of("卫星终端离线了。", satelliteWords = true))
    }
}
