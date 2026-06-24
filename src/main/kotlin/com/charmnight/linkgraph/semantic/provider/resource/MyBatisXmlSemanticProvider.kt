package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 MyBatis XML 语句资源的语义分析能力。
 *
 * MyBatis 通过 XML 文件定义 SQL 语句，本 Provider 把这些语句解析为资源单元，
 * 让图中可以展示 SQL 与 Mapper 方法之间的映射关系。
 */
class MyBatisXmlSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.MYBATIS_STATEMENT) {
    /**
     * 返回当前 Provider 对应的资源类型字符串。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.MYBATIS_STATEMENT.name
}
