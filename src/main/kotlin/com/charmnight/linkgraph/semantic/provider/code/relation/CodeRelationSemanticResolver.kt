package com.charmnight.linkgraph.semantic.provider.code.relation

import com.intellij.psi.PsiMethod

/**
 * 负责从代码方法中解析语义关系。
 *
 * 实现类针对特定关系类型（调用、注入、继承等）做提取；
 * 解析管线会收集所有实现并按需调用，让"新增一种关系类型"只需新增一个 resolver。
 */
interface CodeRelationSemanticResolver {
    /**
     * 基于方法 PSI 和解析上下文提取语义关系。
     *
     * @param method 当前正在分析的方法 PSI
     * @param context 解析上下文（已索引的类、当前作用域等）
     * @return 提取到的关系集合
     */
    fun resolve(
        method: PsiMethod,
        context: RelationExtractionContext,
    ): RelationExtraction
}
