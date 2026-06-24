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

/**
 * 架构索引工作流支撑：封装架构图/类图索引的构建、缓存、刷新策略与失败容错，
 * 作为工作流层调用底层索引运行时与服务的桥梁。
 */
internal class ArchitectureIndexWorkflowSupport(
    private val project: Project,
) : ClassDiagramIndexSupport {
    // 日志记录器，用于输出索引访问过程中的失败与诊断信息
    private val logger = Logger.getInstance(ArchitectureIndexWorkflowSupport::class.java)

    // 最近一次构建得到的"当前生效"索引缓存，避免重复构建
    @Volatile
    private var cachedIndex: ArchitectureGraphIndex? = null

    // 完整索引缓存，用于判断是否已有全量构建结果可复用
    @Volatile
    private var cachedFullIndex: ArchitectureGraphIndex? = null

    /**
     * 获取当前生效的架构图索引：优先返回缓存，缓存缺失时回退到运行时并容忍异常返回 null。
     */
    override fun currentIndex(): ArchitectureGraphIndex? = cachedIndex
        ?: LoggedFailures.orNull(logger, "currentIndex architectureIndexRuntime.currentIndex") {
            project.architectureIndexRuntime().currentIndex()
        }

    /**
     * 获取索引新鲜度信息，失败时返回默认的新鲜度对象以保证流程不中断。
     */
    override fun freshness(): IndexedGraphFreshness =
        LoggedFailures.orDefault(
            logger,
            "freshness architectureIndexService.freshness",
            defaultValue = IndexedGraphFreshness(),
        ) {
            project.architectureIndexService().freshness().toIndexedFreshness()
        }

    /**
     * 以指定预算构建一次索引并同步更新"当前索引"与"完整索引"缓存。
     */
    fun buildIndex(budget: JvmResolutionBudget = defaultBudget()): ArchitectureGraphIndex {
        return project.architectureIndexRuntime().index(budget).also { index ->
            cachedIndex = index
            cachedFullIndex = index
        }
    }

    /**
     * 按索引请求选择构建路径：项目级架构图走概览索引构建，其余走通用构建逻辑。
     */
    override fun buildIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        if (request.view == IndexedGraphView.ARCHITECTURE && request.scope is IndexedGraphScope.Project) {
            buildArchitectureOverviewIndex(request)
        } else {
            buildIndex(request.toResolutionBudget(), forceRebuild = request.forceRebuild)
        }

    /**
     * 带外部符号索引提示的构建入口，将请求转换为预算和重建标记后委托给通用构建逻辑。
     */
    override fun buildIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
    ): ArchitectureGraphIndex =
        buildIndex(
            budget = request.toResolutionBudget(),
            symbolIndexHint = symbolIndexHint,
            forceRebuild = request.forceRebuild,
        )

    /**
     * 构建一个限定"方法体源类集合"范围的类图索引：
     * 复用或构建结构索引，缩小方法体扫描范围并解析调用聚合关系，再组装成架构图索引。
     */
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

    /**
     * 内部通用构建逻辑，按预算与可选的强制重建标记构建索引并刷新两个缓存。
     */
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

    /**
     * 带符号索引提示和重建标记的构建入口，用于在外部已有部分符号索引时复用结果以加速构建。
     */
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

    /**
     * 仅构建类图所需的结构索引（不含方法体调用关系），可按预算与重建标记调用底层运行时。
     */
    private fun buildClassDiagramStructureIndex(
        budget: JvmResolutionBudget = defaultBudget(),
        forceRebuild: Boolean = false,
    ): ArchitectureGraphIndex =
        project.architectureIndexRuntime().classDiagramStructureIndex(
            budget = budget,
            forceRebuild = forceRebuild,
        )

    /**
     * 接收索引请求并构建类图结构索引的对外入口，按请求预算与重建策略委托内部实现。
     */
    override fun buildClassDiagramStructureIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        buildClassDiagramStructureIndex(
            budget = request.toResolutionBudget(),
            forceRebuild = request.forceRebuild,
        )

    /**
     * 构建架构总览索引（项目级架构图的入口），构建结果会同时记录为当前索引以便复用。
     */
    private fun buildArchitectureOverviewIndex(request: IndexedGraphRequest): ArchitectureGraphIndex =
        project.architectureIndexRuntime().architectureOverviewIndex(
            budget = request.toResolutionBudget(),
            forceRebuild = request.forceRebuild,
            recordAsCurrent = true,
        ).also { index ->
            cachedIndex = index
        }

    /**
     * 判断是否已经具备完整的索引：优先检查缓存，否则向运行时确认是否有可复用的完整索引。
     */
    fun hasFullIndex(budget: JvmResolutionBudget = defaultBudget()): Boolean =
        cachedFullIndex != null || project.architectureIndexRuntime().hasCachedFullIndex(budget)

    /**
     * 接收索引请求判断是否已有完整索引的对外入口，按请求预算查询运行时缓存。
     */
    override fun hasFullIndex(request: IndexedGraphRequest): Boolean =
        project.architectureIndexRuntime().hasCachedFullIndex(request.toResolutionBudget())

    /**
     * 使当前类持有的索引缓存与底层运行时缓存全部失效，下一次访问将触发重建。
     */
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

    /**
     * 将索引请求转换为对应的解析预算，便于复用预算相关参数。
     */
    fun resolutionBudget(request: IndexedGraphRequest): JvmResolutionBudget =
        request.toResolutionBudget()

    /**
     * 从运行时获取默认的解析预算，作为构建参数的基线。
     */
    private fun defaultBudget(): JvmResolutionBudget =
        project.architectureIndexRuntime().defaultBudget()

    /**
     * 将索引请求扩展为解析预算：以默认预算为基线，叠加外部库与 JDK 引入策略。
     */
    private fun IndexedGraphRequest.toResolutionBudget(): JvmResolutionBudget =
        defaultBudget().copy(
            includeExternalLibraries = includeExternalLibraries,
            includeJdk = includeJdk,
        )

    /**
     * 判断当前请求是否要求强制重建索引。
     */
    private val IndexedGraphRequest.forceRebuild: Boolean
        get() = refreshPolicy == IndexedGraphRefreshPolicy.ForceRebuild

    private companion object {
        // 限定范围类图索引允许扫描的最大方法体数量
        private const val SCOPED_METHOD_BODY_LIMIT = 1_500
        // 限定范围类图索引允许解析的最大方法调用表达式数量
        private const val SCOPED_METHOD_CALL_LIMIT = 8_000
    }
}

