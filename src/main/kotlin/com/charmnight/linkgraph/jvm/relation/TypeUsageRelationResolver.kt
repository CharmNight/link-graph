package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod

/**
 * JVM 类型使用关系解析器。
 *
 * 委托给 [ClassDiagramRelationExtractor] 抽取字段/参数/返回值等位置上对其他类型的引用，
 * 把它们转换为 USES_TYPE 关系。
 * 这种关系让类图能展示"哪些类用到了某个类型"。
 */
class TypeUsageRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识。 */
    override val id: String = "jvm.type-usage"

    /** 委托给类图关系提取器，复用其类型关系抽取逻辑。 */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return ClassDiagramRelationExtractor.extractPsiTypeRelations(context)
    }
}

/**
 * JVM 依赖注入关系解析器。
 *
 * 扫描项目中带有 @Autowired / @Inject / @Resource 注解的字段、方法、构造器，
 * 把它们与对应类型之间建立 INJECTS 关系。
 *
 * 同时支持构造器隐式注入：如果某类只有一个构造器，即使没有显式注入注解，
 * 也按注入处理（Spring 的常见约定）。
 *
 * 这种关系让架构图能展示"哪些组件依赖注入了哪个服务"。
 */
class InjectionRelationResolver : JvmRelationResolver {
    /** 解析器唯一标识。 */
    override val id: String = "jvm.injection"

    /** 在 JVM 解析上下文中提取注入关系。 */
    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        // 注入注解的简单名集合（按 short name 匹配，避免依赖具体包路径）
        val injectionAnnotations = setOf("Autowired", "Inject", "Resource")
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            // 候选 (元素, 目标类) 对：元素是字段/参数等被注入的位置
            val candidates = mutableListOf<Pair<PsiElement, JvmClassSymbol>>()
            // 字段注入
            psiClass.fields.forEach { field ->
                if (field.hasInjectionAnnotation(injectionAnnotations)) {
                    context.symbolIndex.classByTypeNear(field.type, classSymbol.packageName)
                        ?.let { target -> candidates += field to target }
                }
            }
            // 方法注入（@Autowired 标注在方法上时，按参数类型注入）
            psiClass.methods.forEach { method ->
                if (method.hasInjectionAnnotation(injectionAnnotations)) {
                    method.parameterList.parameters.forEach { parameter ->
                        context.symbolIndex.classByTypeNear(parameter.type, classSymbol.packageName)
                            ?.let { target -> candidates += parameter to target }
                    }
                }
            }
            // 构造器注入：含注入注解，或类只有一个构造器（隐式注入）
            psiClass.constructors.forEach { constructor ->
                val annotated = constructor.hasInjectionAnnotation(injectionAnnotations)
                if (annotated || psiClass.constructors.size == 1) {
                    constructor.parameterList.parameters.forEach { parameter ->
                        context.symbolIndex.classByTypeNear(parameter.type, classSymbol.packageName)
                            ?.let { target -> candidates += parameter to target }
                    }
                }
            }
            // 按目标类分组，把同一目标的多次注入合并为一条关系
            candidates
                .mapNotNull { (element, target) ->
                    // 自注入无意义，跳过
                    if (target.id == classSymbol.id) return@mapNotNull null
                    target to element
                }
                .groupBy({ it.first.id }, { it })
                .forEach { (_, grouped) ->
                    val target = grouped.first().first
                    relations += relation(
                        kind = JvmRelationKind.INJECTS,
                        from = classSymbol,
                        to = target,
                        // 基于规则推断，不是 PSI 直接证明
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.FRAMEWORK_RULE,
                        evidence = grouped.first().second.evidence("injects ${target.qualifiedName}", classSymbol.source),
                        // count 反映同一目标被注入几次（多个字段/参数都注入同一类型）
                        count = grouped.size,
                    )
                }
        }
        return relations
    }

    /** PsiField 是否带有任一注入注解（按简单名匹配）。 */
    private fun PsiField.hasInjectionAnnotation(simpleNames: Set<String>): Boolean =
        annotations.any { annotation -> annotation.qualifiedName?.substringAfterLast('.') in simpleNames }

    /** PsiMethod 是否带有任一注入注解（按简单名匹配）。 */
    private fun PsiMethod.hasInjectionAnnotation(simpleNames: Set<String>): Boolean =
        annotations.any { annotation -> annotation.qualifiedName?.substringAfterLast('.') in simpleNames }
}
