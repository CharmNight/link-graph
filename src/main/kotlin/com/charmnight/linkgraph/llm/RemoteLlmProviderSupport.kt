package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/** 描述一次可直接发往远程 LLM 服务的连接配置。 */
internal data class RemoteLlmConnection(
    /** 当前选择的供应商预设。 */
    val preset: LlmProviderPreset,
    /** 远程服务的基础地址。 */
    val endpoint: String,
    /** 远程服务访问密钥。 */
    val apiKey: String,
    /** 远程调用使用的模型名称。 */
    val model: String,
    /** HTTP 请求超时时间，单位为秒。 */
    val timeoutSeconds: Int,
    /** 采样温度参数。 */
    val temperature: Double,
) {
    /** 将连接配置和提示词拼装成统一的 LLM 请求对象。 */
    fun toRequest(
        systemPrompt: String,
        userPrompt: String,
    ): LlmRequest {
        return LlmRequest(
            protocol = preset.wireProtocol ?: LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            timeoutSeconds = timeoutSeconds,
            temperature = temperature,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    /** 解析当前配置实际要访问的完整请求地址。 */
    fun requestUrl(): String {
        return LlmProtocolUrlResolver.resolve(
            endpoint = endpoint,
            protocol = preset.wireProtocol ?: LlmWireProtocol.OPENAI_CHAT_COMPLETIONS,
        )
    }
}

/** 判断当前设置是否选择了远程 LLM 供应商。 */
internal fun LinkGraphSettingsState.usesRemoteProvider(): Boolean {
    return sanitized().providerPreset().isRemote
}

/** 从设置中提取一份可直接发起远程请求的连接配置。 */
internal fun LinkGraphSettingsState.remoteConnectionOrNull(
    endpointPolicy: RemoteLlmEndpointPolicy = RemoteLlmEndpointPolicy(),
): RemoteLlmConnection? {
    /** 去除无效字段后的设置快照。 */
    val sanitized = sanitized()
    if (!sanitized.llmEnabled) {
        return null
    }
    /** 当前生效的供应商预设。 */
    val preset = sanitized.providerPreset()
    if (!preset.isRemote) {
        return null
    }
    /** 归一化后的 endpoint。 */
    val endpoint = sanitized.effectiveEndpoint()
    /** 去除空白后的 API Key。 */
    val apiKey = sanitized.apiKey.trim()
    /** 生效的模型名称。 */
    val model = sanitized.effectiveModel()
    if (endpoint.isBlank() || apiKey.isBlank() || model.isBlank()) {
        return null
    }
    if (endpointPolicy.validationError(endpoint) != null) {
        return null
    }
    return RemoteLlmConnection(
        preset = preset,
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        timeoutSeconds = sanitized.effectiveTimeoutSeconds(),
        temperature = sanitized.effectiveTemperature(),
    )
}