/**
 * 类图索引支持的对外契约：抽象出获取当前索引、新鲜度、构建索引、范围类图等核心能力。
 */
internal interface ClassDiagramIndexSupport {
    /**
     * 获取当前生效的架构图索引，可能为空。
     */
    fun currentIndex(): ArchitectureGraphIndex?

    /**
     * 获取当前索引的新鲜度描述。
     */
    fun freshness(): IndexedGraphFreshness

    /**
     * 根据索引请求构建架构图索引。
     */
    fun buildIndex(request: IndexedGraphRequest): ArchitectureGraphIndex

    /**
     * 根据索引请求和外部符号索引提示构建架构图索引。
     */
    fun buildIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
    ): ArchitectureGraphIndex

    /**
     * 构建仅包含指定源类集合范围的类图索引，默认实现回退到通用构建入口。
     */
    fun buildScopedClassDiagramIndex(
        request: IndexedGraphRequest,
        symbolIndexHint: JvmSymbolIndex?,
        sourceClassIds: Set<String>,
    ): ArchitectureGraphIndex =
        buildIndex(request, symbolIndexHint)

    /**
     * 构建类图所需的结构索引。
     */
    fun buildClassDiagramStructureIndex(request: IndexedGraphRequest): ArchitectureGraphIndex

    /**
     * 判断是否已经具备满足该请求的完整索引。
     */
    fun hasFullIndex(request: IndexedGraphRequest): Boolean
}
