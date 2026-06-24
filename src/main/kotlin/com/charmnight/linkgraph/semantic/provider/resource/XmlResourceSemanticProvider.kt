package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供普通 XML 资源的语义分析能力。
 *
 * 不属于 MyBatis、Spring 等具体框架的 XML 文件（例如自定义配置 XML）走本 Provider，
 * 让它们也能在资源关系图中以 XML 资源节点出现。
 */
class XmlResourceSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.XML_RESOURCE) {
    /**
     * 返回当前 Provider 对应的资源类型字符串。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.XML_RESOURCE.name
}
