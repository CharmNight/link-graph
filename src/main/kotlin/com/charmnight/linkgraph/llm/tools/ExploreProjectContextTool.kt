package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.agent.tools.*

import com.charmnight.linkgraph.architecture.architectureIndexService
import com.charmnight.linkgraph.architecture.query.TraversalMode

/** 工具实现：一次性返回项目上下文综合包，包含符号、关系、片段、变更符号、索引新鲜度和告警。 */
class ExploreProjectContextTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<ExploreProjectContextInput>() {
    override val name: String = "explore_project_context"
    override val description: String = "一次性获取项目上下文包：符号、关系、片段、变更符号、索引新鲜度和告警"

    override fun parseInput(raw: ToolInputPayload): ExploreProjectContextInput {
        val depth = (optionalInt(raw, "depth") ?: 2).coerceIn(1, 5)
        val maxSymbols = (optionalInt(raw, "maxSymbols") ?: 8).coerceIn(1, 50)
        val maxRelations = (optionalInt(raw, "maxRelations") ?: 30).coerceIn(0, 200)
        val maxSnippets = (optionalInt(raw, "maxSnippets") ?: 6).coerceIn(0, 50)
        return ExploreProjectContextInput(
            query = requireString(raw, "query"),
            depth = depth,
            maxSymbols = maxSymbols,
            maxRelations = maxRelations,
            maxSnippets = maxSnippets,
            changedFiles = optionalStringList(raw, "changedFiles"),
        )
    }

    override fun invokeTyped(input: ExploreProjectContextInput, context: ToolExecutionContext): ToolResult {
        // 失败路径统一收集到 warnings，绝不静默吞异常——尤其 freshness 兜底不能再返回误导性的 FRESH。
        val warnings = mutableListOf<String>()
        val session = facade.openQuerySession(context.project)
        val rankedSymbols = session.queryService.rankedFindSymbol(input.query, input.maxSymbols).map { result ->
            session.symbolPayload(result.symbol) + mapOf(
                "matchKind" to result.matchKind,
                "score" to result.score,
            )
        }
        val semanticSeedSymbols = facade.semanticSeeds(context.project, input.query, input.maxSymbols).mapNotNull { seed ->
            session.index.findSymbol(seed.nodeId)?.let { symbol ->
                session.symbolPayload(symbol) + seed.metadata + mapOf(
                    "matchKind" to "SEMANTIC_SEED",
                    "score" to seed.score,
                )
            }
        }
        val symbols = (rankedSymbols + semanticSeedSymbols)
            .distinctBy { symbol -> symbol["id"] as? String }
            .take(input.maxSymbols)
        val symbolIds = symbols.mapNotNull { it["id"] as? String }
        val relations = symbolIds
            .flatMap { symbolId -> session.queryService.relationsForSymbol(symbolId) }
            .distinctBy { relation -> relation.id }
            .take(input.maxRelations)
            .map(session::relationPayload)
        val snippets = symbols
            .take(input.maxSnippets)
            .map { symbol ->
                mapOf(
                    "symbolId" to symbol["id"],
                    "qualifiedName" to symbol["qualifiedName"],
                    "filePath" to symbol["filePath"],
                )
            }
        val changedSymbols = runCatching {
            facade.review(context.project).changedSymbols(input.changedFiles).take(input.maxSymbols).map { symbol ->
                mapOf(
                    "symbolId" to symbol.symbolId,
                    "qualifiedName" to symbol.qualifiedName,
                    "filePath" to symbol.filePath,
                    "startLine" to symbol.startLine,
                    "endLine" to symbol.endLine,
                    "changeKind" to symbol.changeKind,
                    "baselineOnly" to symbol.baselineOnly,
                )
            }
        }.getOrElse { error ->
            warnings += "变更符号查询失败：${error.message?.trim()?.ifBlank { error::class.java.simpleName } ?: error::class.java.simpleName}"
            emptyList()
        }
        val freshness = runCatching {
            val snapshot = context.project.architectureIndexService().freshness()
            mapOf(
                "state" to snapshot.state,
                "dirtyReason" to snapshot.dirtyReason,
                "pendingFileCount" to snapshot.pendingFileCount,
                "pendingFileSamples" to snapshot.pendingFileSamples,
                "lastIndexedAtEpochMillis" to snapshot.lastIndexedAtEpochMillis,
                "staleSinceEpochMillis" to snapshot.staleSinceEpochMillis,
            )
        }.getOrElse { error ->
            // 关键：失败时不能默认为 FRESH——那会让模型据此跳过等待索引刷新、给出基于过期索引的判断。
            // 用 UNKNOWN 显式告诉模型「不知道」，并附失败原因让模型考虑重新调用或换工具。
            val reason = error.message?.trim()?.ifBlank { error::class.java.simpleName } ?: error::class.java.simpleName
            warnings += "索引新鲜度查询失败：$reason"
            mapOf(
                "state" to "UNKNOWN",
                "dirtyReason" to "freshness query failed: $reason",
            )
        }
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "status" to if (warnings.isEmpty()) "OK" else "PARTIAL",
                "query" to input.query,
                "depth" to input.depth,
                "freshness" to freshness,
                "symbols" to symbols,
                "relations" to relations,
                "snippets" to snippets,
                "changedSymbols" to changedSymbols,
                "semanticSeeds" to semanticSeedSymbols,
                "warnings" to warnings,
            ),
        )
    }
}

