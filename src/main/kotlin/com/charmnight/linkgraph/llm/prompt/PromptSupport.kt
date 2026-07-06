package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.agent.model.GraphEvidenceProfile
import com.charmnight.linkgraph.agent.model.InvocationExpansionContext
import com.charmnight.linkgraph.agent.model.InvocationExpansionSummary
import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.llm.context.PromptRenderBudget
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority
import com.charmnight.linkgraph.llm.llmClassDiagramRelationDisplayLabel
import com.charmnight.linkgraph.llm.llmRelationKindDisplayLabel
import com.charmnight.linkgraph.model.GraphDiffEntry
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.model.GraphEdge
import com.charmnight.linkgraph.model.GraphNode
import com.charmnight.linkgraph.model.sourceFilePathOrLocationPath
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 提示词构造器共享的纯展示辅助函数（P2-1 深度拆分）。
 *
 * 把节点 / 边 / 源码片段 / 差异 / 已确认变更 / 证据边界对象渲染为单行摘要，
 * 供各类提示词构造器复用。无状态、无副作用。
 */

/**
 * 把任意外部内容片段清洗后嵌入提示词。
 *
 * 与 [sanitizeUserField] 行为一致：转义 `<` / `>` 并包入 `<user_input>` 标签。
 * 设计上把清洗责任下沉到渲染辅助函数（[sourceSnippetSummary] / [confirmedChangeSummary] 等），
 * 让所有提示词构造器自动获得等价的注入防御，不需要每个构造器各自包裹。
 *
 * 命名为 `sanitizeContent` 而非 `sanitizeUser` 是因为内容来源不仅是用户直接输入——
 * 也包含历史消息、候选变更标题、源码片段等——但都属于「不可作为系统指令」的范畴。
 */
internal fun sanitizeContent(raw: String): String = sanitizeUserField(raw)

/** 把节点转换为提示词里的单行摘要（id / type / title / signature / inputs / outputs / doc / metadata / sourceTag）。 */
internal fun nodeSummary(node: GraphNode): String {
    val id = "id=${node.id} | "
    val location = node.location?.let { " @ $it" }.orEmpty()
    // 标题 / 签名 / 文档来自源码或用户编辑，存在提示词注入风险，统一清洗。
    val title = sanitizeContent(node.title)
    val signature = node.signature?.let { " | signature=${sanitizeContent(it)}" }.orEmpty()
    val inputs = if (node.inputs.isEmpty()) "" else " | inputs=${node.inputs.joinToString()}"
    val outputs = if (node.outputs.isEmpty()) "" else " | outputs=${node.outputs.joinToString()}"
    val doc = node.doc?.takeIf { it.isNotBlank() }?.let { " | doc=${sanitizeContent(it)}" }.orEmpty()
    val flowchartKind = node.metadata["flowchart.kind"]?.let { " | flowchart.kind=$it" }.orEmpty()
    val ownerMethod = node.metadata["flow.ownerMethod"]?.let { " | flow.ownerMethod=$it" }.orEmpty()
    val sourceTag = " | source=${node.sourceTag.name}"
    return "- $id[${node.type.name}] $title$location$signature$inputs$outputs$doc$flowchartKind$ownerMethod$sourceTag"
}

/** 把源码片段上下文转换成提示词里的单行摘要。 */
internal fun sourceSnippetSummary(snippet: SourceSnippetContext): String {
    return buildString {
        append("- node=")
        append(snippet.nodeId)
        append(" | path=")
        append(snippet.filePath)
        snippet.startLine?.let { append(" | startLine=").append(it) }
        snippet.endLine?.let { append(" | endLine=").append(it) }
        snippet.startOffset?.let { append(" | startOffset=").append(it) }
        snippet.endOffset?.let { append(" | endOffset=").append(it) }
        // 源码片段是源码原文，可能来自第三方库，按不可信内容清洗
        snippet.snippet?.takeIf { it.isNotBlank() }?.let {
            append(" | snippet=").append(sanitizeContent(it))
        }
    }
}

/** 把边转换成提示词里的单行摘要，附带可读关系类型和展示标签。 */
internal fun edgeSummary(edge: GraphEdge): String {
    val label = edgeDisplayLabel(edge)?.let { " | label=$it" }.orEmpty()
    return "- [${llmRelationKindDisplayLabel(edge.type.name)}] ${edge.fromNodeId} -> ${edge.toNodeId}$label"
}

