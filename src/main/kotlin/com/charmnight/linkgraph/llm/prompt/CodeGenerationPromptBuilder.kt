package com.charmnight.linkgraph.llm.prompt

import com.charmnight.linkgraph.llm.GenerationContext
import com.charmnight.linkgraph.llm.GenerationPlan
import com.charmnight.linkgraph.llm.LlmPromptPackage
import com.charmnight.linkgraph.llm.context.PromptComposer
import com.charmnight.linkgraph.llm.context.PromptSection
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.BEHAVIOR_RULE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.CONFIRMED_CHANGE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.EVIDENCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.GRAPH
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SCHEMA
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.SOURCE
import com.charmnight.linkgraph.llm.context.PromptSectionPriority.USER_GOAL
import com.charmnight.linkgraph.settings.LinkGraphSettingsState

/**
 * 代码草稿生成场景的 prompt builder（P2-1 深度拆分）。
 *
 * 基于链路图、已确认草稿变更、真实源码片段、差异和计划项，
 * 输出可写入项目目录的代码草稿（drafts + editOperations + editScopes）。
 */

/** 构造代码草稿生成场景的提示词包。 */
internal fun buildCodeGenerationPromptPackage(
    promptComposer: PromptComposer,
    context: GenerationContext,
    plan: GenerationPlan?,
    settings: LinkGraphSettingsState,
): LlmPromptPackage {
    val nodes = context.graph.nodes.joinToString("\n") { nodeSummary(it) }.ifBlank { "- 无" }
    val edges = context.graph.edges.joinToString("\n") { edgeSummary(it) }.ifBlank { "- 无" }
    val diff = context.diff.entries.joinToString("\n") { entry -> diffSummary(entry) }.ifBlank { "- 无" }
    val planItems = plan?.items.orEmpty().joinToString("\n") { item ->
        "- [${item.risk.name}] ${item.title} | target=${item.targetPath ?: "未指定"} | ${item.description}"
    }.ifBlank { "- 无" }
    val confirmedChangeScopeDetails = context.confirmedChanges
        .flatMap { change ->
            change.editScopes.map { scope ->
                buildString {
                    append("- change=").append(change.sourceChangeId ?: change.entryId)
                    append(" | scopeId=").append(scope.scopeId)
                    append(" | filePath=").append(scope.filePath)
                    append(" | symbolKind=").append(scope.symbolKind)
                    scope.symbolSignature?.let { append(" | symbolSignature=").append(it) }
                    scope.startLine?.let { append(" | startLine=").append(it) }
                    scope.endLine?.let { append(" | endLine=").append(it) }
                    append(" | allowedChangeKinds=").append(scope.allowedChangeKinds.joinToString(", "))
                }
            }
        }
        .ifEmpty { listOf("- 无") }
        .joinToString("\n")
    val confirmedChanges = context.confirmedChanges
        .joinToString("\n") { change -> confirmedChangeSummary(change, context.graph) }
        .ifBlank { "- 无" }
    val sourceSnippets = context.sourceContext.joinToString("\n") { snippet ->
        sourceSnippetSummary(snippet)
    }.ifBlank { "- 无" }
    val systemPrompt = """
        你是 IDEA Link Graph 的代码生成器。
        你的职责是基于链路图、已确认草稿变更、真实源码片段、差异和计划项，输出可写入项目目录的代码草稿。
        如果已确认草稿变更已经明确了要改的现有方法或文件，你必须优先围绕这些确认项生成可落地的代码，而不是只生成新增类壳子。
        下方“相关源码片段”来自当前项目的真实源码；如果某个目标已经给出对应片段，禁止声称未提供源码上下文。
        对于已经存在的目标文件，禁止返回整文件 content；你必须返回 editOperations，并且每条 operation 都要绑定到已给定的 edit scope。
        你只能使用 scope.allowedChangeKinds 明确授权过的 operation kind，禁止超出 scope 授权范围自行扩展。
        对于已经存在的目标文件，你必须保留目标文件中与本次变更无关的现有代码，只修改与确认项直接相关的方法、字段、import 和注释。
        如果目标已经指向现有 Java 文件，生成结果必须继续沿用原有包名、类型名和未提及成员，不允许把整文件改写成无关的新骨架。
        Java existing-file 可用 operation kind：REPLACE_METHOD_BLOCK、REPLACE_METHOD_BODY、ADD_IMPORT、ADD_FIELD、INSERT_METHOD_AFTER。
        Kotlin existing-file 可用 operation kind：REPLACE_METHOD_BLOCK、REPLACE_METHOD_BODY。
        editOperations[].payload 必须是纯源码片段字符串：REPLACE_METHOD_BODY 返回方法体代码块或语句，REPLACE_METHOD_BLOCK 返回完整方法或可替换代码块。
        禁止把 methodSignature、changeType、existingCodeSnippet、newImplementation 等包装字段或元数据序列化进 payload；这些信息只能放在 operation/scope 字段中。
        只允许返回 JSON，不允许输出 Markdown、解释性前言、后缀说明或代码块。
        即使信息不足，也必须返回合法 JSON；列表字段使用 []，不要输出自然语言兜底。
        新文件 draft 才允许返回完整 content。
        当信息不足时，要在 warnings 中说明，不要编造代码事实。
        $USER_INPUT_CONTRACT
    """.trimIndent()
    return buildPromptPackage(
        promptComposer = promptComposer,
        systemPrompt = systemPrompt,
        userSections = listOf(
            PromptSection(
                """
                你正在根据链路图和实现计划生成代码草稿。
                目标模型：${settings.sanitized().model}
                """.trimIndent(),
                priority = USER_GOAL,
            ),
            PromptSection(
                """
                已确认草稿变更：
                $confirmedChanges
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            PromptSection(
                """
                已确认草稿变更附带的 edit scopes：
                $confirmedChangeScopeDetails
                """.trimIndent(),
                priority = CONFIRMED_CHANGE,
            ),
            PromptSection(
                """
                相关源码片段：
                $sourceSnippets
                """.trimIndent(),
                priority = SOURCE,
            ),
            PromptSection(
                """
                计划项：
                $planItems
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                目标文件：
                ${plan?.items.orEmpty().mapNotNull { it.targetPath }.ifEmpty { listOf("未指定") }.joinToString("\n")}
                """.trimIndent(),
                priority = EVIDENCE,
            ),
            PromptSection(
                """
                图节点：
                $nodes
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                图连线：
                $edges
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(
                """
                图差异：
                $diff
                """.trimIndent(),
                priority = GRAPH,
            ),
            PromptSection(codeGenerationBehaviorInstruction(), priority = BEHAVIOR_RULE),
            PromptSection(codeGenerationSchemaInstruction(), priority = SCHEMA),
        ),
    )
}
