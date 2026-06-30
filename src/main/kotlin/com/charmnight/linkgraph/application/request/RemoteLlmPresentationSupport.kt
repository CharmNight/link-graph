package com.charmnight.linkgraph.application.request

import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.settings.LlmProviderPreset
import com.charmnight.linkgraph.settings.LlmWireProtocol
import com.charmnight.linkgraph.settings.RemoteLlmEndpointPolicy

/** 应用层展示远程 LLM 请求状态所需的最小连接信息。 */
internal data class RemoteLlmPresentationConnection(
    val preset: LlmProviderPreset,
    val model: String,
    private val requestUrl: String,
) {
    fun requestUrl(): String = requestUrl
}

/** 判断当前设置是否选择了远程 provider。 */
internal fun LinkGraphSettingsState.usesRemoteProvider(): Boolean =
    sanitized().providerPreset().isRemote

/**
 * 解析应用层展示状态所需的远程连接信息。
 *
 * 这里不暴露 API key，也不构造真实 LLM request；只用于异步状态、流式能力和 endpoint 摘要展示。
 */
internal fun LinkGraphSettingsState.remoteConnectionOrNull(
    endpointPolicy: RemoteLlmEndpointPolicy = RemoteLlmEndpointPolicy(),
): RemoteLlmPresentationConnection? {
    val sanitized = sanitized()
    if (!sanitized.llmEnabled) {
        return null
    }
    val preset = sanitized.providerPreset()
    if (!preset.isRemote) {
        return null
    }
    val endpoint = sanitized.effectiveEndpoint()
    val model = sanitized.effectiveModel()
    if (endpoint.isBlank() || sanitized.apiKey.trim().isBlank() || model.isBlank()) {
        return null
    }
    if (endpointPolicy.validationError(endpoint) != null) {
        return null
    }
    return RemoteLlmPresentationConnection(
        preset = preset,
        model = model,
        requestUrl = resolveRequestUrl(
            endpoint = endpoint,
            protocol = preset.wireProtocol ?: LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
        ),
    )
}

private fun resolveRequestUrl(endpoint: String, protocol: LlmWireProtocol): String {
    val normalized = endpoint.trim().removeSuffix("/")
    if (normalized.isBlank()) {
        return normalized
    }
    return when (protocol) {
        LlmWireProtocol.OPENAI_CHAT_COMPLETIONS ->
            if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
        LlmWireProtocol.OPENAI_RESPONSES ->
            if (normalized.endsWith("/responses")) normalized else "$normalized/responses"
        LlmWireProtocol.ANTHROPIC_MESSAGES -> when {
            normalized.endsWith("/v1/messages") -> normalized
            normalized.endsWith("/messages") -> normalized
            normalized.endsWith("/v1") -> "$normalized/messages"
            else -> "$normalized/v1/messages"
        }
    }
}
