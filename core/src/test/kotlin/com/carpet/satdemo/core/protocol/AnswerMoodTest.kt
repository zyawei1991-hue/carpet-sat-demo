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
        assertEquals(UiEmotion.worried, AnswerMood.of("还没有可用的过境时间。"))
    }

    /** 现场最常出现的那一句：链路是好的，倒计时还在走 —— 读成好消息，不是担心 */
    @Test
    fun `在线就是在线，哪怕后面还有数字`() {
        assertEquals(UiEmotion.happy, AnswerMood.of("当前相位 待命，GT1005 在线。下一次过顶还有 678 秒。"))
    }

    @Test
    fun `窗口打开那一刻是兴奋`() {
        assertEquals(UiEmotion.excited, AnswerMood.of("卫星过境了，这几分钟能发呼救。"))
    }

    @Test
    fun `没命中就说中性`() {
        assertEquals(UiEmotion.neutral, AnswerMood.of("已经记下了。"))
    }
}
