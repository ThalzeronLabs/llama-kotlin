package com.thalzeronlabs.llama

/**
 * A constraint on what the model may write, applied token by token by llama.cpp's grammar sampler: tokens that
 * would break the grammar are never sampled, so the output always matches it.
 *
 * ```kotlin
 * // Free-form GBNF (https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md)
 * val yesNo = Grammar.gbnf("""root ::= "yes" | "no"""")
 *
 * // Any JSON object that follows a JSON Schema
 * val person = Grammar.jsonSchema("""
 *     {"type": "object",
 *      "properties": {"name": {"type": "string"}, "age": {"type": "integer"}},
 *      "required": ["name", "age"]}
 * """)
 * session.chat(messages, grammar = person).toList().joinToString("")  // {"name": "Ana", "age": 31}
 * ```
 *
 * When the grammar is complete the model can only end the answer, so generation stops on its own.
 */
class Grammar private constructor(
    /** The grammar in GBNF. */
    val gbnf: String,
    /** Name of the start rule. */
    val root: String,
) {
    override fun toString() = gbnf

    companion object {
        /**
         * A grammar written in GBNF. It is parsed by llama.cpp when a generation starts; a grammar that does not parse
         * makes that generation fail with [IllegalArgumentException].
         */
        fun gbnf(text: String, root: String = "root"): Grammar {
            require(text.isNotBlank()) { "The grammar is empty." }
            require(root.matches(RULE_NAME)) { "Invalid root rule name: \"$root\"" }
            return Grammar(text, root)
        }

        /**
         * Restricts the output to JSON that validates against [schema] (a JSON Schema document, as text).
         *
         * Supported: `type` (`object`, `array`, `string`, `number`, `integer`, `boolean`, `null`, or a list of
         * them), `properties`, `required`, `items`, `minItems`, `maxItems`, `minLength`, `maxLength`, `enum`, `const`,
         * `anyOf`, `oneOf` and local `$ref`s (`#/$defs/...`, `#/definitions/...`, `#`). Properties are written in the
         * order the schema lists them and only listed properties are allowed. Annotations such as `description` or
         * `title` are ignored. Keywords that cannot be turned into a grammar here (`pattern`, `allOf`, `not`, ...)
         * throw [IllegalArgumentException] instead of being silently dropped. Numeric bounds (`minimum`, `maximum`)
         * and `format` are not enforced: validate those after parsing.
         */
        fun jsonSchema(schema: String): Grammar = Grammar(JsonSchemaGrammar.convert(schema), "root")

        /** Any valid JSON object (`{...}`). */
        fun jsonObject(): Grammar = jsonSchema("""{"type": "object"}""")

        /** Any valid JSON value. */
        fun json(): Grammar = jsonSchema("{}")

        private val RULE_NAME = Regex("[a-zA-Z0-9-]+")
    }
}
