package com.charmnight.linkgraph.llm.tools

import com.charmnight.linkgraph.architecture.architectureIndexService
import com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService
import com.charmnight.linkgraph.architecture.query.TraversalMode

/** 工具实现：一次性返回项目上下文综合包，包含符号、关系、片段、变更符号、索引新鲜度和告警。 */
class ExploreProjectContextTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "explore_project_context"
    override val description: String = "一次性获取项目上下文包：符号、关系、片段、变更符号、索引新鲜度和告警"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val queryText = input.requiredString("query") ?: return missingRequired("query")
        val depth = (input.optionalInt("depth") ?: 2).coerceIn(1, 5)
        val maxSymbols = (input.optionalInt("maxSymbols") ?: 8).coerceIn(1, 50)
        val maxRelations = (input.optionalInt("maxRelations") ?: 30).coerceIn(0, 200)
        val maxSnippets = (input.optionalInt("maxSnippets") ?: 6).coerceIn(0, 50)
        val changedFiles = input.optionalStringList("changedFiles")
        val index = facade.buildIndex(context.project)
        val query = ArchitectureGraphQueryService(index)
        val rankedSymbols = query.rankedFindSymbol(queryText, maxSymbols).map { result ->
            facade.symbolPayload(result.symbol) + mapOf(
                "matchKind" to result.matchKind,
                "score" to result.score,
            )
        }
        val semanticSeedSymbols = facade.semanticSeeds(context.project, queryText, maxSymbols).mapNotNull { seed ->
            index.findSymbol(seed.nodeId)?.let { symbol ->
                facade.symbolPayload(symbol) + seed.metadata + mapOf(
                    "matchKind" to "SEMANTIC_SEED",
                    "score" to seed.score,
                )
            }
        }
        val symbols = (rankedSymbols + semanticSeedSymbols)
            .distinctBy { symbol -> symbol["id"] as? String }
            .take(maxSymbols)
        val symbolIds = symbols.mapNotNull { it["id"] as? String }
        val relations = symbolIds
            .flatMap { symbolId -> query.relationsForSymbol(symbolId) }
            .distinctBy { relation -> relation.id }
            .take(maxRelations)
            .map { relation -> facade.relationPayload(relation, index) }
        val snippets = symbols
            .take(maxSnippets)
            .map { symbol ->
                mapOf(
                    "symbolId" to symbol["id"],
                    "qualifiedName" to symbol["qualifiedName"],
                    "filePath" to symbol["filePath"],
                )
            }
        val changedSymbols = runCatching {
            facade.review(context.project).changedSymbols(changedFiles).take(maxSymbols).map { symbol ->
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
        }.getOrDefault(emptyList())
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
        }.getOrDefault(mapOf("state" to "FRESH"))
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "status" to "OK",
                "query" to queryText,
                "depth" to depth,
                "freshness" to freshness,
                "symbols" to symbols,
                "relations" to relations,
                "snippets" to snippets,
                "changedSymbols" to changedSymbols,
                "semanticSeeds" to semanticSeedSymbols,
                "warnings" to emptyList<String>(),
            ),
        )
    }
}

/** 工具实现：按自然语言问题查询项目图的小上下文子图。 */
class QueryProjectGraphTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "query_project_graph"
    override val description: String = "查询项目图的小上下文子图"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val question = input.requiredString("question") ?: input.requiredString("query") ?: return missingRequired("question")
        val budget = input.optionalInt("budget") ?: 20
        val mode = input.optionalString("mode")
            ?.let { raw -> runCatching { TraversalMode.valueOf(raw.uppercase()) }.getOrNull() }
            ?: TraversalMode.NEIGHBORHOOD
        return ToolResult(
            toolName = name,
            payload = mapOf("result" to facade.query(context.project).queryProjectGraph(question, budget, mode)),
        )
    }
}

/** 工具实现：查找两个项目符号之间的最短关系路径。 */
class FindProjectPathTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_project_path"
    override val description: String = "查找两个项目符号之间的最短关系路径"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val from = input.requiredString("from") ?: return missingRequired("from")
        val to = input.requiredString("to") ?: return missingRequired("to")
        return ToolResult(
            toolName = name,
            payload = mapOf("path" to facade.query(context.project).shortestPath(from, to, input.optionalInt("maxDepth") ?: 6)),
        )
    }
}

/** 工具实现：解释项目图节点的符号信息和出入关系。 */
class ExplainProjectNodeTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "explain_project_node"
    override val description: String = "解释项目图节点的符号和出入关系"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbolOrNodeId") ?: input.requiredString("symbol") ?: return missingRequired("symbolOrNodeId")
        return ToolResult(toolName = name, payload = mapOf("explanation" to facade.query(context.project).explainNode(symbol)))
    }
}

/** 工具实现：按图关系查找指定符号或文件受影响的上下游节点。 */
class AffectedProjectNodesTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "affected_project_nodes"
    override val description: String = "按图关系查找受影响的上下游节点"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbolOrFile") ?: input.requiredString("symbol") ?: return missingRequired("symbolOrFile")
        return ToolResult(toolName = name, payload = mapOf("affected" to facade.query(context.project).affectedNodes(symbol, input.optionalInt("depth") ?: 2)))
    }
}

/** 工具实现：返回当前项目索引的诊断摘要。 */
class GetProjectIndexDigestTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "get_project_index_digest"
    override val description: String = "获取项目图诊断摘要"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val scope = input.optionalString("scope") ?: ""
        return ToolResult(toolName = name, payload = mapOf("digest" to facade.query(context.project).communityOrPackageDigest(scope)))
    }
}
