package com.charmnight.linkgraph.llm

/**
 * 按协议类型把请求路由到具体 LLM 网关实现。
 */
class RoutingLlmGateway(
    /** 保存 OpenAI 协议兼容网关。 */
    private val openAiGatewayFactory: () -> LlmGateway = { OpenAiCompatibleLlmGateway() },
    /** 保存 OpenAI Responses 协议兼容网关。 */
    private val openAiResponsesGatewayFactory: () -> LlmGateway = { OpenAiResponsesLlmGateway() },
    /** 保存 Anthropic 协议兼容网关。 */
    private val anthropicGatewayFactory: () -> LlmGateway = { AnthropicCompatibleLlmGateway() },
) : LlmGateway {
    private val openAiGateway by lazy(LazyThreadSafetyMode.NONE, openAiGatewayFactory)
    private val openAiResponsesGateway by lazy(LazyThreadSafetyMode.NONE, openAiResponsesGatewayFactory)
    private val anthropicGateway by lazy(LazyThreadSafetyMode.NONE, anthropicGatewayFactory)

    /**
     * 根据请求协议选择对应网关执行生成。
     */
    override fun generate(request: LlmRequest): LlmResponse {
        // 协议分发集中在这里，调用方无需关心底层兼容实现。
        return when (request.protocol) {
            LlmWireProtocol.OPENAI_CHAT_COMPLETIONS -> openAiGateway.generate(request)
            LlmWireProtocol.OPENAI_RESPONSES -> openAiResponsesGateway.generate(request)
            LlmWireProtocol.ANTHROPIC_MESSAGES -> anthropicGateway.generate(request)
        }
    }

    override fun stream(
        request: LlmRequest,
        listener: (LlmStreamEvent) -> Unit,
    ): LlmResponse {
        return when (request.protocol) {
            LlmWireProtocol.OPENAI_CHAT_COMPLETIONS -> openAiGateway.stream(request, listener)
            LlmWireProtocol.OPENAI_RESPONSES -> openAiResponsesGateway.stream(request, listener)
            LlmWireProtocol.ANTHROPIC_MESSAGES -> anthropicGateway.stream(request, listener)
        }
    }
}
