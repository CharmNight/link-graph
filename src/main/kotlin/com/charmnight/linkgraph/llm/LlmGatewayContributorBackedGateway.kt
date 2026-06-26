package com.charmnight.linkgraph.llm

import java.net.http.HttpClient

/**
 * 把 [LlmGatewayContributor] 包装为 [LlmGateway] 的适配器（P3-1 EP 暴露）。
 *
 * 第三方通过 EP 注册 contributor（描述请求结构 + 解析响应），
 * 由本类负责实际调用 [LlmGatewayClient] 发送请求 —— SSRF / size guard 等安全策略
 * 在 client 内统一应用，第三方实现无法绕过。
 *
 * 内置 [OpenAiCompatibleLlmGateway] / [OpenAiResponsesLlmGateway] / [AnthropicCompatibleLlmGateway]
 * 已经直接使用 [LlmGatewayClient]，本类主要用于第三方 contributor 的接入。
 *
 * @param contributor 第三方贡献者（描述请求构造 + 响应解析）
 * @param clientFactory HTTP client 工厂；默认走 [LlmGatewayClient.defaultHttpClient]
 */
class LlmGatewayContributorBackedGateway(
    private val contributor: LlmGatewayContributor,
    private val clientFactory: (LlmRequest) -> HttpClient = LlmGatewayClient::defaultHttpClient,
) : LlmGateway {
    override fun generate(request: LlmRequest): LlmResponse {
        val descriptor = contributor.buildHttpRequest(request)
        val headers = descriptor.headers.entries.map { it.key to it.value } +
            ("Authorization" to "Bearer ${request.apiKey}")
        return LlmGatewayClient.generateJson(
            client = clientFactory(request),
            request = request,
            url = descriptor.url,
            headers = headers,
            payload = descriptor.body,
            extractContent = { body -> contributor.parseResponse(body, request).content },
        ).withoutRawBody()
    }

    /**
     * 流式生成暂时回退为完整请求模式（由 [LlmGateway] 接口默认实现处理）。
     *
     * 第三方 contributor 暂时只贡献同步生成路径；如需流式支持，未来在 contributor 接口扩展
     * `parseStreamEvent` 默认方法即可（接口稳定性：默认实现保证不破坏现有 contributor）。
     */
}
