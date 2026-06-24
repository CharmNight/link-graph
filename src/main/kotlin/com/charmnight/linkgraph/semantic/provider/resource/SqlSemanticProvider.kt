package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 SQL 文件资源的语义分析能力。
 *
 * 适用于纯 SQL 文件（非 MyBatis XML 中的语句）。本 Provider 让 SQL 文件也能进入
 * 语义分析流程，便于在资源关系图中展示 SQL 与代码方法的调用关联。
 */
class SqlSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.SQL_FILE) {
    /**
     * 返回当前 Provider 对应的资源类型字符串。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.SQL_FILE.name
}
