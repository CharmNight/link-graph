package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.foundation.LinkGraphRenderTrace
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.jvm.index.PsiJvmSourceTextSymbolExtractor
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.review.git.GitBaselineSymbolMapper
import com.charmnight.linkgraph.review.git.GitChangeSetProvider
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.charmnight.linkgraph.settings.LinkGraphSettingsService
import com.charmnight.linkgraph.settings.LinkGraphSettingsState
import com.charmnight.linkgraph.source.SourceContentResolver
import com.charmnight.linkgraph.source.SourceContentResolverFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class ArchitectureIndexRuntime(
    private val project: Project,
) {
    private val relationResolverRegistry = JvmRelationResolverRegistry()
    private val sourceResolverFactory = SourceContentResolverFactory(project, ::settingsSnapshot)
    private val logger = Logger.getInstance(ArchitectureIndexRuntime::class.java)
    private val traceEnabled: Boolean =
        LinkGraphDebugEnvironment.isEnabled("LINKGRAPH_DEBUG_TRACE")
    private val buildLocks = ConcurrentHashMap<ArchitectureGraphCacheKey, Any>()

    fun currentIndex(): ArchitectureGraphIndex? =
        project.architectureIndexService().currentIndex()

    fun index(
        budget: JvmResolutionBudget = defaultBudget(),
        symbolIndexHint: JvmSymbolIndex? = null,
        recordAsCurrent: Boolean = true,
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("architectureIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        }
        traceStage("architectureIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        if (!forceRebuild) {
            project.architectureIndexService().getCachedIndex(cacheKey)?.let { index ->
                project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
                return index
            }
        }
        val lock = buildLocks.computeIfAbsent(cacheKey) { Any() }
        return synchronized(lock) {
            try {
                if (!forceRebuild) {
                    project.architectureIndexService().getCachedIndex(cacheKey)?.let { index ->
                        project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
                        return@synchronized index
                    }
                }
                val built = buildUncachedIndex(
                    budget = budget,
                    symbolIndexHint = symbolIndexHint,
                    sourceComponents = sourceComponents,
                )
                project.architectureIndexService().putCachedIndex(cacheKey, built, recordAsCurrent)
            } finally {
                buildLocks.remove(cacheKey, lock)
            }
        }
    }

    fun sourceQuery(budget: JvmResolutionBudget = defaultBudget()): SourceContentResolver =
        sourceResolverFactory.create(budget).resolver

    fun symbolQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget, recordAsCurrent = false))

    fun relationQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget, recordAsCurrent = false))

    fun architectureGraphQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget, recordAsCurrent = false))

    fun classDiagramQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget, recordAsCurrent = false))

    fun classDiagramStructureIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex =
        classDiagramStructureIndexWithBoundedReadActions(budget, forceRebuild)

    fun architectureOverviewIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
        recordAsCurrent: Boolean = true,
    ): ArchitectureGraphIndex =
        architectureOverviewIndexWithBoundedReadActions(budget, forceRebuild, recordAsCurrent)

    fun hasCachedFullIndex(budget: JvmResolutionBudget = defaultBudget()): Boolean {
        val settings = settingsSnapshot()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        val cacheKey = ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        return project.architectureIndexService().getCachedIndex(cacheKey) != null
    }

    fun reviewQuery(
        budget: JvmResolutionBudget = defaultBudget(),
        index: ArchitectureGraphIndex = index(budget, recordAsCurrent = false),
    ): ReviewGraphQueryService =
        ReviewGraphQueryService(
            index,
            sourceResolver = sourceQuery(budget),
            baselineSymbolMapper = GitBaselineSymbolMapper(
                GitChangeSetProvider(project.basePath),
                PsiJvmSourceTextSymbolExtractor(project),
            ),
        )

    fun invalidate() {
        project.architectureIndexService().invalidate()
    }

    fun defaultBudget(): JvmResolutionBudget {
        val settings = settingsSnapshot()
        return JvmResolutionBudget(
            includeTests = true,
            includeExternalLibraries = settings.allowExternalLibraryExpansion,
            includeJdk = settings.allowJdkLibraryExpansion,
            includeUserAttachedJars = settings.attachedJars.isNotEmpty(),
            maxExternalClasses = settings.maxExternalClassNodes,
        )
    }

    fun settingsSnapshot(): LinkGraphSettingsState =
        runCatching {
            ApplicationManager.getApplication().getService(LinkGraphSettingsService::class.java).nonSecretSnapshot()
        }.getOrDefault(LinkGraphSettingsState())

    private fun buildUncachedIndex(
        budget: JvmResolutionBudget,
        symbolIndexHint: JvmSymbolIndex? = null,
        sourceComponents: com.charmnight.linkgraph.source.SourceContentResolverComponents,
    ): ArchitectureGraphIndex {
        val symbolStartedAt = System.nanoTime()
        val symbolIndex = symbolIndexHint ?: readActionIfNeeded {
            JvmSymbolIndexBuilder(
                project = project,
                attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                trace = { event ->
                    traceStage(event.stage, event.startedAtNanos, event.details)
                },
            ).build(budget)
        }
        traceStage("architectureIndex.symbolIndex", symbolStartedAt) {
            listOf(
                "source=${if (symbolIndexHint == null) "built" else "hint"}",
                "modules=${symbolIndex.modulesByName.size}",
                "packages=${symbolIndex.packagesByName.size}",
                "classes=${symbolIndex.classesByQualifiedName.size}",
                "methods=${symbolIndex.methodsBySignature.size}",
                "fields=${symbolIndex.fieldsByQualifiedName.size}",
                "resources=${symbolIndex.resourcesByPath.size}",
            )
        }
        val relationStartedAt = System.nanoTime()
        val relationIndex = readActionIfNeeded {
            relationResolverRegistry.resolveAll(
                JvmResolutionContext(
                    project = project,
                    symbolIndex = symbolIndex,
                    sourceResolver = sourceComponents.resolver,
                    budget = budget,
                ),
                traceStage = ::traceStage,
            )
        }
        traceStage("architectureIndex.relationIndex", relationStartedAt) {
            listOf(
                "relations=${relationIndex.relations.size}",
                "truncated=${relationIndex.truncated}",
            )
        }
        val graphStartedAt = System.nanoTime()
        return ArchitectureGraphIndex.from(symbolIndex, relationIndex, budget = budget)
            .also { index ->
                traceStage("architectureIndex.graphBuild", graphStartedAt) {
                    listOf(
                        "graphNodes=${index.graph.nodes.size}",
                        "graphEdges=${index.graph.edges.size}",
                        "truncated=${index.graph.truncated}",
                    )
                }
            }
    }

    private fun <T> readActionIfNeeded(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        return if (application.isReadAccessAllowed) {
            action()
        } else {
            ReadAction.nonBlocking<T> { action() }
                .expireWith(project)
                .executeSynchronously()
        }
    }

    private fun classDiagramStructureIndexWithBoundedReadActions(
        budget: JvmResolutionBudget,
        forceRebuild: Boolean,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("classDiagram.fastIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(
                project = project,
                budget = budget,
                attachedJars = settings.attachedJars,
                attachedJarIndex = sourceComponents.attachedJarIndex,
                purpose = ArchitectureGraphCacheKey.PURPOSE_CLASS_DIAGRAM_STRUCTURE,
            )
        }
        traceStage("classDiagram.fastIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        return project.architectureIndexService().getOrBuildAuxiliaryCachedIndex(
            cacheKey = cacheKey,
            forceRebuild = forceRebuild,
        ) {
            val symbolStartedAt = System.nanoTime()
            val symbolIndex = readActionIfNeeded {
                JvmSymbolIndexBuilder(
                    project = project,
                    attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                    trace = { event ->
                        traceStage(event.stage, event.startedAtNanos, event.details)
                    },
                ).build(budget)
            }
            traceStage("classDiagram.fastIndex.symbolIndex", symbolStartedAt) {
                listOf(
                    "modules=${symbolIndex.modulesByName.size}",
                    "packages=${symbolIndex.packagesByName.size}",
                    "classes=${symbolIndex.classesByQualifiedName.size}",
                    "methods=${symbolIndex.methodsBySignature.size}",
                    "fields=${symbolIndex.fieldsByQualifiedName.size}",
                    "resources=${symbolIndex.resourcesByPath.size}",
                )
            }
            val graphStartedAt = System.nanoTime()
            ClassDiagramFastIndex.fromSymbols(symbolIndex, budget = budget)
                .also { index ->
                    traceStage("classDiagram.fastIndex.graphBuild", graphStartedAt) {
                        listOf(
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                }
        }
    }

    private fun architectureOverviewIndexWithBoundedReadActions(
        budget: JvmResolutionBudget,
        forceRebuild: Boolean,
        recordAsCurrent: Boolean,
    ): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponentsStartedAt = System.nanoTime()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        traceStage("architectureOverview.fastIndex.sourceComponents", sourceComponentsStartedAt) {
            listOf(
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "attachedJarFingerprints=${sourceComponents.attachedJarIndex.fingerprints.size}",
            )
        }
        val cacheKeyStartedAt = System.nanoTime()
        val cacheKey = readActionIfNeeded {
            ArchitectureGraphCacheKey.from(
                project = project,
                budget = budget,
                attachedJars = settings.attachedJars,
                attachedJarIndex = sourceComponents.attachedJarIndex,
                purpose = ArchitectureGraphCacheKey.PURPOSE_ARCHITECTURE_OVERVIEW,
            )
        }
        traceStage("architectureOverview.fastIndex.cacheKey", cacheKeyStartedAt) {
            listOf(
                "includeTests=${budget.includeTests}",
                "includeExternalLibraries=${budget.includeExternalLibraries}",
                "includeJdk=${budget.includeJdk}",
                "includeUserAttachedJars=${budget.includeUserAttachedJars}",
                "maxProjectClasses=${budget.maxProjectClasses}",
                "maxMethods=${budget.maxMethods}",
                "maxRelations=${budget.maxRelations}",
                "attachedJars=${cacheKey.attachedJars.size}",
            )
        }
        return project.architectureIndexService().getOrBuildAuxiliaryCachedIndex(
            cacheKey = cacheKey,
            forceRebuild = forceRebuild,
        ) {
            val symbolStartedAt = System.nanoTime()
            val symbolIndex = ArchitectureOverviewSymbolIndexBuilder(
                project = project,
                trace = ::traceStage,
            ).build(budget)
            traceStage("architectureOverview.fastIndex.symbolIndex", symbolStartedAt) {
                listOf(
                    "modules=${symbolIndex.modulesByName.size}",
                    "packages=${symbolIndex.packagesByName.size}",
                    "classes=${symbolIndex.classesByQualifiedName.size}",
                    "methods=${symbolIndex.methodsBySignature.size}",
                    "fields=${symbolIndex.fieldsByQualifiedName.size}",
                    "resources=${symbolIndex.resourcesByPath.size}",
                )
            }
            val graphStartedAt = System.nanoTime()
            ArchitectureOverviewFastIndex.fromSymbols(symbolIndex, budget = budget)
                .also { index ->
                    traceStage("architectureOverview.fastIndex.graphBuild", graphStartedAt) {
                        listOf(
                            "relations=${index.relationIndex.relations.size}",
                            "graphNodes=${index.graph.nodes.size}",
                            "graphEdges=${index.graph.edges.size}",
                            "truncated=${index.graph.truncated}",
                        )
                    }
                }
        }.also { index ->
            project.architectureIndexService().recordCurrentIndex(index, recordAsCurrent)
        }
    }

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        LinkGraphRenderTrace.stage(
            enabled = traceEnabled,
            log = { message -> logger.warn(message) },
            stage = stage,
            startedAtNanos = startedAtNanos,
            details = details,
        )
    }
}

fun Project.architectureIndexRuntime(): ArchitectureIndexRuntime =
    getService(ArchitectureIndexRuntime::class.java)
