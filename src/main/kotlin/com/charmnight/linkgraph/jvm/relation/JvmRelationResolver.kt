package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.source.SourceContentResolver
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtFile

interface JvmRelationResolver {
    val id: String

    fun resolve(context: JvmResolutionContext): List<JvmRelation>
}

data class JvmResolutionContext(
    val project: Project,
    val symbolIndex: JvmSymbolIndex,
    val sourceResolver: SourceContentResolver,
    val budget: JvmResolutionBudget = JvmResolutionBudget(),
) {
    internal val psiFactIndex: JvmPsiFactIndex by lazy(LazyThreadSafetyMode.NONE) {
        JvmPsiFactIndex.build(project, symbolIndex)
    }

    fun cachedPsiClass(symbolId: String): PsiClass? = psiFactIndex.classBySymbolId[symbolId]

    fun cachedPsiMethod(symbolId: String): PsiMethod? = psiFactIndex.methodBySymbolId[symbolId]

    fun cachedKotlinFiles(): List<KtFile> = psiFactIndex.kotlinFiles
}

data class JvmResolutionBudget(
    val includeTests: Boolean = true,
    val includeExternalLibraries: Boolean = false,
    val includeJdk: Boolean = false,
    val includeUserAttachedJars: Boolean = false,
    val maxProjectClasses: Int = 20_000,
    val maxExternalClasses: Int = 3_000,
    val maxMethods: Int = 80_000,
    val maxRelations: Int = 80_000,
    val maxSamplesPerRelation: Int = 5,
)

class JvmRelationResolverRegistry(
    val resolvers: List<JvmRelationResolver> = listOf(
        InheritanceRelationResolver(),
        AnnotationRelationResolver(),
        TypeUsageRelationResolver(),
        CallAggregationRelationResolver(),
        InjectionRelationResolver(),
        KotlinRelationResolver(),
        SpiRelationResolver(),
        ServiceLoaderCallRelationResolver(),
        ReflectionRelationResolver(),
        TestRelationResolver(),
        ProxyRelationResolver(),
        SpringEventRelationResolver(),
        SpringEndpointRelationResolver(),
        SpringConfigBindingRelationResolver(),
        FrameworkRelationResolver(),
    ),
) {
    init {
        val duplicateIds = resolvers.groupingBy { resolver -> resolver.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Jvm relation resolver id must be unique: ${duplicateIds.joinToString()}"
        }
    }

    fun resolveAll(
        context: JvmResolutionContext,
        traceStage: ((stage: String, startedAtNanos: Long, details: () -> List<String>) -> Unit)? = null,
    ): JvmRelationIndex {
        val relations = mutableListOf<JvmRelation>()
        resolvers.forEach { resolver ->
            ProgressManager.checkCanceled()
            if (relations.size >= context.budget.maxRelations) {
                return@forEach
            }
            val startedAt = System.nanoTime()
            val resolvedRelations = resolver.resolve(context)
            traceStage?.invoke("architectureIndex.relation.${resolver.id}", startedAt) {
                listOf(
                    "resolver=${resolver.id}",
                    "relations=${resolvedRelations.size}",
                    "totalBefore=${relations.size}",
                    "budgetRemaining=${(context.budget.maxRelations - relations.size).coerceAtLeast(0)}",
                )
            }
            resolvedRelations
                .asSequence()
                .take((context.budget.maxRelations - relations.size).coerceAtLeast(0))
                .forEach { relation ->
                    ProgressManager.checkCanceled()
                    relations += relation.copy(
                        metadata = mapOf("relation.resolverId" to resolver.id) + relation.metadata,
                    )
                }
        }
        return JvmRelationIndex(
            relations = relations
                .groupBy(JvmRelation::id)
                .values
                .map { sameId -> mergeRelations(sameId) },
            maxRelations = context.budget.maxRelations,
        )
    }

    private fun mergeRelations(relations: List<JvmRelation>): JvmRelation {
        val first = relations.first()
        if (relations.size == 1) {
            return first
        }
        return first.copy(
            count = relations.sumOf { relation -> relation.count },
            samples = relations.flatMap { relation -> relation.samples }.distinct().take(5),
            metadata = relations.fold(first.metadata) { current, relation -> current + relation.metadata },
        )
    }
}
