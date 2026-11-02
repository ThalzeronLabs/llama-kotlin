package com.thalzeronlabs.llama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class JsonSchemaGrammarTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun rules(schema: String): Map<String, String> =
        JsonSchemaGrammar.convert(schema).lines().filter { it.isNotBlank() }.associate {
            val (name, body) = it.split(" ::= ", limit = 2)
            name to body
        }

    // --- shape of the generated grammar --------------------------------------------------------------------------

    @Test
    fun `an empty schema accepts any JSON value`() {
        assertEquals("value", rules("{}")["root"])
        assertEquals("value", rules("true")["root"])
    }

    @Test
    fun `primitive types map to the shared rules`() {
        assertEquals("string", rules("""{"type": "string"}""")["root"])
        assertEquals("integer", rules("""{"type": "integer"}""")["root"])
        assertEquals("number", rules("""{"type": "number"}""")["root"])
        assertEquals("boolean", rules("""{"type": "boolean"}""")["root"])
        assertEquals("null", rules("""{"type": "null"}""")["root"])
        assertEquals("( string | null )", rules("""{"type": ["string", "null"]}""")["root"])
    }

    @Test
    fun `required properties are written in schema order`() {
        val r = rules("""{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "integer"}}, "required": ["name", "age"]}""")
        val obj = r.getValue(r.getValue("root"))
        assertEquals(""""{" ws value-root-name-kv "," ws value-root-age-kv "}"""", obj)
        assertEquals(""""\"name\"" ws ":" ws string ws""", r["value-root-name-kv"])
        assertEquals(""""\"age\"" ws ":" ws integer ws""", r["value-root-age-kv"])
    }

    @Test
    fun `optional properties may be skipped without leaving a dangling comma`() {
        val r = rules("""{"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}}}""")
        val g = r.values.joinToString("\n")
        // Either "a" (optionally followed by ", b"), or only "b", or nothing.
        assertTrue(g, g.contains("""value-root-a-kv ( "," ws value-root-b-kv )? | ( value-root-b-kv )?"""))
    }

    @Test
    fun `enum and const become literals`() {
        assertEquals("""( "\"red\"" | "\"green\"" | "1" | "null" )""", rules("""{"enum": ["red", "green", 1, null]}""")["root"])
        assertEquals(""""\"fixed\""""", rules("""{"const": "fixed"}""")["root"])
    }

    @Test
    fun `quotes and backslashes in keys are escaped twice (JSON, then GBNF)`() {
        val r = rules("""{"type": "object", "properties": {"say \"hi\"": {"type": "boolean"}}, "required": ["say \"hi\""]}""")
        val kv = r.entries.single { it.key.endsWith("-kv") }.value
        assertEquals(""""\"say \\\"hi\\\"\"" ws ":" ws boolean ws""", kv)
    }

    @Test
    fun `array bounds become repetitions`() {
        val r = rules("""{"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 3}""")
        assertEquals(""""[" ws string ws ( "," ws string ws ){0,2} "]"""", r.getValue(r.getValue("root")))
        val open = rules("""{"type": "array", "items": {"type": "integer"}}""")
        assertEquals(""""[" ws ( integer ws ( "," ws integer ws )* )? "]"""", open.getValue(open.getValue("root")))
    }

    @Test
    fun `string length bounds become repetitions`() {
        val r = rules("""{"type": "string", "minLength": 2, "maxLength": 5}""")
        assertEquals(""""\"" char{2,5} "\""""", r.getValue(r.getValue("root")))
    }

    @Test
    fun `recursive refs resolve to a named rule`() {
        val r = rules(
            """{"${'$'}defs": {"node": {"type": "object", "properties": {"next": {"anyOf": [{"${'$'}ref": "#/${'$'}defs/node"}, {"type": "null"}]}}, "required": ["next"]}},
                "${'$'}ref": "#/${'$'}defs/node"}""",
        )
        assertEquals("ref-node", r["root"])
        assertTrue(r.values.any { it.contains("( ref-node | null )") })
    }

    @Test
    fun `a map schema (additionalProperties only) allows any key`() {
        val r = rules("""{"type": "object", "additionalProperties": {"type": "number"}}""")
        assertEquals(""""{" ws ( string ws ":" ws number ws ( "," ws string ws ":" ws number ws )* )? "}"""", r.getValue(r.getValue("root")))
    }

    // --- errors --------------------------------------------------------------------------------------------------

    private fun fails(schema: String): String =
        runCatching { JsonSchemaGrammar.convert(schema) }.exceptionOrNull()
            .also { assertTrue("expected IllegalArgumentException, got $it", it is IllegalArgumentException) }!!.message!!

    @Test
    fun `unsupported keywords are refused, not ignored`() {
        assertTrue(fails("""{"type": "string", "pattern": "^a+$"}""").contains("pattern"))
        assertTrue(fails("""{"allOf": [{"type": "string"}]}""").contains("allOf"))
    }

    @Test
    fun `broken schemas give a clear message`() {
        assertTrue(fails("""{"type": "object", "properties": {"a": {}}, "required": ["b"]}""").contains("\"b\""))
        assertTrue(fails("""{"${'$'}ref": "https://example.com/schema.json"}""").contains("local"))
        assertTrue(fails("""{"type": "array", "minItems": 3, "maxItems": 1}""").contains("maxItems"))
        assertTrue(fails("""{"type": "strnig"}""").contains("strnig"))
        assertTrue(fails("""{"type": """).contains("not valid JSON"))
    }

    @Test
    fun `annotations are ignored`() {
        assertEquals("integer", rules("""{"type": "integer", "description": "age in years", "title": "Age", "minimum": 0}""")["root"])
    }

    @Test
    fun `the public shortcut uses the root rule`() {
        val g = Grammar.jsonSchema("""{"type": "boolean"}""")
        assertEquals("root", g.root)
        assertTrue(g.gbnf.startsWith("root ::= boolean"))
        assertFalse(Grammar.json().gbnf.isBlank())
        assertTrue(Grammar.jsonObject().gbnf.startsWith("root ::= object"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank GBNF grammar is rejected`() {
        Grammar.gbnf("  ")
    }

    // --- checked by llama.cpp's own grammar engine ----------------------------------------------------------------

    /**
     * Runs llama.cpp's `test-gbnf-validator` on each sample when `-Dllama.gbnfValidator=/path/to/binary` is set
     * (the host integration job in CI builds it). Without it, this test is skipped.
     */
    @Test
    fun `llama_cpp accepts valid JSON and rejects invalid JSON for every schema`() {
        val validator = System.getProperty("llama.gbnfValidator")?.let(::File)
        assumeTrue("set -Dllama.gbnfValidator to run", validator?.canExecute() == true)

        for ((schema, valid, invalid) in SAMPLES) {
            val grammar = tmp.newFile().apply { writeText(JsonSchemaGrammar.convert(schema)) }
            for (sample in valid) assertTrue("should accept: $sample\nschema: $schema", validate(validator!!, grammar, sample))
            for (sample in invalid) assertFalse("should reject: $sample\nschema: $schema", validate(validator!!, grammar, sample))
        }
    }

    private fun validate(validator: File, grammar: File, sample: String): Boolean {
        val input = tmp.newFile().apply { writeText(sample) }
        val process = ProcessBuilder(validator.absolutePath, grammar.absolutePath, input.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "validator timed out" }
        check("Failed to initialize" !in output) {
            "llama.cpp could not parse the grammar:\n$output\n${grammar.readText()}"
        }
        return "Input string is valid" in output
    }

    private companion object {
        val PERSON = """
            {"type": "object",
             "properties": {
               "name": {"type": "string", "minLength": 1},
               "age": {"type": "integer"},
               "email": {"type": ["string", "null"]},
               "tags": {"type": "array", "items": {"type": "string"}, "maxItems": 3},
               "role": {"enum": ["admin", "user"]}
             },
             "required": ["name", "age", "role"]}
        """.trimIndent()

        val SAMPLES = listOf(
            Triple(
                PERSON,
                listOf(
                    """{"name": "Ana", "age": 31, "role": "admin"}""",
                    """{"name":"Ana","age":-2,"email":null,"tags":["a","b"],"role":"user"}""",
                    "{\n  \"name\": \"Zé \\\"Q\\\" 🚀\",\n  \"age\": 0,\n  \"email\": \"a@b.c\",\n  \"role\": \"user\"\n}",
                ),
                listOf(
                    """{"name": "Ana", "role": "admin"}""", // age missing
                    """{"age": 31, "name": "Ana", "role": "admin"}""", // wrong order
                    """{"name": "", "age": 31, "role": "admin"}""", // minLength
                    """{"name": "Ana", "age": 3.5, "role": "admin"}""", // not an integer
                    """{"name": "Ana", "age": 31, "role": "root"}""", // not in enum
                    """{"name": "Ana", "age": 31, "tags": ["a","b","c","d"], "role": "user"}""", // maxItems
                    """{"name": "Ana", "age": 31, "role": "admin",}""", // trailing comma
                    """{"name": "Ana", "age": 31, "role": "admin", "x": 1}""", // unknown property
                ),
            ),
            Triple(
                """{"type": "object", "properties": {"a": {"type": "integer"}, "b": {"type": "boolean"}, "c": {"type": "null"}}}""",
                listOf("{}", """{"a": 1}""", """{"b": true}""", """{"a": 1, "c": null}""", """{"a": 1, "b": false, "c": null}"""),
                listOf("""{,"b": true}""", """{"b": true,}""", """{"b": true, "a": 1}"""),
            ),
            Triple(
                """{"type": "array", "items": {"type": "number"}, "minItems": 1}""",
                listOf("[1]", "[1.5, -2e3, 0]"),
                listOf("[]", "[01]", "[1,]"),
            ),
            Triple("{}", listOf("""{"x": [1, {"y": null}]}""", "\"s\"", "true", "12.5"), listOf("{x: 1}", "'s'", "")),
            Triple(
                """{"${'$'}defs": {"n": {"type": "object", "properties": {"v": {"type": "integer"}, "next": {"anyOf": [{"${'$'}ref": "#/${'$'}defs/n"}, {"type": "null"}]}}, "required": ["v", "next"]}}, "${'$'}ref": "#/${'$'}defs/n"}""",
                listOf("""{"v": 1, "next": {"v": 2, "next": null}}"""),
                listOf("""{"v": 1, "next": {"v": 2}}"""),
            ),
            Triple(
                """{"type": "object", "additionalProperties": {"type": "integer"}}""",
                listOf("{}", """{"any": 1, "keys": 2}"""),
                listOf("""{"a": "x"}"""),
            ),
        )
    }
}
