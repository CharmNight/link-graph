package com.charmnight.linkgraph.jvm.relation

import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiArrayInitializerExpression
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiNewExpression

class ProxyRelationResolver : JvmRelationResolver {
    override val id: String = "jvm.proxy"

    override fun resolve(context: JvmResolutionContext): List<JvmRelation> {
        val relations = mutableListOf<JvmRelation>()
        projectMethods(context.symbolIndex).forEach { methodSymbol ->
            val psiMethod = context.findPsiMethod(methodSymbol) ?: return@forEach
            psiMethod.accept(
                object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        if (isJdkProxyFactory(expression)) {
                            proxyInterfaceNames(expression).forEach { interfaceName ->
                                val target = context.symbolIndex.classByQualifiedName(interfaceName) ?: return@forEach
                                relations += relation(
                                    kind = JvmRelationKind.USES_PROXY,
                                    from = methodSymbol,
                                    to = target,
                                    confidence = JvmRelationConfidence.RULE_INFERRED,
                                    source = JvmRelationSource.FRAMEWORK_RULE,
                                    evidence = expression.evidence(
                                        "JDK dynamic proxy may implement ${target.qualifiedName}",
                                        methodSymbol.source,
                                    ),
                                    qualifier = "${methodSymbol.signature}:${target.qualifiedName}",
                                    metadata = mapOf(
                                        "proxy.kind" to "JDK_DYNAMIC_PROXY",
                                        "proxy.sourceMethod" to methodSymbol.signature,
                                        "relation.confidence.reason" to "Proxy interface is static, invocation target still depends on InvocationHandler.",
                                    ),
                                )
                            }
                        }
                        if (isSpringProxyFactory(expression)) {
                            expression.argumentList.expressions
                                .firstOrNull()
                                ?.let { argument -> com.charmnight.linkgraph.jvm.index.canonicalTypeText(argument.type) }
                                ?.let(context.symbolIndex::classByQualifiedName)
                                ?.let { target ->
                                    relations += relation(
                                        kind = JvmRelationKind.USES_PROXY,
                                        from = methodSymbol,
                                        to = target,
                                        confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                                        source = JvmRelationSource.FRAMEWORK_RULE,
                                        evidence = expression.evidence(
                                            "Spring proxy target requires runtime advisor resolution for ${target.qualifiedName}",
                                            methodSymbol.source,
                                        ),
                                        qualifier = "${methodSymbol.signature}:${target.qualifiedName}",
                                        metadata = mapOf(
                                            "proxy.kind" to "SPRING_PROXY_FACTORY",
                                            "proxy.sourceMethod" to methodSymbol.signature,
                                            "relation.requiredEvidence" to "Runtime proxy factory/advisor state is required for exact invocation target.",
                                        ),
                                    )
                                }
                        }
                        super.visitMethodCallExpression(expression)
                    }

                    override fun visitNewExpression(expression: PsiNewExpression) {
                        val constructed = expression.classReference
                            ?.resolve()
                            ?.let { resolved -> resolved as? PsiClass }
                            ?.qualifiedName
                        if (constructed == "org.springframework.aop.framework.ProxyFactory") {
                            relations += relation(
                                kind = JvmRelationKind.USES_PROXY,
                                from = methodSymbol,
                                to = context.symbolIndex.classByQualifiedName(methodSymbol.ownerClassName) ?: return,
                                confidence = JvmRelationConfidence.RUNTIME_REQUIRED,
                                source = JvmRelationSource.FRAMEWORK_RULE,
                                evidence = expression.evidence(
                                    "Spring ProxyFactory construction requires runtime advisor resolution",
                                    methodSymbol.source,
                                ),
                                qualifier = methodSymbol.signature,
                                metadata = mapOf(
                                    "proxy.kind" to "SPRING_PROXY_FACTORY",
                                    "proxy.sourceMethod" to methodSymbol.signature,
                                    "relation.requiredEvidence" to "Runtime proxy factory/advisor state is required for exact invocation target.",
                                ),
                            )
                        }
                        super.visitNewExpression(expression)
                    }
                },
            )
        }
        return relations
    }

    private fun isJdkProxyFactory(expression: PsiMethodCallExpression): Boolean =
        expression.methodExpression.referenceName == "newProxyInstance" &&
            expression.methodExpression.qualifierExpression?.text in setOf("Proxy", "java.lang.reflect.Proxy")

    private fun isSpringProxyFactory(expression: PsiMethodCallExpression): Boolean =
        expression.methodExpression.referenceName in setOf("getProxy", "createAopProxy") &&
            expression.methodExpression.qualifierExpression?.type?.canonicalText?.contains("ProxyFactory") == true

    private fun proxyInterfaceNames(expression: PsiMethodCallExpression): List<String> {
        val argument = expression.argumentList.expressions.getOrNull(1) ?: return emptyList()
        val initializer = when (argument) {
            is PsiNewExpression -> argument.arrayInitializer
            is PsiArrayInitializerExpression -> argument
            else -> null
        } ?: return emptyList()
        return initializer.initializers.mapNotNull { initializerExpression ->
            val classObject = initializerExpression as? PsiClassObjectAccessExpression ?: return@mapNotNull null
            com.charmnight.linkgraph.jvm.index.canonicalTypeText(classObject.operand.type)
        }
    }
}
