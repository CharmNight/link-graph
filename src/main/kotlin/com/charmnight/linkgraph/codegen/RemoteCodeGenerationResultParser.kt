package com.charmnight.linkgraph.codegen

import com.charmnight.linkgraph.llm.EditScope
import com.charmnight.linkgraph.llm.LlmResultSource
import com.charmnight.linkgraph.llm.RemoteStructuredJsonExtractor

/**
 * 解析远程 LLM 返回的代码草稿 JSON。
 * 这里要求结构稳定；如果格式不对，直接抛错并由上层回退到本地模板。
 */
internal object RemoteCodeGenerationResultParser {
    /** 把远程返回的 JSON 解析为代码草稿结果。 */
    fun parse(
        content: String,
        promptPreview: String,
    ): CodeGenerationResult {
        /** 解析后的 JSON 根对象。 */
        val root = RemoteCodeGenerationJsonParser(unwrapJson(content)).parseValue() as? Map<*, *>
            ?: error("LLM response root must be a JSON object.")
        /** 远程返回的警告列表。 */
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 远程返回的代码草稿列表。 */
        val rawDrafts = root["drafts"] as? List<*> ?: error("LLM response field 'drafts' must be an array.")
        val drafts = rawDrafts.mapIndexed { index, rawDraft ->
            parseDraft(rawDraft as? Map<*, *>, index)
        }
        return CodeGenerationResult(
            drafts = drafts,
            warnings = warnings,
            source = LlmResultSource.REMOTE,
            promptPreview = promptPreview,
        )
    }

    /** 解析单个代码草稿对象。 */
    private fun parseDraft(raw: Map<*, *>?, index: Int): GeneratedCodeDraft {
        raw ?: error("LLM response draft[$index] must be an object.")
        /** 草稿目标路径。 */
        val targetPath = raw["targetPath"] as? String ?: error("LLM response draft[$index].targetPath is required.")
        /** 草稿标题，缺失时回退到文件名。 */
        val title = raw["title"] as? String ?: targetPath.substringAfterLast('/')
        /** 草稿稳定 ID。 */
        val draftId = raw["id"] as? String ?: "draft:$targetPath"
        /** 来源节点 ID，缺失时复用草稿 ID。 */
        val sourceNodeId = raw["sourceNodeId"] as? String ?: draftId
        /** 草稿完整内容。 */
        val content = raw["content"] as? String
        /** 草稿结构化编辑操作。 */
        val editOperations = when (val operations = raw["editOperations"]) {
            null -> emptyList()
            is List<*> -> operations.mapIndexed { operationIndex, operation ->
                parseEditOperation(operation as? Map<*, *>, index, operationIndex)
            }
            else -> error("LLM response draft[$index].editOperations must be an array when present.")
        }
        if (content == null && editOperations.isEmpty()) {
            error("LLM response draft[$index] must provide content or editOperations.")
        }
        /** 草稿局部警告列表。 */
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 草稿级授权 scope，优先解析，主链仍会再用本地 plan 回填。 */
        val editScopes = (raw["editScopes"] as? List<*>).orEmpty().mapNotNull { parseEditScope(it as? Map<*, *>) }
        return GeneratedCodeDraft(
            id = draftId,
            sourceNodeId = sourceNodeId,
            title = title,
            targetPath = targetPath,
            content = content,
            editOperations = editOperations,
            editScopes = editScopes,
            warnings = warnings,
        )
    }

