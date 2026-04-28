package com.charmnight.linkgraph.settings

import com.charmnight.linkgraph.llm.LlmGateway
import com.charmnight.linkgraph.llm.LlmDeliveryMode
import com.charmnight.linkgraph.llm.LlmResponse
import com.charmnight.linkgraph.llm.LlmRequest
import com.charmnight.linkgraph.llm.LlmStreamEvent
import com.charmnight.linkgraph.llm.LlmStructuredOutput
import com.charmnight.linkgraph.llm.LlmStructuredSchemas
import com.charmnight.linkgraph.llm.LlmUserMessageFormatter
import com.charmnight.linkgraph.llm.LlmWireProtocol
import com.charmnight.linkgraph.llm.RemoteLlmEndpointPolicy
import com.charmnight.linkgraph.llm.RemoteLlmConnection
import com.charmnight.linkgraph.llm.RoutingLlmGateway
import com.charmnight.linkgraph.llm.remoteConnectionOrNull

/**
 * 表示远程 LLM 配置校验结果。
 */
data class RemoteLlmSettingsValidationResult(
    /** 标记校验是否通过。 */
    val ok: Boolean,
    /** 保存可直接展示给用户的结果消息。 */
    val message: String,
)

/**
 * 设置页和保存动作共用的远程 LLM 配置校验器。
 * 如果当前并未启用远程 provider，会直接返回“无需远程验证”，避免用户保存时被无关校验打断。
 */
