package com.charmnight.linkgraph.llm

/**
 * 根据所选协议把基础端点补全为最终请求地址。
 *
 * 不同协议（OpenAI Chat Completions / OpenAI Responses / Anthropic Messages）
 * 默认路径不同，本对象把这些规则集中维护，避免调用方各自拼接出错。
 */
internal object LlmProtocolUrlResolver {
    /**
     * 为指定协议解析最终可请求的 URL。
     *
     * @param endpoint 用户配置的端点地址（可能是裸域名或带路径）
     * @param protocol LLM 线协议枚举
     * @return 补全后的 URL；输入为空时直接返回空串
     */
    fun resolve(
        endpoint: String,
        protocol: LlmWireProtocol,
    ): String {
        // 统一去掉尾部斜杠，避免后续拼接出现双斜杠。
        /** 去掉首尾空白和尾斜杠后的地址。 */
        val normalized = endpoint.trim().removeSuffix("/")
        if (normalized.isBlank()) {
            return normalized
        }
        // 不同协议的默认路径不同，这里集中处理补全规则。
        return when (protocol) {
            LlmWireProtocol.OPENAI_CHAT_COMPLETIONS -> {
                // 已带路径就不重复追加，避免出现 /chat/completions/chat/completions
                if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
            }

            LlmWireProtocol.OPENAI_RESPONSES -> {
                if (normalized.endsWith("/responses")) normalized else "$normalized/responses"
            }

            LlmWireProtocol.ANTHROPIC_MESSAGES -> when {
                // 已带版本号与消息路径：原样返回
                normalized.endsWith("/v1/messages") -> normalized
                normalized.endsWith("/messages") -> normalized
                // 只带版本号：补 messages
                normalized.endsWith("/v1") -> "$normalized/messages"
                // 都没有：补全版本号 + messages
                else -> "$normalized/v1/messages"
            }
        }
    }
}
