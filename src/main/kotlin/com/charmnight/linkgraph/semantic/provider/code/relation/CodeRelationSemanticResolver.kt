package com.charmnight.linkgraph.semantic.provider.code.relation

import com.intellij.psi.PsiMethod

/**
 * 负责从代码方法中解析语义关系。
 */
interface CodeRelationSemanticResolver {
    /**
     * 基于方法 PSI 和解析上下文提取语义关系。
     */
    fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction
}
