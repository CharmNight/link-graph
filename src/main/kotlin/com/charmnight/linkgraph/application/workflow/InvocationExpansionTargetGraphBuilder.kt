package com.charmnight.linkgraph.application.workflow

import com.charmnight.linkgraph.model.GraphDocument
import com.charmnight.linkgraph.semantic.graph.GraphAssembler
import com.charmnight.linkgraph.semantic.model.SemanticAnalysisResult
import com.charmnight.linkgraph.semantic.outcome.AnalysisDisplayMode

/** Builds the unprojected flowchart graph needed for invocation expansion merging. */
internal class InvocationExpansionTargetGraphBuilder(
    private val graphAssembler: GraphAssembler = GraphAssembler(),
) {
    fun build(analysisResult: SemanticAnalysisResult): GraphDocument =
        graphAssembler.assemble(analysisResult, AnalysisDisplayMode.FLOWCHART)
}
