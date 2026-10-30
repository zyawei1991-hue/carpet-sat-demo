package com.carpet.satdemo.app.ui

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * **测试专用的最小 PNG 编码器**（RGBA8888，无滤镜）。
 *
 * 为什么不直接用 `ImageIO` / `Bitmap.compress`：
 *  - 单测编译期挂的是 mockable 的 `android.jar`，里面**没有 `javax.imageio`，也没有 `java.awt`**，
 *    引一次就红一次，而且报的是"unresolved reference"这种看起来像配置错的错；
 *  - `Bitmap.compress` 要走 native 图像编码器，Robolectric 给不给全凭版本心情。
 * `java.util.zip`（Deflater/CRC32）在 android.jar 和 JDK 里都在，所以手写 4 个 chunk 最省。
 */
internal fun writePng(file: java.io.File, width: Int, height: Int, argb: IntArray) {
    require(argb.size == width * height) { "像素数对不上：$width x $height != ${argb.size}" }
    val raw = ByteArrayOutputStream(width * height * 4)
    // 每行前一个 filter 字节，0 = None：不做行间预测，编码简单，图也大不了多少
    for (y in 0 until height) {
        raw.write(0)
        val row = y * width
        for (x in 0 until width) {
            val p = argb[row + x]
            raw.write(p shr 16 and 0xFF) // R
            raw.write(p shr 8 and 0xFF) // G
            raw.write(p and 0xFF) // B
            raw.write(p shr 24 and 0xFF) // A
        }
    }
    val idat = ByteArrayOutputStream().also { out ->
        DeflaterOutputStream(out, Deflater(6)).use { it.write(raw.toByteArray()) }
    }.toByteArray()
    file.outputStream().use { s ->
        s.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        s.write(chunk("IHDR", be(width) + be(height) + byteArrayOf(8, 6, 0, 0, 0)))
        s.write(chunk("IDAT", idat))
        s.write(chunk("IEND", ByteArray(0)))
    }
}

private fun chunk(type: String, data: ByteArray): ByteArray {
    val t = type.toByteArray()
    val crc = CRC32().apply { update(t); update(data) }
    return be(data.size) + t + data + be(crc.value.toInt())
}

private fun be(v: Int): ByteArray = byteArrayOf(
    (v shr 24 and 0xFF).toByte(),
    (v shr 16 and 0xFF).toByte(),
    (v shr 8 and 0xFF).toByte(),
    (v and 0xFF).toByte(),
)
