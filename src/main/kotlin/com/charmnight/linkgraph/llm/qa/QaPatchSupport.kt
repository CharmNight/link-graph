package com.charmnight.linkgraph.llm.qa

import com.charmnight.linkgraph.agent.model.EvidenceTraceEntry
import com.charmnight.linkgraph.agent.model.GraphPatchResult
import com.charmnight.linkgraph.agent.model.GraphQaContext
import com.charmnight.linkgraph.llm.LlmUserMessageFormatter
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.NodeType
import com.charmnight.linkgraph.workbench.QaConversationMessage
import com.charmnight.linkgraph.workbench.QaConversationSession
import com.charmnight.linkgraph.workbench.QaMessageRole

/**
 * GraphQaPatchService 的会话 / 警告 / 证据轨迹派生 helper（P2-1 深度拆分）。
 *
 * 这些函数把 QaConversationSession 追加消息、生成空会话、加 RUNTIME 前缀警告、
 * 把源码片段派生为 evidenceTrace 等无状态逻辑收敛在一起。
 */

/** 把当前用户问题写入会话：若末条已是该问题则跳过，避免重复。 */
internal fun ensureUserQuestion(
    session: QaConversationSession,
    question: String,
): QaConversationSession {
    if (session.messages.lastOrNull()?.role == QaMessageRole.USER && session.messages.lastOrNull()?.content == question) {
        return session
    }
    return session.copy(
        messages = session.messages + QaConversationMessage(
            messageId = "${session.sessionId}-user-${session.messages.size + 1}",
            role = QaMessageRole.USER,
            content = question,
            focusTargetId = session.focusTargetId,
        ),
    )
}

/** 基于当前范围生成默认空会话。 */
internal fun emptySession(context: GraphQaContext): QaConversationSession {
    val scopeKey = context.selectedNodeIds.sorted().joinToString(",")
        .ifBlank { (context.editableGraph.nodes.firstOrNull() ?: context.factGraph.nodes.firstOrNull())?.id ?: "graph" }
    return QaConversationSession(
        sessionId = "qa-${GraphNode.stableId(NodeType.DOC_PAGE, scopeKey, "session")}",
        scopeKey = scopeKey,
    )
}

/** 生成远程失败后的回退警告文案。 */
internal fun buildRemoteFallbackWarning(
    scene: String,
    error: Throwable,
): String = runtimeWarning("远程 LLM ${scene}失败，已回退为本地规则分析：${LlmUserMessageFormatter.describe(error)}")

/** 把警告文本统一加上 RUNTIME 前缀，便于 UI 区分运行时产生的提示与其他提示。 */
internal fun runtimeWarning(warning: String): String =
    warning.takeIf { it.startsWith("RUNTIME:") } ?: "RUNTIME: $warning"

/** 把远程返回的警告插到结果前面。 */
internal fun GraphPatchResult.withPrependedWarnings(extraWarnings: List<String>): GraphPatchResult {
    if (extraWarnings.isEmpty()) {
        return this
    }
    return copy(warnings = extraWarnings + warnings)
}

/**
 * 把源码片段补充为取证轨迹条目，使后续模型与 UI 能看到本轮直接附带的源码证据。
 *
 * 已有 evidenceTrace 或无 sourceContext 时直接返回原 context；否则把每个
 * sourceContext 转成一条 evidenceTrace（reason 标注为"本轮问答直接附带的源码片段"）。
 */
internal fun GraphQaContext.withDerivedEvidenceTrace(): GraphQaContext {
    if (evidenceTrace.isNotEmpty() || sourceContext.isEmpty()) {
        return this
    }
    return copy(
        evidenceTrace = sourceContext.map { snippet ->
            EvidenceTraceEntry(
                nodeId = snippet.nodeId,
                filePath = snippet.filePath,
                reason = "本轮问答直接附带的源码片段",
                startLine = snippet.startLine,
                endLine = snippet.endLine,
                includedInPrompt = true,
            )
        },
    )
}
