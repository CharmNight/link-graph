package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiMethodCallExpression

class TestRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.test-relation"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { testMethod ->
            val testClass = context.symbolIndex.classByQualifiedName(testMethod.ownerClassName)
                ?.takeIf(::isIndexedTestClass)
                ?: return@forEach
            val psiMethod = context.findPsiMethod(testMethod) ?: return@forEach
            if (!hasTestMethodEvidence(psiMethod, testClass)) {
                return@forEach
            }
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        val targetMethod = expression.resolveMethod()?.ownerMethodSymbol(context.symbolIndex)
                            ?: targetMethodFromUnresolvedCall(expression, testClass.packageName, context)
                        if (targetMethod != null && targetMethod.ownerClassName != testMethod.ownerClassName) {
                            relations += relation(
                                kind = JvmRelationKind.TESTS,
                                from = testMethod,
                                to = targetMethod,
                                confidence = JvmRelationConfidence.PROVEN,
                                source = JvmRelationSource.PSI,
                                evidence = expression.evidence("test method calls ${targetMethod.signature}", testMethod.source),
                                qualifier = "${testMethod.signature}:${targetMethod.signature}:${expression.textRange.startOffset}",
                                metadata = mapOf(
                                    "test.reason" to "CALL_PATH",
                                    "test.framework" to "true",
                                    "test.methodSignature" to testMethod.signature,
                                    "test.targetMethodSignature" to targetMethod.signature,
                                ),
                            )
                        }
                        super.visitMethodCallExpression(expression)
                    }
                },
            )
        }
        return relations
    }

    private fun targetMethodFromUnresolvedCall(
        expression: PsiMethodCallExpression,
        ownerPackageName: String,
        context: JvmResolutionContext,
    ): com.charmnight.linkgraph.jvm.index.JvmMethodSymbol? {
        val methodName = expression.methodExpression.referenceName ?: return null
        val qualifier = expression.methodExpression.qualifierExpression
        val targetClass = qualifier
            ?.type
            ?.let { type -> context.symbolIndex.classByTypeNear(type, ownerPackageName) }
            ?: qualifier
                ?.text
                ?.let(::constructedSimpleClassName)
                ?.let { simpleName -> context.symbolIndex.classByQualifiedName("$ownerPackageName.$simpleName") }
            ?: return null
        return context.symbolIndex.methodsBySignature.values
            .filter { method -> method.ownerClassName == targetClass.qualifiedName && method.simpleName == methodName }
            .singleOrNull()
    }

    private fun constructedSimpleClassName(text: String): String? =
        Regex("""new\s+([A-Za-z_$][\w$]*)\s*\(""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

    private fun isIndexedTestClass(symbol: JvmClassSymbol): Boolean =
        symbol.testSource || symbol.qualifiedName.endsWith("Test") || symbol.qualifiedName.endsWith("Tests")

    private fun hasTestMethodEvidence(
        method: com.intellij.psi.PsiMethod,
        ownerClass: JvmClassSymbol,
    ): Boolean {
        if (!ownerClass.testSource) {
            return false
        }
        val methodAnnotations = method.modifierList.annotations
            .mapNotNull { annotation -> annotation.qualifiedName?.substringAfterLast('.') ?: annotation.nameReferenceElement?.referenceName }
            .toSet()
        val classAnnotations = method.containingClass?.modifierList?.annotations.orEmpty()
            .mapNotNull { annotation -> annotation.qualifiedName?.substringAfterLast('.') ?: annotation.nameReferenceElement?.referenceName }
            .toSet()
        return methodAnnotations.any { annotation -> annotation in TEST_METHOD_ANNOTATIONS } ||
            classAnnotations.any { annotation -> annotation in TEST_CLASS_ANNOTATIONS }
    }

    private companion object {
        val TEST_METHOD_ANNOTATIONS = setOf(
            "Test",
            "ParameterizedTest",
            "RepeatedTest",
            "TestFactory",
            "TestTemplate",
            "Theory",
        )
        val TEST_CLASS_ANNOTATIONS = setOf(
            "RunWith",
            "ExtendWith",
            "SpringBootTest",
            "WebMvcTest",
            "DataJpaTest",
            "Specification",
        )
    }
}
