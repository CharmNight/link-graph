package com.charmnight.linkgraph.jvm.relation

class CallAggregationRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.call-aggregation"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return ClassDiagramRelationExtractor.extractMethodCallRelations(context)
    }
}
