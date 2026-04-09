package com.charmnight.linkgraph.semantic.relation

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle

/**
 * 负责从资源主题中解析补充关系。
 */
interface RelationResolver {
    /**
     * 根据资源主题、资源单元和目标方法单元生成语义关系。
     */
    fun resolve(
        handle: ResourceSubjectHandle,
        resourceUnit: ResourceUnit,
        targetMethodUnit: MethodLikeUnit?,
    ): List<SemanticRelation>
}
