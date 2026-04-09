package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供普通 XML 资源的语义分析能力。
 */
class XmlResourceSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.XML_RESOURCE) {
    /**
     * 返回当前 Provider 对应的资源类型。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.XML_RESOURCE.name
}
