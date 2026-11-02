package com.thalzeronlabs.llama

/**
 * A small, strict JSON reader and writer, enough to read JSON Schemas without adding a dependency.
 * Values map to: object → [Map] (insertion-ordered), array → [List], string → [String], number → [Double] or
 * [Long], `true`/`false` → [Boolean], `null` → `null`.
 */
internal object Json {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("unexpected content after the JSON value")
        return value
    }

    /** Writes [value] as compact JSON. */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun quote(text: String): String = StringBuilder().also { writeString(it, text) }.toString()

    private fun writeTo(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Long, is Int -> out.append(value)
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) out.append(value.toLong()) else out.append(value)
            is Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(out, k.toString())
                    out.append(':')
                    writeTo(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeTo(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Cannot write ${value::class.java.name} as JSON")
        }
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Reader(private val text: String) {
        private var pos = 0

        fun atEnd() = pos >= text.length

        fun fail(message: String): Nothing = throw IllegalArgumentException("Invalid JSON at offset $pos: $message")

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(): Any? {
            if (atEnd()) fail("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) fail("expected $word")
            pos += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected a property name")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                pos++
                skipWhitespace()
                map[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = text[pos++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("short \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                out.append(hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && text[pos] in "0123456789.eE+-") pos++
            val raw = text.substring(start, pos)
            if (raw.none { it in ".eE" }) raw.toLongOrNull()?.let { return it }
            return raw.toDoubleOrNull() ?: fail("bad number '$raw'")
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end of input") else text[pos]
    }
}
