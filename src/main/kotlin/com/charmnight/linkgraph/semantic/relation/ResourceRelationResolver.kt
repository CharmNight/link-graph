package com.charmnight.linkgraph.semantic.relation

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 根据资源主题种类生成资源到方法的语义关系。
 */
class ResourceRelationResolver : RelationResolver {
    /**
     * 解析资源单元与目标方法单元之间的关系。
     */
    override fun resolve(
        handle: ResourceSubjectHandle,
        resourceUnit: ResourceUnit,
        targetMethodUnit: MethodLikeUnit?,
    ): List<SemanticRelation> {
        // 缺少目标方法时无法建立资源到方法的关系。
        val target = targetMethodUnit ?: return emptyList()
        // 不同资源种类映射到不同的语义关系类型。
        val kind = when (handle.kind) {
            ResourceSubjectKind.MYBATIS_STATEMENT -> SemanticRelationKind.BINDS_TO
            ResourceSubjectKind.CONFIG_ITEM -> SemanticRelationKind.BINDS_TO
            ResourceSubjectKind.MARKDOWN_PAGE -> SemanticRelationKind.DOCUMENTS
            ResourceSubjectKind.SQL_FILE,
            ResourceSubjectKind.XML_RESOURCE -> SemanticRelationKind.REFERENCES
        }
        // 当前实现每次只生成一条资源到目标方法的关系。
        return listOf(
            SemanticRelation(
                kind = kind,
                fromUnitId = resourceUnit.id,
                toUnitId = target.id,
                label = handle.kind.name,
            ),
        )
    }
}
