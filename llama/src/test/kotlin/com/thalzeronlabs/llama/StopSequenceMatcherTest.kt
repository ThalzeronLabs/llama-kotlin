package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSequenceMatcherTest {

    private fun run(stops: List<String>, vararg chunks: String): Pair<String, Boolean> {
        val m = StopSequenceMatcher(stops)
        val out = StringBuilder()
        for (c in chunks) {
            out.append(m.push(c))
            if (m.stopped) return out.toString() to true
        }
        out.append(m.flush())
        return out.toString() to false
    }

    @Test
    fun `no stop sequences pass everything through`() {
        val m = StopSequenceMatcher(emptyList())
        assertEquals("abc", m.push("abc"))
        assertFalse(m.stopped)
    }

    @Test
    fun `a stop sequence inside one chunk cuts the text before it`() {
        assertEquals("Hello " to true, run(listOf("STOP"), "Hello STOP and more"))
    }

    @Test
    fun `a stop sequence split across chunks is caught and never shown`() {
        val m = StopSequenceMatcher(listOf("</answer>"))
        assertEquals("42", m.push("42</"))
        assertEquals("", m.push("ans"))
        assertEquals("", m.push("wer> trailing"))
        assertTrue(m.stopped)
    }

    @Test
    fun `the earliest of several stop sequences wins`() {
        assertEquals("a" to true, run(listOf("ccc", "b"), "abccc"))
    }

    @Test
    fun `held back text is released when it stops matching`() {
        val m = StopSequenceMatcher(listOf("###"))
        assertEquals("x", m.push("x#"))
        assertEquals("#y", m.push("y"))
    }

    @Test
    fun `overlapping prefixes keep the longest possible start`() {
        // "aab" must be found even though the buffer starts matching at the first "a".
        assertEquals("x" to true, run(listOf("aab"), "xa", "a", "b"))
    }

    @Test
    fun `flush returns a pending partial match`() {
        assertEquals("end <st" to false, run(listOf("<stop>"), "end <st"))
    }

    @Test
    fun `empty and duplicate stop strings are ignored`() {
        assertEquals("abc" to false, run(listOf("", ""), "abc"))
    }
}
