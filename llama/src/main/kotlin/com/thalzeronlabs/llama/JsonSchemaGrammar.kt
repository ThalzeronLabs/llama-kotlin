package com.thalzeronlabs.llama

/**
 * Turns a JSON Schema into a GBNF grammar whose every sentence is JSON valid against the schema.
 * See [Grammar.jsonSchema] for the supported subset.
 */
internal object JsonSchemaGrammar {

    fun convert(schemaText: String): String {
        val schema = try {
            Json.parse(schemaText)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The JSON Schema is not valid JSON: ${e.message}", e)
        }
        return Converter(schema).build()
    }

    /** Shared building blocks, as in llama.cpp's grammars/json.gbnf. Whitespace is bounded so it cannot loop. */
    private val PRIMITIVES = linkedMapOf(
        "ws" to """| " " | "\n" [ \t]{0,20}""",
        "value" to """object | array | string | number | boolean | null""",
        "object" to """"{" ws ( string ws ":" ws value ws ( "," ws string ws ":" ws value ws )* )? "}"""",
        "array" to """"[" ws ( value ws ( "," ws value ws )* )? "]"""",
        "string" to """"\"" char* "\""""",
        "char" to """[^"\\\x7F\x00-\x1F] | "\\" ( ["\\/bfnrt] | "u" [0-9a-fA-F]{4} )""",
        "number" to """integer ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )?""",
        "integer" to """"-"? ( "0" | [1-9] [0-9]{0,15} )""",
        "boolean" to """"true" | "false"""",
        "null" to """"null"""",
    )

    private val UNSUPPORTED = listOf(
        "pattern", "patternProperties", "allOf", "not", "if", "then", "else", "prefixItems", "contains",
        "uniqueItems", "propertyNames", "dependentSchemas", "dependentRequired", "unevaluatedProperties",
        "unevaluatedItems", "minProperties", "maxProperties",
    )

    private class Converter(private val rootSchema: Any?) {
        private val rules = LinkedHashMap<String, String>()
        private val refRules = HashMap<String, String>()

        fun build(): String {
            val top = visit(rootSchema, "value-root")
            return buildString {
                append("root ::= ").append(top).append('\n')
                rules.forEach { (name, body) -> append(name).append(" ::= ").append(body).append('\n') }
                PRIMITIVES.forEach { (name, body) -> append(name).append(" ::= ").append(body).append('\n') }
            }
        }

        /** Returns a GBNF expression (a rule name or an inline expression) matching [schema]. */
        private fun visit(schema: Any?, hint: String): String {
            if (schema == true) return "value"
            if (schema == false) throw IllegalArgumentException("A schema of `false` accepts nothing.")
            if (schema !is Map<*, *>) throw IllegalArgumentException("A schema must be an object or a boolean, got: ${Json.write(schema)}")

            UNSUPPORTED.firstOrNull { it in schema }?.let {
                throw IllegalArgumentException("The JSON Schema keyword \"$it\" is not supported by Grammar.jsonSchema().")
            }

            (schema["\$ref"] as? String)?.let { return ref(it) }
            if ("const" in schema) return literal(Json.write(schema["const"]))
            (schema["enum"] as? List<*>)?.let { values ->
                require(values.isNotEmpty()) { "\"enum\" must not be empty." }
                return group(values.map { literal(Json.write(it)) })
            }
            (schema["anyOf"] ?: schema["oneOf"])?.let { alternatives ->
                require(alternatives is List<*> && alternatives.isNotEmpty()) { "\"anyOf\"/\"oneOf\" must be a non-empty list." }
                return group(alternatives.mapIndexed { i, alt -> visit(alt, "$hint-$i") })
            }

            return when (val type = schema["type"]) {
                null -> when {
                    "properties" in schema || "additionalProperties" in schema || "required" in schema -> objectRule(schema, hint)
                    "items" in schema -> arrayRule(schema, hint)
                    else -> "value"
                }
                is String -> typed(type, schema, hint)
                is List<*> -> group(type.map { typed(it as? String ?: throw IllegalArgumentException("Bad \"type\": $type"), schema, "$hint-$it") })
                else -> throw IllegalArgumentException("Bad \"type\": ${Json.write(type)}")
            }
        }

        private fun typed(type: String, schema: Map<*, *>, hint: String): String = when (type) {
            "object" -> objectRule(schema, hint)
            "array" -> arrayRule(schema, hint)
            "string" -> stringRule(schema, hint)
            "number" -> "number"
            "integer" -> "integer"
            "boolean" -> "boolean"
            "null" -> "null"
            else -> throw IllegalArgumentException("Unknown JSON Schema type \"$type\".")
        }

        private fun stringRule(schema: Map<*, *>, hint: String): String {
            val min = schema.int("minLength") ?: 0
            val max = schema.int("maxLength")
            if (min == 0 && max == null) return "string"
            require(max == null || max >= min) { "maxLength ($max) is smaller than minLength ($min)." }
            val name = newRule(hint)
            rules[name] = """"\"" char${repetition(min, max)} "\"""""
            return name
        }

