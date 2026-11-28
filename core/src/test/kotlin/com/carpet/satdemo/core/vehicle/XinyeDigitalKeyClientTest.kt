package com.carpet.satdemo.core.vehicle

import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

class XinyeDigitalKeyClientTest {

    private val credentials = XinyeCredentials(
        appId = "test-app",
        key = "test-key",
        licenseNumber = "TEST-001",
    )

    @Test
    fun `默认传输禁止连接重试与重定向重放`() {
        val http = XinyeDigitalKeyClient.sharedHttp

        assertFalse(http.retryOnConnectionFailure)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
    }

    @Test
    fun `认证签名与正式 ENGINE STOP 枚举按原文发出`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","status":0}]}""")

        val result = partner.client.send(XinyeInstruction.ENGINE_STOP)

        val auth = partner.requests.single { it.url.encodedPath.endsWith("/auth") }
        val authBody = auth.bodyJson()
        assertEquals("test-app", authBody.string("appid"))
        assertEquals(1_700_000_000L, authBody.long("time"))
        assertEquals("a1fb1e5c65e8b4f4be980c357fb48a0f", authBody.string("signature"))
        val send = partner.requests.single { it.url.encodedPath.endsWith("/xinye/instruction/send") }
        assertEquals("token-1", send.url.queryParameter("accessToken"))
        val sendBody = send.bodyJson()
        assertEquals("TEST-001", sendBody.string("licenseNumber"))
        assertEquals("ENGINE STOP", sendBody.string("instruction"))
        assertFalse("imei" in sendBody)
        val accepted = assertIs<XinyeSendResult.Accepted>(result)
        assertEquals("263", accepted.historyId)
        assertEquals(null, accepted.lookupId)
        assertEquals(XinyeLookupIdSource.HistoryId, accepted.lookupIdSource)
        assertFalse(credentials.toString().contains("test-key"))
    }

    @Test
    fun `令牌复用且功能调用遵守每秒十次上限`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        repeat(2) {
            partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","status":0}]}""")
        }

        partner.client.send(XinyeInstruction.ENGINE_STOP)
        partner.client.send(XinyeInstruction.ENGINE_STOP)

        assertEquals(1, partner.requests.count { it.url.encodedPath.endsWith("/auth") })
        assertEquals(listOf(100L), partner.sleeps)
    }

    @Test
    fun `九十分钟缓存到期后重新认证`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","status":0}]}""")
        partner.client.send(XinyeInstruction.ENGINE_STOP)
        partner.now += 90 * 60 * 1000L
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-2"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"264","status":0}]}""")

        val result = partner.client.send(XinyeInstruction.ENGINE_STOP)

        assertEquals(2, partner.requests.count { it.url.encodedPath.endsWith("/auth") })
        assertEquals("264", assertIs<XinyeSendResult.Accepted>(result).historyId)
    }

    @Test
    fun `发送回包含 uuid 时优先按 uuid 查回执`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","uuid":"u-1","status":0}]}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":1,"response":"Success"}]}""")

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))
        val receipt = partner.client.receipt(sent.lookupId!!)

        assertEquals(XinyeLookupIdSource.Uuid, sent.lookupIdSource)
        assertEquals("u-1", partner.requests.last().url.queryParameter("uuid"))
        assertEquals(XinyeReceipt.Responded("Success"), receipt)
    }

    @Test
    fun `发送 status 1 只算合作方回复，执行仍要查回执`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":1,"response":"Success"}]}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":1,"response":"Success"}]}""")

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))
        assertTrue(sent.replied)
        assertEquals("Success", sent.reply)
        assertEquals(XinyeReceipt.Responded("Success"), partner.client.receipt(sent.lookupId!!))
    }

    @Test
    fun `回执编号不一致时拒绝冒充当前指令结果`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":0}]}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-2","status":1,"response":"Success"}]}""")

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))
        val receipt = assertIs<XinyeReceipt.Rejected>(partner.client.receipt(sent.lookupId!!))
        assertEquals(XinyeFailureKind.Protocol, receipt.failure.kind)
    }

    @Test
    fun `单条回执缺少 uuid 时拒绝冒充当前指令结果`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":0}]}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"status":1,"response":"Success"}]}""")

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))
        val receipt = assertIs<XinyeReceipt.Rejected>(partner.client.receipt(sent.lookupId!!))

        assertEquals(XinyeFailureKind.Protocol, receipt.failure.kind)
    }

    @Test
    fun `只有 historyId 时保留编号但不冒充 uuid 查回执`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","status":0}]}""")

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))

        assertEquals("263", sent.historyId)
        assertEquals(null, sent.lookupId)
        assertEquals(XinyeLookupIdSource.HistoryId, sent.lookupIdSource)
        assertEquals(2, partner.requests.size)
        assertTrue(partner.requests.none { it.url.encodedPath.endsWith("/instruction/relay") })
    }

    @Test
    fun `合作方错误与限流分别返回且不输出凭据`() {
        val limited = FakePartner(credentials)
        limited.enqueue("""{"code":429,"result":"too many"}""", status = 429)
        val limitedResult = limited.client.send(XinyeInstruction.ENGINE_STOP)
        assertEquals(
            XinyeFailureKind.RateLimited,
            assertIs<XinyeSendResult.Rejected>(limitedResult).failure.kind,
        )

        val rejected = FakePartner(credentials)
        rejected.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        rejected.enqueue("""{"code":-1,"result":"车牌号不能为空"}""")
        val failure = assertIs<XinyeSendResult.Rejected>(rejected.client.send(XinyeInstruction.ENGINE_STOP)).failure
        assertEquals(XinyeFailureKind.Rejected, failure.kind)
        assertEquals("车牌号不能为空", failure.message)
        assertFalse(failure.message.contains("test-key"))
    }

    @Test
    fun `配置必须有目标且脱敏字符串不含 key`() {
        val parsed = XinyeCredentials.fromProperties(
            """
            appid=test-app
            key=test-key
            imei=863082071686561
            mobile=13800000000
            """.trimIndent(),
        )
        assertEquals("863082071686561", parsed.imei)
        assertFalse(parsed.toString().contains("test-key"))
        assertFalse(parsed.toString().contains("863082071686561"))
        val error = runCatching {
            XinyeCredentials.fromProperties("appid=test-app\nkey=test-key\n")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertFalse(error.message.orEmpty().contains("test-key"))
    }

    @Test
    fun `401 清掉失效令牌后下次重新认证`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":401}""", status = 401)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-2"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"264","status":0}]}""")

        assertEquals(
            XinyeFailureKind.Auth,
            assertIs<XinyeSendResult.Rejected>(partner.client.send(XinyeInstruction.ENGINE_STOP)).failure.kind,
        )
        assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))

        assertEquals(2, partner.requests.count { it.url.encodedPath.endsWith("/auth") })
        assertEquals(
            "token-2",
            partner.requests.last { it.url.encodedPath.endsWith("/xinye/instruction/send") }
                .url.queryParameter("accessToken"),
        )
    }

    @Test
    fun `响应体断流转为网络失败而不是抛异常`() {
        val client = XinyeDigitalKeyClient(credentials, transport = { request ->
            stubResponse(request, "").newBuilder()
                .body(
                    object : ResponseBody() {
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = -1L
                        override fun source() = object : ForwardingSource(Buffer()) {
                            override fun read(sink: Buffer, byteCount: Long): Long =
                                throw IOException("broken body")
                        }.buffer()
                    },
                )
                .build()
        })

        assertEquals(
            XinyeFailureKind.Network,
            assertIs<XinyeSendResult.Rejected>(client.send(XinyeInstruction.ENGINE_STOP)).failure.kind,
        )
    }

    @Test
    fun `鉴权完成后权限关闭则不发送指令`() {
        val requests = mutableListOf<Request>()
        val client = XinyeDigitalKeyClient(
            credentials,
            transport = { request ->
                requests += request
                check(request.url.encodedPath.endsWith("/auth"))
                stubResponse(request, """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
            },
            beforeSend = { false },
        )

        val result = assertIs<XinyeSendResult.Rejected>(client.send(XinyeInstruction.ENGINE_STOP))

        assertEquals(XinyeFailureKind.Cancelled, result.failure.kind)
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/auth"))
    }

    @Test
    fun `未知发送状态不冒充已发送`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263","status":2}]}""")

        assertEquals(
            XinyeFailureKind.Protocol,
            assertIs<XinyeSendResult.Rejected>(partner.client.send(XinyeInstruction.ENGINE_STOP)).failure.kind,
        )
    }

    @Test
    fun `发送缺少 status 时不冒充已发送`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"historyId":"263"}]}""")

        assertEquals(
            XinyeFailureKind.Protocol,
            assertIs<XinyeSendResult.Rejected>(partner.client.send(XinyeInstruction.ENGINE_STOP)).failure.kind,
        )
    }

    @Test
    fun `同一 uuid 返回多条回执时拒绝任取一条`() {
        val partner = FakePartner(credentials)
        partner.enqueue("""{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""")
        partner.enqueue("""{"code":0,"details":[{"code":0,"uuid":"u-1","status":0}]}""")
        partner.enqueue(
            """{"code":0,"details":[{"code":0,"uuid":"u-1","status":0},{"code":0,"uuid":"u-1","status":1,"response":"ENGINE STOP Success"}]}""",
        )

        val sent = assertIs<XinyeSendResult.Accepted>(partner.client.send(XinyeInstruction.ENGINE_STOP))
        val receipt = assertIs<XinyeReceipt.Rejected>(partner.client.receipt(sent.lookupId!!))

        assertEquals(XinyeFailureKind.Protocol, receipt.failure.kind)
    }

    @Test
    fun `并发发送只认证一次`() {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val responses = Collections.synchronizedList(
            mutableListOf(
                """{"code":0,"expiresIn":7200000,"accessToken":"token-1"}""",
                """{"code":0,"details":[{"code":0,"historyId":"263","status":0}]}""",
                """{"code":0,"details":[{"code":0,"historyId":"264","status":0}]}""",
            ),
        )
        val authEntered = CountDownLatch(1)
        val releaseAuth = CountDownLatch(1)
        val client = XinyeDigitalKeyClient(
            credentials,
            transport = { request ->
                requests += request
                if (request.url.encodedPath.endsWith("/auth")) {
                    authEntered.countDown()
                    assertTrue(releaseAuth.await(2, TimeUnit.SECONDS))
                }
                stubResponse(request, responses.removeAt(0))
            },
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit(Callable { client.send(XinyeInstruction.ENGINE_STOP) })
            assertTrue(authEntered.await(2, TimeUnit.SECONDS))
            val second = pool.submit(Callable { client.send(XinyeInstruction.ENGINE_STOP) })
            releaseAuth.countDown()
            assertIs<XinyeSendResult.Accepted>(first.get(2, TimeUnit.SECONDS))
            assertIs<XinyeSendResult.Accepted>(second.get(2, TimeUnit.SECONDS))
        } finally {
            releaseAuth.countDown()
            pool.shutdownNow()
        }
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/auth") })
    }

    private inner class FakePartner(private val credentials: XinyeCredentials) {
        var now = 1_700_000_000_000L
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val sleeps = mutableListOf<Long>()
        private val responses = ArrayDeque<Stub>()

        val client = XinyeDigitalKeyClient(
            credentials = credentials,
            transport = { request ->
                requests += request
                val stub = responses.removeFirst()
                stubResponse(request, stub.body, stub.status)
            },
            nowMs = { now },
            sleeper = { millis -> sleeps += millis; now += millis },
        )

        fun enqueue(body: String, status: Int = 200) {
            responses += Stub(body, status)
        }
    }

    private data class Stub(val body: String, val status: Int = 200)

    private fun Request.bodyJson(): JsonObject =
        Json.parseToJsonElement(Buffer().also { body?.writeTo(it) }.readUtf8()).jsonObject

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content
    private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()

    private fun stubResponse(request: Request, body: String, status: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("stub")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
}
