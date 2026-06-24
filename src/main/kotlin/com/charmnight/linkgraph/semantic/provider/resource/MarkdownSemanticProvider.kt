package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 Markdown 文档资源的语义分析能力。
 *
 * Markdown 文档在项目中通常作为设计稿/说明文档存在，本 Provider 让它们
 * 也能进入语义分析流程（例如建立文档与代码概念的关联）。
 */
class MarkdownSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.MARKDOWN_PAGE) {
    /**
     * 返回当前 Provider 对应的资源类型字符串。
     * 默认与构造时传入的种类一致。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.MARKDOWN_PAGE.name
}