        private fun objectRule(schema: Map<*, *>, hint: String): String {
            val properties = (schema["properties"] as? Map<*, *>).orEmpty()
            val required = (schema["required"] as? List<*>).orEmpty().map { it.toString() }.toSet()
            required.firstOrNull { it !in properties }?.let {
                throw IllegalArgumentException("Required property \"$it\" is not listed in \"properties\".")
            }

            if (properties.isEmpty()) {
                val additional = schema["additionalProperties"]
                if (additional !is Map<*, *> || additional.isEmpty()) return "object"
                val name = newRule(hint)
                val valueExpr = visit(additional, "$name-value")
                val kv = """string ws ":" ws $valueExpr ws"""
                rules[name] = """"{" ws ( $kv ( "," ws $kv )* )? "}""""
                return name
            }

            val name = newRule(hint)
            val keys = properties.keys.map { it.toString() }
            val kvs = keys.map { key ->
                val kvName = newRule("$name-${sanitize(key)}-kv")
                rules[kvName] = "" // reserve the name before visiting (nested rules get their own)
                val valueExpr = visit(properties[key], "$name-${sanitize(key)}")
                rules[kvName] = """${literal(Json.quote(key))} ws ":" ws $valueExpr ws"""
                kvName
            }
            val isRequired = keys.map { it in required }

            // rest(j): properties j..n-1 when at least one property was already written (so each needs a comma).
            fun rest(j: Int): String = (j until keys.size).joinToString(" ") { k ->
                if (isRequired[k]) """"," ws ${kvs[k]}""" else """( "," ws ${kvs[k]} )?"""
            }

            // first(i): properties i..n-1 when nothing was written yet.
            fun first(i: Int): String {
                if (i == keys.size) return ""
                val sequence = listOf(kvs[i], rest(i + 1)).filter { it.isNotEmpty() }.joinToString(" ")
                if (isRequired[i]) return sequence
                val skip = first(i + 1)
                if (skip.isEmpty()) return "( $sequence )?"
                val alt = newRule("$name-from-${sanitize(keys[i])}")
                rules[alt] = "$sequence | $skip"
                return alt
            }

            val members = first(0)
            rules[name] = if (members.isEmpty()) """"{" ws "}"""" else """"{" ws $members "}""""
            return name
        }

        private fun arrayRule(schema: Map<*, *>, hint: String): String {
            val min = schema.int("minItems") ?: 0
            val max = schema.int("maxItems")
            require(max == null || max >= min) { "maxItems ($max) is smaller than minItems ($min)." }
            val name = newRule(hint)
            rules[name] = ""
            val item = schema["items"]?.let { visit(it, "$name-item") } ?: "value"
            val elements = when {
                max == 0 -> ""
                min == 0 -> {
                    val more = if (max == 1) "" else """ ( "," ws $item ws )${repetition(0, max?.minus(1))}"""
                    "( $item ws$more )?"
                }
                else -> {
                    val more = if (max == 1) "" else """ ( "," ws $item ws )${repetition(min - 1, max?.minus(1))}"""
                    "$item ws$more"
                }
            }
            rules[name] = if (elements.isEmpty()) """"[" ws "]"""" else """"[" ws $elements "]""""
            return name
        }

        private fun ref(ref: String): String {
            refRules[ref]?.let { return it }
            require(ref == "#" || ref.startsWith("#/")) { "Only local \$refs (\"#/...\") are supported, got \"$ref\"." }
            var target: Any? = rootSchema
            if (ref != "#") {
                for (part in ref.removePrefix("#/").split('/')) {
                    val key = part.replace("~1", "/").replace("~0", "~")
                    target = (target as? Map<*, *>)?.get(key)
                        ?: throw IllegalArgumentException("Cannot resolve \$ref \"$ref\".")
                }
            }
            val name = newRule("ref-" + sanitize(ref.substringAfterLast('/').ifEmpty { "root" }))
            refRules[ref] = name
            rules[name] = ""
            rules[name] = visit(target, name)
            return name
        }

        private fun newRule(hint: String): String {
            val base = sanitize(hint)
            var name = base
            var n = 2
            while (name in rules || name in PRIMITIVES || name == "root") name = "$base-${n++}"
            rules[name] = ""
            return name
        }
    }

    private fun Map<*, *>.int(key: String): Int? = when (val v = this[key]) {
        null -> null
        is Number -> v.toInt().also { require(it >= 0) { "\"$key\" must not be negative." } }
        else -> throw IllegalArgumentException("\"$key\" must be a number.")
    }

    private fun group(alternatives: List<String>): String =
        if (alternatives.size == 1) alternatives[0] else alternatives.joinToString(" | ", "( ", " )")

    private fun repetition(min: Int, max: Int?): String = when {
        max == null && min == 0 -> "*"
        max == null && min == 1 -> "+"
        max == null -> "{$min,}"
        min == max -> "{$min}"
        else -> "{$min,$max}"
    }

    /** A GBNF string literal matching [text] exactly. */
    fun literal(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\x%02X".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun sanitize(text: String): String =
        text.replace(Regex("[^a-zA-Z0-9]+"), "-").trim('-').ifEmpty { "p" }
}
