package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 SQL 文件资源的语义分析能力。
 */
class SqlSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.SQL_FILE) {
    /**
     * 返回当前 Provider 对应的资源类型。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.SQL_FILE.name
}
