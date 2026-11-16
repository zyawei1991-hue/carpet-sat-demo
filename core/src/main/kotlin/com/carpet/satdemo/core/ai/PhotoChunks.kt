package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.CommandPayload
import com.carpet.satdemo.core.protocol.CommandType
import com.carpet.satdemo.core.protocol.MsgType
import com.carpet.satdemo.core.protocol.ScreenMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Base64

/**
 * 相册照片怎么切成 `photo_chunk`（11-11 清单 §8.3 / B §5 的推图通道）。
 *
 * 和 [AudioChunks] 同族：**不新开 frame `t`**，在已有 `command` 帧里加一个新 cmd ——
 * 固件解不开陌生的 `t` 是直接关连接，收到不认识的 `cmd` 只是丢这一帧，后者才是加性修订。
 * 屏侧现在还没有 photo_chunk 的 handler（PG_PHOTO 还是占位页）：老固件静默忽略，
 * App 先实装是安全的失败方式。
 *
 * args：`album_id`（一组照片的轮播标识，同一组的 seq 接续）、`seq` 从 0 连续（缺号整组丢）、
 * `total` 是**这一张** JPEG 的总字节数（一张一个组，跟 audio_chunk 的"整轮"口径不同：
 * 照片没有"接续念下一段"的需求，缺号整张丢、下一张换 album_id 重来）、`b64` 是块的 base64。
 */
object PhotoChunks {
    /**
     * 一块的字节数。照片没有实时节拍的约束，块可以比语音大；上限仍是帧：
     * base64 之后 2736 字符 + JSON 骨架 ≈ 2.9 KB，离 3800 的组帧上限留足余量。
     */
    const val BYTES_PER_CHUNK = 2048

    /**
     * 单张 JPEG 的上限。472×472 质量 85 的照片实测 30~60 KB，1 MB 是五倍的裕量 ——
     * 超了整张不发（半张照片比没有更难解释），由调用方给出"这张为什么没推"。
     */
    const val MAX_TOTAL_BYTES = 1024 * 1024

    /** 固件那边 `SCREEN_LINK_MAX_FRAME` 是 4096，同 [AudioChunks.JSON_LIMIT] 的口径 */
    const val JSON_LIMIT = 3800

    /**
     * 一张 JPEG → 一串 `photo_chunk` 帧。空输入、超限返回空表 —— 照片失败绝不能挡别的通道，
     * 调用方拿空表给"这张没推出去"的可见反馈。
     */
    fun commands(albumId: String, jpeg: ByteArray, tsMs: Long): List<ScreenMessage> {
        if (albumId.isEmpty() || jpeg.isEmpty() || jpeg.size > MAX_TOTAL_BYTES) return emptyList()
        val b64 = Base64.getEncoder()
        val out = ArrayList<ScreenMessage>((jpeg.size + BYTES_PER_CHUNK - 1) / BYTES_PER_CHUNK)
        for ((index, offset) in (0 until jpeg.size step BYTES_PER_CHUNK).withIndex()) {
            val end = minOf(offset + BYTES_PER_CHUNK, jpeg.size)
            val part = jpeg.copyOfRange(offset, end)
            val frame = ScreenMessage(
                t = MsgType.command,
                seq = index.toLong(),
                ts = tsMs,
                command = CommandPayload(
                    cmd = CommandType.photo_chunk,
                    args = buildJsonObject {
                        put("album_id", JsonPrimitive(albumId))
                        put("seq", JsonPrimitive(index))
                        put("total", JsonPrimitive(jpeg.size))
                        put("fmt", JsonPrimitive("jpeg"))
                        put("b64", JsonPrimitive(b64.encodeToString(part)))
                    },
                ),
            )
            // 编码之后超了 4096，固件那头是**静默丢帧**（组帧缓冲溢出），不是报错。
            // 所以这条必须在手机端当场抛，而不是让现场去怀疑 Wi-Fi。
            check(frame.encode().toByteArray(Charsets.UTF_8).size <= JSON_LIMIT) {
                "photo_chunk 第 $index 块编出来 ${frame.encode().toByteArray(Charsets.UTF_8).size} 字节，超了屏侧 $JSON_LIMIT 的组帧上限"
            }
            out += frame
        }
        return out
    }
}
