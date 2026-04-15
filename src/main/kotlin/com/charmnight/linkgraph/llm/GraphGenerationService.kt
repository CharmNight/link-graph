package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.sync.SyncPreviewRisk
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 统一封装“根据当前图上下文生成实现计划”的入口。
 * 当远程 LLM 未启用或不可用时，会明确返回规则化结果或警告，而不是中途抛出异常截断。
 */
class GraphGenerationService(
    /** 负责构造实现计划提示词的工厂。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责发起远程请求的网关。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
) {
    /** 负责结构化响应请求和解析的辅助组件。 */
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    /** 根据当前图上下文生成实现计划。 */
    fun generatePlan(
        context: GenerationContext,
        settings: LinkGraphSettingsState,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GenerationPlan {
        /** 清洗后的设置快照。 */
        val sanitizedSettings = settings.sanitized()
        /** 当前上下文对应的提示词包。 */
        val promptPackage = promptFactory.buildGenerationPromptPackage(context, sanitizedSettings)

        if (!sanitizedSettings.llmEnabled) {
            return GenerationPlan(
                source = GenerationPlanSource.DISABLED,
                summary = "LLM 生成功能已关闭。",
                warnings = listOf("LLM 未开启，当前不会请求远程模型。"),
                promptPreview = promptPackage.preview,
            )
        }

        if (!sanitizedSettings.usesRemoteProvider()) {
            return buildMockPlan(context, promptPackage.preview)
        }
        /** 生效的远程连接配置。 */
        val remoteConnection = sanitizedSettings.remoteConnectionOrNull()
        if (remoteConnection == null) {
            /** 远程配置不完整时的规则化回退计划。 */
            val fallbackPlan = buildMockPlan(context, promptPackage.preview)
            return fallbackPlan.copy(
                warnings = listOf(
                    sanitizedSettings.remoteLlmSetupHint("规则化生成计划"),
                ) + fallbackPlan.warnings,
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "实现计划生成",
                schema = GENERATION_PLAN_SCHEMA,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                parseRemotePlan(content, promptPackage.preview)
            }
        }.map { remote ->
            remote.value.withPrependedWarnings(remote.warnings)
        }.getOrElse { error ->
            /** 远程失败后的规则化回退计划。 */
            val fallbackPlan = buildMockPlan(context, promptPackage.preview)
            fallbackPlan.copy(
                warnings = listOf(
                    "远程 LLM 生成失败，已回退为规则化生成计划：${LlmUserMessageFormatter.describe(error)}",
                ) + fallbackPlan.warnings,
            )
        }
    }

    /** 用同步预览项生成规则化实现计划。 */
    private fun buildMockPlan(
        context: GenerationContext,
        prompt: String,
    ): GenerationPlan {
        /** 根据已确认草稿变更转换出的计划条目。 */
        val confirmedItems = context.confirmedChanges.map { change -> confirmedChangeToPlanItem(change, context) }
        /** 根据同步预览项转换出的计划条目。 */
        val previewItems = context.syncPreviewItems.map { preview ->
            GenerationPlanItem(
                id = preview.id,
                title = preview.title,
                description = preview.description,
                risk = preview.risk,
                targetPath = inferTargetPath(preview),
            )
        }
        val items = (confirmedItems + previewItems).distinctBy { it.id }
        /** 计划摘要。 */
        val summary = if (items.isEmpty()) {
            "当前图中未推断出明确的代码改动项。"
        } else {
            items.joinToString(separator = "; ") { it.title }
        }
        /** 规则化生成时的警告信息。 */
        val warnings = buildList {
            if (context.mermaidIssues.isNotEmpty()) {
                add("Mermaid 图仍有 ${context.mermaidIssues.size} 个待处理问题。")
            }
            if (items.isEmpty()) {
                add("当前计划来自规则化推断，未发现可执行的同步项。")
            }
            if (confirmedItems.isEmpty() && context.confirmedChanges.isNotEmpty()) {
                add("已确认草稿变更存在，但当前仍无法为它们定位明确的代码文件。")
            }
        }
        return GenerationPlan(
            source = GenerationPlanSource.MOCK,
            summary = summary,
            items = items,
            warnings = warnings,
            promptPreview = prompt,
        )
    }

    /** 解析远程 LLM 返回的实现计划。 */
    private fun parseRemotePlan(
        content: String,
        prompt: String,
    ): GenerationPlan {
        /** 解析后的 JSON 根对象。 */
        val root = LlmJsonParser(RemoteStructuredJsonExtractor.extract(content)).parseValue() as? Map<*, *>
            ?: error("LLM response root must be a JSON object.")
        /** 远程返回的计划条目列表。 */
        val items = (root["items"] as? List<*>).orEmpty().mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            /** 条目风险等级。 */
            val riskName = item["risk"] as? String ?: SyncPreviewRisk.MEDIUM.name
            GenerationPlanItem(
                id = item["id"] as? String ?: item["title"] as? String ?: "generated-item",
                title = item["title"] as? String ?: return@mapNotNull null,
                description = item["description"] as? String ?: "",
                risk = SyncPreviewRisk.entries.firstOrNull { it.name == riskName } ?: SyncPreviewRisk.MEDIUM,
                targetPath = item["targetPath"] as? String,
                editScopes = (item["editScopes"] as? List<*>).orEmpty().mapNotNull { rawScope ->
                    val scope = rawScope as? Map<*, *> ?: return@mapNotNull null
                    EditScope(
                        scopeId = scope["scopeId"] as? String ?: return@mapNotNull null,
                        targetNodeId = scope["targetNodeId"] as? String ?: return@mapNotNull null,
                        filePath = scope["filePath"] as? String ?: return@mapNotNull null,
                        language = scope["language"] as? String ?: "TEXT",
                        symbolKind = scope["symbolKind"] as? String ?: "UNKNOWN",
                        symbolSignature = scope["symbolSignature"] as? String,
                        startOffset = (scope["startOffset"] as? Number)?.toInt(),
                        endOffset = (scope["endOffset"] as? Number)?.toInt(),
                        startLine = (scope["startLine"] as? Number)?.toInt(),
                        endLine = (scope["endLine"] as? Number)?.toInt(),
                        allowedChangeKinds = (scope["allowedChangeKinds"] as? List<*>).orEmpty().mapNotNull { it as? String },
                        supportingFindingIds = (scope["supportingFindingIds"] as? List<*>).orEmpty().mapNotNull { it as? String },
                    )
                },
            )
        }
        /** 远程返回的警告信息。 */
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return GenerationPlan(
            source = GenerationPlanSource.REMOTE,
            summary = root["summary"] as? String ?: "远程生成计划",
            items = items,
            warnings = warnings,
            promptPreview = prompt,
        )
    }

    /** 把额外警告插入到计划警告列表前部。 */
    private fun GenerationPlan.withPrependedWarnings(extraWarnings: List<String>): GenerationPlan {
        if (extraWarnings.isEmpty()) {
            return this
        }
        return copy(warnings = extraWarnings + warnings)
    }

    /** 从同步预览项描述中推断目标路径。 */
    private fun inferTargetPath(item: SyncPreviewItem): String? {
        /** 从描述文本中直接匹配出的文件路径。 */
        val description = item.description
        val candidate = Regex("""[A-Za-z0-9_./-]+\.(java|kt|xml|sql|md)""").find(description)?.value
        if (candidate != null) {
            return candidate
        }
        /** 从标题中推断出的类型名。 */
        val typeName = Regex("""\b[A-Z][A-Za-z0-9_]+""").find(item.title)?.value ?: return null
        return if (item.risk == SyncPreviewRisk.LOW) {
            "src/main/java/$typeName.java"
        } else {
            null
        }
    }

    /** 把已确认草稿变更转换为规则化计划条目。 */
    private fun confirmedChangeToPlanItem(
        change: DraftWorkbenchEntry,
        context: GenerationContext,
    ): GenerationPlanItem {
        val nodeById = context.graph.nodes.associateBy(GraphNode::id)
        val targetPath = change.targetNodeIds
            .asSequence()
            .mapNotNull { nodeId ->
                nodeById[nodeId]?.metadata?.get("source.filePath")
                    ?: nodeById[nodeId]?.location?.substringBefore(':')
            }
            .firstOrNull()
        val description = buildString {
            append(change.reason.ifBlank { "根据已确认草稿变更执行代码修改。" })
            change.afterState?.takeIf { it.isNotBlank() }?.let {
                append(" 修改目标：").append(it)
            }
            change.impactSummary.takeIf { it.isNotBlank() }?.let {
                append(" 影响：").append(it)
            }
        }
        return GenerationPlanItem(
            id = change.sourceChangeId ?: change.entryId,
            title = change.title.ifBlank { change.sourceChangeId ?: change.entryId },
            description = description,
            risk = if (targetPath != null) SyncPreviewRisk.MEDIUM else SyncPreviewRisk.HIGH,
            targetPath = targetPath,
            editScopes = change.editScopes,
        )
    }

    private companion object {
        /** 远程实现计划结构化结果的 JSON Schema 示例。 */
        private const val GENERATION_PLAN_SCHEMA = """
{
  "summary": "简短计划摘要",
  "items": [
    {
      "id": "稳定ID",
      "title": "需要变更的内容",
      "description": "原因与做法",
      "risk": "LOW|MEDIUM|HIGH",
      "targetPath": "可选路径",
      "editScopes": []
    }
  ],
  "warnings": ["可选警告"]
}
"""
    }
}

/**
 * 只解析生成计划所需的轻量 JSON，避免为测试和最小功能引入额外依赖。
 */
private class LlmJsonParser(private val text: String) {
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
