package com.charmnight.linkgraph.semantic.provider.resource

import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectKind

/**
 * 提供 YAML 配置项资源的语义分析能力。
 *
 * 适用于 application.yml 等 YAML 配置文件。本 Provider 把配置项解析为资源单元，
 * 让图可以展示"哪些代码绑定到了哪个配置项"。
 */
class YamlPropertiesSemanticProvider : AbstractResourceSemanticProvider(ResourceSubjectKind.CONFIG_ITEM) {
    /**
     * 返回当前 Provider 对应的资源类型字符串。
     */
    override fun resourceKind(handle: ResourceSubjectHandle): String = ResourceSubjectKind.CONFIG_ITEM.name
}
