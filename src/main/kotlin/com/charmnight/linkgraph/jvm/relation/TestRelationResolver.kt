package com.charmnight.linkgraph.jvm.relation

import com.charmnight.linkgraph.jvm.index.JvmClassSymbol
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiMethodCallExpression

/** 测试关系解析器：把测试方法与其调用的被测方法/类关联起来。 */
class TestRelationResolver : JvmRelationResolver {
    /** 解析器标识，用于在关系管线中识别测试关系来源。 */
    override val id: String = "jvm.test-relation"

    /**
     * 遍历索引中的测试方法，找到它调用的被测方法并产出 TESTS 关系。
     * 只在确认方法所在类是测试类、且方法本身带有测试注解时才解析，避免误报。
     */
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

    /**
     * 当 PSI 无法直接解析调用目标时（例如被测对象通过反射/构造器被引用），
     * 借助限定符类型或构造器文本来猜测目标类，再按方法签名匹配出唯一目标方法。
     */
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

    /** 从 `new Xxx(...)` 文本中提取简单类名，用于无法直接解析类型时的兜底推断。 */
    private fun constructedSimpleClassName(text: String): String? =
        Regex("""new\s+([A-Za-z_$][\w$]*)\s*\(""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)

    /** 判断该类是否为测试类：来自测试源或类名以 Test/Tests 结尾。 */
    private fun isIndexedTestClass(symbol: JvmClassSymbol): Boolean =
        symbol.testSource || symbol.qualifiedName.endsWith("Test") || symbol.qualifiedName.endsWith("Tests")

    /**
     * 综合方法注解和所在类注解判断是否为测试方法：
     * 必须来自测试源，并且方法或类上带有测试框架的标准注解（@Test、@SpringBootTest 等）。
     */
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
        /** 标识单个测试方法的注解集合，覆盖 JUnit4/5 及 Theory 等。 */
        val TEST_METHOD_ANNOTATIONS = setOf(
            "Test",
            "ParameterizedTest",
            "RepeatedTest",
            "TestFactory",
            "TestTemplate",
            "Theory",
        )
        /** 标识整个测试类的注解集合，覆盖 JUnit runners、Spring 测试切片与 Spock。 */
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
