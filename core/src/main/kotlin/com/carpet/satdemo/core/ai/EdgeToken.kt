package com.carpet.satdemo.core.ai

/**
 * Edge「朗读」接口的 `Sec-MS-GEC` v2 令牌。
 *
 * **出处**：逐行移植自 `edge-tts` 7.3.1 的 `edge_tts/drm.py`（LGPL-3.0）。上游这段是对
 * Microsoft Edge 朗读接口的逆向实现，不是微软公开的规范；它是那个接口的准入令牌，算法随时可能变。
 * 桌面那侧一直走 pip 装的 `edge_tts`（[tools/tts-edge.py]），这里只是把同一套算法搬进手机，
 * 让手机和桌面念同一个晓伊。
 *
 * 为什么不能用 `MessageDigest`：这段"SHA-256"换了初始状态、第 15 个轮常量、两处 σ 修正，
 * 和标准 SHA-256 的输出不一样（对同一输入逐一比过）。CS64 那半段是另一台 32 位混合器，
 * 也没法用标准库拼出来。所以两段都得自己算。
 *
 * 移植时只做了一件事：Python 的大整数换成了 64 位 `Long`。所有运算最终都 `& 0xFFFFFFFF`，
 * 而 64 位补码乘法/移位天然保留低 32 位，所以结果和上游逐位一致 —— 下面两个时间戳的令牌
 * 是上游 `generate_sec_ms_gec_v2()` 亲手生成的，[EdgeTokenTest] 拿它们做回归。
 */
