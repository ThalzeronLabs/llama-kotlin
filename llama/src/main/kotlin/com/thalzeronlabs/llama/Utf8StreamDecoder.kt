package com.thalzeronlabs.llama

/**
 * Turns a stream of byte chunks into text without breaking multi-byte characters.
 *
 * A model token is a piece of UTF-8, not a character: an emoji or an accented letter can arrive split across two
 * tokens. Decoding every token on its own would print "�". This keeps the unfinished tail of a character until the
 * rest of its bytes arrive.
 */
class Utf8StreamDecoder {

    private var pending = ByteArray(0)

    /** Adds [bytes] and returns all text that is now complete (possibly empty). */
    fun push(bytes: ByteArray): String {
        val all = pending + bytes
        val cut = completePrefixLength(all)
        pending = all.copyOfRange(cut, all.size)
        return String(all, 0, cut, Charsets.UTF_8)
    }

    /** Returns whatever is left; an unfinished character at the very end becomes "�". */
    fun flush(): String {
        val rest = String(pending, Charsets.UTF_8)
        pending = ByteArray(0)
        return rest
    }

    private fun completePrefixLength(bytes: ByteArray): Int {
        // Walk back over at most 3 continuation bytes to find where the last character starts.
        var i = bytes.size - 1
        var continuation = 0
        while (i >= 0 && continuation < 3 && bytes[i].isContinuation()) {
            i--
            continuation++
        }
        if (i < 0) return bytes.size // only stray continuation bytes: let the decoder replace them
        val needed = sequenceLength(bytes[i])
        val available = bytes.size - i
        return if (needed > available) i else bytes.size
    }

    private fun Byte.isContinuation() = (toInt() and 0xC0) == 0x80

    private fun sequenceLength(lead: Byte): Int {
        val b = lead.toInt() and 0xFF
        return when {
            b < 0x80 -> 1
            b shr 5 == 0b110 -> 2
            b shr 4 == 0b1110 -> 3
            b shr 3 == 0b11110 -> 4
            else -> 1 // invalid lead byte: emit it and let the decoder replace it
        }
    }
}
