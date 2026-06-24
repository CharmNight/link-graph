package com.charmnight.linkgraph.application.request

import com.charmnight.linkgraph.llm.runtime.AgentRunState
import com.charmnight.linkgraph.llm.runtime.AgentStepRecord
import com.charmnight.linkgraph.llm.runtime.RunBudget
import java.net.URI

/**
 * 把 Agent runtime 状态（[AgentRunState] / [RunBudget] / [AgentStepRecord]）格式化为
 * 可读的 trace 字符串；以及把 endpoint URL 提炼为前端展示用的简短摘要。
 *
 * 从 AsyncRequestLifecycleSupport.kt 抽出（P2-1）：纯展示逻辑，无状态、无副作用，
 * 与 lifecycle / tracker 调度逻辑解耦后便于单独调整 trace 文本格式。
 */

/** 把 runtime 状态完整格式化为多行文本：header + budget + 每步记录。 */
internal fun formatRuntimeDetail(runtimeState: AgentRunState): String {
    val sections = mutableListOf<String>()
    sections += runtimeHeader(runtimeState)
    sections += formatBudget(runtimeState.budget)
    runtimeState.stepRecords.forEach { record ->
        sections += formatStepRecord(record)
    }
    return sections.joinToString(separator = "\n")
}

/** 输出包含 runId、capability 与失败原因的 runtime 头部摘要。 */
internal fun runtimeHeader(runtimeState: AgentRunState): String {
    return buildString {
        append("runtime runId=").append(runtimeState.runId)
        append(", capability=").append(runtimeState.capabilityId)
        runtimeState.failureReason?.let {
            append(", failureReason=").append(it.name)
        }
    }
}

/** 把步数、文件、片段、代码行四类预算消耗格式化为单行文本。 */
internal fun formatBudget(budget: RunBudget): String {
    return buildString {
        append("runtime budget steps=").append(budget.usedSteps).append('/').append(budget.maxSteps)
        append(", files=").append(budget.filesRead).append('/').append(budget.maxFilesRead)
        append(", snippets=").append(budget.snippetsRead).append('/').append(budget.maxSnippets)
        append(", lines=").append(budget.totalSnippetLinesRead).append('/').append(budget.maxTotalSnippetLines)
    }
}

/** 把单步执行记录（序号、阶段、摘要、工具、节点）格式化为可读文本。 */
internal fun formatStepRecord(record: AgentStepRecord): String {
    return buildString {
        append("step[").append(record.stepIndex).append("]")
        append(" phase=").append(record.phase.name)
        append(", summary=").append(record.summary)
        record.toolName?.let { toolName ->
            append(", tool=").append(toolName)
        }
        record.nodeId?.let { nodeId ->
            append(", nodeId=").append(nodeId)
        }
    }
}

/**
 * 提炼前端可展示的 endpoint 摘要：scheme://host[:port][/path]。
 *
 * 非 remote 或 endpoint 为空时返回 null；解析失败时回退返回原始 endpoint。
 */
internal fun resolveEndpointSummary(
    endpoint: String?,
    remotePresetSelected: Boolean,
): String? {
    if (!remotePresetSelected || endpoint.isNullOrBlank()) {
        return null
    }
    return runCatching {
        val uri = URI(endpoint)
        buildString {
            append(uri.scheme ?: "https")
            append("://")
            append(uri.host ?: endpoint)
            uri.port.takeIf { it > 0 }?.let { append(":").append(it) }
            val path = uri.path?.trim()?.takeIf { it.isNotEmpty() && it != "/" }
            if (path != null) {
                append(path)
            }
        }
    }.getOrElse {
        endpoint
    }
}
