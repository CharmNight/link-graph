package com.charmnight.linkgraph.semantic.provider.code.relation

import com.charmnight.linkgraph.jvm.index.JvmMethodSymbol
import com.charmnight.linkgraph.jvm.relation.JvmRelation
import com.charmnight.linkgraph.jvm.relation.JvmRelationKind
import com.charmnight.linkgraph.model.EdgeType
import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticIdFactory
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.subject.SourceRange
import com.charmnight.linkgraph.semantic.subject.methodSignature
import com.intellij.psi.PsiMethod

/**
 * 基于架构索引的关系解析器：把已经预先建立的 JVM 架构图（方法/类的调用、注入、SPI、
 * 消息、事件、Dubbo、Feign 等关系）转换为语义层单元和语义关系，使图谱视图能够展示
 * 跨进程、跨服务的拓扑连接，而不仅限于当前文件内的直接依赖。
 */
class ArchitectureIndexSemanticResolver : CodeRelationSemanticResolver {
    /**
     * 把指定方法在架构索引中命中的所有外向关系展开成语义单元、关系与源码映射。
     * 找不到架构索引、方法或所属类时直接返回空结果。
     */
    override fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction {
        val index = context.architectureIndex() ?: return RelationExtraction()
        val ownerSignature = methodSignature(method)
        val ownerMethod = index.findMethod(ownerSignature) ?: return RelationExtraction()
        val ownerClass = index.findClass(ownerMethod.ownerClassName) ?: return RelationExtraction()
        val ownerUnitId = SemanticIdFactory.methodUnitId(ownerSignature)
        // 当前方法解析过程中累积的语义单元，使用 linkedMap 保留插入顺序并按 id 去重
        val semanticUnits = linkedMapOf<String, com.charmnight.linkgraph.semantic.model.SemanticUnit>()
        // 当前方法解析过程中累积的语义关系，使用 linkedMap 按复合 key 去重
        val relations = linkedMapOf<String, SemanticRelation>()
        // 单元到源码位置的映射，便于在编辑器中跳转定位
        val sourceMappings = linkedMapOf<String, SourceMapping>()

        // 把命中方法级目标的关系展开为：owner -> 中转资源单元 -> 目标方法 三段式语义边
        fun addMethodTarget(targetMethod: JvmMethodSymbol, relation: JvmRelation, edgeType: EdgeType) {
            val unit = MethodLikeUnit(
                id = SemanticIdFactory.methodUnitId(targetMethod.signature),
                title = targetMethod.qualifiedName.substringBefore('(').substringAfterLast('.'),
                signature = targetMethod.signature,
            )
            semanticUnits.putIfAbsent(unit.id, unit)
            sourceMappingOf(unit.id, targetMethod.source)?.let { mapping ->
                sourceMappings.putIfAbsent(mapping.targetUnitId, mapping)
            }
            val bridgeUnit = ResourceUnit(
                id = SemanticIdFactory.resourceUnitId(
                    resourceKind = "architecture-relation",
                    subjectId = relation.id,
                ),
                title = edgeTitle(relation),
                resourceKind = "CONFIG_ITEM",
                metadata = relationMetadata(relation, edgeType),
            )
            semanticUnits.putIfAbsent(bridgeUnit.id, bridgeUnit)
            sourceMappingOf(bridgeUnit.id, relation.samples.firstOrNull())?.let { mapping ->
                sourceMappings.putIfAbsent(mapping.targetUnitId, mapping)
            }
            putRelation(
                relations,
                SemanticRelation(
                    kind = SemanticRelationKind.REFERENCES,
                    fromUnitId = ownerUnitId,
                    toUnitId = bridgeUnit.id,
                    label = edgeType.name,
                    metadata = relationMetadata(relation, edgeType),
                ),
            )
            putRelation(
                relations,
                SemanticRelation(
                    kind = SemanticRelationKind.REFERENCES,
                    fromUnitId = bridgeUnit.id,
                    toUnitId = unit.id,
                    label = edgeType.name,
                    metadata = relationMetadata(relation, edgeType),
                ),
            )
        }

        // 当前方法或其宿主类在架构索引中的全部直接外向关系
        val directOwnerRelations = index.relationIndex.outgoing(ownerClass.id) + index.relationIndex.outgoing(ownerMethod.id)
        // 在直接关系基础上，把 Feign 客户端调用继续向下游推进到其路由目标，形成完整 HTTP 调用链
        val ownerRelations = (directOwnerRelations + directOwnerRelations.flatMap { relation ->
            when (relation.kind) {
                JvmRelationKind.FEIGN_CLIENT_CALLS -> index.relationIndex.outgoing(relation.toSymbolId)
                    .filter { targetRelation -> targetRelation.kind == JvmRelationKind.FEIGN_ROUTES_TO }
                else -> emptyList()
            }
        })
            // 只保留能够增强控制流可见性的关系类型，过滤掉注解等噪声
            .filter { relation -> relation.kind in FLOW_ENHANCEMENT_RELATION_KINDS }
            // 对调用关系进一步按行号过滤，确保采样证据落在当前方法体内
            .filter { relation ->
                relation.kind != JvmRelationKind.CALLS || relation.samples.any { sample ->
                    sample.startLine == null ||
                        ownerMethod.source?.startLine == null ||
                        ownerMethod.source?.endLine == null ||
                        sample.startLine in ownerMethod.source.startLine..ownerMethod.source.endLine
                }
            }
            // 当关系元数据标明了具体来源方法时，要求与当前方法签名一致，避免把整类的元信息误挂到单个方法上
            .filter { relation -> relation.metadata["reflect.sourceMethod"] == null || relation.metadata["reflect.sourceMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["spring.publisherMethod"] == null || relation.metadata["spring.publisherMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["service.loader.sourceMethod"] == null || relation.metadata["service.loader.sourceMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["proxy.sourceMethod"] == null || relation.metadata["proxy.sourceMethod"] == ownerSignature }
            .distinctBy(JvmRelation::id)

        // 按关系类型分发到对应的展开策略：方法级目标会得到三段式语义边，
        // 类级候选则统一作为资源单元呈现，便于在图谱中聚合展示。
        ownerRelations.forEach { relation ->
            when (relation.kind) {
                JvmRelationKind.CALLS -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.CALL)
                JvmRelationKind.REFLECTS_TO -> {
                    val targetMethod = index.findSymbol(relation.toSymbolId) as? JvmMethodSymbol
                    if (targetMethod != null) {
                        addMethodTarget(targetMethod, relation, EdgeType.REFLECTS_TO)
                    } else {
                        addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.REFLECTS_TO)
                    }
                }
                JvmRelationKind.TESTS -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.TESTS)
                JvmRelationKind.SPI_PROVIDES,
                JvmRelationKind.SERVICE_LOADER_LOADS,
                -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.SPI_RESOLVES_TO)
                JvmRelationKind.USES_PROXY -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.USES_PROXY)
                JvmRelationKind.INJECTS -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.INJECT)
                JvmRelationKind.SPRING_EVENT_LISTENS,
                JvmRelationKind.SPRING_EVENT_PUBLISHES,
                -> {
                    val listenerMethod = relation.metadata["spring.listenerMethod"]?.let(index::findMethod)
                    if (listenerMethod != null) {
                        addMethodTarget(listenerMethod, relation, EdgeType.PUBLISHES_TO)
                    } else {
                        addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.PUBLISHES_TO)
                    }
                }
                JvmRelationKind.DUBBO_REFERENCES -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.USES_PROXY)
                JvmRelationKind.DUBBO_PROVIDES -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.SPI_RESOLVES_TO)
                JvmRelationKind.FEIGN_CLIENT_CALLS -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.USES_PROXY)
                JvmRelationKind.FEIGN_ROUTES_TO -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.ROUTES_TO)
                JvmRelationKind.MQ_PUBLISHES -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.PUBLISHES_TO)
                JvmRelationKind.MQ_CONSUMES -> addClassCandidate(semanticUnits, sourceMappings, relations, ownerUnitId, relation, EdgeType.CONSUMES_FROM)
                else -> Unit
            }
        }

        return RelationExtraction(
            semanticUnits = semanticUnits.values.toList(),
            relations = relations.values.toList(),
            additionalMethods = ownerRelations.flatMap { relation ->
                methodCandidatesForRelation(context, relation, index)
            }.distinctBy { candidate -> methodSignature(candidate) },
            sourceMappings = sourceMappings.values.toList(),
        )
    }

    /**
     * 把无法精确定位到具体方法的目标关系展开为单个候选资源单元，并连一条 owner -> 候选 的语义边，
     * 让用户能在图谱中看到候选项，再自行下钻确认。
     */
    private fun addClassCandidate(
        semanticUnits: MutableMap<String, com.charmnight.linkgraph.semantic.model.SemanticUnit>,
        sourceMappings: MutableMap<String, SourceMapping>,
        relations: MutableMap<String, SemanticRelation>,
        ownerUnitId: String,
        relation: JvmRelation,
        edgeType: EdgeType,
    ) {
        val unit = ResourceUnit(
            id = SemanticIdFactory.resourceUnitId(
                resourceKind = "architecture-candidate",
                subjectId = relation.id,
            ),
            title = edgeTitle(relation),
            resourceKind = "CONFIG_ITEM",
            metadata = relationMetadata(relation, edgeType),
        )
        semanticUnits.putIfAbsent(unit.id, unit)
        sourceMappingOf(unit.id, relation.samples.firstOrNull())?.let { mapping ->
            sourceMappings.putIfAbsent(mapping.targetUnitId, mapping)
        }
        putRelation(
            relations,
            SemanticRelation(
                kind = SemanticRelationKind.REFERENCES,
                fromUnitId = ownerUnitId,
                toUnitId = unit.id,
                label = edgeType.name,
                metadata = relationMetadata(relation, edgeType),
            ),
        )
    }

    /**
     * 根据关系类型给出在图谱节点上显示的中文标题，统一各种调用、注入、事件、消息等场景的展示文案。
     */
    private fun edgeTitle(relation: JvmRelation): String =
        when (relation.kind) {
            JvmRelationKind.REFLECTS_TO -> "反射目标"
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            -> "SPI 候选"
            JvmRelationKind.INJECTS -> "注入候选"
            JvmRelationKind.USES_PROXY -> "代理候选"
            JvmRelationKind.ANNOTATED_BY -> "注解关系"
            JvmRelationKind.CALLS -> "调用候选"
            JvmRelationKind.TESTS -> "相关测试"
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            -> "事件监听"
            JvmRelationKind.DUBBO_PROVIDES -> "Dubbo 服务"
            JvmRelationKind.DUBBO_REFERENCES -> "Dubbo 引用"
            JvmRelationKind.FEIGN_CLIENT_CALLS -> "Feign 客户端"
            JvmRelationKind.FEIGN_ROUTES_TO -> "HTTP 路由"
            JvmRelationKind.MQ_PUBLISHES -> "消息发布"
            JvmRelationKind.MQ_CONSUMES -> "消息消费"
            else -> relation.kind.name
        }

    /**
     * 对于需要在图谱中"顺藤摸瓜"继续展开的关系，从架构索引反向找到对应的 PsiMethod 候选，
     * 用作关系跳转的额外入口；类目标会取其前若干个方法作为代表，避免一次性返回过多方法。
     */
    private fun methodCandidatesForRelation(
        context: RelationExtractionContext,
        relation: JvmRelation,
        index: com.charmnight.linkgraph.architecture.ArchitectureGraphIndex,
    ): List<PsiMethod> {
        if (relation.kind !in ADDITIONAL_METHOD_RELATION_KINDS) {
            return emptyList()
        }
        val targetMethod = index.findSymbol(relation.toSymbolId) as? JvmMethodSymbol
        if (targetMethod != null) {
            return listOfNotNull(context.methodBySignature(targetMethod.signature))
        }
        val targetClass = index.findSymbol(relation.toSymbolId) as? com.charmnight.linkgraph.jvm.index.JvmClassSymbol
            ?: return emptyList()
        return index.symbolIndex.methodsBySignature.values
            .filter { methodSymbol -> methodSymbol.ownerClassName == targetClass.qualifiedName }
            .filter { methodSymbol ->
                relation.metadata["spring.listenerMethod"] == null || methodSymbol.signature == relation.metadata["spring.listenerMethod"]
            }
            .mapNotNull { methodSymbol -> context.methodBySignature(methodSymbol.signature) }
            .take(5)
    }

    /**
     * 合并原始关系元数据与图谱视图所需的标准化字段（关系 id、类型、置信度、来源、边类型等），
     * 让下游渲染层无需感知底层关系模型就能直接读取关键字段。
     */
    private fun relationMetadata(
        relation: JvmRelation,
        edgeType: EdgeType,
    ): Map<String, String> =
        relation.metadata + mapOf(
            "jvm.relation.id" to relation.id,
            "jvm.relation.kind" to relation.kind.name,
            "jvm.relation.confidence" to relation.confidence.name,
            "jvm.relation.source" to relation.source.name,
            "relation.confidence" to relation.confidence.name,
            "relation.resolverId" to (relation.metadata["relation.resolverId"] ?: "architecture-index"),
            "relation.edgeType" to edgeType.name,
        )

    /** 把 JVM 源码引用转换为语义单元的源码映射，源码缺失时返回 null。 */
    private fun sourceMappingOf(
        targetUnitId: String,
        source: com.charmnight.linkgraph.jvm.index.JvmSourceRef?,
    ): SourceMapping? {
        val ref = source ?: return null
        return SourceMapping(
            sourcePath = ref.displayPath,
            sourceRange = SourceRange(
                startOffset = 0,
                endOffset = 0,
                startLine = ref.startLine,
                endLine = ref.endLine,
            ),
            targetUnitId = targetUnitId,
        )
    }

    /** 把关系采样证据文件位置转换为语义单元的源码映射，缺失文件路径时返回 null。 */
    private fun sourceMappingOf(
        targetUnitId: String,
        evidence: com.charmnight.linkgraph.jvm.relation.JvmEvidenceRef?,
    ): SourceMapping? {
        val filePath = evidence?.filePath ?: return null
        return SourceMapping(
            sourcePath = filePath,
            sourceRange = SourceRange(
                startOffset = 0,
                endOffset = 0,
                startLine = evidence.startLine,
                endLine = evidence.endLine,
            ),
            targetUnitId = targetUnitId,
        )
    }

    /** 用关系类型、两端单元、标签以及原始 JVM 关系 id 组成复合键去重，避免重复边污染图谱。 */
    private fun putRelation(
        relations: MutableMap<String, SemanticRelation>,
        relation: SemanticRelation,
    ) {
        // 复合 key：包含语义层和原始 JVM 关系 id，确保不同来源的同方向边不会互相覆盖
        val key = listOf(
            relation.kind.name,
            relation.fromUnitId,
            relation.toUnitId,
            relation.label.orEmpty(),
            relation.metadata["jvm.relation.id"].orEmpty(),
        ).joinToString("|")
        relations.putIfAbsent(key, relation)
    }

    private companion object {
        // 参与控制流增强的关系类型集合：这些关系能贡献方法图谱中的真实拓扑边
        private val FLOW_ENHANCEMENT_RELATION_KINDS = setOf(
            JvmRelationKind.INJECTS,
            JvmRelationKind.CALLS,
            JvmRelationKind.TESTS,
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            JvmRelationKind.DUBBO_PROVIDES,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.FEIGN_ROUTES_TO,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.MQ_CONSUMES,
        )
        // 在图谱展开时需要顺带提供方法级候选的关系类型集合，用于支持跳转和下钻
        private val ADDITIONAL_METHOD_RELATION_KINDS = setOf(
            JvmRelationKind.CALLS,
            JvmRelationKind.TESTS,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.FEIGN_ROUTES_TO,
        )
    }
}
