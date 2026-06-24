package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.source.SourceContentResolver
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtFile

/** JVM 关系解析器接口，每种解析器负责识别一类关系。 */
interface JvmRelationResolver {
    /** 解析器唯一标识。 */
    val id: String

    /** 在给定解析上下文中识别关系。 */
    fun resolve(context: JvmResolutionContext): List<JvmRelation>
}

/** 关系解析上下文，承载项目、符号索引、源码解析器、预算与 PSI 缓存。 */
data class JvmResolutionContext(
    /** 当前 IntelliJ 项目，用于获取 PSI 与项目级服务。 */
    val project: Project,
    /** 已构建好的 JVM 符号索引，提供类/方法检索能力。 */
    val symbolIndex: JvmSymbolIndex,
    /** 源码内容解析器，按需读取源文本。 */
    val sourceResolver: SourceContentResolver,
    /** 解析预算，控制扫描范围与各项上限。 */
    val budget: JvmResolutionBudget = JvmResolutionBudget(),
) {
    /** 惰性构建的 PSI 事实索引，避免重复扫描 PSI。 */
    internal val psiFactIndex: JvmPsiFactIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        JvmPsiFactIndex.build(project, symbolIndex)
    }

    /** 按 ID 重解析 PSI 类；每次访问都从 JavaPsiFacade 取当前 VFS 状态下的 PsiClass，避免持有失效元素。 */
    fun cachedPsiClass(symbolId: String): PsiClass? = psiFactIndex.lookupPsiClass(project, symbolId)

    /** 按 ID 重解析 PSI 方法；每次访问都从 owner 类的方法列表按签名匹配，避免持有失效元素。 */
    fun cachedPsiMethod(symbolId: String): PsiMethod? = psiFactIndex.lookupPsiMethod(project, symbolId)

    /** 重解析项目内所有 Kotlin 文件；每次访问都返回当前 VFS 状态下的 KtFile 列表。 */
    fun cachedKotlinFiles(): List<KtFile> = psiFactIndex.lookupKotlinFiles(project)
}

/** 关系解析预算，控制扫描范围与各项上限。 */
data class JvmResolutionBudget(
    /** 是否纳入测试源码参与扫描。 */
    val includeTests: Boolean = true,
    /** 是否纳入外部依赖库中的类参与扫描。 */
    val includeExternalLibraries: Boolean = false,
    /** 是否纳入 JDK 内置类参与扫描。 */
    val includeJdk: Boolean = false,
    /** 是否纳入用户附加的 jar 参与扫描。 */
    val includeUserAttachedJars: Boolean = false,
    /** 项目内类的扫描上限，避免极端规模导致内存膨胀。 */
    val maxProjectClasses: Int = 20_000,
    /** 外部类的扫描上限。 */
    val maxExternalClasses: Int = 3_000,
    /** 方法的扫描上限。 */
    val maxMethods: Int = 80_000,
    /** 关系数量的总上限，超过即停止累加。 */
    val maxRelations: Int = 80_000,
    /** 每条关系保留的样本数量上限。 */
    val maxSamplesPerRelation: Int = 5,
    /** 需要展开方法体的源类 ID 集合，用于精细化的调用解析。 */
    val methodBodySourceClassIds: Set<String> = emptySet(),
    /** 最多扫描多少方法体，控制方法体展开成本。 */
    val maxMethodBodiesScanned: Int = Int.MAX_VALUE,
    /** 最多解析多少方法调用表达式，避免调用图爆炸。 */
    val maxMethodCallExpressionsResolved: Int = Int.MAX_VALUE,
)

/**
 * 关系解析器注册表，聚合内置的所有解析器，按顺序执行并合并去重。
 */
class JvmRelationResolverRegistry(
    /** 内置关系解析器列表，默认按继承、注解、类型使用、调用聚合、注入、Kotlin、SPI、反射等顺序聚合。 */
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
    /** 校验所有 resolver 的 id 唯一，避免后续去重时出现歧义。 */
    init {
        val duplicateIds = resolvers.groupingBy { resolver -> resolver.id }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        require(duplicateIds.isEmpty()) {
            "Jvm relation resolver id must be unique: ${duplicateIds.joinToString()}"
        }
    }

    /** 按顺序执行所有解析器，合并并按关系 ID 去重，返回最终关系索引。 */
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

    /** 合并相同 ID 的关系：累加计数、合并样本与元数据。 */
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
