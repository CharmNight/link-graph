package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiModifierListOwner

class AnnotationRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.annotation"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectClasses(context.symbolIndex).forEach { classSymbol ->
            val psiClass = context.findPsiClass(classSymbol) ?: return@forEach
            psiClass.annotations.forEach { annotation ->
                annotationRelation(context, classSymbol, annotation)?.let(relations::add)
            }
            psiClass.fields.forEach { field ->
                val fieldSymbol = context.symbolIndex.findField("${classSymbol.qualifiedName}.${field.name}") ?: return@forEach
                field.annotations.forEach { annotation ->
                    annotationRelation(context, fieldSymbol, annotation)?.let(relations::add)
                }
            }
            psiClass.methods.forEach { method ->
                val methodSymbol = method.ownerMethodSymbol(context.symbolIndex) ?: return@forEach
                method.annotations.forEach { annotation ->
                    annotationRelation(context, methodSymbol, annotation)?.let(relations::add)
                }
                method.parameterList.parameters.forEach { parameter ->
                    parameter.annotations.forEach { annotation ->
                        annotationRelation(context, methodSymbol, annotation, qualifier = parameter.name)?.let(relations::add)
                    }
                }
            }
        }
        return relations
    }

    private fun annotationRelation(
        context: JvmResolutionContext,
        owner: com.charmnight.linkgraph.jvm.index.JvmSymbol,
        annotation: PsiAnnotation,
        qualifier: String? = null,
    ): JvmRelation? {
        val annotationClass = annotation.resolveAnnotationType()
            ?.ownerClassSymbol(context.symbolIndex)
            ?: return null
        if (annotationClass.id == owner.id) {
            return null
        }
        return relation(
            kind = JvmRelationKind.ANNOTATED_BY,
            from = owner,
            to = annotationClass,
            confidence = JvmRelationConfidence.PROVEN,
            source = JvmRelationSource.PSI,
            evidence = annotation.evidence("annotated by ${annotationClass.qualifiedName}", owner.source),
            qualifier = listOfNotNull(annotation.qualifiedName, qualifier).joinToString(":"),
            metadata = buildMap {
                put("annotation.qualifiedName", annotationClass.qualifiedName)
                if (owner is com.charmnight.linkgraph.jvm.index.JvmMethodSymbol) {
                    put("annotation.ownerMethod", owner.signature)
                }
                if (owner is com.charmnight.linkgraph.jvm.index.JvmFieldSymbol) {
                    put("annotation.ownerField", owner.qualifiedName)
                }
                qualifier?.let { put("annotation.parameterName", it) }
            },
        )
    }
}

private val PsiModifierListOwner.annotations: Array<PsiAnnotation>
    get() = modifierList?.annotations ?: emptyArray()
