package com.charmnight.linkgraph.semantic.relation

import com.charmnight.linkgraph.semantic.model.MethodLikeUnit
import com.charmnight.linkgraph.semantic.model.ResourceUnit
import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.subject.ResourceSubjectHandle

/**
 * 负责从资源主题中解析补充关系。
 *
 * 资源（SQL、HTTP 等）与代码方法之间的关系通常需要专门解析（按注解、按配置等），
 * 本接口让不同资源类型各自实现，解析管线按需调用。
 */
interface RelationResolver {
    /**
     * 根据资源主题、资源单元和目标方法单元生成语义关系。
     *
     * @param handle 资源主题句柄
     * @param resourceUnit 资源单元（具体资源对象）
     * @param targetMethodUnit 目标方法单元；为 null 表示尚未定位到具体方法
     * @return 解析出的语义关系列表
     */
    fun resolve(
        handle: ResourceSubjectHandle,
        resourceUnit: ResourceUnit,
        targetMethodUnit: MethodLikeUnit?,
    ): List<SemanticRelation>
}
