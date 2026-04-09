package com.charmnight.linkgraph.llm

/**
 * 轻量 JSON 解析器，用于解析远程 LLM 网关返回值。
 */
internal class LlmGatewayJsonParser(private val text: String) {
    /** 保存当前扫描到的字符下标。 */
    private var index: Int = 0

    /**
     * 解析当前游标处的 JSON 值。
     */
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

    /**
     * 解析 JSON 对象。
     */
    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        // 使用有序映射保留字段顺序，便于调试。
        val result = linkedMapOf<String, Any?>()
        if (peek('}')) {
            expect('}')
            return result
        }
        while (true) {
            // 对象键必须是字符串。
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

    /**
     * 解析 JSON 数组。
     */
    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        // 使用可变列表按顺序收集数组元素。
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

    /**
     * 解析 JSON 字符串。
     */
    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    // 反斜杠后必须跟随转义字符。
                    val escaped = text[index++]
                    when (escaped) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            // `\uXXXX` 形式统一按四位十六进制解码。
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

    /**
     * 解析 JSON 数字。
     */
    private fun parseNumber(): Number {
        // 记录数字起始位置，便于最后截取完整子串。
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
        val numberText = text.substring(start, index)
        return if (isFloat) numberText.toDouble() else numberText.toLong()
    }

    /**
     * 解析固定字面量。
     */
    private fun parseLiteral(expected: String, value: Any?): Any? {
        if (!text.regionMatches(index, expected, 0, expected.length)) {
            error("Expected '$expected' at $index")
        }
        index += expected.length
        return value
    }

    /**
     * 跳过当前游标后的所有空白字符。
     */
    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }

    /**
     * 断言当前位置是指定字符并向前推进一位。
     */
    private fun expect(ch: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != ch) {
            error("Expected '$ch' at $index")
        }
        index++
    }

    /**
     * 判断当前非空白字符是否为指定字符。
     */
    private fun peek(ch: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == ch
    }
}
