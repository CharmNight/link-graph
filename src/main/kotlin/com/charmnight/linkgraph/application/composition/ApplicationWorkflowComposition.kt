package com.charmnight.linkgraph.application.composition

internal class ApplicationWorkflowComposition(
    private val workflowsProvider: () -> ApplicationWorkflows,
) {
    fun workflows(): ApplicationWorkflows = workflowsProvider()
}
