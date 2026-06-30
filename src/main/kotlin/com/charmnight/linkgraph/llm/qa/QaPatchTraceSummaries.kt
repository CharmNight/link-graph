package com.charmnight.linkgraph.llm.qa

import com.charmnight.linkgraph.agent.model.EvidenceTraceEntry
import com.charmnight.linkgraph.agent.model.ResultEvidenceFinding
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.workbench.CandidateDraftChange
import com.charmnight.linkgraph.workbench.InvestigationThread

/**
 * 把 GraphQaPatchService 内部用于 trace 日志的 4 类摘要格式化逻辑抽出（P2-1）。
 *
 * 每个函数把对应数据结构（candidate / thread / sourceSnippet / evidenceTrace）
 * 渲染为简短的方括号字符串，给 logger.warn 与 debug trace 复用。
 * 纯展示、无状态、无副作用，与 QA patch 的应用 / 校验 / 派生逻辑解耦。
 */

/** 生成候选变更的简短摘要字符串，用于 trace 日志输出。 */
internal fun candidateSummaries(changes: List<CandidateDraftChange>): String {
    if (changes.isEmpty()) {
        return "[]"
    }
    return changes.take(3).joinToString(
        prefix = "[",
        postfix = if (changes.size > 3) ", ...]" else "]",
    ) { change ->
        buildString {
            append(change.changeId)
            append(':')
            append(change.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE")
            append(':')
            append(change.targetNodeIds.joinToString("|").ifBlank { "-" })
        }
    }
}

/** 生成风险线程的简短摘要字符串，用于 trace 日志输出。 */
internal fun threadSummaries(threads: List<InvestigationThread>): String {
    if (threads.isEmpty()) {
        return "[]"
    }
    return threads.take(3).joinToString(
        prefix = "[",
        postfix = if (threads.size > 3) ", ...]" else "]",
    ) { thread ->
        buildString {
            append(thread.threadId)
            append(':')
            append(thread.evidence.maxOfOrNull(ResultEvidenceFinding::evidenceLevel)?.name ?: "NONE")
            append(':')
            append(thread.targetNodeIds.joinToString("|").ifBlank { "-" })
        }
    }
}

/** 生成源码片段的简短摘要字符串，用于 trace 日志输出。 */
internal fun sourceContextSummaries(sourceContext: List<SourceSnippetContext>): String {
    if (sourceContext.isEmpty()) {
        return "[]"
    }
    return sourceContext.take(4).joinToString(
        prefix = "[",
        postfix = if (sourceContext.size > 4) ", ...]" else "]",
    ) { snippet ->
        buildString {
            append(snippet.nodeId)
            append('@')
            append(snippet.filePath)
            snippet.startLine?.let { append(':').append(it) }
            snippet.endLine?.let { append('-').append(it) }
            append(" => ")
            append(
                snippet.snippet
                    .orEmpty()
                    .lineSequence()
                    .joinToString(" \\n ") { it.trim() }
                    .take(220),
            )
        }
    }
}

/** 生成取证轨迹的简短摘要字符串，用于 trace 日志输出。 */
internal fun evidenceTraceSummaries(evidenceTrace: List<EvidenceTraceEntry>): String {
    if (evidenceTrace.isEmpty()) {
        return "[]"
    }
    return evidenceTrace.take(6).joinToString(
        prefix = "[",
        postfix = if (evidenceTrace.size > 6) ", ...]" else "]",
    ) { trace ->
        buildString {
            append(trace.nodeId)
            append('@')
            append(trace.filePath)
            trace.startLine?.let { append(':').append(it) }
            trace.endLine?.let { append('-').append(it) }
            append('#')
            append(trace.reason)
            append("#included=")
            append(trace.includedInPrompt)
        }
    }
}
