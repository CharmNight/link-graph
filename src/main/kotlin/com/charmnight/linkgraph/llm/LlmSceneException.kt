package com.charmnight.linkgraph.llm

import com.charmnight.linkgraph.agent.model.*
import com.charmnight.linkgraph.settings.*

/**
 * 远程 LLM 场景化请求失败的类型化异常。
 *
 * 历史上 [RemoteStructuredResponseParser] 用 [IllegalStateException] + 中文字面量消息包装失败原因，
 * [LlmUserMessageFormatter.describe] 再用 `rawMessage.contains("结构化 JSON")` / `contains("重试 1 次后仍失败")`
 * 反向匹配消息来决定输出——文案改一个字就 break 控制流。
 *
 * 本 sealed class 把场景失败归类为类型化子类，formatter 按 `when (error)` 类型分发，
 * 文案与控制流解耦。
 *
 * - [TransportRetryExhausted]：首轮 transport 重试耗尽（网络/超时），message 已格式化
 * - [StructuredParseFailed]：首轮 + 修复重试都失败（返回内容无法解析为结构化 JSON）
 *
 * 仍继承 [IllegalStateException] 与既有 catch 块兼容（service 层 catch IllegalStateException 不变）。
 */
sealed class LlmSceneException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    /** 业务场景名（如 "代码生成"、"差异分析"），用于错误消息定位。 */
    abstract val scene: String

    /**
     * 首轮 transport 重试耗尽：网络/超时/连接失败。
     *
     * [formattedMessage] 已包含 [LlmUserMessageFormatter.describe] 对底层 cause 的格式化结果，
     * formatter 收到本类型时直接返回 message，不再二次格式化。
     */
    class TransportRetryExhausted(
        override val scene: String,
        cause: Throwable,
        formattedMessage: String,
    ) : LlmSceneException(formattedMessage, cause)

    /**
     * 首轮响应 + 修复重试都失败：返回内容无法解析为结构化 JSON。
     *
     * 详细字段（[firstError] / [repairError] / [firstContent] / [repairedContent]）
     * 供 formatter / 日志按需自取。message 包含完整诊断信息（首次/重试错误与返回片段），
     * 让上游可以原样写入 diagnosticDetail 字段供调试展示。
     */
    class StructuredParseFailed(
        override val scene: String,
        val firstError: Throwable,
        val repairError: Throwable,
        val firstContent: String,
        val repairedContent: String,
    ) : LlmSceneException(
        message = buildStructuredFailureMessage(scene, firstError, firstContent, repairError, repairedContent),
        cause = firstError,
    )
}

/** 拼接两轮解析都失败时的完整错误消息，保留首次/重试错误与返回片段供上游 diagnosticDetail 使用。 */
private fun buildStructuredFailureMessage(
    scene: String,
    firstError: Throwable,
    firstContent: String,
    repairError: Throwable,
    repairedContent: String,
): String = buildString {
    append("远程 LLM ")
    append(scene)
    append("失败：返回内容无法解析为结构化 JSON，且自动修复重试仍失败。")
    append("\n首次解析错误：")
    append(firstError.message?.trim().orEmpty().ifBlank { firstError::class.java.simpleName })
    append("\n首次返回片段：")
    append(truncateForTrace(firstContent, 240))
    append("\n重试解析错误：")
    append(repairError.message?.trim().orEmpty().ifBlank { repairError::class.java.simpleName })
    append("\n重试返回片段：")
    append(truncateForTrace(repairedContent, 240))
}
