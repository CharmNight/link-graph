package com.charmnight.linkgraph.mermaid

data class MermaidIssue(
    val category: Category,
    val code: String,
    val message: String,
    val line: Int? = null,
    val nodeId: String? = null,
    val edgeId: String? = null,
) {
    enum class Category {
        SYNTAX,
        STRUCTURE,
        SEMANTIC,
        BINDING,
    }
}
