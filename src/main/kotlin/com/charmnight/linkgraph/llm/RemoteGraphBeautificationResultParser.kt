package com.charmnight.linkgraph.llm

/**
 * 解析远程 LLM 返回的链路讲解结果。
 * 除总览摘要外，还会拆出多个结构化 section 供前端按块展示。
 */
internal object RemoteGraphBeautificationResultParser {
    /** 把远程返回的 JSON 文本解析为讲解结果对象。 */
    fun parse(
        content: String,
        prompt: String,
    ): GraphBeautificationResult {
        /** 解析后的 JSON 根对象。 */
        val root = RemoteBeautificationJsonParser(RemoteStructuredJsonExtractor.extract(content)).parseValue() as? Map<*, *>
            ?: error("LLM response root must be a JSON object.")
        /** 远程返回的讲解分段列表。 */
        val sections = (root["sections"] as? List<*>).orEmpty().mapNotNull { raw ->
            parseSection(raw as? Map<*, *>)
        }
        return GraphBeautificationResult(
            source = LlmResultSource.REMOTE,
            summaryTitle = root["summaryTitle"] as? String ?: "当前链路讲解",
            summary = root["summary"] as? String ?: root["answer"] as? String ?: error("LLM response must contain summary."),
            sections = sections,
            findings = parseResultEvidenceFindings(root["findings"]),
            promptPreview = prompt,
            warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String },
        )
    }

    /** 解析单个讲解 section。 */
    private fun parseSection(raw: Map<*, *>?): GraphBeautificationSection? {
        raw ?: return null
        /** section 标题。 */
        val title = raw["title"] as? String ?: return null
        /** section 正文内容。 */
        val content = raw["content"] as? String ?: return null
        /** section 稳定标识，缺失时回退到标题。 */
        val id = raw["id"] as? String ?: title
        return GraphBeautificationSection(
            id = id,
            title = title,
            content = content,
        )
    }
}

/** 供讲解结果解析使用的最小 JSON 解析器。 */
private class RemoteBeautificationJsonParser(private val text: String) {
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
                    /** 当前读取到的转义字符。 */
                    val escaped = text[index++]
                    when (escaped) {
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
        error("Unterminated string literal.")
    }

    /** 校验并解析固定字面量。 */
    private fun parseLiteral(
        literal: String,
        value: Any?,
    ): Any? {
        if (!text.startsWith(literal, index)) {
            error("Expected '$literal' at $index")
        }
        index += literal.length
        return value
    }

    /** 解析 JSON 数字。 */
    private fun parseNumber(): Number {
        /** 数字片段的起始下标。 */
        val start = index
        while (index < text.length && text[index] in "-+0123456789.eE") {
            index += 1
        }
        /** 数字的原始文本表示。 */
        val raw = text.substring(start, index)
        return if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
            raw.toDouble()
        } else {
            raw.toLong()
        }
    }

    /** 跳过当前游标后的所有空白字符。 */
    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index += 1
        }
    }

    /** 断言当前字符符合预期，并推进游标。 */
    private fun expect(ch: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != ch) {
            error("Expected '$ch' at $index")
        }
        index += 1
    }

    /** 查看下一个非空白字符是否为目标字符。 */
    private fun peek(ch: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == ch
    }
}
