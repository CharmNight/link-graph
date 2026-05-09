package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.AuditMessageRole
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionMessage
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession

/**
 * 围绕当前实现建议做追问，不再把用户跳回风险问答链路。
 */
class GenerationPlanDiscussionService(
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    private val gateway: LlmGateway = RoutingLlmGateway(),
) {
    private val responseSupport = RemoteStructuredResponseSupport(gateway)

    fun discuss(
        context: GenerationContext,
        plan: GenerationPlan,
        question: String,
        settings: LinkGraphSettingsState,
        session: GenerationPlanDiscussionSession? = null,
        focusItemId: String? = null,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GenerationPlanDiscussionResult {
        val sanitized = settings.sanitized()
        val normalizedQuestion = question.trim()
        val effectiveFocusItemId = focusItemId?.takeIf(String::isNotBlank) ?: session?.focusItemId
        val promptPackage = promptFactory.buildGenerationPlanDiscussionPromptPackage(
            context = context,
            plan = plan,
            question = normalizedQuestion,
            settings = sanitized,
            session = session,
            focusItemId = effectiveFocusItemId,
        )

        if (!sanitized.llmEnabled) {
            return buildMockResult(
                plan = plan,
                question = normalizedQuestion,
                prompt = promptPackage.preview,
                session = session,
                focusItemId = effectiveFocusItemId,
                source = LlmResultSource.DISABLED,
                extraWarnings = listOf("LLM 未开启，当前基于本地规则整理实现建议说明。"),
            )
        }
        if (!sanitized.usesRemoteProvider()) {
            return buildMockResult(
                plan = plan,
                question = normalizedQuestion,
                prompt = promptPackage.preview,
                session = session,
                focusItemId = effectiveFocusItemId,
            )
        }
        val remoteConnection = sanitized.remoteConnectionOrNull()
        if (remoteConnection == null) {
            return buildMockResult(
                plan = plan,
                question = normalizedQuestion,
                prompt = promptPackage.preview,
                session = session,
                focusItemId = effectiveFocusItemId,
                extraWarnings = listOf(sanitized.remoteLlmSetupHint("实现建议追问")),
            )
        }
        return runCatching {
            responseSupport.request(
                remoteConnection.toRequest(
                    systemPrompt = promptPackage.systemPrompt,
                    userPrompt = promptPackage.userPrompt,
                ),
                scene = "实现建议追问",
                schema = LlmStructuredSchemas.DISCUSSION,
                preferStreaming = remoteConnection.preset.capabilities.supportsStreaming,
                onPreview = onPreview,
            ) { content ->
                parseRemoteDiscussion(
                    content = content,
                    prompt = promptPackage.preview,
                    question = normalizedQuestion,
                    session = session,
                    plan = plan,
                    focusItemId = effectiveFocusItemId,
                )
            }
        }.map { remote ->
            remote.value.copy(warnings = remote.warnings + remote.value.warnings)
        }.getOrElse { error ->
            buildMockResult(
                plan = plan,
                question = normalizedQuestion,
                prompt = promptPackage.preview,
                session = session,
                focusItemId = effectiveFocusItemId,
                extraWarnings = listOf(
                    "远程 LLM 实现建议追问失败，已回退为本地说明：${LlmUserMessageFormatter.describe(error)}",
                ),
            )
        }
    }

    private fun buildMockResult(
        plan: GenerationPlan,
        question: String,
        prompt: String,
        session: GenerationPlanDiscussionSession?,
        focusItemId: String?,
        source: LlmResultSource = LlmResultSource.LOCAL_RULE,
        extraWarnings: List<String> = emptyList(),
    ): GenerationPlanDiscussionResult {
        val focusedItem = plan.items.firstOrNull { item -> item.id == focusItemId }
        val answer = buildString {
            append("当前回答只针对这份实现建议，不会把你跳回风险问答流程。")
            if (focusedItem != null) {
                append("\n聚焦任务：").append(focusedItem.title).append("。")
                append("建议描述：").append(focusedItem.description.ifBlank { "当前建议没有补充描述。" })
                focusedItem.targetPath?.let { targetPath ->
                    append("\n目标文件：").append(targetPath).append("。")
                }
            } else {
                append("\n当前建议摘要：").append(plan.summary).append("。")
            }
            append("\n如果你继续追问，可以直接围绕“为什么这样拆、影响哪些文件、有没有更小改法”展开。")
            if (question.contains("为什么")) {
                append("\n这类建议通常是为了把已确认草稿里的业务意图落到具体代码位置，而不是重新做一轮风险归因。")
            }
        }
        return GenerationPlanDiscussionResult(
            source = source,
            question = question,
            answer = answer,
            promptPreview = prompt,
            focusItemId = focusItemId,
            session = appendMessages(
                session = session,
                question = question,
                answer = answer,
                focusItemId = focusItemId,
            ),
            warnings = extraWarnings,
        )
    }

    private fun parseRemoteDiscussion(
        content: String,
        prompt: String,
        question: String,
        session: GenerationPlanDiscussionSession?,
        plan: GenerationPlan,
        focusItemId: String?,
    ): GenerationPlanDiscussionResult {
        val root = GenerationPlanDiscussionJsonParser(RemoteStructuredJsonExtractor.extract(content)).parseValue() as? Map<*, *>
            ?: error("LLM response root must be a JSON object.")
        val remoteFocusItemId = (root["focusItemId"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.takeIf { candidate -> plan.items.any { item -> item.id == candidate } }
            ?: focusItemId
        val answer = (root["answer"] as? String)?.trim().orEmpty().ifBlank {
            "当前追问没有返回可用回答，请继续围绕这份实现建议补充更具体的问题。"
        }
        val warnings = (root["warnings"] as? List<*>).orEmpty().mapNotNull { warning -> warning as? String }
        return GenerationPlanDiscussionResult(
            source = LlmResultSource.REMOTE,
            question = question,
            answer = answer,
            promptPreview = prompt,
            focusItemId = remoteFocusItemId,
            session = appendMessages(
                session = session,
                question = question,
                answer = answer,
                focusItemId = remoteFocusItemId,
            ),
            warnings = warnings,
        )
    }

    private fun appendMessages(
        session: GenerationPlanDiscussionSession?,
        question: String,
        answer: String,
        focusItemId: String?,
    ): GenerationPlanDiscussionSession {
        val existingMessages = session?.messages.orEmpty()
        val sessionId = session?.sessionId ?: "plan-discussion"
        val nextMessages = existingMessages + listOf(
            GenerationPlanDiscussionMessage(
                messageId = "$sessionId-user-${existingMessages.size + 1}",
                role = AuditMessageRole.USER,
                content = question,
                focusItemId = focusItemId,
            ),
            GenerationPlanDiscussionMessage(
                messageId = "$sessionId-assistant-${existingMessages.size + 2}",
                role = AuditMessageRole.ASSISTANT,
                content = answer,
                focusItemId = focusItemId,
            ),
        )
        return GenerationPlanDiscussionSession(
            sessionId = sessionId,
            messages = nextMessages,
            focusItemId = focusItemId,
        )
    }
}

private class GenerationPlanDiscussionJsonParser(
    private val text: String,
) {
    private var index: Int = 0

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

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
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

    private fun parseArray(): List<Any?> {
        expect('[')
        skipWhitespace()
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

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(char)
            }
        }
        error("Unterminated string literal.")
    }

    private fun parseEscape(): Char {
        if (index >= text.length) {
            error("Unexpected end of input in escape sequence.")
        }
        return when (val escaped = text[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toInt(16).toChar()
            }
            else -> error("Unsupported escape sequence: \\$escaped")
        }
    }

    private fun parseNumber(): Number {
        val start = index
        if (text[index] == '-') {
            index++
        }
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index].isDigit()) {
                index++
            }
            return text.substring(start, index).toDouble()
        }
        return text.substring(start, index).toLong()
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) {
            error("Expected literal $literal at $index")
        }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (index >= text.length || text[index] != expected) {
            error("Expected '$expected' at $index")
        }
        index++
    }

    private fun peek(expected: Char): Boolean {
        skipWhitespace()
        return index < text.length && text[index] == expected
    }
}