    private fun parseEditOperation(
        raw: Map<*, *>?,
        draftIndex: Int,
        operationIndex: Int,
    ): CodeEditOperation {
        raw ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex] must be an object.")
        val operationId = raw["operationId"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].operationId is required.")
        val filePath = raw["filePath"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].filePath is required.")
        val kindName = raw["kind"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].kind is required.")
        val payload = raw["payload"] as? String
            ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].payload is required.")
        val warnings = (raw["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return CodeEditOperation(
            operationId = operationId,
            filePath = filePath,
            scopeId = raw["scopeId"] as? String,
            kind = CodeEditOperationKind.entries.firstOrNull { it.name == kindName }
                ?: error("LLM response draft[$draftIndex].editOperations[$operationIndex].kind '$kindName' is unsupported."),
            payload = CodeEditPayloadNormalizer.normalize(payload),
            warnings = warnings,
        )
    }

    private fun parseEditScope(raw: Map<*, *>?): EditScope? {
        raw ?: return null
        return EditScope(
            scopeId = raw["scopeId"] as? String ?: return null,
            targetNodeId = raw["targetNodeId"] as? String ?: return null,
            filePath = raw["filePath"] as? String ?: return null,
            language = raw["language"] as? String ?: "TEXT",
            symbolKind = raw["symbolKind"] as? String ?: "UNKNOWN",
            symbolSignature = raw["symbolSignature"] as? String,
            startOffset = (raw["startOffset"] as? Number)?.toInt(),
            endOffset = (raw["endOffset"] as? Number)?.toInt(),
            startLine = (raw["startLine"] as? Number)?.toInt(),
            endLine = (raw["endLine"] as? Number)?.toInt(),
            allowedChangeKinds = (raw["allowedChangeKinds"] as? List<*>).orEmpty().mapNotNull { it as? String },
            supportingFindingIds = (raw["supportingFindingIds"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
    }

    /** 提取可能被代码块包裹的纯 JSON 文本。 */
    private fun unwrapJson(content: String): String {
        return RemoteStructuredJsonExtractor.extract(content)
    }
}

/** 供代码草稿结果解析使用的最小 JSON 解析器。 */
internal class RemoteCodeGenerationJsonParser(private val text: String) {
    /** 当前读取游标位置。 */
    private var index: Int = 0

    /** 解析下一个 JSON 值。 */
    fun parseValue(): Any? {
        skipWhitespace()
        if (index >= text.length) {
            error("Unexpected end of input.")
        }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected token '${text[index]}' at $index")
        }
    }

    /** 解析 JSON 对象。 */
    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        /** 保持原始顺序的对象结果。 */
        val result = linkedMapOf<String, Any?>()
        if (peek('}')) {
            expect('}')
            return result
        }
        while (true) {
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
            skipWhitespace()
            if (peek('}')) {
                expect('}')
                return result
            }
            expect(',')
        }
    }

    /** 解析 JSON 数组。 */
    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        /** 保持原始顺序的数组结果。 */
        val result = mutableListOf<Any?>()
        if (peek(']')) {
            expect(']')
            return result
        }
        while (true) {
            result.add(parseValue())
            skipWhitespace()
            if (peek(']')) {
                expect(']')
                return result
            }
            expect(',')
        }
    }

    /** 解析 JSON 字符串并处理转义序列。 */
    private fun parseString(): String {
        expect('"')
        /** 累积字符串内容的缓冲区。 */
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) {
                        error("Unterminated escape at $index")
                    }
                    /** 当前读取到的转义字符。 */
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = text.substring(index, index + 4)
                            out.append(hex.toInt(16).toChar())
                            index += 4
                        }

                        else -> error("Unsupported escape '\\$escaped'")
                    }
                }

                else -> out.append(ch)
            }
        }
        error("Unterminated string.")
    }

    /** 解析 JSON 数字，按是否含小数位决定返回类型。 */
    private fun parseNumber(): Number {
        /** 数字片段的起始下标。 */
        val start = index
        if (text[index] == '-') {
            index++
        }
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        var isFloat = false
        if (index < text.length && text[index] == '.') {
            isFloat = true
            index++
            while (index < text.length && text[index].isDigit()) {
                index++
            }
        }
        /** 数字的原始文本表示。 */
        val numberText = text.substring(start, index)
        return if (isFloat) numberText.toDouble() else numberText.toLong()
    }

    /** 校验并解析固定字面量。 */
    private fun parseLiteral(expected: String, value: Any?): Any? {
        if (!text.regionMatches(index, expected, 0, expected.length)) {
            error("Expected '$expected' at $index")
        }
        index += expected.length
        return value
    }

    /** 断言当前字符符合预期，并推进游标。 */
    private fun expect(ch: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != ch) {
            error("Expected '$ch' at $index")
        }
        index++
    }

    /** 查看下一个非空白字符是否为目标字符。 */
    private fun peek(ch: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == ch
    }

    /** 跳过当前游标后的所有空白字符。 */
    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }
}
