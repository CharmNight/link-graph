package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 Markdown 文档资源的语义分析能力。
 */
class MarkdownSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.MARKDOWN_PAGE) {
    /**
     * 返回当前 Provider 对应的资源类型。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.MARKDOWN_PAGE.name
}
