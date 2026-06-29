package com.charmnight.linkgraph.llm.tools

/** 工具实现：返回当前项目架构/JVM 索引的整体摘要信息。 */
class GetArchitectureIndexSummaryTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<GetArchitectureIndexSummaryInput>() {
    override val name: String = "get_architecture_index_summary"
    override val description: String = "获取当前项目架构/JVM 索引摘要"

    override fun parseInput(raw: Map<String, Any?>): GetArchitectureIndexSummaryInput = GetArchitectureIndexSummaryInput

    override fun invokeTyped(input: GetArchitectureIndexSummaryInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val summary = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index).summary()
        return ToolResult(toolName = name, payload = mapOf("summary" to summary))
    }
}

/** [GetArchitectureIndexSummaryTool] 的入参（无参数，用 object 表达）。 */
object GetArchitectureIndexSummaryInput

/** 工具实现：按 id、全限定名或关键字查找 JVM 符号。 */
class FindJvmSymbolTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<FindJvmSymbolInput>() {
    override val name: String = "find_jvm_symbol"
    override val description: String = "按 id、全限定名或关键字查找 JVM 符号"

    override fun parseInput(raw: Map<String, Any?>): FindJvmSymbolInput = FindJvmSymbolInput(
        query = requireString(raw, "query"),
    )

    override fun invokeTyped(input: FindJvmSymbolInput, context: ToolExecutionContext): ToolResult {
        val symbols = facade.query(context.project).findSymbol(input.query).map(facade::symbolPayload)
        return ToolResult(toolName = name, payload = mapOf("symbols" to symbols))
    }
}

/** [FindJvmSymbolTool] 的强类型入参。 */
data class FindJvmSymbolInput(val query: String)

/** 工具实现：查找指定 JVM 符号的关系，支持按种类和方向过滤。 */
class FindJvmRelationsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<JvmRelationsInput>() {
    override val name: String = "find_jvm_relations"
    override val description: String = "查找某个 JVM 符号的关系"

    override fun parseInput(raw: Map<String, Any?>): JvmRelationsInput = JvmRelationsInput(
        symbol = requireString(raw, "symbol"),
        kind = optionalString(raw, "kind"),
        direction = optionalString(raw, "direction"),
    )

    override fun invokeTyped(input: JvmRelationsInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val query = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
        val relations = query.relationsForSymbol(
            symbolIdOrName = input.symbol,
            kind = facade.relationKind(input.kind),
            direction = facade.direction(input.direction),
        ).map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("relations" to relations))
    }
}

/** [FindJvmRelationsTool] / [QueryArchitectureRelationsTool] 共用的强类型入参。 */
data class JvmRelationsInput(
    val symbol: String,
    val kind: String?,
    val direction: String?,
)

/** 工具实现：通过统一架构运行时查询 JVM/架构关系，与 FindJvmRelationsTool 行为等价但走统一运行时入口。 */
class QueryArchitectureRelationsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<JvmRelationsInput>() {
    override val name: String = "query_architecture_relations"
    override val description: String = "通过统一架构运行时查询 JVM/架构关系"

    override fun parseInput(raw: Map<String, Any?>): JvmRelationsInput = JvmRelationsInput(
        symbol = requireString(raw, "symbol"),
        kind = optionalString(raw, "kind"),
        direction = optionalString(raw, "direction"),
    )

    override fun invokeTyped(input: JvmRelationsInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val relations = facade.query(context.project).relationsForSymbol(
            symbolIdOrName = input.symbol,
            kind = facade.relationKind(input.kind),
            direction = facade.direction(input.direction),
        ).map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("relations" to relations))
    }
}

/** 工具实现：查找 Java SPI 接口的 provider 实现类。 */
class FindServiceProvidersTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<FindServiceProvidersInput>() {
    override val name: String = "find_service_providers"
    override val description: String = "查找 Java SPI provider"

    override fun parseInput(raw: Map<String, Any?>): FindServiceProvidersInput = FindServiceProvidersInput(
        interfaceName = requireString(raw, "interfaceName"),
    )

    override fun invokeTyped(input: FindServiceProvidersInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val providers = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .serviceProviders(input.interfaceName)
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("providers" to providers))
    }
}

/** [FindServiceProvidersTool] 的强类型入参。 */
data class FindServiceProvidersInput(val interfaceName: String)

/** 工具实现：查找静态可证明的反射调用目标。 */
class FindReflectionTargetsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<FindReflectionTargetsInput>() {
    override val name: String = "find_reflection_targets"
    override val description: String = "查找静态可证明反射目标"

    override fun parseInput(raw: Map<String, Any?>): FindReflectionTargetsInput = FindReflectionTargetsInput(
        symbol = requireString(raw, "symbol"),
    )

    override fun invokeTyped(input: FindReflectionTargetsInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val targets = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .reflectionTargets(input.symbol)
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("targets" to targets))
    }
}

