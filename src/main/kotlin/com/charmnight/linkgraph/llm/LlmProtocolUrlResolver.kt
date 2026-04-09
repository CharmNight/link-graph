package com.charmnight.linkgraph.llm

/**
 * 根据所选协议把基础端点补全为最终请求地址。
 */
internal object LlmProtocolUrlResolver {
    /**
     * 为指定协议解析最终可请求的 URL。
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
                if (normalized.endsWith("/chat/completions")) normalized else "$normalized/chat/completions"
            }

            LlmWireProtocol.OPENAI_RESPONSES -> {
                if (normalized.endsWith("/responses")) normalized else "$normalized/responses"
            }

            LlmWireProtocol.ANTHROPIC_MESSAGES -> when {
                normalized.endsWith("/v1/messages") -> normalized
                normalized.endsWith("/messages") -> normalized
                normalized.endsWith("/v1") -> "$normalized/messages"
                else -> "$normalized/v1/messages"
            }
        }
    }
}
