package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.canonicalTypeText
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypeElement

class TypeUsageRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.type-usage"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            val targets = linkedMapOf<String, MutableList<PsiElement>>()
            fun addTarget(targetName: String?, evidenceElement: PsiElement) {
                val target = context.symbolIndex.classByQualifiedName(targetName) ?: return
                if (target.id == classSymbol.id) {
                    return
                }
                targets.getOrPut(target.id) { mutableListOf() } += evidenceElement
            }

            psiClass.fields.forEach { field ->
                addTarget(canonicalTypeText(field.type), field)
            }
            psiClass.methods.forEach { method ->
                addTarget(canonicalTypeText(method.returnType), method)
                method.parameterList.parameters.forEach { parameter ->
                    addTarget(canonicalTypeText(parameter.type), parameter)
                }
                method.throwsList.referencedTypes.forEach { thrownType ->
                    addTarget(canonicalTypeText(thrownType), method.throwsList)
                }
            }
            psiClass.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitTypeElement(type: PsiTypeElement) {
                        addTarget(canonicalTypeText(type.type), type)
                        super.visitTypeElement(type)
                    }

                    override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                        val resolvedClass = reference.resolve() as? PsiClass
                        addTarget(resolvedClass?.qualifiedName, reference)
                        super.visitReferenceElement(reference)
                    }
                },
            )

            targets.forEach { (targetId, evidenceElements) ->
                val target = context.symbolIndex.findSymbol(targetId) ?: return@forEach
                relations += relation(
                    kind = JvmRelationKind.USES_TYPE,
                    from = classSymbol,
                    to = target,
                    confidence = JvmRelationConfidence.PROVEN,
                    source = JvmRelationSource.PSI,
                    evidence = evidenceElements.firstOrNull()
                        ?.evidence("uses type ${target.qualifiedName}", classSymbol.source)
                        ?: classSymbol.evidence("uses type ${target.qualifiedName}"),
                    count = evidenceElements.size,
                    metadata = mapOf("usage.count" to evidenceElements.size.toString()),
                )
            }
        }
        return relations
    }
}

class InjectionRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.injection"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val injectionAnnotations = setOf("Autowired", "Inject", "Resource")
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            val candidates = mutableListOf<Pair<PsiElement, String>>()
            psiClass.fields.forEach { field ->
                if (field.hasInjectionAnnotation(injectionAnnotations)) {
                    canonicalTypeText(field.type)?.let { typeName -> candidates += field to typeName }
                }
            }
            psiClass.methods.forEach { method ->
                if (method.hasInjectionAnnotation(injectionAnnotations)) {
                    method.parameterList.parameters.forEach { parameter ->
                        canonicalTypeText(parameter.type)?.let { typeName -> candidates += parameter to typeName }
                    }
                }
            }
            psiClass.constructors.forEach { constructor ->
                val annotated = constructor.hasInjectionAnnotation(injectionAnnotations)
                if (annotated || psiClass.constructors.size == 1) {
                    constructor.parameterList.parameters.forEach { parameter ->
                        canonicalTypeText(parameter.type)?.let { typeName -> candidates += parameter to typeName }
                    }
                }
            }
            candidates
                .mapNotNull { (element, typeName) ->
                    val target = context.symbolIndex.classByQualifiedName(typeName) ?: return@mapNotNull null
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
