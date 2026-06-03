package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.architecture.architectureIndexService
import com.charmnight.linkgraph.architecture.toIndexedFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRefreshPolicy
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.intellij.openapi.project.Project

internal class ArchitectureIndexWorkflowSupport(
    private val project: Project,
) {
    @Volatile
    private var cachedIndex: ArchitectureGraphIndex? = null

    @Volatile
    private var cachedFullIndex: ArchitectureGraphIndex? = null

    fun currentIndex(): ArchitectureGraphIndex? = cachedIndex
        ?: runCatching { project.architectureIndexRuntime().currentIndex() }.getOrNull()

    fun freshness(): IndexedGraphFreshness =
        runCatching { project.architectureIndexService().freshness().toIndexedFreshness() }
            .getOrDefault(IndexedGraphFreshness())

    fun buildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget).also { index ->
            cachedIndex = index
            cachedFullIndex = index
        }
    }

    fun buildIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        if (request.view == IndexedGraphView.ARCHITECTURE && request.scope is IndexedGraphScope.Project) {
            buildArchitectureOverviewIndex(request)
        } else {
            buildIndex(request.toResolutionBudget(), forceRebuild = request.forceRebuild)
        }

    fun buildIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
    ): ArchitectureGraphIndex =
        buildIndex(
            budget = request.toResolutionBudget(),
            symbolIndexHint = symbolIndexHint,
            forceRebuild = request.forceRebuild,
        )

    private fun buildIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(
            budget = budget,
            forceRebuild = forceRebuild,
        ).also { index ->
            cachedIndex = index
            cachedFullIndex = index
        }
    }

    fun buildIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        symbolIndexHint: JvmSymbolIndex?,
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(
            budget = budget,
            symbolIndexHint = symbolIndexHint,
            forceRebuild = forceRebuild,
        ).also { index ->
            cachedIndex = index
            cachedFullIndex = index
        }
    }

    private fun buildClassDiagramStructureIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex =
        project.architectureIndexRuntime().classDiagramStructureIndex(
            budget = budget,
            forceRebuild = forceRebuild,
        )

    fun buildClassDiagramStructureIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        buildClassDiagramStructureIndex(
            budget = request.toResolutionBudget(),
            forceRebuild = request.forceRebuild,
        )

    private fun buildArchitectureOverviewIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        project.architectureIndexRuntime().architectureOverviewIndex(
            budget = request.toResolutionBudget(),
            forceRebuild = request.forceRebuild,
            recordAsCurrent = true,
        ).also { index ->
            cachedIndex = index
        }

    fun hasFullIndex(budget: JvmResolutionBudget = defaultBudget()): Boolean =
        cachedFullIndex != null || project.architectureIndexRuntime().hasCachedFullIndex(budget)

    fun hasFullIndex(request: IndexedGraphRequest): Boolean =
        project.architectureIndexRuntime().hasCachedFullIndex(request.toResolutionBudget())

    fun invalidateCache() {
        runCatching { project.architectureIndexRuntime().invalidate() }
        cachedIndex = null
        cachedFullIndex = null
    }

    fun resolutionBudget(request: IndexedGraphRequest): JvmResolutionBudget =
        request.toResolutionBudget()

    private fun defaultBudget(): JvmResolutionBudget =
        project.architectureIndexRuntime().defaultBudget()

    private fun IndexedGraphRequest.toResolutionBudget(): JvmResolutionBudget =
        defaultBudget().copy(
            includeExternalLibraries = includeExternalLibraries,
            includeJdk = includeJdk,
        )

    private val IndexedGraphRequest.forceRebuild: Boolean
        get() = refreshPolicy == IndexedGraphRefreshPolicy.ForceRebuild
}
