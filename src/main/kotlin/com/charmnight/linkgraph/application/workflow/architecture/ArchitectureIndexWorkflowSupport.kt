package com.charmnight.linkgraph.application.workflow.architecture

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.ClassDiagramFastIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.architecture.architectureIndexService
import com.charmnight.linkgraph.architecture.toIndexedFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphFreshness
import com.charmnight.linkgraph.application.indexed.IndexedGraphRequest
import com.charmnight.linkgraph.application.indexed.IndexedGraphRefreshPolicy
import com.charmnight.linkgraph.application.indexed.IndexedGraphScope
import com.charmnight.linkgraph.application.indexed.IndexedGraphView
import com.charmnight.linkgraph.foundation.LoggedFailures
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.relation.CallAggregationRelationResolver
import com.charmnight.linkgraph.jvm.relation.JvmRelationIndex
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.source.IdeSourceContentResolver
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

internal class ArchitectureIndexWorkflowSupport(
    private val project: Project,
) : ClassDiagramIndexSupport {
    private val logger = Logger.getInstance(ArchitectureIndexWorkflowSupport::class.java)

    @Volatile
    private var cachedIndex: ArchitectureGraphIndex? = null

    @Volatile
    private var cachedFullIndex: ArchitectureGraphIndex? = null

    override fun currentIndex(): ArchitectureGraphIndex? = cachedIndex
        ?: LoggedFailures.orNull(logger, "currentIndex architectureIndexRuntime.currentIndex") {
            project.architectureIndexRuntime().currentIndex()
        }

    override fun freshness(): IndexedGraphFreshness =
        LoggedFailures.orDefault(
            logger,
            "freshness architectureIndexService.freshness",
            defaultValue = IndexedGraphFreshness(),
        ) {
            project.architectureIndexService().freshness().toIndexedFreshness()
        }

    fun buildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget).also { index ->
            cachedIndex = index
            cachedFullIndex = index
        }
    }

    override fun buildIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        if (request.view == IndexedGraphView.ARCHITECTURE && request.scope is IndexedGraphScope.Project) {
            buildArchitectureOverviewIndex(request)
        } else {
            buildIndex(request.toResolutionBudget(), forceRebuild = request.forceRebuild)
        }

    override fun buildIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
    ): ArchitectureGraphIndex =
        buildIndex(
            budget = request.toResolutionBudget(),
            symbolIndexHint = symbolIndexHint,
            forceRebuild = request.forceRebuild,
        )

    override fun buildScopedClassDiagramIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
        sourceClassIds: Set<String>,
    ): ArchitectureGraphIndex {
        val symbolIndex = symbolIndexHint ?: buildClassDiagramStructureIndex(request).symbolIndex
        val budget = request.toResolutionBudget().copy(
            includeTests = false,
            methodBodySourceClassIds = sourceClassIds,
            maxMethodBodiesScanned = SCOPED_METHOD_BODY_LIMIT,
            maxMethodCallExpressionsResolved = SCOPED_METHOD_CALL_LIMIT,
        )
        val callRelations = CallAggregationRelationResolver().resolve(
            JvmResolutionContext(
                project = project,
                symbolIndex = symbolIndex,
                sourceResolver = IdeSourceContentResolver(project),
                budget = budget,
            ),
        )
        val relationIndex = JvmRelationIndex(
            relations = ClassDiagramFastIndex.structureRelations(symbolIndex) + callRelations,
            maxRelations = budget.maxRelations,
        )
        return ArchitectureGraphIndex.from(
            symbolIndex = symbolIndex,
            relationIndex = relationIndex,
            budget = budget,
        )
    }

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

    override fun buildClassDiagramStructureIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
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

    override fun hasFullIndex(request: IndexedGraphRequest): Boolean =
        project.architectureIndexRuntime().hasCachedFullIndex(request.toResolutionBudget())

    fun invalidateCache() {
        LoggedFailures.orDefault(
            logger,
            "invalidateCache architectureIndexRuntime.invalidate",
            defaultValue = Unit,
        ) {
            project.architectureIndexRuntime().invalidate()
        }
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

    private companion object {
        private const val SCOPED_METHOD_BODY_LIMIT = 1_500
        private const val SCOPED_METHOD_CALL_LIMIT = 8_000
    }
}

internal interface ClassDiagramIndexSupport {
    fun currentIndex(): ArchitectureGraphIndex?

    fun freshness(): IndexedGraphFreshness

    fun buildIndex(request: IndexedGraphRequest): ArchitectureGraphIndex

    fun buildIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
    ): ArchitectureGraphIndex

    fun buildScopedClassDiagramIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
        sourceClassIds: Set<String>,
    ): ArchitectureGraphIndex =
        buildIndex(request, symbolIndexHint)

    fun buildClassDiagramStructureIndex(request: IndexedGraphRequest): ArchitectureGraphIndex

    fun hasFullIndex(request: IndexedGraphRequest): Boolean
}
