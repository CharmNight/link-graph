package com.charmnight.linkgraph.jvm.relation

class JvmRelationIndex(
    relations: List<JvmRelation> = emptyList(),
    maxRelations: Int = Int.MAX_VALUE,
) {
    val truncated: Boolean = relations.size > maxRelations
    val relations: List<JvmRelation> = relations
        .sortedWith(
            compareBy<JvmRelation> { relation -> relation.kind.retentionPriority() }
                .thenBy { relation -> relation.confidence.ordinal }
                .thenBy { relation -> relation.id },
        )
        .take(maxRelations)
    val relationsBySourceId: Map<String, List<JvmRelation>> = relations.groupBy { relation -> relation.fromSymbolId }
    val relationsByTargetId: Map<String, List<JvmRelation>> = relations.groupBy { relation -> relation.toSymbolId }
    val relationsByKind: Map<JvmRelationKind, List<JvmRelation>> = relations.groupBy { relation -> relation.kind }

    fun outgoing(symbolId: String): List<JvmRelation> = relationsBySourceId[symbolId].orEmpty()

    fun incoming(symbolId: String): List<JvmRelation> = relationsByTargetId[symbolId].orEmpty()

    fun byKind(kind: JvmRelationKind): List<JvmRelation> = relationsByKind[kind].orEmpty()

    fun findReflectiveTargets(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.REFLECTS_TO }

    fun serviceLoaderLoads(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.SERVICE_LOADER_LOADS }

    fun proxyTargets(symbolId: String): List<JvmRelation> =
        outgoing(symbolId).filter { relation -> relation.kind == JvmRelationKind.USES_PROXY }

    private fun JvmRelationKind.retentionPriority(): Int =
        when (this) {
            JvmRelationKind.EXTENDS,
            JvmRelationKind.IMPLEMENTS,
            JvmRelationKind.INJECTS,
            JvmRelationKind.SPI_PROVIDES,
            JvmRelationKind.SERVICE_LOADER_LOADS,
            JvmRelationKind.REFLECTS_TO,
            JvmRelationKind.TESTS,
            JvmRelationKind.USES_PROXY,
            JvmRelationKind.SPRING_EVENT_PUBLISHES,
            JvmRelationKind.SPRING_EVENT_LISTENS,
            JvmRelationKind.DUBBO_PROVIDES,
            JvmRelationKind.DUBBO_REFERENCES,
            JvmRelationKind.FEIGN_CLIENT_CALLS,
            JvmRelationKind.FEIGN_ROUTES_TO,
            JvmRelationKind.MQ_PUBLISHES,
            JvmRelationKind.MQ_CONSUMES,
            -> 0
            JvmRelationKind.ANNOTATED_BY,
            JvmRelationKind.RESOURCE_BINDS,
            -> 1
            JvmRelationKind.CALLS,
            JvmRelationKind.USES_TYPE,
            -> 2
            JvmRelationKind.MODULE_CONTAINS_PACKAGE,
            JvmRelationKind.PACKAGE_CONTAINS_CLASS,
            -> 3
        }
}
