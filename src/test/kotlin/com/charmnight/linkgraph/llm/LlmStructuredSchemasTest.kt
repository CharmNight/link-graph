package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

import com.charmnight.linkgraph.testing.*

import kotlin.test.Test
import kotlin.test.assertTrue

class LlmStructuredSchemasTest {
    @Test
    fun strictStructuredSchemasRequireAllObjectProperties() {
        val violations = buildList {
            collectViolations("GENERATION_PLAN", LlmStructuredSchemas.GENERATION_PLAN, this)
            collectViolations("DISCUSSION", LlmStructuredSchemas.DISCUSSION, this)
            collectViolations("CODE_GENERATION_RESULT", LlmStructuredSchemas.CODE_GENERATION_RESULT, this)
            collectViolations("PATCH_RESULT", LlmStructuredSchemas.PATCH_RESULT, this)
            collectViolations("BEAUTIFICATION", LlmStructuredSchemas.BEAUTIFICATION, this)
        }

        assertTrue(
            violations.isEmpty(),
            "Structured schemas must satisfy strict object requirements:\n${violations.joinToString("\n")}",
        )
    }

    private fun collectViolations(
        name: String,
        schemaText: String,
        sink: MutableList<String>,
    ) {
        val root = LlmJsonCodec.parseValue(schemaText)
        visitSchema(
            node = root,
            path = name,
            sink = sink,
        )
    }

    private fun visitSchema(
        node: Any?,
        path: String,
        sink: MutableList<String>,
    ) {
        when (node) {
            is Map<*, *> -> {
                val typeValue = node["type"]
                val isObjectSchema = when (typeValue) {
                    "object" -> true
                    is List<*> -> typeValue.contains("object")
                    else -> false
                }
                val properties = node["properties"] as? Map<*, *>
                if (isObjectSchema && properties != null) {
                    if (node["additionalProperties"] != false) {
                        sink += "$path -> additionalProperties must be false"
                    }
                    val propertyNames = properties.keys.mapNotNull { it as? String }.toSet()
                    val requiredNames = (node["required"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet()
                    val missingRequired = (propertyNames - requiredNames).sorted()
                    if (missingRequired.isNotEmpty()) {
                        sink += "$path -> missing required fields: ${missingRequired.joinToString(", ")}"
                    }
                }
                node.forEach { (key, value) ->
                    visitSchema(value, "$path/$key", sink)
                }
            }

            is List<*> -> node.forEachIndexed { index, value ->
                visitSchema(value, "$path[$index]", sink)
            }
        }
    }
}