class RemoteLlmSettingsValidator(
    /** 保存执行远程校验请求的网关。 */
    private val gateway: LlmGateway = RoutingLlmGateway(),
    /** 保存远程 endpoint 协议策略。 */
    private val endpointPolicy: RemoteLlmEndpointPolicy = RemoteLlmEndpointPolicy(),
) {
    /**
     * 校验设置页中的远程 LLM 配置是否可用。
     */
    fun validate(state: LinkGraphSettingsState): RemoteLlmSettingsValidationResult {
        // 先对输入值做 trim，避免尾部空白影响后续 URL 和模型判断。
        val rawEndpoint = state.endpoint.trim().removeSuffix("/")
        val rawApiKey = state.apiKey.trim()
        val rawModel = state.model.trim()
        // 使用规范化后的值生成最终待校验配置。
        val sanitized = state.copy(
            endpoint = rawEndpoint,
            apiKey = rawApiKey,
            model = rawModel,
        ).sanitized()
        if (!sanitized.llmEnabled) {
            return RemoteLlmSettingsValidationResult(
                ok = true,
                message = "LLM 未启用，当前不会请求远程服务。",
            )
        }
        if (!sanitized.providerPreset().isRemote) {
            return RemoteLlmSettingsValidationResult(
                ok = true,
                message = "当前使用本地规则，无需验证远程连接。",
            )
        }
        val effectiveEndpoint = sanitized.effectiveEndpoint()
        val effectiveModel = sanitized.effectiveModel()
        if (
            rawApiKey.isBlank() ||
            effectiveEndpoint.isBlank() ||
            effectiveModel.isBlank()
        ) {
            // 收集所有缺失项，便于一次性反馈给用户。
            val missingFields = buildList {
                if (effectiveEndpoint.isBlank()) {
                    add("请求地址")
                }
                if (rawApiKey.isBlank()) {
                    add("API 密钥")
                }
                if (effectiveModel.isBlank()) {
                    add("模型名")
                }
            }.joinToString("、")
            return RemoteLlmSettingsValidationResult(
                ok = false,
                message = buildString {
                    append("远程 LLM 配置不完整。")
                    append("\n缺少：")
                    append(missingFields)
                    append("\n位置：链路图设置（IDE 设置 > 工具 > 链路图）")
                    append("\n下一步：补全后点击“验证远程配置”。")
                },
            )
        }
        endpointPolicy.validationError(effectiveEndpoint)?.let { errorMessage ->
            return RemoteLlmSettingsValidationResult(
                ok = false,
                message = errorMessage,
            )
        }
        // 只有生成出完整远程连接配置后，才真正发起验证请求。
        val remoteConnection = sanitized.remoteConnectionOrNull(endpointPolicy = endpointPolicy)
            ?: return RemoteLlmSettingsValidationResult(
                ok = false,
                message = "远程 LLM 配置不完整，请检查请求地址、API 密钥和模型名。",
            )
        // 记录最终命中的请求地址，便于成功或失败时回显给用户。
        val validatedUrl = remoteConnection.requestUrl()

        // 用一条极小的探针请求验证鉴权、地址和模型配置。
        return runCatching {
            executeValidationProbe(remoteConnection)
        }.fold(
            onSuccess = { _: LlmResponse ->
                RemoteLlmSettingsValidationResult(
                    ok = true,
                    message = buildString {
                        append("远程 LLM 配置验证通过。")
                        append("\n已验证接口：")
                        append(validatedUrl)
                        append("\n模型：")
                        append(remoteConnection.model)
                    },
                )
            },
            onFailure = { error ->
                // 失败时尽量带上人类可读错误、目标地址和模型，便于立即排查。
                RemoteLlmSettingsValidationResult(
                    ok = false,
                    message = buildString {
                        append(LlmUserMessageFormatter.describe(error))
                        append("\n已尝试接口：")
                        append(validatedUrl)
                        append("\n模型：")
                        append(remoteConnection.model)
                        append("\n下一步：修正后再次点击“验证远程配置”。")
                    },
                )
            },
        )
    }

    /**
     * 设置页验证必须覆盖真实问答会用到的远程能力，而不是只测一个普通完整返回。
     * 否则会出现“验证连接成功，但问答请求因流式或结构化输出不兼容失败”的假阳性。
     */
    private fun executeValidationProbe(remoteConnection: RemoteLlmConnection): LlmResponse {
        val request = remoteConnection
            .toRequest(
                systemPrompt = buildValidationSystemPrompt(remoteConnection),
                userPrompt = buildValidationUserPrompt(remoteConnection),
            )
            .withQuestionRequestShape()
        if (!remoteConnection.preset.capabilities.supportsStreaming) {
            return gateway.generate(request.copy(deliveryMode = LlmDeliveryMode.FULL))
        }
        val responseBuilder = StringBuilder()
        return gateway.stream(request.copy(deliveryMode = LlmDeliveryMode.STREAM)) { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> responseBuilder.append(event.text)
                is LlmStreamEvent.Failed -> throw event.error
                is LlmStreamEvent.Started,
                is LlmStreamEvent.Completed -> Unit
            }
        }
    }

    /**
     * 对齐问答请求使用的协议特性。
     */
    private fun LlmRequest.withQuestionRequestShape(): LlmRequest {
        if (protocol != LlmWireProtocol.OPENAI_RESPONSES || structuredOutput != null) {
            return this
        }
        return copy(
            structuredOutput = LlmStructuredOutput(
                name = "linkgraph_settings_validation",
                schema = LlmStructuredSchemas.PATCH_RESULT,
            ),
        )
    }

    private fun buildValidationSystemPrompt(remoteConnection: RemoteLlmConnection): String {
        return if (remoteConnection.preset.wireProtocol == LlmWireProtocol.OPENAI_RESPONSES) {
            "你是 IDEA Link Graph 的远程配置校验器。只允许返回符合给定 JSON Schema 的配置校验结果。"
        } else {
            "你是 IDEA Link Graph 的远程配置校验器。只允许返回纯文本 OK。"
        }
    }

    private fun buildValidationUserPrompt(remoteConnection: RemoteLlmConnection): String {
        return if (remoteConnection.preset.wireProtocol == LlmWireProtocol.OPENAI_RESPONSES) {
            """
            这是链路图插件的配置校验请求。
            请返回一个最小结构化问答结果：
            - answer 为 OK
            - warnings、findings、candidateChanges、investigationThreads 均为空数组
            - patch 为 null
            """.trimIndent()
        } else {
            "这是链路图插件的配置校验请求。请只返回 OK。"
        }
    }
}
