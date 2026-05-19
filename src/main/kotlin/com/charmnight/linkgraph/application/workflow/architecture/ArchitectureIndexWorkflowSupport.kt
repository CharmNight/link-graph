package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.intellij.openapi.project.Project

internal class ArchitectureIndexWorkflowSupport(
    private val project: Project,
) {
    @Volatile
    private var cachedIndex: ArchitectureGraphIndex? = null

    fun currentIndex(): ArchitectureGraphIndex? = cachedIndex
        ?: runCatching { project.architectureIndexRuntime().currentIndex() }.getOrNull()

    fun buildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget).also { index ->
            cachedIndex = index
        }
    }

    fun invalidateCache() {
        runCatching { project.architectureIndexRuntime().invalidate() }
        cachedIndex = null
    }

    private fun defaultBudget(): JvmResolutionBudget =
        project.architectureIndexRuntime().defaultBudget()
}
