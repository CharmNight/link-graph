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

class ArchitectureIndexSemanticResolver : CodeRelationSemanticResolver {
    override fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction {
        val index = context.architectureIndex() ?: return RelationExtraction()
        val ownerSignature = methodSignature(method)
        val ownerMethod = index.findMethod(ownerSignature) ?: return RelationExtraction()
        val ownerClass = index.findClass(ownerMethod.ownerClassName) ?: return RelationExtraction()
        val ownerUnitId = SemanticIdFactory.methodUnitId(ownerSignature)
        val semanticUnits = linkedMapOf<String, com.charmnight.linkgraph.semantic.model.SemanticUnit>()
        val relations = linkedMapOf<String, SemanticRelation>()
        val sourceMappings = linkedMapOf<String, SourceMapping>()

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

        val directOwnerRelations = index.relationIndex.outgoing(ownerClass.id) + index.relationIndex.outgoing(ownerMethod.id)
        val ownerRelations = (directOwnerRelations + directOwnerRelations.flatMap { relation ->
            when (relation.kind) {
                JvmRelationKind.FEIGN_CLIENT_CALLS -> index.relationIndex.outgoing(relation.toSymbolId)
                    .filter { targetRelation -> targetRelation.kind == JvmRelationKind.FEIGN_ROUTES_TO }
                else -> emptyList()
            }
        })
            .filter { relation -> relation.kind in FLOW_ENHANCEMENT_RELATION_KINDS }
            .filter { relation ->
                relation.kind != JvmRelationKind.CALLS || relation.samples.any { sample ->
                    sample.startLine == null ||
                        ownerMethod.source?.startLine == null ||
                        ownerMethod.source?.endLine == null ||
                        sample.startLine in ownerMethod.source.startLine..ownerMethod.source.endLine
                }
            }
            .filter { relation -> relation.metadata["reflect.sourceMethod"] == null || relation.metadata["reflect.sourceMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["spring.publisherMethod"] == null || relation.metadata["spring.publisherMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["service.loader.sourceMethod"] == null || relation.metadata["service.loader.sourceMethod"] == ownerSignature }
            .filter { relation -> relation.metadata["proxy.sourceMethod"] == null || relation.metadata["proxy.sourceMethod"] == ownerSignature }
            .distinctBy(JvmRelation::id)

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

    private fun putRelation(
        relations: MutableMap<String, SemanticRelation>,
        relation: SemanticRelation,
    ) {
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
