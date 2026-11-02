package com.thalzeronlabs.llama

/**
 * Finds stop sequences in streamed text. Text that could be the beginning of a stop sequence is held back until
 * it is clear whether the sequence completes, so the stop sequence itself never reaches the caller, even when it is
 * split across several tokens.
 */
internal class StopSequenceMatcher(stops: List<String>) {

    private val stops = stops.filter { it.isNotEmpty() }.distinct()
    private val buffer = StringBuilder()

    /** True once a stop sequence was found; nothing more should be pushed. */
    var stopped = false
        private set

    /** Adds [text] and returns what can safely be shown now. */
    fun push(text: String): String {
        if (stops.isEmpty()) return text
        check(!stopped) { "A stop sequence was already found." }
        buffer.append(text)

        val hit = stops.mapNotNull { stop -> buffer.indexOf(stop).takeIf { it >= 0 } }.minOrNull()
        if (hit != null) {
            stopped = true
            val out = buffer.substring(0, hit)
            buffer.setLength(0)
            return out
        }

        val keep = longestSuffixThatStartsAStop()
        val out = buffer.substring(0, buffer.length - keep)
        buffer.delete(0, buffer.length - keep)
        return out
    }

    /** Returns the held-back text when the generation ends without a stop sequence. */
    fun flush(): String {
        val out = if (stopped) "" else buffer.toString()
        buffer.setLength(0)
        return out
    }

    private fun longestSuffixThatStartsAStop(): Int {
        val maxLen = minOf(buffer.length, (stops.maxOfOrNull { it.length } ?: 1) - 1)
        for (len in maxLen downTo 1) {
            val suffix = buffer.substring(buffer.length - len)
            if (stops.any { it.startsWith(suffix) }) return len
        }
        return 0
    }
}
