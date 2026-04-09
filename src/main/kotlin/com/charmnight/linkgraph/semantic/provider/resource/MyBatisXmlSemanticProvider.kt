package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 MyBatis XML 语句资源的语义分析能力。
 */
class MyBatisXmlSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.MYBATIS_STATEMENT) {
    /**
     * 返回当前 Provider 对应的资源类型。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.MYBATIS_STATEMENT.name
}