/** 按优先级从边元数据中取出展示标签，并把原始标签映射为用户可读的中文标签。 */
internal fun edgeDisplayLabel(edge: GraphEdge): String? =
    (
        edge.metadata["classDiagram.relation.label"]
            ?: edge.metadata["uml.relation.label"]
            ?: edge.metadata["uml.relation.aggregate.primaryLabel"]
            ?: edge.metadata["uml.relation.aggregate.label"]
            ?: edge.label
            ?: edge.metadata["jvm.relation.kind"]
            ?: edge.type.name
        )
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::llmClassDiagramRelationDisplayLabel)

/** 把差异条目转换成提示词里的单行摘要。 */
internal fun diffSummary(entry: GraphDiffEntry): String {
    val fields = if (entry.fields.isEmpty()) "" else " | fields=${entry.fields.joinToString()}"
    // entry.message 可能来自用户编辑或第三方源码注释，sanitize 后嵌入
    val message = entry.message?.let { " | ${sanitizeContent(it)}" }.orEmpty()
    return "- [${entry.status.name}] ${entry.elementKind.name}:${entry.elementId}$fields$message"
}

/** 把已确认草稿变更转换成提示词里的单行摘要。 */
internal fun confirmedChangeSummary(
    change: DraftWorkbenchEntry,
    graph: GraphDocument,
): String {
    val targetFiles = targetFilesFor(change, graph)
    return confirmedChangeSummary(change, targetFiles)
}

private fun confirmedChangeSummary(
    change: DraftWorkbenchEntry,
    targetFiles: List<String>,
): String {
    val targets = change.targetNodeIds.joinToString("; ").ifBlank { "未指定节点" }
    // beforeState / afterState / reason / impactSummary / title 均可能含用户编辑文本，sanitize 后嵌入
    val before = sanitizeContent(change.beforeState?.takeIf { it.isNotBlank() } ?: "无")
    val after = sanitizeContent(change.afterState?.takeIf { it.isNotBlank() } ?: "无")
    val reason = sanitizeContent(change.reason.ifBlank { "无" })
    val impact = sanitizeContent(change.impactSummary.takeIf { it.isNotBlank() } ?: "无")
    val claimType = change.claimType ?: "未标注"
    val evidenceLevels = change.evidence.map { it.evidenceLevel.name }.distinct().ifEmpty { listOf("未标注") }
    return "- ${change.sourceChangeId ?: change.entryId} | ${sanitizeContent(change.title)} | targets=$targets | files=${targetFiles.joinToString()} | before=$before | after=$after | reason=$reason | impact=$impact | claimType=$claimType | evidence=${evidenceLevels.joinToString()}"
}

private fun targetFilesFor(
    change: DraftWorkbenchEntry,
    graph: GraphDocument,
): List<String> {
    val remainingTargetIds = change.targetNodeIds.toMutableSet()
    if (remainingTargetIds.isEmpty()) {
        return listOf("未指定文件")
    }
    val targetFiles = linkedSetOf<String>()
    for (node in graph.nodes) {
        if (node.id in remainingTargetIds) {
            node.sourceFilePathOrLocationPath()?.let(targetFiles::add)
            remainingTargetIds -= node.id
            if (remainingTargetIds.isEmpty()) {
                break
            }
        }
    }
    return targetFiles.ifEmpty { linkedSetOf("未指定文件") }.toList()
}

/** 把证据边界对象整理为可直接嵌入提示词的多行文本。 */
internal fun buildEvidenceProfileText(profile: GraphEvidenceProfile): String {
    val modes = profile.allowedExplanationModes.joinToString(", ") { mode -> mode.name }.ifBlank { "无" }
    val forbiddenSummary = profile.forbiddenClaims.joinToString("；").ifBlank { "无" }
    val forbidden = profile.forbiddenClaims.joinToString("\n") { claim -> "- $claim" }.ifBlank { "- 无" }
    val gapsSummary = profile.evidenceGaps.joinToString("；").ifBlank { "无" }
    val gaps = profile.evidenceGaps.joinToString("\n") { gap -> "- $gap" }.ifBlank { "- 无" }
    val relations = profile.availableRelationKinds.joinToString(", ") { llmRelationKindDisplayLabel(it) }.ifBlank { "无" }
    val drilldowns = profile.recommendedDrilldowns.joinToString(", ").ifBlank { "无" }
    return """
        锚点类型：${profile.anchorNodeType?.name ?: "UNKNOWN"}
        架构类型：${profile.anchorArchitectureKind ?: "UNKNOWN"}
        允许讲解模式：$modes
        可用关系类型：$relations
        入边数量：${profile.incomingRelationCount}
        出边数量：${profile.outgoingRelationCount}
        具备方法调用证据：${profile.hasMethodCallEvidence}
        具备源码证据：${profile.hasSourceEvidence}
        具备包成员证据：${profile.hasPackageMemberEvidence}
        禁止声明：$forbiddenSummary
        禁止声明：
        $forbidden
        证据缺口：$gapsSummary
        证据缺口：
        $gaps
        推荐下钻：
        $drilldowns
    """.trimIndent()
}

