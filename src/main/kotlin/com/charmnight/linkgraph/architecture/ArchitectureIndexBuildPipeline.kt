package com.charmnight.linkgraph.architecture

import com.charmnight.linkgraph.foundation.LinkGraphDebugEnvironment
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndex
import com.charmnight.linkgraph.jvm.index.JvmSymbolIndexBuilder
import com.charmnight.linkgraph.jvm.relation.JvmRelationResolverRegistry
import com.charmnight.linkgraph.jvm.relation.JvmResolutionBudget
import com.charmnight.linkgraph.jvm.relation.JvmResolutionContext
import com.charmnight.linkgraph.source.SourceContentResolverComponents
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * 架构索引构建管道（P2-1 真正的架构分解）。
 *
 * 从 ArchitectureIndexRuntime 抽出的独立 class，负责从项目源码构建
 * ArchitectureGraphIndex 的完整流程：
 * 1. 构建 JVM 符号索引（模块/包/类/方法/字段/资源）
 * 2. 解析 JVM 关系索引（调用/继承/注入/SPI/反射等）
 * 3. 组装为 ArchitectureGraphIndex（图节点 + 图边 + 预算标记）
 *
 * 完全无状态、不涉及缓存：输入 project + budget + sourceComponents → 输出 ArchitectureGraphIndex。
 * 缓存协调和持久化片段管理仍由 ArchitectureIndexRuntime 负责。
 *
 * @param project 当前 IntelliJ 项目
 * @param relationResolverRegistry JVM 关系解析器注册表
 * @param logger 日志记录器
 * @param traceEnabled 是否开启构建阶段追踪
 */
internal class ArchitectureIndexBuildPipeline(
    private val project: Project,
    private val relationResolverRegistry: JvmRelationResolverRegistry,
    private val logger: Logger,
    private val traceEnabled: Boolean,
) {
    /**
     * 从头构建架构索引（不使用缓存）。
     *
     * @param budget 构建预算
     * @param symbolIndexHint 可选的预构建符号索引（跳过符号索引步骤）
     * @param sourceComponents 源码内容解析器组件
     * @return 完整的架构图索引
     */
    fun build(
        budget: JvmResolutionBudget,
        symbolIndexHint: JvmSymbolIndex? = null,
        sourceComponents: SourceContentResolverComponents,
    ): ArchitectureGraphIndex {
        val symbolIndex = buildSymbolIndex(budget, symbolIndexHint, sourceComponents)
        val relationIndex = buildRelationIndex(budget, symbolIndex, sourceComponents)
        return buildGraphIndex(budget, symbolIndex, relationIndex)
    }

    private fun buildSymbolIndex(
        budget: JvmResolutionBudget,
        symbolIndexHint: JvmSymbolIndex?,
        sourceComponents: SourceContentResolverComponents,
    ): JvmSymbolIndex {
        val startedAt = System.nanoTime()
        val symbolIndex = symbolIndexHint ?: readActionIfNeeded {
            JvmSymbolIndexBuilder(
                project = project,
                attachedJarIndexProvider = { sourceComponents.attachedJarIndex },
                trace = { event -> traceStage(event.stage, event.startedAtNanos, event.details) },
            ).build(budget)
        }
        traceStage("architectureIndex.symbolIndex", startedAt) {
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
        return symbolIndex
    }

    private fun buildRelationIndex(
        budget: JvmResolutionBudget,
        symbolIndex: JvmSymbolIndex,
        sourceComponents: SourceContentResolverComponents,
    ): com.charmnight.linkgraph.jvm.relation.JvmRelationIndex {
        val startedAt = System.nanoTime()
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
        traceStage("architectureIndex.relationIndex", startedAt) {
            listOf(
                "relations=${relationIndex.relations.size}",
                "truncated=${relationIndex.truncated}",
            )
        }
        return relationIndex
    }

    private fun buildGraphIndex(
        budget: JvmResolutionBudget,
        symbolIndex: JvmSymbolIndex,
        relationIndex: com.charmnight.linkgraph.jvm.relation.JvmRelationIndex,
    ): ArchitectureGraphIndex {
        val startedAt = System.nanoTime()
        return ArchitectureGraphIndex.from(symbolIndex, relationIndex, budget = budget)
            .also { index ->
                traceStage("architectureIndex.graphBuild", startedAt) {
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

    private fun traceStage(
        stage: String,
        startedAtNanos: Long,
        details: () -> List<String>,
    ) {
        if (!traceEnabled) return
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000.0
        logger.warn("$stage completed in ${"%.1f".format(elapsedMillis)}ms: ${details().joinToString(", ")}")
    }
}
