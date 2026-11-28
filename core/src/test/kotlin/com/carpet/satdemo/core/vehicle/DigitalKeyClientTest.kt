package com.carpet.satdemo.core.vehicle

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 数字钥匙接入的离线钉子。**不碰真实网络、不碰真实密钥** ——
 * 测的是四种失败态互相可分辨（这是 owner 的硬要求）和签名算法的形状。
 * 真接口联调留给真机实测（owner 11-28 拍板口径）。
 */
class DigitalKeyClientTest {

    @Test
    fun `凭据没配时 authProbe 报缺哪几项而不是笼统失败`() {
        val savedAppid = DigitalKeyClient.appid
        val savedKey = DigitalKeyClient.key
        try {
            DigitalKeyClient.appid = ""
            DigitalKeyClient.key = ""
            val r = runBlocking { DigitalKeyClient.authProbe() }
            val nc = r as DigitalKeyClient.Dk.NoCred
            assertEquals(listOf("APPID（登录名）", "KEY（登录秘钥）"), nc.fields)

            DigitalKeyClient.appid = "some-appid"
            DigitalKeyClient.key = ""
            val r2 = runBlocking { DigitalKeyClient.authProbe() }
            assertEquals(listOf("KEY（登录秘钥）"), (r2 as DigitalKeyClient.Dk.NoCred).fields)
        } finally {
            DigitalKeyClient.appid = savedAppid
            DigitalKeyClient.key = savedKey
        }
    }

    @Test
    fun `签名是 md5 双层的 32 位小写十六进制且随时间变化`() {
        val sig = DigitalKeyClient.signatureFor("demokey", 1499768051L)
        assertEquals(32, sig.length)
        assertTrue(sig.all { it in "0123456789abcdef" })
        // 独立算一遍：md5(md5(key)+time)，与文档「key 要加密两次」口径一致
        val inner = MessageDigest.getInstance("MD5").digest("demokey".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val outer = MessageDigest.getInstance("MD5").digest((inner + "1499768051").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(outer, sig)
        assertTrue(sig != DigitalKeyClient.signatureFor("demokey", 1499768052L))
        assertTrue(sig != DigitalKeyClient.signatureFor("otherkey", 1499768051L))
    }

    @Test
    fun `指令发送缺车牌又缺设备号时报可分辨的形状错误而非假成功`() {
        val savedAppid = DigitalKeyClient.appid
        val savedKey = DigitalKeyClient.key
        try {
            DigitalKeyClient.appid = "x"
            DigitalKeyClient.key = "y"
            val r = runBlocking { DigitalKeyClient.sendInstruction("LOCK DOOR") }
            assertTrue(r is DigitalKeyClient.Dk.BadShape)
        } finally {
            DigitalKeyClient.appid = savedAppid
            DigitalKeyClient.key = savedKey
        }
    }

    @Test
    fun `配了假凭据走真实域名时失败态是出网或对方码之一而不是崩溃`() {
        val savedAppid = DigitalKeyClient.appid
        val savedKey = DigitalKeyClient.key
        val savedBase = DigitalKeyClient.baseUrl
        try {
            DigitalKeyClient.appid = "unit-test-not-real"
            DigitalKeyClient.key = "unit-test-not-real"
            DigitalKeyClient.baseUrl = "https://open.figps.com.invalid-for-tests/api"
            DigitalKeyClient.invalidateToken()
            val r = runBlocking { DigitalKeyClient.deviceStatus("863082071686561") }
            // 无 DNS/无网络环境各是什么就报什么：Net 或 Http，绝不容忍成 Ok/假数据
            assertTrue(
                r is DigitalKeyClient.Dk.Net || r is DigitalKeyClient.Dk.Http || r is DigitalKeyClient.Dk.Api,
                "期望出网类失败，实际=$r"
            )
        } finally {
            DigitalKeyClient.appid = savedAppid
            DigitalKeyClient.key = savedKey
            DigitalKeyClient.baseUrl = savedBase
            DigitalKeyClient.invalidateToken()
        }
    }
}
