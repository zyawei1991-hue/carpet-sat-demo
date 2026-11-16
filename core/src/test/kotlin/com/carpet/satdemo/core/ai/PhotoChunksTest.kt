package com.carpet.satdemo.core.ai

import com.carpet.satdemo.core.protocol.CommandType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotoChunksTest {
    @Test
    fun `splits into continuous seq with whole-image total`() {
        val jpeg = ByteArray(PhotoChunks.BYTES_PER_CHUNK * 2 + 100) { (it % 251).toByte() }
        val frames = PhotoChunks.commands("p-abc", jpeg, 1234L)
        assertEquals(3, frames.size)
        frames.forEachIndexed { i, f ->
            val cmd = f.command!!
            assertEquals(CommandType.photo_chunk, cmd.cmd)
            assertEquals("p-abc", cmd.args!!["album_id"]!!.toString().trim('"'))
            assertEquals(i, cmd.args!!["seq"]!!.toString().toInt())
            assertEquals(jpeg.size, cmd.args!!["total"]!!.toString().toInt())
            assertEquals("jpeg", cmd.args!!["fmt"]!!.toString().trim('"'))
        }
    }

    @Test
    fun `every frame fits the firmware limit`() {
        val jpeg = ByteArray(PhotoChunks.MAX_TOTAL_BYTES / 2) { 0x55 }
        val frames = PhotoChunks.commands("p-big", jpeg, 1L)
        assertTrue(frames.size >= 250)
        frames.forEachIndexed { i, f ->
            val size = f.encode().toByteArray(Charsets.UTF_8).size
            assertTrue(size <= PhotoChunks.JSON_LIMIT, "第 $i 块 $size 字节超了 ${PhotoChunks.JSON_LIMIT}")
        }
    }

    @Test
    fun `empty and oversize return empty list`() {
        assertTrue(PhotoChunks.commands("p-x", ByteArray(0), 1L).isEmpty())
        assertTrue(
            PhotoChunks.commands("p-x", ByteArray(PhotoChunks.MAX_TOTAL_BYTES + 1), 1L).isEmpty()
        )
        assertTrue(PhotoChunks.commands("", ByteArray(10), 1L).isEmpty())
    }

    @Test
    fun `b64 decodes back to the source bytes`() {
        val jpeg = ByteArray(5000) { (it * 7).toByte() }
        val joined = PhotoChunks.commands("p-y", jpeg, 1L)
            .flatMap { f -> java.util.Base64.getDecoder().decode(f.command!!.args!!["b64"]!!.toString().trim('"')).toList() }
        assertEquals(jpeg.toList(), joined)
    }
}
