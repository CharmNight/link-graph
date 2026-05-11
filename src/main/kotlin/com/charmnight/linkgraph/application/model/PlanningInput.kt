package com.charmnight.linkgraph.application.model

import com.charmnight.linkgraph.llm.SourceSnippetContext
import com.charmnight.linkgraph.mermaid.MermaidIssue
import com.charmnight.linkgraph.model.GraphDiff
import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.sync.SyncPreviewItem
import com.charmnight.linkgraph.workbench.DraftWorkbenchEntry

data class PlanningInput(
    val planningGraph: GraphDocument,
    val diff: GraphDiff,
    val previewItems: List<SyncPreviewItem>,
    val confirmedChanges: List<DraftWorkbenchEntry> = emptyList(),
    val mermaidIssues: List<MermaidIssue> = emptyList(),
    val sourceContext: List<SourceSnippetContext> = emptyList(),
)
