package com.charmnight.linkgraph.semantic.relation

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticRelationKind
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 根据资源主题种类生成资源到方法的语义关系。
 *
 * 不同资源种类（MyBatis 语句、配置项、Markdown 文档、SQL、XML 等）
 * 与方法的关系语义不同，本类按种类映射到对应的 [SemanticRelationKind]，
 * 让图能展示准确的"资源 ↔ 方法"关系。
 */
class ResourceRelationResolver : RelationResolver {
    /**
     * 解析资源单元与目标方法单元之间的关系。
     *
     * @param handle 资源主题句柄（提供种类信息）
     * @param resourceUnit 资源单元
     * @param targetMethodUnit 目标方法单元；为 null 时不生成任何关系
     * @return 关系列表；当前实现每次只生成一条
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
            // MyBatis 语句和配置项与方法是"绑定"关系
            ResourceSubjectKind.MYBATIS_STATEMENT -> SemanticRelationKind.BINDS_TO
            ResourceSubjectKind.CONFIG_ITEM -> SemanticRelationKind.BINDS_TO
            // Markdown 文档与方法是"文档化"关系
            ResourceSubjectKind.MARKDOWN_PAGE -> SemanticRelationKind.DOCUMENTS
            // SQL 文件和 XML 资源与方法是"引用"关系
            ResourceSubjectKind.SQL_FILE,
            ResourceSubjectKind.XML_RESOURCE -> SemanticRelationKind.REFERENCES
        }
        // 当前实现每次只生成一条资源到目标方法的关系。
        return listOf(
            SemanticRelation(
                kind = kind,
                fromUnitId = resourceUnit.id,
                toUnitId = target.id,
                // label 用资源种类名，便于在 UI 上区分
                label = handle.kind.name,
            ),
        )
    }
}
