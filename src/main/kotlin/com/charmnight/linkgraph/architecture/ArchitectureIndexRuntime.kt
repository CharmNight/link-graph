package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
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
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ArchitectureIndexRuntime(
    private val project: Project,
) {
    private val relationResolverRegistry = JvmRelationResolverRegistry()
    private val sourceResolverFactory = SourceContentResolverFactory(project, ::settingsSnapshot)

    fun currentIndex(): ArchitectureGraphIndex? =
        project.architectureIndexService().currentIndex()

    fun index(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        val application = ApplicationManager.getApplication()
        return if (application.isReadAccessAllowed) {
            indexInReadAction(budget)
        } else {
            ReadAction.compute<ArchitectureGraphIndex, RuntimeException> {
                indexInReadAction(budget)
            }
        }
    }

    fun sourceQuery(budget: JvmResolutionBudget = defaultBudget()): SourceContentResolver =
        sourceResolverFactory.create(budget).resolver

    fun symbolQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget))

    fun relationQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget))

    fun architectureGraphQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget))

    fun classDiagramQuery(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(index(budget))

    fun reviewQuery(
        budget: JvmResolutionBudget = defaultBudget(),
        index: ArchitectureGraphIndex = index(budget),
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

    private fun indexInReadAction(budget: JvmResolutionBudget): ArchitectureGraphIndex {
        val settings = settingsSnapshot()
        val sourceComponents = sourceResolverFactory.create(budget, settings)
        val cacheKey = ArchitectureGraphCacheKey.from(project, budget, settings.attachedJars, sourceComponents.attachedJarIndex)
        return project.architectureIndexService().getOrBuildCachedIndex(cacheKey) {
            val symbolIndex = JvmSymbolIndexBuilder(project) { sourceComponents.attachedJarIndex }.build(budget)
            val relationIndex = relationResolverRegistry.resolveAll(
                JvmResolutionContext(
                    project = project,
                    symbolIndex = symbolIndex,
                    sourceResolver = sourceComponents.resolver,
                    budget = budget,
                ),
            )
            ArchitectureGraphIndex.from(symbolIndex, relationIndex, budget = budget)
        }
    }
}

fun Project.architectureIndexRuntime(): ArchitectureIndexRuntime =
    getService(ArchitectureIndexRuntime::class.java)