/** [FindReflectionTargetsTool] 的强类型入参。 */
data class FindReflectionTargetsInput(val symbol: String)

/** 工具实现：查找静态可识别的代理关系目标。 */
class FindProxyTargetsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<FindProxyTargetsInput>() {
    override val name: String = "find_proxy_targets"
    override val description: String = "查找静态可识别的代理关系目标"

    override fun parseInput(raw: Map<String, Any?>): FindProxyTargetsInput = FindProxyTargetsInput(
        symbol = requireString(raw, "symbol"),
    )

    override fun invokeTyped(input: FindProxyTargetsInput, context: ToolExecutionContext): ToolResult {
        val index = facade.buildIndex(context.project)
        val relations = com.charmnight.linkgraph.architecture.query.ArchitectureGraphQueryService(index)
            .relationsForSymbol(
                symbolIdOrName = input.symbol,
                kind = com.charmnight.linkgraph.jvm.relation.JvmRelationKind.USES_PROXY,
                direction = com.charmnight.linkgraph.architecture.query.RelationDirection.OUTGOING,
            )
            .map { relation -> facade.relationPayload(relation, index) }
        return ToolResult(toolName = name, payload = mapOf("targets" to relations))
    }
}

/** [FindProxyTargetsTool] 的强类型入参。 */
data class FindProxyTargetsInput(val symbol: String)

/** 工具实现：把传入的变更文件列表映射为架构索引符号。 */
class GetChangedSymbolsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<ChangedFilesInput>() {
    override val name: String = "get_changed_symbols"
    override val description: String = "把 changedFiles 映射为架构索引符号"

    override fun parseInput(raw: Map<String, Any?>): ChangedFilesInput = ChangedFilesInput(
        changedFiles = optionalStringList(raw, "changedFiles"),
    )

    override fun invokeTyped(input: ChangedFilesInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("changedSymbols" to facade.review(context.project).changedSymbols(input.changedFiles)),
        )
}

/** 工具实现：基于 review graph 查询变更文件的影响面，支持指定遍历深度。 */
class GetBlastRadiusTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<GetBlastRadiusInput>() {
    override val name: String = "get_blast_radius"
    override val description: String = "查询 changedFiles 的影响面"

    override fun parseInput(raw: Map<String, Any?>): GetBlastRadiusInput = GetBlastRadiusInput(
        changedFiles = optionalStringList(raw, "changedFiles"),
        depth = optionalInt(raw, "depth") ?: 2,
    )

    override fun invokeTyped(input: GetBlastRadiusInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("blastRadius" to facade.review(context.project).blastRadius(input.changedFiles, input.depth)),
        )
}

/** [GetBlastRadiusTool] 的强类型入参。depth 缺省 2。 */
data class GetBlastRadiusInput(
    val changedFiles: List<String>,
    val depth: Int,
)

/** 工具实现：基于 review graph 查找变更文件的相关测试符号。 */
class FindRelatedTestsTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<ChangedFilesInput>() {
    override val name: String = "find_related_tests"
    override val description: String = "基于架构索引查找 changedFiles 的相关测试"

    override fun parseInput(raw: Map<String, Any?>): ChangedFilesInput = ChangedFilesInput(
        changedFiles = optionalStringList(raw, "changedFiles"),
    )

    override fun invokeTyped(input: ChangedFilesInput, context: ToolExecutionContext): ToolResult {
        val tests = facade.review(context.project).blastRadius(input.changedFiles).relatedTests.map(facade::symbolPayload)
        return ToolResult(toolName = name, payload = mapOf("relatedTests" to tests))
    }
}

/** 工具实现：为变更审查构建最小证据包，便于后续问答或评审使用。 */
class BuildReviewEvidenceBundleTool(
    private val facade: ArchitectureIndexToolFacade = ArchitectureIndexToolFacade(),
) : TypedAgentTool<BuildReviewEvidenceBundleInput>() {
    override val name: String = "build_review_evidence_bundle"
    override val description: String = "为变更审查构建最小证据包"

    override fun parseInput(raw: Map<String, Any?>): BuildReviewEvidenceBundleInput = BuildReviewEvidenceBundleInput(
        changedFiles = optionalStringList(raw, "changedFiles"),
        depth = optionalInt(raw, "depth") ?: 2,
    )

    override fun invokeTyped(input: BuildReviewEvidenceBundleInput, context: ToolExecutionContext): ToolResult =
        ToolResult(
            toolName = name,
            payload = mapOf("evidenceBundle" to facade.review(context.project).buildEvidenceBundle(input.changedFiles, input.depth)),
        )
}

/** [BuildReviewEvidenceBundleTool] 的强类型入参。depth 缺省 2。 */
data class BuildReviewEvidenceBundleInput(
    val changedFiles: List<String>,
    val depth: Int,
)

/** 多个工具共用的「变更文件列表」入参；changedFiles 缺省为空列表。 */
data class ChangedFilesInput(val changedFiles: List<String>)