object EdgeToken {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val WIN_EPOCH = 11644473600L
    private const val MASK = 0xFFFFFFFFL

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )

    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )

    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )

    private val SHA256_ROUND = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
        0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
        0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
        0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
        0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
        0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
        0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
        0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
        0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    /** 第 15 个轮常量被换掉的那份表 */
    private val SHA256_BLOCK = SHA256_ROUND.copyOf().also { it[14] = SHA256_ADJUSTMENT }

    private val CS64_COEFFICIENTS = arrayOf(
        longArrayOf(0xE82E91B1L, 0xB32700D0L),
        longArrayOf(0x44DE24C6L, 0x88DFF51DL),
        longArrayOf(0x3BEB65BCL, 0x29514038L),
        longArrayOf(0xC89C5933L, 0xB328CBDFL),
        longArrayOf(0x8D1EC211L, 0xD405B4D5L),
        longArrayOf(0x314EF5D5L, 0x26E55F3CL),
        longArrayOf(0xE92491B1L, 0x607801C6L),
        longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
        longArrayOf(0x06064CF3L, 0xFA51A3DAL),
        longArrayOf(0xDA79FA60L, 0x207CC464L),
        longArrayOf(0xBED20560L, 0x3C3C74F3L),
        longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
        longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
        longArrayOf(0xCB49E3C6L, 0xC5626167L),
    )

    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    private const val HEX = "0123456789ABCDEF"

    /**
     * 生成当前时刻的令牌，形如 `<16 hex>-<64 hex>`。
     * 令牌按 5 分钟一档，同一档内所有请求复用同一个值。
     *
     * @param unixSeconds 覆盖当前时间，只给测试用。
     */
    fun generate(unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300
        val data = (ticks * 10_000_000L).toString() + TRUSTED_CLIENT_TOKEN
        val raw = data.toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(raw)
        val cs = cs64Digest(raw, digest)
        return hexLittleEndian(cs[0]) + hexLittleEndian(cs[1]) + "-" + digest.toHexUpper()
    }

    private fun rol16(value: Long): Long {
        val v = value and MASK
        return ((v shl 16) or (v ushr 16)) and MASK
    }

    /** 小端 32 位切片；奇数个补一个 0 —— 上游那台混合器要求成对消费 */
    private fun wordsLe4Even(data: ByteArray): LongArray {
        val padded = data.size + ((4 - data.size % 4) % 4)
        val words = ArrayList<Long>(padded / 4 + 1)
        var i = 0
        while (i < padded) {
            var w = 0L
            for (b in 0 until 4) {
                val idx = i + b
                val v = if (idx < data.size) data[idx].toLong() and 0xFF else 0L
                w = w or (v shl (8 * b))
            }
            words.add(w)
            i += 4
        }
        if (words.size and 1 == 1) words.add(0L)
        return words.toLongArray()
    }

    private fun sigma0(v: Long): Long =
        ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and MASK

    private fun sigma1(v: Long): Long =
        ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and MASK

    /** 乘 (2^32+1) 再右移，低 32 位就是循环右移；三段异或只有低 32 位有意义 */
    private fun rot6(x: Long): Long {
        val r = x * 0x100000001L
        return (r ushr 6) xor (r ushr 11) xor (r ushr 25)
    }

    private fun rot2(x: Long): Long {
        val r = x * 0x100000001L
        return (r ushr 2) xor (r ushr 13) xor (r ushr 22)
    }

    private fun sha256Rounds(state: LongArray, words: LongArray, firstRound: Int = 0): LongArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        val constants = if (firstRound == 14) SHA256_BLOCK else SHA256_ROUND
        val aligned = minOf(64, ((firstRound + 7) / 8) * 8)

        var i = firstRound
        while (i < aligned) {
            val t1 = h + rot6(e) + (g xor (e and (f xor g))) + constants[i] + words[i]
            val t2 = rot2(a) + ((a and b) or (c and (a or b)))
            val na = (t1 + t2) and MASK
            val nd = (d + t1) and MASK
            h = g; g = f; f = e; e = nd; d = c; c = b; b = a; a = na
            i++
        }

        // 八轮一轮回：工作寄存器名字回到原位，只改两个寄存器，省掉每轮八个赋值
        i = aligned
        while (i < 64) {
            var t1 = h + rot6(e) + (g xor (e and (f xor g))) + constants[i] + words[i]
            d = (d + t1) and MASK
            h = (t1 + rot2(a) + ((a and b) or (c and (a or b)))) and MASK

            t1 = g + rot6(d) + (f xor (d and (e xor f))) + constants[i + 1] + words[i + 1]
            c = (c + t1) and MASK
            g = (t1 + rot2(h) + ((h and a) or (b and (h or a)))) and MASK

            t1 = f + rot6(c) + (e xor (c and (d xor e))) + constants[i + 2] + words[i + 2]
            b = (b + t1) and MASK
            f = (t1 + rot2(g) + ((g and h) or (a and (g or h)))) and MASK

            t1 = e + rot6(b) + (d xor (b and (c xor d))) + constants[i + 3] + words[i + 3]
            a = (a + t1) and MASK
            e = (t1 + rot2(f) + ((f and g) or (h and (f or g)))) and MASK

            t1 = d + rot6(a) + (c xor (a and (b xor c))) + constants[i + 4] + words[i + 4]
            h = (h + t1) and MASK
            d = (t1 + rot2(e) + ((e and f) or (g and (e or f)))) and MASK

            t1 = c + rot6(h) + (b xor (h and (a xor b))) + constants[i + 5] + words[i + 5]
            g = (g + t1) and MASK
            c = (t1 + rot2(d) + ((d and e) or (f and (d or e)))) and MASK

            t1 = b + rot6(g) + (a xor (g and (h xor a))) + constants[i + 6] + words[i + 6]
            f = (f + t1) and MASK
            b = (t1 + rot2(c) + ((c and d) or (e and (c or d)))) and MASK

            t1 = a + rot6(f) + (h xor (f and (g xor h))) + constants[i + 7] + words[i + 7]
            e = (e + t1) and MASK
            a = (t1 + rot2(b) + ((b and c) or (d and (b or c)))) and MASK

            i += 8
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: MutableList<Long>, firstWord: Int = 16) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words.add((s1 + words[index - 7] + s0 + words[index - 16]) and MASK)
        }
    }

    /** 用前 7 个字节决定初始状态，这是"改了初值"的那一半 */
    private fun sha256Init(data: ByteArray): LongArray {
        val words = MutableList<Long>(14) { 0L }
        var w14 = 0L
        for (i in 0 until 3) w14 = (w14 shl 8) or (data[i].toLong() and 0xFF)
        words.add(w14)
        var w15 = 0L
        for (i in 3 until 7) w15 = (w15 shl 8) or (data[i].toLong() and 0xFF)
        words.add(w15)

        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words.add(value and MASK)
        }
        words.add(
            (sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND[14]) and MASK
        )
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words.toLongArray(), 14)
        return LongArray(8) { (SHA256_INITIAL[it] + working[it]) and MASK }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "令牌原文至少要 7 字节" }
        val length = 57 + data.size
        val padded = ArrayList<Byte>(data.size + 64)
        for (i in 7 until data.size) padded.add(data[i])
        padded.add(0x80.toByte())
        val zeros = ((55 - length) % 64 + 64) % 64
        repeat(zeros) { padded.add(0) }

        var lenBits = length.toLong() * 8
        val lenBytes = ByteArray(8)
        for (i in 7 downTo 0) {
            lenBytes[i] = (lenBits and 0xFF).toByte()
            lenBits = lenBits ushr 8
        }
        for (b in lenBytes) padded.add(b)

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = ArrayList<Long>(64)
            for (w in 0 until 16) {
                var v = 0L
                for (k in 0 until 4) {
                    v = (v shl 8) or (padded[offset + w * 4 + k].toLong() and 0xFF)
                }
                words.add(v)
            }
            expandSchedule(words)
            val working = sha256Rounds(state, words.toLongArray())
            state = LongArray(8) { (state[it] + working[it]) and MASK }
            offset += 64
        }
        val out = ByteArray(32)
        for (w in 0 until 8) {
            for (k in 0 until 4) {
                out[w * 4 + k] = ((state[w] ushr (8 * (3 - k))) and 0xFF).toByte()
            }
        }
        return out
    }

    /** CS64：以 SHA 结果的前 8 字节当钥匙的第二台混合器 */
    private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
        var keyA = readLe32(digest, 0) or 1L
        var keyB = readLe32(digest, 4) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and MASK, (keyB * CS64_FACTOR) and MASK)

        val terms = ArrayList<Long>(CS64_COEFFICIENTS.size + 16)
        val offsets = ArrayList<Long>(CS64_COEFFICIENTS.size + 16)
        for (i in CS64_COEFFICIENTS.indices) {
            terms.add((CS64_COEFFICIENTS[i][0] * factors[i % 2]) and MASK)
            offsets.add(CS64_COEFFICIENTS[i][1])
        }

        val withZero = ByteArray(data.size + 1)
        System.arraycopy(data, 0, withZero, 1, data.size)
        val words = wordsLe4Even(withZero)
        for (index in words.indices) {
            val word = words[index]
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term and MASK)
            offsets.add(offset and MASK)
        }

        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]
            val y = terms[i + 1]

            var t = rol16((offsets[i] + x) and MASK)
            var q = (t * 0x76F50F34L) and MASK
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and MASK
            t = rol16((offsets[i + 1] + y) and MASK)
            q = (t * 0x1881CE3CL) and MASK
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and MASK
            swapAcc = (swapAcc + swapLast) and MASK

            state = (state * keyA + x) and MASK
            state = (rol16(state) * 0xCF98B111L) and MASK
            state = (rol16(state) * 0x87085B9FL) and MASK
            state = (rol16(state) * 0x12CEB96DL) and MASK
            state = (rol16(state) * 0x257E1D83L) and MASK
            acc += state

            t = (state * keyB + y) and MASK
            t = (rol16(t) * 0xA27416F5L) and MASK
            t = (rol16(t) * 0xD38396FFL) and MASK
            state = (rol16((rol16(t) * 0x7C932B89L) and MASK) * 0xBFA49F69L) and MASK
            acc = (acc + state) and MASK
            i += 2
        }
        return longArrayOf(swapLast xor state, swapAcc xor acc)
    }

    private fun readLe32(bytes: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 4) v = v or ((bytes[at + k].toLong() and 0xFF) shl (8 * k))
        return v
    }

    /** 与 `struct.pack("<II", ...).hex().upper()` 同序 */
    private fun hexLittleEndian(word: Long): String {
        val sb = StringBuilder(8)
        for (k in 0 until 4) {
            val b = ((word ushr (8 * k)) and 0xFF).toInt()
            sb.append(HEX[b ushr 4]).append(HEX[b and 0xF])
        }
        return sb.toString()
    }

    private fun ByteArray.toHexUpper(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }
}
