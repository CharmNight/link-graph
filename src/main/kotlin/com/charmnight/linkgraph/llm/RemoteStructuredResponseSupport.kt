package com.charmnight.linkgraph.llm

import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/** 保存结构化远程请求结果及其附带警告。 */
internal data class RemoteStructuredResult<T>(
    /** 解析后的结构化值。 */
    val value: T,
    /** 自动重试或修复过程中产生的警告。 */
    val warnings: List<String> = emptyList(),
)

/** 从模型输出里尽量提取可解析的 JSON 片段。 */
internal object RemoteStructuredJsonExtractor {
    /** 提取最像 JSON 的文本片段。 */
    fun extract(content: String): String {
        /** 去掉首尾空白后的原始内容。 */
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return trimmed
        }

        /** 可能可用的 JSON 候选集合。 */
        val candidates = linkedSetOf<String>()
        candidates += trimmed
        extractFenceCandidates(trimmed).forEach(candidates::add)
        extractBalancedJsonCandidate(trimmed)?.let(candidates::add)

        return candidates.firstOrNull { candidate ->
            val normalized = candidate.trim()
            normalized.startsWith("{") || normalized.startsWith("[")
        } ?: trimmed
    }

    /** 提取 Markdown 代码块中的 JSON 候选。 */
    private fun extractFenceCandidates(content: String): List<String> {
        return Regex("""```(?:json|JSON)?\s*([\s\S]*?)```""")
            .findAll(content)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
            .toList()
    }

    /** 从自由文本里提取首段括号平衡的 JSON。 */
    private fun extractBalancedJsonCandidate(content: String): String? {
        /** JSON 起始位置。 */
        var start = -1
        /** 当前括号栈深度。 */
        var stackDepth = 0
        /** 当前是否位于字符串内部。 */
        var quoteOpen = false
        /** 当前字符是否处于转义状态。 */
        var escaping = false
        /** 期待的最外层闭合字符。 */
        var expectedClosing = '\u0000'

        content.forEachIndexed { index, ch ->
            if (start == -1) {
                if (ch == '{' || ch == '[') {
                    start = index
                    stackDepth = 1
                    expectedClosing = if (ch == '{') '}' else ']'
                }
                return@forEachIndexed
            }

            if (quoteOpen) {
                if (escaping) {
                    escaping = false
                } else if (ch == '\\') {
                    escaping = true
                } else if (ch == '"') {
                    quoteOpen = false
                }
                return@forEachIndexed
            }

            when (ch) {
                '"' -> quoteOpen = true
                '{', '[' -> stackDepth += 1
                '}', ']' -> {
                    stackDepth -= 1
                    if (stackDepth == 0 && ch == expectedClosing) {
                        return content.substring(start, index + 1)
                    }
                }
            }
        }

        return null
    }
}

