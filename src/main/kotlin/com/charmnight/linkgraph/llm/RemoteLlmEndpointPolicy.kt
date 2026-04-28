package com.charmnight.linkgraph.llm

import java.net.URI

/**
 * 统一约束远程 LLM endpoint 的协议策略。
 * 默认只允许 HTTPS；HTTP 只能通过显式调试开关放行。
 */
class RemoteLlmEndpointPolicy(
    private val allowInsecureHttp: Boolean = debugAllowInsecureHttp(),
) {
    fun validationError(endpoint: String): String? {
        val uri = runCatching { URI.create(endpoint.trim()) }.getOrNull()
            ?: return "请求地址格式不正确，请填写以 https:// 开头的地址。"
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (allowInsecureHttp) {
                null
            } else {
                "请求地址必须使用 https://；http:// 仅允许在调试开关开启时使用。"
            }

            else -> "请求地址格式不正确，请填写以 https:// 开头的地址。"
        }
    }

    companion object {
        private fun debugAllowInsecureHttp(): Boolean {
            val property = System.getProperty("linkgraph.allowInsecureHttp")
            if (property.equals("true", ignoreCase = true)) {
                return true
            }
            return System.getenv("LINKGRAPH_ALLOW_INSECURE_HTTP")?.equals("true", ignoreCase = true) == true
        }
    }
}
