package com.carpet.satdemo.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.carpet.satdemo.core.ai.PhotoChunks
import com.carpet.satdemo.core.screen.ScreenLinkServer
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 相册推图（11-11 清单 §8.3 / B §5）：把用户在手机上选的照片压成 472×472 JPEG，
 * 按 [PhotoChunks.commands] 切成 `photo_chunk` 推给圆屏。
 *
 * **照片不打包进 APK**（APK 不许变大）：每一张都是推的那一刻从相册 URI 现读现压，
 * 应用里不存任何一份。屏还在占位页固件上时这些帧被静默忽略 —— App 先实装、屏随后跟，
 * 失败方式就是契约要的那种（不掉线）。
 *
 * 压到 472×472 的理由：屏是 472×472 的圆屏（星轨底图那一条的口径），
 * 原图 4000×3000 直推一帧都放不下，白占链路。
 */
class PhotoPush(
    private val server: () -> ScreenLinkServer?,
    private val log: (String) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "photo-push").apply { isDaemon = true }
    }

    /** 屏上有没有人在（至少一条 v3 连接）。没有就白压一遍 JPEG */
    fun screenReady(): Boolean {
        val live = server() ?: return false
        return live.negotiatedVersion >= 3 && live.audioReady
    }

    /**
     * 推一张相册照片。压缩和切块都在后台线程（大图解码要一两秒，不能卡主线程）。
     *
     * @return 受理与否。false = 屏不在线，调用方给可见反馈；真推没推成功看日志。
     */
    fun push(context: Context, uri: android.net.Uri): Boolean {
        if (!screenReady()) {
            log("圆屏还没连上，照片没推；连上后再试一次")
            return false
        }
        worker.execute {
            try {
                val jpeg = compress(context, uri)
                if (jpeg == null) {
                    log("这张照片读不出来（可能是云端相册的），换一张本地的")
                    return@execute
                }
                val frames = PhotoChunks.commands("p-${UUID.randomUUID()}", jpeg, System.currentTimeMillis())
                if (frames.isEmpty()) {
                    log("这张照片太大了，圆屏一次放不下；换一张小点的试试")
                    return@execute
                }
                server()?.broadcast(frames)
                log("照片已经推过去了，它现在就能看到")
            } catch (e: Exception) {
                log("推图失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
        return true
    }

    /**
     * URI → 472×472 JPEG 字节。EXIF 方向先转正（相册里的竖拍照片按像素存是横的），
     * 再等比缩到覆盖 472 后居中裁方 —— 圆屏只显示方图，留黑边比裁边更难看。
     */
    private fun compress(context: Context, uri: android.net.Uri): ByteArray? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 先降采样到 944（2×472，缩放要的过采样余量），省内存也省缩放时间
        val opts = BitmapFactory.Options().apply {
            inSampleSize = maxOf(bounds.outWidth, bounds.outHeight) / 944 + 1
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
        val turned = turnUpRight(decoded, context, uri)
        val square = centerCrop(turned, 472)
        val out = ByteArrayOutputStream()
        // 质量 85：屏上那张的观看距离下，85 和 95 几乎看不出，字节数差一倍
        square.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    private fun turnUpRight(bitmap: Bitmap, context: Context, uri: android.net.Uri): Bitmap {
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }.getOrNull() ?: return bitmap
        val rotation = when (
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bitmap
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val scale = size.toFloat() / minOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(size)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val x = (w - size) / 2
        val y = (h - size) / 2
        return Bitmap.createBitmap(scaled, x, y, size, size)
    }
}
