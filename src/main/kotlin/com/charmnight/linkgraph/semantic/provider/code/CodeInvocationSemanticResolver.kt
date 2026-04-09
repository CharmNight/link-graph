package com.charmnight.linkgraph.semantic.provider.code

import com.charmnight.linkgraph.semantic.model.SemanticRelation
import com.charmnight.linkgraph.semantic.model.SemanticUnit
import com.charmnight.linkgraph.semantic.model.SourceMapping
import com.charmnight.linkgraph.semantic.provider.code.relation.CodeRelationSemanticResolver
import com.charmnight.linkgraph.semantic.provider.code.relation.RelationExtractionContext
import com.charmnight.linkgraph.semantic.provider.code.relation.ResourceBindingSemanticResolver
import com.intellij.psi.PsiMethod

/**
 * 表示代码调用关系扩展解析后的汇总结果。
 */
data class CodeInvocationSemanticResolution(
    /** 保存新增的语义单元。 */
    val semanticUnits: List<SemanticUnit> = emptyList(),
    /** 保存新增的语义关系。 */
    val relations: List<SemanticRelation> = emptyList(),
    /** 保存解析过程中补充发现的方法。 */
    val additionalMethods: List<PsiMethod> = emptyList(),
    /** 保存新增的源码映射。 */
    val sourceMappings: List<SourceMapping> = emptyList(),
)

/**
 * 代码语义层的资源/集成关系扩展点。
 *
 * 当前版本先完成与 legacy extract 的边界切断，把关系扩展收口到统一语义协议，
 * 后续在这里继续补充 MyBatis、HTTP、MQ、文档等一等关系生产器。
 */
class CodeInvocationSemanticResolver(
    /** 保存当前启用的关系解析器列表。 */
    private val relationResolvers: List<CodeRelationSemanticResolver> = listOf(
        ResourceBindingSemanticResolver(),
    ),
) {
    /**
     * 汇总所有关系解析器的结果，并做去重合并。
     */
    fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): CodeInvocationSemanticResolution {
        // 统一用有序映射收集结果，既能去重，又能保留稳定输出顺序。
        val units = linkedMapOf<String, SemanticUnit>()
        val relations = linkedMapOf<String, SemanticRelation>()
        val additionalMethods = linkedMapOf<String, PsiMethod>()
        val sourceMappings = linkedMapOf<String, SourceMapping>()

        relationResolvers.forEach { resolver ->
            // 每个解析器只负责一类关系，最终在这里汇总。
            val extraction = resolver.resolve(method, context)
            extraction.semanticUnits.forEach { unit -> units.putIfAbsent(unit.id, unit) }
            extraction.relations.forEach { relation ->
                // 用关系关键字段拼出唯一键，避免同一关系被多次加入。
                val key = listOf(relation.kind.name, relation.fromUnitId, relation.toUnitId, relation.label.orEmpty()).joinToString("|")
                relations.putIfAbsent(key, relation)
            }
            extraction.additionalMethods.forEach { additionalMethod ->
                additionalMethods.putIfAbsent(additionalMethod.name + "|" + additionalMethod.textOffset, additionalMethod)
            }
            extraction.sourceMappings.forEach { mapping ->
                sourceMappings.putIfAbsent(mapping.targetUnitId, mapping)
            }
        }

        // 返回去重后的统一解析结果，供上层语义分析器继续组装。
        return CodeInvocationSemanticResolution(
            semanticUnits = units.values.toList(),
            relations = relations.values.toList(),
            additionalMethods = additionalMethods.values.toList(),
            sourceMappings = sourceMappings.values.toList(),
        )
    }
}
