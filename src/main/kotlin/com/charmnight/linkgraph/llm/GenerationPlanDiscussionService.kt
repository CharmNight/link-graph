package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.workbench.QaMessageRole
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionMessage
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionResult
import com.charmnight.linkgraph.workbench.GenerationPlanDiscussionSession

/**
 * 围绕已生成的实现建议做追问对话，保持用户停留在当前建议上下文，不会被引导回风险问答链路。
 * 该服务负责组装提示词、调用远程模型、解析响应，并在远程不可用时回退到本地规则化解释。
 */
class GenerationPlanDiscussionService(
    /** 负责构造实现建议追问场景提示词的工厂。 */
    private val promptFactory: LlmPromptFactory = LlmPromptFactory(),
    /** 负责真正发起远程请求的网关。 */
    private val gateway: LlmGateway = com.charmnight.linkgraph.llm.RoutingLlmGateway(),
) {
    /** 负责处理结构化 JSON 响应、自动重试与 JSON 修复的辅助组件。 */
    private val responseSupport = RemoteStructuredResponseParser(gateway)

    /**
     * 针对当前实现建议回答用户追问。
     * 远程 LLM 未启用、配置不完整或调用失败时，统一回退为本地规则化解释，避免链路被截断。
     *
     * @param context 当前生成上下文，提供图、草稿与源码背景。
     * @param plan 当前已经生成的实现建议，追问会围绕其中的条目展开。
     * @param question 用户输入的追问问题。
     * @param settings 当前 LLM 设置快照，用于判断是否走远程链路。
     * @param session 当前追问会话；为空表示本轮是首条追问。
     * @param focusItemId 当前聚焦的条目标识，缺失时回退到会话记录的焦点条目。
     * @param onPreview 流式预览回调，用于把远程流式输出实时回传给 UI。
     */
    fun discuss(
        context: GenerationContext,
        plan: GenerationPlan,
        question: String,
        settings: LinkGraphSettingsState,
        session: GenerationPlanDiscussionSession? = null,
        focusItemId: String? = null,
        onPreview: ((String, Boolean) -> Unit)? = null,
    ): GenerationPlanDiscussionResult {
        /** 去除无效字段后的设置快照。 */
        val sanitized = settings.sanitized()
        /** 去除首尾空白后的用户问题。 */
        val normalizedQuestion = question.trim()
        /** 优先采用调用方传入的焦点条目，缺失时回退到会话中已有的焦点条目。 */
        val effectiveFocusItemId = focusItemId?.takeIf(String::isNotBlank) ?: session?.focusItemId
        /** 当前追问场景的提示词包，包含系统/用户提示词以及界面预览。 */
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
        /** 解析后的远程连接配置，缺失表示当前不能发起远程请求。 */
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

    /**
     * 构造本地规则化的追问回答。
     * 不请求远程模型，仅基于实现建议条目与问题关键字整理说明，确保链路始终可回退。
     */
    private fun buildMockResult(
        plan: GenerationPlan,
        question: String,
        prompt: String,
        session: GenerationPlanDiscussionSession?,
        focusItemId: String?,
        source: LlmResultSource = LlmResultSource.LOCAL_RULE,
        extraWarnings: List<String> = emptyList(),
    ): GenerationPlanDiscussionResult {
        /** 根据焦点条目 ID 命中的建议项；为空表示当前轮没有具体聚焦条目。 */
        val focusedItem = plan.items.firstOrNull { item -> item.id == focusItemId }
        /** 本地规则化回答文本。 */
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

    /**
     * 解析远程返回的追问 JSON 内容。
     * 校验 focusItemId 是否仍属于当前实现建议，过滤非法值，缺失时保留调用方传入的焦点条目。
     */
    private fun parseRemoteDiscussion(
        content: String,
        prompt: String,
        question: String,
        session: GenerationPlanDiscussionSession?,
        plan: GenerationPlan,
        focusItemId: String?,
    ): GenerationPlanDiscussionResult {
        /** 解析后的 JSON 根对象。 */
        val root = LlmJsonCodec.parseObject(RemoteStructuredJsonExtractor.extract(content))
        /** 远程返回的聚焦条目 ID，需命中实现建议条目才会采纳，否则回退到调用方传入值。 */
        val remoteFocusItemId = (root["focusItemId"] as? String)
            ?.takeIf(String::isNotBlank)
            ?.takeIf { candidate -> plan.items.any { item -> item.id == candidate } }
            ?: focusItemId
        /** 远程返回的问答回答文本，为空时使用提示文案占位。 */
        val answer = (root["answer"] as? String)?.trim().orEmpty().ifBlank {
            "当前追问没有返回可用回答，请继续围绕这份实现建议补充更具体的问题。"
        }
        /** 远程返回的警告列表。 */
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

    /**
     * 把本轮用户问题与远程/本地回答追加为两条消息，形成新的追问会话快照。
     */
    private fun appendMessages(
        session: GenerationPlanDiscussionSession?,
        question: String,
        answer: String,
        focusItemId: String?,
    ): GenerationPlanDiscussionSession {
        /** 当前会话中已有的消息列表，为空时表示首次追问。 */
        val existingMessages = session?.messages.orEmpty()
        /** 会话稳定标识，沿用已有值或使用默认值。 */
        val sessionId = session?.sessionId ?: "plan-discussion"
        /** 追加本轮用户与助手消息后的完整消息序列。 */
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
