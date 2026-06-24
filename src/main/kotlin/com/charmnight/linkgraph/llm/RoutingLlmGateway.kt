package com.charmnight.linkgraph.llm

/**
 * 按协议类型把请求路由到具体 LLM 网关实现。
 *
 * 不同协议（OpenAI Chat Completions / OpenAI Responses / Anthropic Messages）
 * 各自有不同的请求/响应格式与流式协议。本类作为统一入口按 protocol 字段分发，
 * 让上层只依赖 [LlmGateway] 接口，不感知底层差异。
 *
 * 三个具体网关使用惰性初始化（lazy），第一次调用时才构造，避免启动期开销。
 */
class RoutingLlmGateway(
    /** 保存 OpenAI 协议兼容网关。 */
    private val openAiGatewayFactory: () -> LlmGateway = { OpenAiCompatibleLlmGateway() },
    /** 保存 OpenAI Responses 协议兼容网关。 */
    private val openAiResponsesGatewayFactory: () -> LlmGateway = { OpenAiResponsesLlmGateway() },
    /** 保存 Anthropic 协议兼容网关。 */
    private val anthropicGatewayFactory: () -> LlmGateway = { AnthropicCompatibleLlmGateway() },
) : LlmGateway {
    /** OpenAI Chat Completions 网关的惰性实例。 */
    private val openAiGateway by lazy(LazyThreadSafetyMode.NONE, openAiGatewayFactory)
    /** OpenAI Responses 网关的惰性实例。 */
    private val openAiResponsesGateway by lazy(LazyThreadSafetyMode.NONE, openAiResponsesGatewayFactory)
    /** Anthropic Messages 网关的惰性实例。 */
    private val anthropicGateway by lazy(LazyThreadSafetyMode.NONE, anthropicGatewayFactory)

    /**
     * 根据请求协议选择对应网关执行生成。
     *
     * @param request LLM 请求，protocol 字段决定路由目标
     * @return LLM 响应
     */
    override fun generate(request: LlmRequest): LlmResponse {
        // 协议分发集中在这里，调用方无需关心底层兼容实现。
        return when (request.protocol) {
            LlmWireProtocol.OPENAI_CHAT_COMPLETIONS -> openAiGateway.generate(request)
            LlmWireProtocol.OPENAI_RESPONSES -> openAiResponsesGateway.generate(request)
            LlmWireProtocol.ANTHROPIC_MESSAGES -> anthropicGateway.generate(request)
        }
    }

    /**
     * 流式生成版本的路由：与 [generate] 同样按协议分发，
     * 但通过 [listener] 实时回调流式事件。
     *
     * @param request LLM 请求
     * @param listener 流式事件回调
     * @return 最终的 LLM 响应（流结束后聚合）
     */
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
