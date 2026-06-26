package com.charmnight.linkgraph.llm

/**
 * LLM 网关贡献者接口（P3-1 EP 暴露）。
 *
 * 第三方通过 IntelliJ EP 注册实现此接口，**不能直接发 HTTP 请求** —— 只描述请求结构，
 * 由内置 [LlmGatewayClient] 统一发送并应用 SSRF / size guard 等安全策略。
 *
 * 这是「强制经 client」的实现：第三方贡献的是请求构造 + 响应解析逻辑，
 * 实际网络请求由内置 client 完成，安全 guard 一定生效。
 *
 * 接口稳定性承诺：本接口属于公共 API，破坏性变更需要 major version 演进。
 * 字段新增可以走默认值兼容（Kotlin 接口 default impl）。
 */
interface LlmGatewayContributor {
    /**
     * 此贡献者支持的协议标识。
     *
     * 与 [LlmRequest.protocol] 的 [LlmWireProtocol] 名对应，如 "OPENAI_CHAT_COMPLETIONS"。
     * 第三方也可以自定义协议名（如 "AZURE_OPENAI"），由 [LlmRequest.protocol] 显式指定。
     */
    val supportedProtocol: String

    /**
     * 为给定请求构造 HTTP 请求描述符。
     *
     * 第三方实现应把 LlmRequest 转换为目标 API 的 URL / headers / body，
     * 但**不应**实际发送请求。客户端会按描述符发请求。
     */
    fun buildHttpRequest(request: LlmRequest): LlmHttpRequestDescriptor

    /**
     * 解析 HTTP 响应体为 [LlmResponse]。
     *
     * 第三方实现根据 API 协议（如 OpenAI Chat / Responses / Anthropic Messages 各自不同）
     * 把响应 JSON 转换为统一的 LlmResponse。
     *
     * 注意：响应体已由内置 client 通过 size guard 流式读取后传入，
     * 第三方实现不需要再处理流式读取。
     */
    fun parseResponse(responseBody: String, request: LlmRequest): LlmResponse
}

/**
 * HTTP 请求描述符（不实际发请求）。
 *
 * 第三方 [LlmGatewayContributor] 构造此对象，由 [LlmGatewayClient] 实际发送。
 */
data class LlmHttpRequestDescriptor(
    /** HTTP 方法，默认 POST。 */
    val method: String = "POST",
    /** 目标 URL。 */
    val url: String,
    /** 请求头（不含 Authorization，由 client 在执行时从 LlmRequest.apiKey 注入）。 */
    val headers: Map<String, String> = emptyMap(),
    /** 请求体（通常已 JSON 化）。 */
    val body: String,
    /** 超时（秒）。 */
    val timeoutSeconds: Int,
)
