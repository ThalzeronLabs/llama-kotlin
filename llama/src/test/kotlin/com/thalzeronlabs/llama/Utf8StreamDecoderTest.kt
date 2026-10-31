package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8StreamDecoderTest {

    @Test
    fun `plain ascii passes straight through`() {
        val decoder = Utf8StreamDecoder()
        assertEquals("Hello", decoder.push("Hello".toByteArray()))
        assertEquals("", decoder.flush())
    }

    @Test
    fun `accented letter split across two tokens is held until complete`() {
        val bytes = "ã".toByteArray() // 2 bytes
        val decoder = Utf8StreamDecoder()
        assertEquals("", decoder.push(byteArrayOf(bytes[0])))
        assertEquals("ã", decoder.push(byteArrayOf(bytes[1])))
    }

    @Test
    fun `emoji split byte by byte comes out once, whole`() {
        val bytes = "🚀".toByteArray() // 4 bytes
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        bytes.forEach { out.append(decoder.push(byteArrayOf(it))) }
        assertEquals("🚀", out.toString())
    }

    @Test
    fun `complete text before a split character is emitted right away`() {
        val bytes = "ok 😀".toByteArray()
        val decoder = Utf8StreamDecoder()
        assertEquals("ok ", decoder.push(bytes.copyOfRange(0, bytes.size - 2)))
        assertEquals("😀", decoder.push(bytes.copyOfRange(bytes.size - 2, bytes.size)))
    }

    @Test
    fun `unfinished character at the end becomes a replacement char on flush`() {
        val bytes = "é".toByteArray()
        val decoder = Utf8StreamDecoder()
        decoder.push(byteArrayOf(bytes[0]))
        assertEquals("�", decoder.flush())
    }

    @Test
    fun `mixed scripts streamed in random chunks round-trip exactly`() {
        val text = "Olá, mundo! こんにちは 🌎 Ünïcødé"
        val bytes = text.toByteArray()
        val decoder = Utf8StreamDecoder()
        val out = StringBuilder()
        var i = 0
        var step = 1
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + step)
            out.append(decoder.push(bytes.copyOfRange(i, end)))
            i = end
            step = step % 3 + 1
        }
        out.append(decoder.flush())
        assertEquals(text, out.toString())
    }
}
