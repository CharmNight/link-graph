package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod

class TypeUsageRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.type-usage"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        return ClassDiagramRelationExtractor.extractPsiTypeRelations(context)
    }
}

class InjectionRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.injection"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val injectionAnnotations = setOf("Autowired", "Inject", "Resource")
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            val candidates = mutableListOf<Pair<PsiElement, JvmClassSymbol>>()
            psiClass.fields.forEach { field ->
                if (field.hasInjectionAnnotation(injectionAnnotations)) {
                    context.symbolIndex.classByTypeNear(field.type, classSymbol.packageName)
                        ?.let { target -> candidates += field to target }
                }
            }
            psiClass.methods.forEach { method ->
                if (method.hasInjectionAnnotation(injectionAnnotations)) {
                    method.parameterList.parameters.forEach { parameter ->
                        context.symbolIndex.classByTypeNear(parameter.type, classSymbol.packageName)
                            ?.let { target -> candidates += parameter to target }
                    }
                }
            }
            psiClass.constructors.forEach { constructor ->
                val annotated = constructor.hasInjectionAnnotation(injectionAnnotations)
                if (annotated || psiClass.constructors.size == 1) {
                    constructor.parameterList.parameters.forEach { parameter ->
                        context.symbolIndex.classByTypeNear(parameter.type, classSymbol.packageName)
                            ?.let { target -> candidates += parameter to target }
                    }
                }
            }
            candidates
                .mapNotNull { (element, target) ->
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
                        confidence = JvmRelationConfidence.RULE_INFERRED,
                        source = JvmRelationSource.FRAMEWORK_RULE,
                        evidence = grouped.first().second.evidence("injects ${target.qualifiedName}", classSymbol.source),
                        count = grouped.size,
                    )
                }
        }
        return relations
    }

    private fun PsiField.hasInjectionAnnotation(simpleNames: Set<String>): Boolean =
        annotations.any { annotation -> annotation.qualifiedName?.substringAfterLast('.') in simpleNames }

    private fun PsiMethod.hasInjectionAnnotation(simpleNames: Set<String>): Boolean =
        annotations.any { annotation -> annotation.qualifiedName?.substringAfterLast('.') in simpleNames }
}
