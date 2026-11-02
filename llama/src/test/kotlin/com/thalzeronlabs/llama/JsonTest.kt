package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {

    @Test
    fun `parses every JSON type`() {
        val v = Json.parse("""{"s": "a\"bé\n", "i": -12, "d": 1.5e2, "t": true, "f": false, "n": null, "a": [1, [], {}]}""")
        v as Map<*, *>
        assertEquals("a\"bé\n", v["s"])
        assertEquals(-12L, v["i"])
        assertEquals(150.0, v["d"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertNull(v["n"])
        assertEquals(listOf(1L, emptyList<Any>(), emptyMap<String, Any>()), v["a"])
    }

    @Test
    fun `keeps the order of object keys`() {
        val v = Json.parse("""{"z": 1, "a": 2, "m": 3}""") as Map<*, *>
        assertEquals(listOf("z", "a", "m"), v.keys.toList())
    }

    @Test
    fun `writes compact JSON that parses back`() {
        val text = """{"k":["x",1,2.5,true,null],"q":"say \"hi\"\\"}"""
        assertEquals(text, Json.write(Json.parse(text)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing garbage is rejected`() {
        Json.parse("""{"a": 1} x""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unterminated strings are rejected`() {
        Json.parse(""""abc""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing commas are rejected`() {
        Json.parse("""[1, 2,]""")
    }
}
