package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassKind
import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiModifier

class CallAggregationRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.call-aggregation"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val calls = linkedMapOf<Pair<String, String>, MutableList<JvmEvidenceRef>>()
        val metadata = linkedMapOf<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            val sourceClass = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return@forEach
            val ownerPackageName = sourceClass.packageName
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        val targetMethod = expression.resolveMethod()
                        val resolvedTargetClass = targetMethod?.containingClass
                        val targetClass = resolvedTargetClass?.ownerClassSymbol(context.symbolIndex)
                            ?: targetClassFromUnresolvedCall(expression, context, ownerPackageName)
                        if (targetClass != null && targetClass.id != sourceClass.id) {
                            val key = sourceClass.id to targetClass.id
                            calls.getOrPut(key) { mutableListOf() } +=
                                expression.evidence("calls ${targetMethod?.name ?: expression.methodExpression.referenceName.orEmpty()}", methodSymbol.source)
                            metadata.putValue(key, "call.sourceMethodSignatures", methodSymbol.signature)
                            val runtimeDispatch = resolvedTargetClass?.needsRuntimeDispatch() == true || targetClass.needsRuntimeDispatch()
                            val confidence = if (runtimeDispatch) {
                                JvmRelationConfidence.RUNTIME_REQUIRED
                            } else {
                                JvmRelationConfidence.PROVEN
                            }
                            metadata.putValue(key, "call.confidences", confidence.name)
                            if (runtimeDispatch) {
                                metadata.putValue(
                                    key,
                                    "jvm.dispatch.kind",
                                    if (resolvedTargetClass?.isInterface == true || targetClass.kind == JvmClassKind.INTERFACE) {
                                        "INTERFACE_DISPATCH"
                                    } else {
                                        "ABSTRACT_DISPATCH"
                                    },
                                )
                            }
                            val targetMethodSymbol = targetMethod?.ownerMethodSymbol(context.symbolIndex)
                                ?: targetMethodFromUnresolvedCall(expression, targetClass, context)
                            targetMethodSymbol?.signature?.let { signature ->
                                metadata.putValue(key, "call.targetMethodSignatures", signature)
                            }
                            if (sourceClass.testSource) {
                                metadata.putValue(key, "test.framework", "true")
                            }
                        }
                        super.visitMethodCallExpression(expression)
                    }

                    override fun visitNewExpression(expression: PsiNewExpression) {
                        val targetClass = expression.classReference?.resolve() as? com.intellij.psi.PsiClass
                        val targetSymbol = targetClass?.ownerClassSymbol(context.symbolIndex)
                            ?: expression.classReference?.referenceName
                                ?.let { simpleName -> context.symbolIndex.classByQualifiedName("$ownerPackageName.$simpleName") }
                        if (targetSymbol != null && targetSymbol.id != sourceClass.id) {
                            val key = sourceClass.id to targetSymbol.id
                            calls.getOrPut(key) { mutableListOf() } +=
                                expression.evidence("constructs ${targetSymbol.qualifiedName}", methodSymbol.source)
                            metadata.putValue(key, "call.sourceMethodSignatures", methodSymbol.signature)
                            metadata.putValue(key, "call.confidences", JvmRelationConfidence.PROVEN.name)
                            if (sourceClass.testSource) {
                                metadata.putValue(key, "test.framework", "true")
                            }
                        }
                        super.visitNewExpression(expression)
                    }
                },
            )
        }
        return calls.mapNotNull { (key, samples) ->
            val from = context.symbolIndex.findSymbol(key.first) ?: return@mapNotNull null
            val to = context.symbolIndex.findSymbol(key.second) ?: return@mapNotNull null
            val relationMetadata = metadata[key].orEmpty()
                .mapValues { (_, values) -> values.sorted().joinToString(";") }
            val confidence = relationMetadata["call.confidences"]
                ?.split(';')
                ?.mapNotNull { value -> runCatching { JvmRelationConfidence.valueOf(value) }.getOrNull() }
                ?.maxByOrNull(JvmRelationConfidence::ordinal)
                ?: JvmRelationConfidence.PROVEN
            JvmRelation(
                id = jvmRelationId(JvmRelationKind.CALLS, from.id, to.id),
                kind = JvmRelationKind.CALLS,
                fromSymbolId = from.id,
                toSymbolId = to.id,
                confidence = confidence,
                source = JvmRelationSource.PSI,
                count = samples.size,
                samples = samples.distinct().take(context.budget.maxSamplesPerRelation),
                metadata = mapOf("call.count" to samples.size.toString()) + relationMetadata - "call.confidences",
            )
        }
    }

    private fun MutableMap<Pair<String, String>, MutableMap<String, LinkedHashSet<String>>>.putValue(
        key: Pair<String, String>,
        name: String,
        value: String,
    ) {
        getOrPut(key) { linkedMapOf() }
            .getOrPut(name) { linkedSetOf() }
            .add(value)
    }

    private fun targetClassFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        context: JvmResolutionContext,
        ownerPackageName: String,
    ): com.charmnight.linkgraph.jvm.index.JvmClassSymbol? {
        val qualifierType = expression.methodExpression.qualifierExpression?.type
        qualifierType?.let { type ->
            context.symbolIndex.classByTypeNear(type, ownerPackageName)?.let { return it }
        }
        val qualifierText = expression.methodExpression.qualifierExpression?.text?.takeIf(String::isNotBlank)
            ?: return null
        val constructorOwner = expression.text.substringBefore(".$qualifierText", missingDelimiterValue = "")
        return context.symbolIndex.classByQualifiedName("$ownerPackageName.$constructorOwner")
    }

    private fun targetMethodFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        targetClass: com.charmnight.linkgraph.jvm.index.JvmClassSymbol,
        context: JvmResolutionContext,
    ): com.charmnight.linkgraph.jvm.index.JvmMethodSymbol? {
        val methodName = expression.methodExpression.referenceName ?: return null
        return context.symbolIndex.methodsBySignature.values
            .filter { method -> method.ownerClassName == targetClass.qualifiedName && method.simpleName == methodName }
            .singleOrNull()
    }

    private fun com.intellij.psi.PsiClass.needsRuntimeDispatch(): Boolean =
        isInterface || hasModifierProperty(PsiModifier.ABSTRACT)

    private fun JvmClassSymbol.needsRuntimeDispatch(): Boolean =
        kind == JvmClassKind.INTERFACE || abstract
}