/** 把调用展开上下文整理成可直接嵌入提示词的多行文本。 */
internal fun buildInvocationExpansionContextText(context: InvocationExpansionContext): String {
    if (
        context.activeExpansionPath.isEmpty() &&
        context.fullExpansionIds.isEmpty() &&
        context.summaryExpansionIds.isEmpty() &&
        context.summaries.isEmpty()
    ) {
        return "- 无"
    }
    val summaries = if (context.summaries.isEmpty()) {
        "- 无"
    } else {
        context.summaries.joinToString("\n") { summary -> invocationExpansionSummaryText(summary) }
    }
    return """
        模式：${context.mode.name}
        活动路径：${context.activeExpansionPath.joinToString(" -> ").ifBlank { "无" }}
        完整证据扩展：${context.fullExpansionIds.joinToString(", ").ifBlank { "无" }}
        摘要证据扩展：${context.summaryExpansionIds.joinToString(", ").ifBlank { "无" }}
        摘要明细：
        $summaries
    """.trimIndent()
}

/** 把单个摘要展开压成一行文本。 */
internal fun invocationExpansionSummaryText(summary: InvocationExpansionSummary): String {
    val source = summary.sourceInvocationNodeId?.let { " | source=$it" }.orEmpty()
    val root = summary.rootNodeId?.let { " | root=$it" }.orEmpty()
    val title = summary.title?.let { " | title=${sanitizeContent(it)}" }.orEmpty()
    val signature = summary.targetSignature?.let { " | signature=${sanitizeContent(it)}" }.orEmpty()
    return "- ${summary.expansionId}$title$signature$source$root | ownedNodes=${summary.ownedNodeCount} | branches=${summary.branchCount} | returns=${summary.returnCount} | children=${summary.childExpansionCount} | borrowedRoot=${summary.hasBorrowedRoot}"
}

internal fun <T> budgetedPromptSection(
    header: String,
    items: Iterable<T>,
    priority: PromptSectionPriority,
    emptyText: String = "- 无",
    renderItem: (T) -> String,
): PromptSection =
    PromptSection.lazy(priority) { budget ->
        renderBudgetedLines(
            budget = budget,
            header = header,
            items = items,
            emptyText = emptyText,
            renderItem = renderItem,
        )
    }

internal fun budgetedStaticPromptSection(
    header: String,
    body: String,
    priority: PromptSectionPriority,
): PromptSection =
    PromptSection.lazy(priority) { budget ->
        budget.trim(
            """
            $header
            ${body.ifBlank { "- 无" }}
            """.trimIndent(),
        )
    }

private fun <T> renderBudgetedLines(
    budget: PromptRenderBudget,
    header: String,
    items: Iterable<T>,
    emptyText: String,
    renderItem: (T) -> String,
): String {
    val builder = StringBuilder(header)
    if (builder.length >= budget.maxCharacters || budget.estimateTokens(builder.toString()) >= budget.maxTokens) {
        return budget.trim(builder.toString())
    }
    val iterator = items.iterator()
    if (!iterator.hasNext()) {
        appendLineWithinBudget(builder, emptyText, budget)
        return builder.toString()
    }
    while (iterator.hasNext()) {
        val line = renderItem(iterator.next())
        if (!appendLineWithinBudget(builder, line, budget)) {
            break
        }
    }
    return builder.toString()
}

private fun appendLineWithinBudget(
    builder: StringBuilder,
    line: String,
    budget: PromptRenderBudget,
): Boolean {
    val prefix = if (builder.isEmpty()) "" else "\n"
    val candidate = builder.toString() + prefix + line
    if (candidate.length <= budget.maxCharacters && budget.estimateTokens(candidate) <= budget.maxTokens) {
        builder.append(prefix).append(line)
        return true
    }
    val remainingCharacters = budget.maxCharacters - builder.length - prefix.length
    val remainingTokens = budget.maxTokens - budget.estimateTokens(builder.toString()) - budget.estimateTokens(prefix)
    if (remainingCharacters <= 0 || remainingTokens <= 0) {
        return false
    }
    val trimmedLine = budget.budgetController.trim(line, remainingCharacters, remainingTokens)
    if (trimmedLine.isBlank()) {
        return false
    }
    builder.append(prefix).append(trimmedLine)
    return false
}
