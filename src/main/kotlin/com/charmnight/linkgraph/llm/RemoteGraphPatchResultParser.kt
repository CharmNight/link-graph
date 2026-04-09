package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.BindingStatus
import com.charmnight.linkgraph.model.Certainty
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.model.GraphDiffElementKind
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.GraphPatch
import com.charmnight.linkgraph.model.GraphPatchAction
import com.charmnight.linkgraph.model.GraphPatchOperation
import com.charmnight.linkgraph.model.GraphSourceTag
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 解析远程 LLM 返回的图补丁结果。
 * 输出内容既包含自然语言回答，也可能附带结构化补丁操作。
 */
internal object RemoteGraphPatchResultParser {
    /** 把远程响应解析为统一的补丁结果对象。 */
    fun parse(
        content: String,
        prompt: String,
        question: String,
    ): GraphPatchResult {
        /** 解析后的 JSON 根对象。 */
        val root = RemotePatchJsonParser(unwrapJson(content)).parseValue() as? Map<*, *>
            ?: error("LLM response root must be a JSON object.")
        /** LLM 对用户问题的直接回答文本。 */
        val answer = root["answer"] as? String
            ?: root["summary"] as? String
            ?: error("LLM response must contain answer.")
        /** 远程返回的警告列表。 */
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        /** 远程返回的结构化补丁。 */
        val patch = (root["patch"] as? Map<*, *>)?.let(::parsePatch)
        return GraphPatchResult(
            source = LlmResultSource.REMOTE,
            question = question,
            answer = answer,
            promptPreview = prompt,
            patch = patch,
            findings = parseResultEvidenceFindings(root["findings"]),
            warnings = warnings,
        )
    }

    /** 提取可能被 Markdown 代码块包裹的纯 JSON 文本。 */
    private fun unwrapJson(content: String): String {
        return RemoteStructuredJsonExtractor.extract(content)
    }

    /** 解析图补丁主体。 */
    private fun parsePatch(raw: Map<*, *>): GraphPatch {
        return GraphPatch(
            summary = raw["summary"] as? String,
            operations = (raw["operations"] as? List<*>).orEmpty().mapNotNull { parseOperation(it as? Map<*, *>) },
            addedNodeIds = stringList(raw["addedNodeIds"]),
            removedNodeIds = stringList(raw["removedNodeIds"]),
            addedEdgeIds = stringList(raw["addedEdgeIds"]),
            removedEdgeIds = stringList(raw["removedEdgeIds"]),
        )
    }

    /** 解析单条补丁操作。 */
    private fun parseOperation(raw: Map<*, *>?): GraphPatchOperation? {
        raw ?: return null
        /** 补丁动作类型。 */
        val action = enumValue<GraphPatchAction>(raw["action"] as? String) ?: return null
        /** 变更元素类型，默认视为节点。 */
        val elementKind = enumValue<GraphDiffElementKind>(raw["elementKind"] as? String) ?: GraphDiffElementKind.NODE
        return GraphPatchOperation(
            id = raw["id"] as? String ?: return null,
            action = action,
            elementKind = elementKind,
            elementId = raw["elementId"] as? String ?: return null,
            title = raw["title"] as? String,
            summary = raw["summary"] as? String,
            node = parseNode(raw["node"] as? Map<*, *>),
            edge = parseEdge(raw["edge"] as? Map<*, *>),
            metadata = stringMap(raw["metadata"]),
        )
    }

    /** 解析补丁中的节点定义。 */
    private fun parseNode(raw: Map<*, *>?): GraphNode? {
        raw ?: return null
        /** 节点类型，缺失时回退到文档页。 */
        val type = enumValue<NodeType>(raw["type"] as? String) ?: NodeType.DOC_PAGE
        return GraphNode(
            id = raw["id"] as? String ?: return null,
            type = type,
            title = raw["title"] as? String ?: return null,
            location = raw["location"] as? String,
            signature = raw["signature"] as? String,
            inputs = stringList(raw["inputs"]),
            outputs = stringList(raw["outputs"]),
            doc = raw["doc"] as? String,
            bindingStatus = enumValue<BindingStatus>(raw["bindingStatus"] as? String) ?: BindingStatus.DESIGN_ONLY,
            certainty = enumValue<Certainty>(raw["certainty"] as? String) ?: Certainty.LLM_SUGGESTED,
            metadata = stringMap(raw["metadata"]),
            sourceTag = enumValue<GraphSourceTag>(raw["sourceTag"] as? String) ?: GraphSourceTag.DRAFT_AI,
        )
    }

    /** 解析补丁中的边定义。 */
    private fun parseEdge(raw: Map<*, *>?): GraphEdge? {
        raw ?: return null
        /** 边类型，缺失时回退到 `GENERATES`。 */
        val type = enumValue<EdgeType>(raw["type"] as? String) ?: EdgeType.GENERATES
        return GraphEdge(
            id = raw["id"] as? String ?: return null,
            type = type,
            fromNodeId = raw["fromNodeId"] as? String ?: return null,
            toNodeId = raw["toNodeId"] as? String ?: return null,
            label = raw["label"] as? String,
            bindingStatus = enumValue<BindingStatus>(raw["bindingStatus"] as? String) ?: BindingStatus.DESIGN_ONLY,
            certainty = enumValue<Certainty>(raw["certainty"] as? String) ?: Certainty.LLM_SUGGESTED,
            metadata = stringMap(raw["metadata"]),
            sourceTag = enumValue<GraphSourceTag>(raw["sourceTag"] as? String) ?: GraphSourceTag.DRAFT_AI,
        )
    }

    /** 把任意 JSON 数组安全转换成字符串列表。 */
    private fun stringList(raw: Any?): List<String> {
        return (raw as? List<*>).orEmpty().mapNotNull { it as? String }
    }

    /** 把任意 JSON 对象安全转换成字符串映射。 */
    private fun stringMap(raw: Any?): Map<String, String> {
        return (raw as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
            /** 当前条目的字符串键。 */
            val stringKey = key as? String ?: return@mapNotNull null
            /** 当前条目的字符串值。 */
            val stringValue = value as? String ?: return@mapNotNull null
            stringKey to stringValue
        }.toMap()
    }

    /** 按枚举名称做安全解析。 */
    private inline fun <reified T : Enum<T>> enumValue(name: String?): T? {
        return enumValueByName<T>(name)
    }
}

/** 判断当前设置是否具备远程补丁生成能力。 */
internal fun LinkGraphSettingsState.isRemotePatchReady(): Boolean {
    return remoteConnectionOrNull() != null
}

/** 供补丁结果解析使用的最小 JSON 解析器。 */
private class RemotePatchJsonParser(private val text: String) {
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
