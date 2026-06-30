package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.agent.model.SourceSnippetContext
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

/**
 * 进入"实现计划"阶段的输入聚合。
 *
 * 把规划所需的所有信息（图、差异、预览项、已确认改动、Mermaid 问题、源码上下文、用户目标）
 * 一次性打包传入规划器，让规划器有完整上下文生成可执行计划。
 */
data class PlanningInput(
    /** 用于规划的图文档（通常是当前工作台图）。 */
    val planningGraph: GraphDocument,
    /** 图与目标（代码或设计）之间的差异。 */
    val diff: GraphDiff,
    /** 同步预览项列表，描述将要同步的具体内容。 */
    val previewItems: List<SyncPreviewItem>,
    /** 用户已经确认的草稿改动；空列表表示没有已确认项。 */
    val confirmedChanges: List<DraftWorkbenchEntry> = emptyList(),
    /** Mermaid 解析或校验中产生的问题列表；空列表表示无问题。 */
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    /** 源码上下文片段；规划器可基于这些片段做更精确的判断。 */
    val sourceContext: List<SourceSnippetContext> = emptyList(),
    /** 用户当前希望达成的目标描述，作为规划的高层指导。 */
    val userGoal: String = "",
)