/** [ExploreProjectContextTool] 的强类型入参；所有数值字段在 parse 阶段就做范围 clamp。 */
data class ExploreProjectContextInput(
    val query: String,
    val depth: Int,
    val maxSymbols: Int,
    val maxRelations: Int,
    val maxSnippets: Int,
    val changedFiles: List<String>,
)

/** 工具实现：按自然语言问题查询项目图的小上下文子图。 */
class QueryProjectGraphTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<QueryProjectGraphInput>() {
    override val name: String = "query_project_graph"
    override val description: String = "查询项目图的小上下文子图"

    override fun parseInput(raw: ToolInputPayload): QueryProjectGraphInput = QueryProjectGraphInput(
        // 兼容 question / query 两个 key 名（前者是新规范，后者是历史 fallback）
        question = optionalString(raw, "question") ?: optionalString(raw, "query") ?: missing("question"),
        budget = optionalInt(raw, "budget") ?: 20,
        // mode 缺省 NEIGHBORHOOD；但传了非法值时必须告诉模型而不是静默兜底——否则模型永远学不到正确枚举集合
        mode = optionalString(raw, "mode")
            ?.let { rawMode ->
                runCatching { TraversalMode.valueOf(rawMode.uppercase()) }.getOrNull()
                    ?: wrongEnum("mode", rawMode, TraversalMode.values().map { it.name })
            }
            ?: TraversalMode.NEIGHBORHOOD,
    )

    override fun invokeTyped(input: QueryProjectGraphInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("result" to facade.query(context.project).queryProjectGraph(input.question, input.budget, input.mode)),
        )
}

/** [QueryProjectGraphTool] 的强类型入参。 */
data class QueryProjectGraphInput(
    val question: String,
    val budget: Int,
    val mode: TraversalMode,
)

/** 工具实现：查找两个项目符号之间的最短关系路径。 */
class FindProjectPathTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<FindProjectPathInput>() {
    override val name: String = "find_project_path"
    override val description: String = "查找两个项目符号之间的最短关系路径"

    override fun parseInput(raw: ToolInputPayload): FindProjectPathInput = FindProjectPathInput(
        from = requireString(raw, "from"),
        to = requireString(raw, "to"),
        maxDepth = optionalInt(raw, "maxDepth") ?: 6,
    )

    override fun invokeTyped(input: FindProjectPathInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("path" to facade.query(context.project).shortestPath(input.from, input.to, input.maxDepth)),
        )
}

/** [FindProjectPathTool] 的强类型入参。maxDepth 缺省 6。 */
data class FindProjectPathInput(
    val from: String,
    val to: String,
    val maxDepth: Int,
)

/** 工具实现：解释项目图节点的符号信息和出入关系。 */
class ExplainProjectNodeTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<ExplainProjectNodeInput>() {
    override val name: String = "explain_project_node"
    override val description: String = "解释项目图节点的符号和出入关系"

    override fun parseInput(raw: ToolInputPayload): ExplainProjectNodeInput = ExplainProjectNodeInput(
        // 兼容 symbolOrNodeId / symbol 两个 key 名（前者是新规范，后者是历史 fallback）
        symbol = optionalString(raw, "symbolOrNodeId") ?: optionalString(raw, "symbol") ?: missing("symbolOrNodeId"),
    )

    override fun invokeTyped(input: ExplainProjectNodeInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("explanation" to facade.query(context.project).explainNode(input.symbol)),
        )
}

/** [ExplainProjectNodeTool] 的强类型入参。 */
data class ExplainProjectNodeInput(val symbol: String)

/** 工具实现：按图关系查找指定符号或文件受影响的上下游节点。 */
class AffectedProjectNodesTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<AffectedProjectNodesInput>() {
    override val name: String = "affected_project_nodes"
    override val description: String = "按图关系查找受影响的上下游节点"

    override fun parseInput(raw: ToolInputPayload): AffectedProjectNodesInput = AffectedProjectNodesInput(
        // 兼容 symbolOrFile / symbol 两个 key 名
        symbol = optionalString(raw, "symbolOrFile") ?: optionalString(raw, "symbol") ?: missing("symbolOrFile"),
        depth = optionalInt(raw, "depth") ?: 2,
    )

    override fun invokeTyped(input: AffectedProjectNodesInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("affected" to facade.query(context.project).affectedNodes(input.symbol, input.depth)),
        )
}

/** [AffectedProjectNodesTool] 的强类型入参。depth 缺省 2。 */
data class AffectedProjectNodesInput(
    val symbol: String,
    val depth: Int,
)

/** 工具实现：返回当前项目索引的诊断摘要。 */
class GetProjectIndexDigestTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<GetProjectIndexDigestInput>() {
    override val name: String = "get_project_index_digest"
    override val description: String = "获取项目图诊断摘要"

    override fun parseInput(raw: ToolInputPayload): GetProjectIndexDigestInput = GetProjectIndexDigestInput(
        scope = optionalString(raw, "scope") ?: "",
    )

    override fun invokeTyped(input: GetProjectIndexDigestInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("digest" to facade.query(context.project).communityOrPackageDigest(input.scope)),
        )
}

/** [GetProjectIndexDigestTool] 的强类型入参；scope 缺省空串（全局 digest）。 */
data class GetProjectIndexDigestInput(val scope: String)
