/*
 * Kotlin adaptation of edge-tts drm.py, commit ef51a4d (Sec-MS-GEC v2).
 * Copyright edge-tts contributors. SPDX-License-Identifier: LGPL-3.0-only
 * Source: https://github.com/rany2/edge-tts/blob/ef51a4d/src/edge_tts/drm.py
 * See THIRD_PARTY_NOTICES.md and licenses/edge-tts-LGPL-3.0.txt.
 */
package com.eugen.ankiaudio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Edge's current read-aloud request token. Integer operations wrap at 32 bits. */
internal object EdgeToken {
    private val initial = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt(),
    )
    private val initialState = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49.toInt(), 0x04BD35AB.toInt(), 0xFEB0A7E4.toInt(), 0x03EFFBD2.toInt(),
    )
    private val schedule = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F.toInt(), 0xA0C09EE2.toInt(), 0x0CFA32BA.toInt(),
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E.toInt(), 0x7188E606.toInt(), 0x00E5AB33.toInt(),
        0xD258B060.toInt(), 0x72766564.toInt(),
    )
    private val roundConstants = intArrayOf(
        0x428A2F98.toInt(), 0x71374491.toInt(), 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B.toInt(), 0x59F111F1.toInt(), 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01.toInt(), 0x243185BE.toInt(), 0x550C7DC3.toInt(),
        0x72BE5D74.toInt(), 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6.toInt(), 0x240CA1CC.toInt(),
        0x2DE92C6F.toInt(), 0x4A7484AA.toInt(), 0x5CB0A9DC.toInt(), 0x76F988DA.toInt(),
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351.toInt(), 0x14292967.toInt(),
        0x27B70A85.toInt(), 0x2E1B2138.toInt(), 0x4D2C6DFC.toInt(), 0x53380D13.toInt(),
        0x650A7354.toInt(), 0x766A0ABB.toInt(), 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070.toInt(),
        0x19A4C116.toInt(), 0x1E376C08.toInt(), 0x2748774C.toInt(), 0x34B0BCB5.toInt(),
        0x391C0CB3.toInt(), 0x4ED8AA4A.toInt(), 0x5B9CCA4F.toInt(), 0x682E6FF3.toInt(),
        0x748F82EE.toInt(), 0x78A5636F.toInt(), 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
    private val coefficients = arrayOf(
        intArrayOf(0xE82E91B1.toInt(), 0xB32700D0.toInt()),
        intArrayOf(0x44DE24C6.toInt(), 0x88DFF51D.toInt()),
        intArrayOf(0x3BEB65BC.toInt(), 0x29514038.toInt()),
        intArrayOf(0xC89C5933.toInt(), 0xB328CBDF.toInt()),
        intArrayOf(0x8D1EC211.toInt(), 0xD405B4D5.toInt()),
        intArrayOf(0x314EF5D5.toInt(), 0x26E55F3C.toInt()),
        intArrayOf(0xE92491B1.toInt(), 0x607801C6.toInt()),
        intArrayOf(0xF0A5B9D5.toInt(), 0x82B2C1FB.toInt()),
        intArrayOf(0x06064CF3.toInt(), 0xFA51A3DA.toInt()),
        intArrayOf(0xDA79FA60.toInt(), 0x207CC464.toInt()),
        intArrayOf(0xBED20560.toInt(), 0x3C3C74F3.toInt()),
        intArrayOf(0x89F9C9F8.toInt(), 0xD2D14B61.toInt()),
        intArrayOf(0x35FA38C6.toInt(), 0xBB04A2C1.toInt()),
        intArrayOf(0xCB49E3C6.toInt(), 0xC5626167.toInt()),
    )

    fun generate(unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        val seconds = unixSeconds + 11_644_473_600L
        val ticks = (seconds - seconds % 300) * 10_000_000L
        val data = "${ticks}6A5AA1D4EAFF4E9FB37E23D68491D6F4".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        val cs = cs64(data, digest)
        return hex(cs) + "-" + hex(digest)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun sigma0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
    private fun swap(x: Int) = Integer.rotateLeft(x, 16)

    private fun expand(w: IntArray, start: Int = 16) {
        for (i in start until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, words: IntArray, first: Int = 0): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in first until 64) {
            val k = if (first == 14 && i == 14) 0xC9DC06A7.toInt() else roundConstants[i]
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                (g xor (e and (f xor g))) + k + words[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val words = IntArray(64)
        words[14] = (data[0].toInt() and 255 shl 16) or (data[1].toInt() and 255 shl 8) or (data[2].toInt() and 255)
        words[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += if (i == 16) sigma1(words[i - 2]) xor 0x000B92C0 else sigma1(words[i - 2])
            if (i - 7 >= 14) value += words[i - 7]
            if (i - 15 >= 14) value += if (i == 29) sigma0(words[i - 15]) xor 0x059C0B80 else sigma0(words[i - 15])
            words[i] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            0xC9DC06A7.toInt() - roundConstants[14]
        expand(words, 31)
        var state = rounds(initialState, words, 14).mapIndexed { i, x -> initial[i] + x }.toIntArray()
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val padded = data.copyOfRange(7, data.size) + byteArrayOf(0x80.toByte()) + ByteArray(zeros) +
            ByteBuffer.allocate(8).putLong(length.toLong() * 8).array()
        for (offset in padded.indices step 64) {
            val buffer = ByteBuffer.wrap(padded, offset, 64)
            for (i in 0 until 16) words[i] = buffer.int
            expand(words)
            val working = rounds(state, words)
            state = IntArray(8) { state[it] + working[it] }
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun cs64(data: ByteArray, digest: ByteArray): ByteArray {
        val key = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val a = key.int or 1
        val b = key.int or 1
        val factors = intArrayOf(a * 0x82893933.toInt(), b * 0x82893933.toInt())
        val terms = coefficients.mapIndexed { i, row -> row[0] * factors[i % 2] }.toMutableList()
        val offsets = coefficients.map { it[1] }.toMutableList()
        val input = byteArrayOf(0) + data
        val padded = input.copyOf(((input.size + 7) / 8) * 8)
        val buffer = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (buffer.hasRemaining()) {
            val word = buffer.int
            var term = word * if (i % 2 == 0) a else b
            var offset = swap(word) * if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt()
            if (i == 0) {
                term += 0xF2F90B1A.toInt() * factors[0]
                offset += 0x0B1A0000
            }
            terms += term; offsets += offset
            i++
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        for (j in terms.indices step 2) {
            val x = terms[j]; val y = terms[j + 1]
            var q = swap(offsets[j] + x) * 0x76F50F34
            swapAcc += swap(q) * 0xC3EFEA97.toInt() + q
            q = swap(offsets[j + 1] + y) * 0x1881CE3C
            swapLast = swap(q) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * a + x
            state = swap(state) * 0xCF98B111.toInt()
            state = swap(state) * 0x87085B9F.toInt()
            state = swap(state) * 0x12CEB96D
            state = swap(state) * 0x257E1D83
            acc += state
            var t = state * b + y
            t = swap(t) * 0xA27416F5.toInt()
            t = swap(t) * 0xD38396FF.toInt()
            state = swap(swap(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }
}
