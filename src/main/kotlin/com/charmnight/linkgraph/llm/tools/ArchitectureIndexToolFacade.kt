package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.architecture.ArchitectureGraphIndex
import com.charmnight.linkgraph.architecture.architectureIndexRuntime
import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.architecture.query.RelationDirection
import com.charmnight.linkgraph.architecture.query.ProjectSemanticSeedIndex
import com.charmnight.linkgraph.architecture.query.ProjectSemanticSeedResult
import com.charmnight.linkgraph.jvm.index.JvmSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.review.ReviewGraphQueryService
import com.intellij.openapi.project.Project

/**
 * 架构索引工具统一门面。
 * 封装构建索引、执行查询、构造符号/关系负载和参数解析等通用逻辑，
 * 让具体工具实现只关心输入参数与最终输出。
 */
class ArchitectureIndexToolFacade(
    /** 语义种子索引提供者，默认返回禁用实例，便于上层按需替换为真实实现。 */
    private val semanticSeedIndexProvider: (Project) -> ProjectSemanticSeedIndex = {
        ProjectSemanticSeedIndex(enabled = false)
    },
    /** 架构图索引提供者，默认从项目架构运行时获取最新索引。 */
    private val indexProvider: (Project) -> ArchitectureGraphIndex = { project ->
        project.architectureIndexRuntime().index()
    },
) {
    /** 构建当前项目的架构图索引。 */
    fun buildIndex(project: Project): ArchitectureGraphIndex {
        return indexProvider(project)
    }

    /** 基于当前索引创建架构图查询服务。仅供不需要 relation payload 的 tool 使用——
     *  relation payload 需要同时拿到 index 与 query service，应改走 [openQuerySession]，
     *  避免 tool 内重复 buildIndex。 */
    fun query(project: Project): ArchitectureGraphQueryService =
        ArchitectureGraphQueryService(buildIndex(project))

    /**
     * 一次性打开查询会话：构建索引 + 派生查询服务 + 把 relationPayload 闭包到 session 内，
     * 让 tool 实现只与 [ArchitectureQuerySession] 交互，不再重复 buildIndex、不再 FQN 直构造。
     */
    fun openQuerySession(project: Project): ArchitectureQuerySession {
        val index = buildIndex(project)
        return ArchitectureQuerySession(
            facade = this,
            index = index,
            queryService = ArchitectureGraphQueryService(index),
        )
    }

    /** 按查询文本在语义种子索引中检索 TopK 候选结果。 */
    fun semanticSeeds(project: Project, query: String, topK: Int): List<ProjectSemanticSeedResult> =
        semanticSeedIndexProvider(project).search(query, topK)

    /** 创建 Review Graph 查询服务，用于变更审查场景的证据构建。 */
    fun review(project: Project): ReviewGraphQueryService =
        project.architectureIndexRuntime().reviewQuery(index = buildIndex(project))

    /** 把符号对象转换为可序列化为工具负载的映射。 */
    fun symbolPayload(symbol: JvmSymbol): ToolPayload =
        mapOf(
            "id" to symbol.id,
            "qualifiedName" to symbol.qualifiedName,
            "simpleName" to symbol.simpleName,
            "origin" to symbol.origin.name,
            "filePath" to symbol.source?.displayPath,
            "virtualFileUrl" to symbol.source?.virtualFileUrl,
            "decompiled" to (symbol.source?.decompiled ?: false),
        )

    /** 把关系对象转换为可序列化为工具负载的映射，并附带样例证据引用。 */
    fun relationPayload(relation: JvmRelation, index: ArchitectureGraphIndex): ToolPayload =
        mapOf(
            "id" to relation.id,
            "kind" to relation.kind.name,
            "fromSymbolId" to relation.fromSymbolId,
            "fromQualifiedName" to index.findSymbol(relation.fromSymbolId)?.qualifiedName,
            "toSymbolId" to relation.toSymbolId,
            "toQualifiedName" to index.findSymbol(relation.toSymbolId)?.qualifiedName,
            "confidence" to relation.confidence.name,
            "source" to relation.source.name,
            "count" to relation.count,
            "metadata" to relation.metadata,
            "evidenceRefs" to relation.samples.map { sample ->
                mapOf(
                    "filePath" to sample.filePath,
                    "virtualFileUrl" to sample.virtualFileUrl,
                    "startLine" to sample.startLine,
                    "endLine" to sample.endLine,
                    "decompiled" to sample.decompiled,
                    "claim" to sample.claim,
                )
            },
        )

    /** 把字符串安全解析为关系种类枚举，空白或无法识别时返回 null。 */
    fun relationKind(value: String?): JvmRelationKind? =
        value?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { JvmRelationKind.valueOf(raw.uppercase()) }.getOrNull()
        }

    /** 把字符串安全解析为关系方向枚举，空白或无法识别时回退为 BOTH。 */
    fun direction(value: String?): RelationDirection =
        value?.trim()?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching { RelationDirection.valueOf(raw.uppercase()) }.getOrNull()
        } ?: RelationDirection.BOTH

}

/**
 * 一次 tool 调用内的「索引 + 查询服务 + 负载构造」会话。
 *
 * 设计目的：消除 tool 内 `facade.buildIndex()` + `ArchitectureGraphQueryService(index)` 直构造
 * + `facade.relationPayload(relation, index)` 重复传 index 的样板，让所有 tool 走统一形态：
 *
     * 示例：`val session = facade.openQuerySession(context.project)`
     * 示例：`val relations = session.queryService.relationsForSymbol(...).map(session::relationPayload)`
 *
 * session 持有的 [index] 与 [queryService] 来自同一次 buildIndex 调用，避免重复构建。
 */
class ArchitectureQuerySession(
    private val facade: ArchitectureIndexToolFacade,
    val index: ArchitectureGraphIndex,
    val queryService: ArchitectureGraphQueryService,
) {
    /** 见 [ArchitectureIndexToolFacade.symbolPayload]。 */
    fun symbolPayload(symbol: JvmSymbol): ToolPayload = facade.symbolPayload(symbol)

    /** 见 [ArchitectureIndexToolFacade.relationPayload]，index 自动绑定为 session 内的索引。 */
    fun relationPayload(relation: JvmRelation): ToolPayload =
        facade.relationPayload(relation, index)

    /** 见 [ArchitectureIndexToolFacade.relationKind]。 */
    fun relationKind(value: String?): JvmRelationKind? = facade.relationKind(value)

    /** 见 [ArchitectureIndexToolFacade.direction]。 */
    fun direction(value: String?): RelationDirection = facade.direction(value)
}
