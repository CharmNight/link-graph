package com.charmnight.linkgraph.llm

import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/**
 * 把远程 LLM 的底层异常翻译成更适合 UI 展示和设置页校验的中文提示。
 * 这里保留 HTTP 状态和 provider code，避免“只知道失败，不知道为什么”。
 */
internal object LlmUserMessageFormatter {
    /** 解析 HTTP 状态码和 provider 错误码的正则。 */
    private val httpPattern = Regex("""HTTP\s+(\d+)(?:\s+\(([^)]+)\))?:?\s*(.*)""", RegexOption.IGNORE_CASE)
    /** 从服务端消息里提取模型名的正则。 */
    private val modelPattern = Regex("""model\s+([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)

    /** 把底层异常转换成适合直接展示给用户的中文文案。 */
    fun describe(error: Throwable): String {
        /** 原始异常消息。 */
        val rawMessage = error.message?.trim().orEmpty()
        /** 小写化后的异常消息。 */
        val lowerRawMessage = rawMessage.lowercase()
        /** HTTP 模式匹配结果。 */
        val parsed = httpPattern.find(rawMessage)
        /** 解析出的 HTTP 状态码。 */
        val statusCode = parsed?.groupValues?.getOrNull(1)?.toIntOrNull()
        /** 解析出的 provider 错误码。 */
        val errorCode = parsed?.groupValues?.getOrNull(2)?.trim().orEmpty().ifBlank { null }
        /** 解析出的 provider 文本消息。 */
        val providerMessage = parsed?.groupValues?.getOrNull(3)?.trim().orEmpty().ifBlank {
            rawMessage.takeIf { it.isNotBlank() }
        }
        /** 从 provider 文本里提取的模型名。 */
        val modelName = providerMessage
            ?.let(modelPattern::find)
            ?.groupValues
            ?.getOrNull(1)

        /** 统一拼装带技术标签的用户提示文案。 */
        fun withTag(summary: String, suggestion: String? = null): String {
            /** 拼接后的技术标签。 */
            val technicalTag = listOfNotNull(
                statusCode?.let { "HTTP $it" },
                errorCode,
            ).joinToString(" / ")
            return buildString {
                append(summary)
                if (technicalTag.isNotBlank()) {
                    append("（")
                    append(technicalTag)
                    append("）")
                }
                append("。")
                suggestion?.takeIf { it.isNotBlank() }?.let {
                    append(it)
                }
            }
        }

        when {
            rawMessage.contains("结构化 JSON") -> {
                return rawMessage
            }

            rawMessage.contains("重试 1 次后仍失败") -> {
                return rawMessage
            }

            error is HttpConnectTimeoutException || error is HttpTimeoutException || lowerRawMessage.contains("timed out") || lowerRawMessage.contains("timeout") -> {
                return withTag(
                    summary = "请求远程 LLM 超时",
                    suggestion = "请检查网络连通性，或适当调大超时时间。",
                )
            }

            error is ConnectException || error is UnknownHostException || lowerRawMessage.contains("connection refused") || lowerRawMessage.contains("failed to connect") -> {
                return withTag(
                    summary = "无法连接到远程 LLM 服务",
                    suggestion = "请检查请求地址、代理和网络连通性。",
                )
            }

            errorCode == "model_not_found" || providerMessage?.contains("No available channel for model", ignoreCase = true) == true -> {
                val modelSummary = modelName?.let { "模型 $it 在当前兼容服务中不可用" } ?: "当前模型在兼容服务中不可用"
                return withTag(
                    summary = modelSummary,
                    suggestion = "请在设置中改成服务端已开通的模型。",
                )
            }

            statusCode == 401 -> {
                return withTag(
                    summary = "API 密钥无效或已过期",
                    suggestion = "请检查 API 密钥是否正确。",
                )
            }

            statusCode == 403 -> {
                return withTag(
                    summary = "当前 API 密钥没有访问该模型或接口的权限",
                    suggestion = "请检查服务端授权、分组权限和模型开通状态。",
                )
            }

            statusCode == 404 -> {
                return withTag(
                    summary = "请求地址不可用，未找到兼容的 /chat/completions 接口",
                    suggestion = "请检查请求地址是否正确，必要时补齐 /v1。",
                )
            }

            statusCode == 429 -> {
                return withTag(
                    summary = "请求被限流或当前额度不足",
                    suggestion = "请稍后重试，或检查服务端配额与频控。",
                )
            }

            statusCode != null && statusCode >= 500 -> {
                return withTag(
                    summary = "远程 LLM 服务暂时不可用",
                    suggestion = providerMessage?.takeIf { it.isNotBlank() }?.let { "服务提示：$it。" },
                )
            }

            rawMessage.isNotBlank() -> {
                return withTag(
                    summary = rawMessage.removePrefix("Remote LLM request failed with ").trim(),
                    suggestion = "请检查请求地址、鉴权和模型配置。",
                )
            }

            else -> {
                return "远程 LLM 调用失败。请检查请求地址、鉴权和模型配置。"
            }
        }
    }

    /** 判断当前字符串是否像一个可用的 HTTP/HTTPS endpoint。 */
    fun isLikelyEndpoint(endpoint: String): Boolean {
        return runCatching {
            /** 解析出的 URI。 */
            val uri = URI.create(endpoint)
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)
        }.getOrDefault(false)
    }
}