/** 统一处理远程结构化请求、自动重试和 JSON 修复。 */
internal class RemoteStructuredResponseSupport(
    /** 负责真正发起远程请求的网关。 */
    private val gateway: LlmGateway,
) {
    /** 请求远程结构化结果，必要时自动重试并尝试修复 JSON。 */
    fun <T> request(
        request: LlmRequest,
        scene: String,
        schema: String,
        preferStreaming: Boolean = false,
        onPreview: ((String, Boolean) -> Unit)? = null,
        parse: (String) -> T,
    ): RemoteStructuredResult<T> {
        /** 带原生结构化输出约束的请求。 */
        val structuredRequest = request.withStructuredOutput(scene, schema)
        /** 请求过程中的附加警告。 */
        val responseWarnings = mutableListOf<String>()
        /** 首轮远程响应。 */
        val firstResponse = runCatching {
            executeRequest(
                request = structuredRequest,
                preferStreaming = preferStreaming,
                onPreview = onPreview,
            )
        }.recoverCatching { error ->
            if (!error.isRetryableTransportFailure()) {
                throw error
            }
            responseWarnings += "远程 LLM $scene 首轮请求超时或连接失败，已自动重试 1 次。"
            runCatching {
                executeRequest(
                    request = structuredRequest,
                    preferStreaming = preferStreaming,
                    onPreview = onPreview,
                )
            }.getOrElse { retryError ->
                throw IllegalStateException(
                    "远程 LLM $scene 首轮请求超时或连接失败，重试 1 次后仍失败：${LlmUserMessageFormatter.describe(retryError)}",
                    retryError,
                )
            }
        }.getOrThrow()
        /** 首轮响应的解析结果。 */
        val firstResult = runCatching { parse(firstResponse.content) }
        if (firstResult.isSuccess) {
            return RemoteStructuredResult(firstResult.getOrThrow(), responseWarnings)
        }

        /** 首轮解析异常。 */
        val firstError = firstResult.exceptionOrNull() ?: error("Unknown parse failure.")
        /** 用于修复 JSON 的二次请求。 */
        val repairRequest = structuredRequest.copy(
            systemPrompt = buildRepairSystemPrompt(scene),
            userPrompt = buildRepairUserPrompt(
                scene = scene,
                schema = schema,
                request = structuredRequest,
                originalContent = firstResponse.content,
                firstError = firstError,
            ),
            deliveryMode = LlmDeliveryMode.FULL,
        )
        /** 修复请求返回的响应。 */
        val repairedResponse = gateway.generate(repairRequest)
        /** 修复响应的解析结果。 */
        val repairedResult = runCatching { parse(repairedResponse.content) }
        return repairedResult.fold(
            onSuccess = { value ->
                RemoteStructuredResult(
                    value = value,
                    warnings = responseWarnings + "远程 LLM $scene 首轮返回不是可解析的结构化 JSON，已自动修复重试 1 次并成功。",
                )
            },
            onFailure = { repairError ->
                error(
                    buildStructuredFailureMessage(
                        scene = scene,
                        firstError = firstError,
                        firstContent = firstResponse.content,
                        repairError = repairError,
                        repairedContent = repairedResponse.content,
                    ),
                )
            },
        )
    }

    /** 根据偏好选择完整返回或流式执行。 */
    private fun executeRequest(
        request: LlmRequest,
        preferStreaming: Boolean,
        onPreview: ((String, Boolean) -> Unit)?,
    ): LlmResponse {
        if (!preferStreaming) {
            return gateway.generate(request.copy(deliveryMode = LlmDeliveryMode.FULL))
        }
        val preview = StringBuilder()
        val response = gateway.stream(request.copy(deliveryMode = LlmDeliveryMode.STREAM)) { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    preview.append(event.text)
                    onPreview?.invoke(preview.toString(), false)
                }

                is LlmStreamEvent.Completed -> {
                    if (preview.isEmpty() && event.response.content.isNotEmpty()) {
                        preview.append(event.response.content)
                    }
                    onPreview?.invoke(preview.toString(), true)
                }

                is LlmStreamEvent.Failed -> throw event.error
                is LlmStreamEvent.Started -> Unit
            }
        }
        if (preview.isEmpty() && response.content.isNotEmpty()) {
            onPreview?.invoke(response.content, true)
        }
        return response
    }

    /** 构造 JSON 修复场景的系统提示词。 */
    private fun buildRepairSystemPrompt(scene: String): String {
        return """
            你是 IDEA Link Graph 的 $scene JSON 修复器。
            你只能输出严格合法的 JSON。
            禁止输出 Markdown、代码块、解释性前言、后缀说明。
            不允许编造新的代码事实；如果信息不足，请保留简短说明，并让数组字段返回 []。
        """.trimIndent()
    }

    /** 构造 JSON 修复场景的用户提示词。 */
    private fun buildRepairUserPrompt(
        scene: String,
        schema: String,
        request: LlmRequest,
        originalContent: String,
        firstError: Throwable,
    ): String {
        val parseError = firstError.message?.trim().orEmpty().ifBlank { firstError::class.java.simpleName }
        return """
            当前场景：$scene

            目标 JSON 结构：
            $schema

            原始 system prompt：
            ${request.systemPrompt}

            原始 user prompt：
            ${request.userPrompt}

            上一次结构化校验失败的具体原因：
            $parseError

            修复要求：
            1. 必须严格匹配“目标 JSON 结构”，补齐所有必填字段。
            2. 如果错误包含 `is required`，对应路径上的字段必须显式提供，不能省略。
            3. 如果错误指向数组中的某个对象，必须检查同类对象是否也满足相同约束。
            4. 保留原始任务事实，只修复结构与字段类型，不要添加解释文本。

            上一次模型输出：
            ${truncate(originalContent, 10_000)}

            上一次输出无法直接解析为结构化 JSON。请基于原始任务上下文与上述校验错误，重新输出一个合法 JSON。
            只返回 JSON。
        """.trimIndent()
    }

    /** 构造两轮解析都失败时的完整错误消息。 */
    private fun buildStructuredFailureMessage(
        scene: String,
        firstError: Throwable,
        firstContent: String,
        repairError: Throwable,
        repairedContent: String,
    ): String {
        return buildString {
            append("远程 LLM ")
            append(scene)
            append("失败：返回内容无法解析为结构化 JSON，且自动修复重试仍失败。")
            append("\n首次解析错误：")
            append(firstError.message?.trim().orEmpty().ifBlank { firstError::class.java.simpleName })
            append("\n首次返回片段：")
            append(truncate(firstContent, 240))
            append("\n重试解析错误：")
            append(repairError.message?.trim().orEmpty().ifBlank { repairError::class.java.simpleName })
            append("\n重试返回片段：")
            append(truncate(repairedContent, 240))
        }
    }

    /** 规范化并裁剪响应片段，便于写入错误消息。 */
    private fun truncate(content: String, limit: Int): String {
        /** 适合写入日志和提示的标准化文本。 */
        val normalized = content.replace("\r", "").replace("\n", "\\n").trim()
        return if (normalized.length <= limit) normalized else normalized.take(limit) + "..."
    }

    /** 判断异常是否属于可自动重试的网络故障。 */
    private fun Throwable.isRetryableTransportFailure(): Boolean {
        /** 归一化后的异常消息。 */
        val message = message.orEmpty().lowercase()
        return this is HttpTimeoutException ||
            this is HttpConnectTimeoutException ||
            this is ConnectException ||
            this is UnknownHostException ||
            message.contains("timed out") ||
            message.contains("timeout") ||
            message.contains("connection refused") ||
            message.contains("failed to connect")
    }

    /** 为结构化请求补齐 provider 可消费的 schema 元数据。 */
    private fun LlmRequest.withStructuredOutput(
        scene: String,
        schema: String,
    ): LlmRequest {
        if (structuredOutput != null) {
            return this
        }
        if (!protocol.supportsNativeStructuredOutput()) {
            return this
        }
        return copy(
            structuredOutput = LlmStructuredOutput(
                name = normalizeStructuredOutputName(scene),
                schema = schema.trim(),
            ),
        )
    }

    /** 归一化 provider 侧使用的 schema 名称。 */
    private fun normalizeStructuredOutputName(scene: String): String {
        val ascii = scene
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return if (ascii.isNotEmpty()) {
            "linkgraph_$ascii"
        } else {
            "linkgraph_structured_${scene.hashCode().toUInt().toString(16)}"
        }
    }

    private fun LlmWireProtocol.supportsNativeStructuredOutput(): Boolean {
        return this == LlmWireProtocol.OPENAI_RESPONSES
    }
}
