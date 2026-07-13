package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.LlmPromptPackage
import com.charmnight.linkgraph.agent.model.LlmRequest
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmWireProtocol

/** 远程请求中各类数据的安全分类。 */
internal enum class RemoteDataClassification {
    SOURCE_CODE,
    DIFF_SNIPPET,
    DECOMPILED_CODE,
    USER_CONTENT,
    GRAPH_METADATA,
}

/** 进入远程请求创建边界的提示词与数据分类。 */
internal data class RemotePromptContent(
    val systemPrompt: String,
    val userPrompt: String,
    val classifications: Set<RemoteDataClassification> = setOf(
        RemoteDataClassification.USER_CONTENT,
        RemoteDataClassification.GRAPH_METADATA,
    ),
)

/**
 * 唯一允许把连接配置和提示词组装成 [LlmRequest] 的出口。
 *
 * 上游仍负责按场景裁剪上下文；这里做最终的授权校验，防止新增调用点遗漏源码策略。
 */
internal object RemoteLlmRequestFactory {
    fun create(
        connection: RemoteLlmConnection,
        settings: LinkGraphSettingsState,
        content: RemotePromptContent,
    ): LlmRequest {
        val sanitized = settings.sanitized()
        val sourceClassifications = content.classifications.intersect(SOURCE_CLASSIFICATIONS)
        require(sanitized.allowRemoteSourceContext || sourceClassifications.isEmpty()) {
            "Remote source context is not authorized: ${sourceClassifications.joinToString()}"
        }
        return LlmRequest(
            protocol = connection.preset.wireProtocol ?: LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = connection.endpoint,
            apiKey = connection.apiKey,
            model = connection.model,
            timeoutSeconds = connection.timeoutSeconds,
            temperature = connection.temperature,
            systemPrompt = content.systemPrompt,
            userPrompt = content.userPrompt,
            maxOutputTokens = connection.preset.maxOutputTokens,
        )
    }

    private val SOURCE_CLASSIFICATIONS = setOf(
        RemoteDataClassification.SOURCE_CODE,
        RemoteDataClassification.DIFF_SNIPPET,
        RemoteDataClassification.DECOMPILED_CODE,
    )
}

/** 把已构造的提示词包送入唯一远程请求边界。 */
internal fun LlmPromptPackage.toRemotePromptContent(
    classifications: Set<RemoteDataClassification> = emptySet(),
): RemotePromptContent =
    RemotePromptContent(
        systemPrompt = systemPrompt,
        userPrompt = userPrompt,
        classifications = classifications +
            RemoteDataClassification.USER_CONTENT +
            RemoteDataClassification.GRAPH_METADATA,
    )

/** 根据实际仍保留的源码片段生成请求分类。 */
internal fun sourceContextClassifications(
    sourceContexts: Iterable<SourceSnippetContext>,
    sourceClassification: RemoteDataClassification = RemoteDataClassification.SOURCE_CODE,
): Set<RemoteDataClassification> =
    sourceContexts.mapTo(linkedSetOf()) { source ->
        if (source.decompiled) RemoteDataClassification.DECOMPILED_CODE else sourceClassification
    }
