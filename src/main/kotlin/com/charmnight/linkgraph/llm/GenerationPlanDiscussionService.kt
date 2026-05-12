package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaMessageRole
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
        val root = LlmJsonSupport.parseObject(RemoteStructuredJsonExtractor.extract(content))
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
                role = QaMessageRole.USER,
                content = question,
                focusItemId = focusItemId,
            ),
            GenerationPlanDiscussionMessage(
                messageId = "$sessionId-assistant-${existingMessages.size + 2}",
                role = QaMessageRole.ASSISTANT,
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
