package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 YAML 配置项资源的语义分析能力。
 */
class YamlPropertiesSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.CONFIG_ITEM) {
    /**
     * 返回当前 Provider 对应的资源类型。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.CONFIG_ITEM.name
}
