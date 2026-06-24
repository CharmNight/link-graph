package com.charmnight.linkgraph.llm

import java.net.URI

/**
 * 统一约束远程 LLM endpoint 的协议策略。
 *
 * 默认只允许 HTTPS；HTTP 只能通过显式调试开关放行。
 * 这种约束避免用户在不知情的情况下把 API key 通过明文 HTTP 发送出去。
 */
class RemoteLlmEndpointPolicy(
    /** 是否允许明文 HTTP；默认从调试开关读取。 */
    private val allowInsecureHttp: Boolean = debugAllowInsecureHttp(),
) {
    /**
     * 校验给定 endpoint 字符串。
     *
     * @param endpoint 待校验的 endpoint URL
     * @return 错误说明；返回 null 表示通过
     */
    fun validationError(endpoint: String): String? {
        // 解析 URL 失败直接给出格式错误
        val uri = runCatching { URI.create(endpoint.trim()) }.getOrNull()
            ?: return "请求地址格式不正确，请填写以 https:// 开头的地址。"
        return when (uri.scheme?.lowercase()) {
            "https" -> null
            "http" -> if (allowInsecureHttp) {
                // 调试模式允许 HTTP
                null
            } else {
                "请求地址必须使用 https://；http:// 仅允许在调试开关开启时使用。"
            }

            // 其他 scheme（ftp、file 等）一律拒绝
            else -> "请求地址格式不正确，请填写以 https:// 开头的地址。"
        }
    }

    companion object {
        /**
         * 从系统属性或环境变量判断是否允许 HTTP。
         * 同时支持两种来源是为了适配不同部署环境（IDE 启动参数 vs 容器 env）。
         */
        private fun debugAllowInsecureHttp(): Boolean {
            val property = System.getProperty("linkgraph.allowInsecureHttp")
            if (property.equals("true", ignoreCase = true)) {
                return true
            }
            return System.getenv("LINKGRAPH_ALLOW_INSECURE_HTTP")?.equals("true", ignoreCase = true) == true
        }
    }
}
