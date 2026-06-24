package com.charmnight.linkgraph.llm.tools

/** 工具实现：返回当前项目架构/JVM 索引的整体摘要信息。 */
class GetArchitectureIndexSummaryTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "get_architecture_index_summary"
    override val description: String = "获取当前项目架构/JVM 索引摘要"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val summary = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index).summary()
        return ToolResult(
            toolName = name,
            payload = mapOf(
                "summary" to summary,
            ),
        )
    }
}

/** 工具实现：按 id、全限定名或关键字查找 JVM 符号。 */
class FindJvmSymbolTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_jvm_symbol"
    override val description: String = "按 id、全限定名或关键字查找 JVM 符号"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val query = input.requiredString("query") ?: return missingRequired("query")
        val symbols = facade.query(context.project).findSymbol(query).map(facade::symbolPayload)
        return ToolResult(toolName = name, payload = mapOf("symbols" to symbols))
    }
}

/** 工具实现：查找指定 JVM 符号的关系，支持按种类和方向过滤。 */
class FindJvmRelationsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_jvm_relations"
    override val description: String = "查找某个 JVM 符号的关系"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbol") ?: return missingRequired("symbol")
        val index = facade.buildIndex(context.project)
        val query = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
        val relations = query.relationsForSymbol(
            symbolIdOrName = symbol,
            kind = facade.relationKind(input.optionalString("kind")),
            direction = facade.direction(input.optionalString("direction")),
        ).map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("relations" to relations))
    }
}

/** 工具实现：通过统一架构运行时查询 JVM/架构关系，与 FindJvmRelationsTool 行为等价但走统一运行时入口。 */
class QueryArchitectureRelationsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "query_architecture_relations"
    override val description: String = "通过统一架构运行时查询 JVM/架构关系"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbol") ?: return missingRequired("symbol")
        val index = facade.buildIndex(context.project)
        val relations = facade.query(context.project).relationsForSymbol(
            symbolIdOrName = symbol,
            kind = facade.relationKind(input.optionalString("kind")),
            direction = facade.direction(input.optionalString("direction")),
        ).map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("relations" to relations))
    }
}

/** 工具实现：查找 Java SPI 接口的 provider 实现类。 */
class FindServiceProvidersTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_service_providers"
    override val description: String = "查找 Java SPI provider"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val interfaceName = input.requiredString("interfaceName") ?: return missingRequired("interfaceName")
        val index = facade.buildIndex(context.project)
        val providers = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .serviceProviders(interfaceName)
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("providers" to providers))
    }
}

/** 工具实现：查找静态可证明的反射调用目标。 */
class FindReflectionTargetsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_reflection_targets"
    override val description: String = "查找静态可证明反射目标"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbol") ?: return missingRequired("symbol")
        val index = facade.buildIndex(context.project)
        val targets = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .reflectionTargets(symbol)
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("targets" to targets))
    }
}

/** 工具实现：查找静态可识别的代理关系目标。 */
class FindProxyTargetsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_proxy_targets"
    override val description: String = "查找静态可识别的代理关系目标"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val symbol = input.requiredString("symbol") ?: return missingRequired("symbol")
        val index = facade.buildIndex(context.project)
        val relations = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .relationsForSymbol(
                symbolIdOrName = symbol,
                kind = com.charmnight.linkgraph.jvm.relation.JvmRelationKind.USES_PROXY,
                direction = com.charmnight.linkgraph.architecture.query.RelationDirection.OUTGOING,
            )
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("targets" to relations))
    }
}

/** 工具实现：把传入的变更文件列表映射为架构索引符号。 */
class GetChangedSymbolsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "get_changed_symbols"
    override val description: String = "把 changedFiles 映射为架构索引符号"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val changedFiles = input.optionalStringList("changedFiles")
        return ToolResult(
            toolName = name,
            payload = mapOf("changedSymbols" to facade.review(context.project).changedSymbols(changedFiles)),
        )
    }
}

/** 工具实现：基于 review graph 查询变更文件的影响面，支持指定遍历深度。 */
class GetBlastRadiusTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "get_blast_radius"
    override val description: String = "查询 changedFiles 的影响面"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val changedFiles = input.optionalStringList("changedFiles")
        val depth = input.optionalInt("depth") ?: 2
        return ToolResult(
            toolName = name,
            payload = mapOf("blastRadius" to facade.review(context.project).blastRadius(changedFiles, depth)),
        )
    }
}

/** 工具实现：基于 review graph 查找变更文件的相关测试符号。 */
class FindRelatedTestsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "find_related_tests"
    override val description: String = "基于架构索引查找 changedFiles 的相关测试"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val changedFiles = input.optionalStringList("changedFiles")
        val tests = facade.review(context.project).blastRadius(changedFiles).relatedTests.map(facade::symbolPayload)
        return ToolResult(toolName = name, payload = mapOf("relatedTests" to tests))
    }
}

/** 工具实现：为变更审查构建最小证据包，便于后续问答或评审使用。 */
class BuildReviewEvidenceBundleTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : AgentTool {
    override val name: String = "build_review_evidence_bundle"
    override val description: String = "为变更审查构建最小证据包"

    override fun invoke(input: Map<String, Any?>, context: ToolExecutionContext): ToolResult {
        val changedFiles = input.optionalStringList("changedFiles")
        val depth = input.optionalInt("depth") ?: 2
        return ToolResult(
            toolName = name,
            payload = mapOf("evidenceBundle" to facade.review(context.project).buildEvidenceBundle(changedFiles, depth)),
        )
    }
}
