package com.charmnight.linkgraph.mermaid

import com.charmnight.linkgraph.model.GraphDocument

data class MermaidParseResult(
    val document: GraphDocument,
    val issues: List<MermaidIssue> = emptyList(),
)
